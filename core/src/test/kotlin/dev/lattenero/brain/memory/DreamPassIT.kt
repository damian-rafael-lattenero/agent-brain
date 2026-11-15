package dev.lattenero.brain.memory

import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.chat.FakeChatProvider
import dev.lattenero.brain.config.Config
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.Migrator
import dev.lattenero.brain.embeddings.FakeProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.seconds

/**
 * The dream pass on real Postgres: COOLING rows (B3's own classification,
 * backdated past one half-life of silence) clustered by direct embedding
 * similarity, judged pairwise with the SAME verdict machinery:
 *
 * - a Supersede verdict invalidates the OLD claim of the pair (Zep law: stamp
 *   only);
 * - a Same verdict merges as dedup does;
 * - rows a verdict already killed are not judged again (no double-
 *   invalidation, no verdict spent on a dead claim);
 * - the pass is IDEMPOTENT per pair: invalidated rows leave the input set,
 *   so a second run plans no pairs and changes nothing;
 * - --dry-run plans without writing and without spending one chat call;
 * - forgotten (ARCHIVED) rows are NOT dream inputs (B3 integration law).
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DreamPassIT {
    private val dim = 768
    private val embedder = FakeProvider(dim, FakeProvider.Mode.HASH)
    private val halfLifeDays = 30
    private val seedClaim = "The orders database failover is in progress."
    private val updateClaim = "The orders database failover completed today."

    private class Fixture(val config: Config, val db: Database, val store: MemoryStore) : AutoCloseable {
        override fun close() = db.close()
    }

    private fun fixture(tag: String): Fixture {
        val config = TestDatabase.config(tag, provider = "fake")
        val db = Database(config)
        runBlocking { Migrator(db, dim).apply() }
        return Fixture(config, db, MemoryStore(db))
    }

    /** A unit vector along slot 0, and a vector at exactly cosine [c] from it. */
    private fun seedVector(): FloatArray = FloatArray(dim).also { it[0] = 1f }

    private fun tilted(c: Double, slot: Int): FloatArray = FloatArray(dim).also {
        it[0] = c.toFloat()
        it[slot] = sqrt(1.0 - c * c).toFloat()
    }

    /** Plants a COOLING row: salience 1.0, last touched [daysAgo] days ago (1-3 half-lives). */
    private suspend fun Fixture.plantCooling(content: String, vector: FloatArray, idHint: String, daysAgo: Int): Long {
        val id = store.saveManual(MemoryKind.FACT.wire, content, embedder.model, vector, "ses-dream-$idHint")
        db.withConnection { conn ->
            conn.prepareStatement(
                "UPDATE memories SET created_at = now() - (? * interval '1 day'), " +
                    "last_accessed = now() - (? * interval '1 day') WHERE id = ?",
            ).use { ps ->
                ps.setInt(1, daysAgo)
                ps.setInt(2, daysAgo)
                ps.setLong(3, id)
                ps.executeUpdate()
            }
        }
        return id
    }

    /** A chat answering [verdict] when the OLD side is [oldKey], distinct otherwise. */
    private fun scriptedChat(oldKey: String, verdict: String): FakeChatProvider = FakeChatProvider { _, user ->
        FakeChatProvider.json(
            "verdict" to JsonPrimitive(if (user.contains(oldKey)) verdict else VerdictParser.DISTINCT),
            "rationale" to JsonPrimitive("scripted verdict"),
        )
    }

    private suspend fun Fixture.aliveIds(): List<Long> = db.withConnection { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT id FROM memories ORDER BY id").use { rs ->
                generateSequence { if (rs.next()) rs.getLong(1) else null }.toList()
            }
        }
    }

    @Test
    fun `a Supersede verdict invalidates the old claim and a dead seed is never judged again`() = runBlocking<Unit> {
        TestEnv.needPostgres()
        fixture("dream_supersede").use { f ->
            val seedId = f.plantCooling(seedClaim, seedVector(), "seed", 45)
            val updateId = f.plantCooling(updateClaim, tilted(0.85, 1), "update", 44)
            val bystanderId = f.plantCooling("A grandmother's lemon pie needs the oven.", tilted(0.85, 2), "bystander", 43)

            val chat = scriptedChat(seedClaim, "supersede")
            val report = DreamPass(f.store, VerdictJudge(chat, 10.seconds)).run(halfLifeDays, 100, dryRun = false)

            // ONE cluster of the three cooling rows; the first pair kills the seed...
            assertThat(report.clusters).hasSize(1)
            assertThat(report.invalidated).isEqualTo(1)
            assertThat(f.store.isInvalidated(seedId)).isTrue() // the old claim is stamped, not deleted
            assertThat(f.aliveIds()).contains(seedId)
            // ...and the SECOND pair against the now-dead seed is never judged (no chat call spent)
            assertThat(chat.calls).hasSize(1)
            assertThat(f.store.isInvalidated(updateId)).isFalse()
            assertThat(f.store.isInvalidated(bystanderId)).isFalse()
        }
    }

    @Test
    fun `dream is idempotent per pair - the second run plans and changes nothing`() = runBlocking<Unit> {
        TestEnv.needPostgres()
        fixture("dream_idempotent").use { f ->
            f.plantCooling(seedClaim, seedVector(), "seed", 45)
            f.plantCooling(updateClaim, tilted(0.85, 1), "update", 44)
            val first = DreamPass(f.store, VerdictJudge(scriptedChat(seedClaim, "supersede"), 10.seconds))
                .run(halfLifeDays, 100, dryRun = false)
            assertThat(first.invalidated).isEqualTo(1)

            // Re-run: the invalidated seed left the input set, so no cluster forms again.
            val chat = scriptedChat(seedClaim, "supersede")
            val second = DreamPass(f.store, VerdictJudge(chat, 10.seconds)).run(halfLifeDays, 100, dryRun = false)
            assertThat(second.clusters).isEmpty()
            assertThat(second.plannedPairs).isZero()
            assertThat(second.changes).isZero()
            assertThat(chat.calls).isEmpty() // no pair re-judged: idempotent
        }
    }

    @Test
    fun `a Same verdict merges as dedup does - the seed reinforced, the restatement absorbed`() = runBlocking<Unit> {
        TestEnv.needPostgres()
        fixture("dream_same").use { f ->
            val seedId = f.plantCooling(seedClaim, seedVector(), "seed", 45)
            val restatementId = f.plantCooling(updateClaim, tilted(0.85, 1), "restatement", 44)

            val report = DreamPass(f.store, VerdictJudge(scriptedChat(seedClaim, "same"), 10.seconds))
                .run(halfLifeDays, 100, dryRun = false)

            assertThat(report.merged).isEqualTo(1)
            assertThat(f.aliveIds()).containsExactly(seedId) // the restatement is gone
            f.db.withConnection { conn ->
                conn.prepareStatement("SELECT salience FROM memories WHERE id = ?").use { ps ->
                    ps.setLong(1, seedId)
                    ps.executeQuery().use { rs -> rs.next(); assertThat(rs.getDouble(1)).isCloseTo(2.0, within(1e-6)) }
                }
            }
        }
    }

    @Test
    fun `a dry run prints the plan - no write, no chat call`() = runBlocking<Unit> {
        TestEnv.needPostgres()
        fixture("dream_dryrun").use { f ->
            val seedId = f.plantCooling(seedClaim, seedVector(), "seed", 45)
            val updateId = f.plantCooling(updateClaim, tilted(0.85, 1), "update", 44)
            val bystanderId = f.plantCooling("A grandmother's lemon pie needs the oven.", tilted(0.85, 2), "by", 43)

            val chat = scriptedChat(seedClaim, "supersede")
            val report = DreamPass(f.store, VerdictJudge(chat, 10.seconds)).run(halfLifeDays, 100, dryRun = true)

            assertThat(report.dryRun).isTrue()
            assertThat(report.scanned).isEqualTo(3)
            assertThat(report.clusters).hasSize(1)
            assertThat(report.plannedPairs).isEqualTo(2) // cluster of 3: two pairs WOULD be judged
            assertThat(report.changes).isZero()
            assertThat(chat.calls).isEmpty() // the plan judges nothing
            assertThat(f.aliveIds()).containsExactlyInAnyOrder(seedId, updateId, bystanderId) // nothing written
        }
    }

    @Test
    fun `a forgotten row is not a dream input - only cooling memory dreams`() = runBlocking<Unit> {
        TestEnv.needPostgres()
        fixture("dream_forgotten").use { f ->
            // ARCHIVED: 120 days of silence at salience 1.0 (four half-lives) - below the forget floor.
            val forgottenId = f.plantCooling(seedClaim, seedVector(), "forgotten", 120)
            val coolingId = f.plantCooling(updateClaim, tilted(0.85, 1), "cooling", 45)

            val chat = scriptedChat(seedClaim, "supersede")
            val report = DreamPass(f.store, VerdictJudge(chat, 10.seconds)).run(halfLifeDays, 100, dryRun = false)

            assertThat(report.scanned).isEqualTo(1) // the forgotten row never entered the plan
            assertThat(report.clusters).isEmpty() // a lone cooling row: singleton, judged nothing
            assertThat(chat.calls).isEmpty()
            assertThat(f.store.isInvalidated(forgottenId)).isFalse()
            assertThat(f.store.isInvalidated(coolingId)).isFalse()
        }
    }
}
