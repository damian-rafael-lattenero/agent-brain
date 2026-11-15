package dev.lattenero.brain.memory

import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.config.Config
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.Migrator
import dev.lattenero.brain.embeddings.FakeProvider
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The invalidation laws on real Postgres: a Supersede stamps EXACTLY the old
 * row (invalid_at set, content byte-identical, salience untouched, NOT
 * deletion), the stamp is idempotent, invalidated rows leave BOTH search legs
 * and every dedup path — and invalidation is orthogonal to forgetting: even a
 * reinforced (resurrected) row stays excluded while its stamp stands.
 *
 * One fresh database per law: the laws plant the same claims on purpose, and
 * the store's replay index must never see them twice in one schema.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryInvalidationIT {
    private val dim = 768
    private val provider = FakeProvider(dim, FakeProvider.Mode.BOW)
    private val staleClaim = "The orders database failover is in progress."
    private val siblingClaim = "Grandmother's lemon pie recipe needs patience and the oven."

    private class Fixture(val config: Config, val db: Database) : AutoCloseable {
        val store = MemoryStore(db)
        override fun close() = db.close()
    }

    private fun fixture(tag: String): Fixture {
        val config = TestDatabase.config(tag, provider = "fake")
        val db = Database(config)
        runBlocking { Migrator(db, dim).apply() }
        return Fixture(config, db)
    }

    /** One row planted with the store's own manual path; returns its id. */
    private suspend fun plant(f: Fixture, content: String, kind: String = MemoryKind.FACT.wire): Long {
        val vector = provider.embed(listOf(content)).single()
        return f.store.saveManual(kind, content, provider.model, vector, "ses-invalidation")
    }

    private suspend fun rowOf(f: Fixture, id: Long): Triple<String, Double, java.sql.Timestamp> = f.db.withConnection { conn ->
        conn.prepareStatement("SELECT content, salience, invalid_at FROM memories WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs ->
                rs.next()
                Triple(rs.getString(1), rs.getDouble(2), rs.getTimestamp(3))
            }
        }
    }

    /** LAW 1 — the stamp touches invalid_at and NOTHING else on the old row. */
    @Test
    fun `law 1 - supersede stamps exactly the old row - content byte-identical, salience untouched`() = runBlocking<Unit> {
        TestEnv.needPostgres()
        fixture("inval_law1").use { f ->
            val oldId = plant(f, staleClaim)
            val (contentBefore, salienceBefore, stampBefore) = rowOf(f, oldId)
            assertThat(stampBefore).isNull()

            assertThat(f.store.invalidate(oldId)).isTrue()

            val (contentAfter, salienceAfter, stampAfter) = rowOf(f, oldId)
            assertThat(contentAfter).isEqualTo(contentBefore) // byte-identical content
            assertThat(salienceAfter).isCloseTo(salienceBefore, within(1e-9)) // salience untouched
            assertThat(stampAfter).isNotNull // the stamp exists
            assertThat(f.store.isInvalidated(oldId)).isTrue()
            assertThat(f.store.byId(oldId)).isNotNull // NOT deletion: the row stays
        }
    }

    /** LAW 2 — the stamp is idempotent: the second call stamps nothing. */
    @Test
    fun `law 2 - invalidation is idempotent - the first stamp is the only stamp`() = runBlocking<Unit> {
        TestEnv.needPostgres()
        fixture("inval_law2").use { f ->
            val id = plant(f, staleClaim)
            assertThat(f.store.invalidate(id)).isTrue()
            val firstStamp = rowOf(f, id).third
            assertThat(f.store.invalidate(id)).isFalse()
            assertThat(rowOf(f, id).third).isEqualTo(firstStamp)
        }
    }

    /** LAW 3 — an invalidated row leaves BOTH search legs, even a strongly reinforced one. */
    @Test
    fun `law 3 - invalidated rows leave both search legs - reinforcement cannot resurrect them`() = runBlocking<Unit> {
        TestEnv.needPostgres()
        fixture("inval_law3").use { f ->
            val oldId = plant(f, staleClaim)
            val vector = provider.embed(listOf("orders database failover")).single()
            val text = "failover"

            val before = f.store.search(vector, text, provider.model, k = 10, lifecycle = Lifecycle.Params(30))
            assertThat(before.map { it.content }).contains(staleClaim) // the legs WOULD return it

            f.store.invalidate(oldId)
            f.db.withConnection { conn ->
                // Reinforce past the cooling floor: resurrection by reinforcement is
                // B3's law for forgotten rows, and invalidation must NOT obey it.
                conn.prepareStatement("UPDATE memories SET salience = salience + 5, last_accessed = now() WHERE id = ?")
                    .use { ps -> ps.setLong(1, oldId); ps.executeUpdate() }
            }
            val after = f.store.search(vector, text, provider.model, k = 10, lifecycle = Lifecycle.Params(30))
            assertThat(after.map { it.content }).doesNotContain(staleClaim)
        }
    }

    /** LAW 4 — a dead claim cannot absorb a new landing: the restatement of the truth lands fresh. */
    @Test
    fun `law 4 - dedup never merges into an invalidated row`() = runBlocking<Unit> {
        TestEnv.needPostgres()
        fixture("inval_law4").use { f ->
            val oldId = plant(f, staleClaim)
            f.store.invalidate(oldId)
            // The identical restatement arrives through the worker's own insert path
            // (its own event id, so the replay index cannot confuse the two).
            val vector = provider.embed(listOf(staleClaim)).single()
            val transcript = Transcript("e-restatement", "ses-invalidation", "turn-restatement", "test", staleClaim, "", emptyList())
            val report = f.store.insertAllDeduped(
                transcript,
                provider.model,
                listOf(MemoryStore.MemoryRow(MemoryKind.FACT.wire, staleClaim, vector)),
            )
            assertThat(report.inserted).isEqualTo(1) // it LANDED, never merged into the dead row
            assertThat(report.merged).isEmpty()
            val restated = report.insertedIds.single()
            assertThat(f.store.isInvalidated(oldId)).isTrue() // the dead row stays dead, untouched
            assertThat(f.store.isInvalidated(restated)).isFalse() // the fresh restatement is alive
            // and the backfill reads neither side of it: only rows without a stamp are dedup inputs.
            val rows = f.store.dedupRows()
            assertThat(rows.map { it.id }).doesNotContain(oldId)
            assertThat(rows.map { it.id }).contains(restated)
        }
    }

    /** LAW 5 — the neighbor band never proposes an invalidated row as a verdict input. */
    @Test
    fun `law 5 - the neighbor band never proposes an invalidated row`() = runBlocking<Unit> {
        TestEnv.needPostgres()
        fixture("inval_law5").use { f ->
            val deadId = plant(f, staleClaim)
            val siblingId = plant(f, siblingClaim)
            f.store.invalidate(deadId)
            val newContent = "The orders database failover completed today."
            val newId = plant(f, newContent)
            val newVector = provider.embed(listOf(newContent)).single()
            val neighbor = f.store.nearestNeighbor(
                embedding = newVector,
                selfId = newId,
                embedModel = provider.model,
                band = MemoryReconcile.band(30),
            )
            // Whatever the band proposes, it is never the invalidated row.
            if (neighbor != null) assertThat(neighbor.id).isNotEqualTo(deadId)
            assertThat(f.store.isInvalidated(siblingId)).isFalse()
        }
    }
}
