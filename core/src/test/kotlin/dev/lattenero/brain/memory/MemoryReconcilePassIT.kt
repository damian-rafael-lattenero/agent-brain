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
 * The reconcile pass's verdict-driven state changes on real Postgres: with a
 * scripted chat and hand-crafted embeddings pinned INSIDE the band (cosine
 * 0.85, between BAND_LOW 0.80 and the dedup threshold 0.90), the verdict —
 * and ONLY the verdict — decides what happens to the rows:
 *
 * - Supersede: the old claim is stamped invalid, content byte-identical,
 *   salience untouched, the new claim stands and retrieval surfaces only it
 *   (the defect [MemoryContradictionDefectIT] pinned, now fixed);
 * - Same: merge as dedup does — the oldest reinforced, the restatement gone;
 * - Distinct: nothing changes;
 * - a chat OUTAGE: nothing changes at all — a chat failure degrades to a
 *   no-op, row by row, and no chat call is spent when no candidate is in the
 *   band.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryReconcilePassIT {
    private val dim = 768
    private val embedder = FakeProvider(dim, FakeProvider.Mode.HASH)
    private val oldClaim = "The orders database failover is in progress."
    private val newClaim = "The orders database failover completed today."

    private class Fixture(val config: Config, val db: Database, val store: MemoryStore) : AutoCloseable {
        override fun close() = db.close()
    }

    private fun fixture(tag: String): Fixture {
        val config = TestDatabase.config(tag, provider = "fake")
        val db = Database(config)
        runBlocking { Migrator(db, dim).apply() }
        return Fixture(config, db, MemoryStore(db))
    }

    /** Two unit vectors whose cosine is exactly [cosine] — pins the pair inside (or outside) the band. */
    private fun pairVectors(cosine: Double): Pair<FloatArray, FloatArray> {
        val a = FloatArray(dim).also { it[0] = 1f }
        val b = FloatArray(dim).also {
            it[0] = cosine.toFloat()
            it[1] = sqrt(1.0 - cosine * cosine).toFloat()
        }
        return a to b
    }

    /** A chat that answers [verdict] when the OLD side of the pair is [oldKey], distinct otherwise. */
    private fun scriptedChat(oldKey: String, verdict: String): FakeChatProvider = FakeChatProvider { _, user ->
        FakeChatProvider.json(
            "verdict" to JsonPrimitive(if (user.contains(oldKey)) verdict else VerdictParser.DISTINCT),
            "rationale" to JsonPrimitive("scripted verdict"),
        )
    }

    private suspend fun Fixture.plant(content: String, vector: FloatArray): Long =
        store.saveManual(MemoryKind.FACT.wire, content, embedder.model, vector, "ses-reconcile")

    /** The row's (invalidated, salience) — the pass must only ever touch the first. */
    private suspend fun Fixture.rowState(id: Long): Pair<Boolean, Double> = db.withConnection { conn ->
        conn.prepareStatement("SELECT invalid_at IS NOT NULL, salience FROM memories WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getBoolean(1) to rs.getDouble(2)
            }
        }
    }

    private suspend fun Fixture.aliveIds(): List<Long> = db.withConnection { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT id FROM memories ORDER BY id").use { rs ->
                generateSequence { if (rs.next()) rs.getLong(1) else null }.toList()
            }
        }
    }

    @Test
    fun `a Supersede verdict invalidates the old claim - retrieval surfaces only the new one`() = runBlocking<Unit> {
        TestEnv.needPostgres()
        fixture("rec_supersede").use { f ->
            val (oldVector, newVector) = pairVectors(0.85)
            val oldId = f.plant(oldClaim, oldVector)
            val newId = f.plant(newClaim, newVector)
            assertThat(MemoryReconcile.bandContains(0.85)).isTrue()

            val judge = VerdictJudge(scriptedChat(oldClaim, "supersede"), 10.seconds)
            val report = MemoryReconciler(f.store, judge).reconcileInserted(listOf(newId), embedder.model, 30)

            assertThat(report.invalidated).isEqualTo(1)
            assertThat(report.changes).isEqualTo(1)
            assertThat(f.rowState(oldId).first).isTrue() // the old claim is stamped
            assertThat(f.store.isInvalidated(newId)).isFalse() // exactly the old row, never the new
            // salience untouched by the stamp (Zep law)
            assertThat(f.rowState(newId).first).isFalse()
            assertThat(f.rowState(newId).second).isCloseTo(1.0, within(1e-9))
            // retrieval: the contradicting update is now the ONLY answer
            val hits = f.store.search(newVector, "failover completed", embedder.model, k = 10, Lifecycle.Params(30))
            assertThat(hits.map { it.content }).doesNotContain(oldClaim)
            assertThat(hits.map { it.content }).contains(newClaim)
        }
    }

    @Test
    fun `a Same verdict merges as dedup does - the oldest reinforced, the restatement absorbed`() = runBlocking<Unit> {
        TestEnv.needPostgres()
        fixture("rec_same").use { f ->
            val (oldVector, newVector) = pairVectors(0.85)
            val oldId = f.plant(oldClaim, oldVector)
            val newId = f.plant(newClaim, newVector)

            val judge = VerdictJudge(scriptedChat(oldClaim, "same"), 10.seconds)
            val report = MemoryReconciler(f.store, judge).reconcileInserted(listOf(newId), embedder.model, 30)

            assertThat(report.merged).isEqualTo(1)
            assertThat(f.rowState(oldId).second).isCloseTo(1.0 + MemoryStore.REINFORCE_STEP, within(1e-6))
            assertThat(f.aliveIds()).containsExactly(oldId) // the restatement is absorbed
            assertThat(f.rowState(oldId).first).isFalse() // never invalidated
        }
    }

    @Test
    fun `a Distinct verdict changes nothing`() = runBlocking<Unit> {
        TestEnv.needPostgres()
        fixture("rec_distinct").use { f ->
            val (oldVector, newVector) = pairVectors(0.85)
            val oldId = f.plant(oldClaim, oldVector)
            val newId = f.plant(newClaim, newVector)

            val judge = VerdictJudge(scriptedChat(oldClaim, "distinct"), 10.seconds)
            val report = MemoryReconciler(f.store, judge).reconcileInserted(listOf(newId), embedder.model, 30)

            assertThat(report.distinct).isEqualTo(1)
            assertThat(report.changes).isZero()
            assertThat(f.rowState(oldId).first).isFalse()
            assertThat(f.rowState(newId).first).isFalse()
            assertThat(f.aliveIds()).containsExactlyInAnyOrder(oldId, newId)
        }
    }

    @Test
    fun `a chat outage is a no-op - not one row changes`() = runBlocking<Unit> {
        TestEnv.needPostgres()
        fixture("rec_outage").use { f ->
            val (oldVector, newVector) = pairVectors(0.85)
            val oldId = f.plant(oldClaim, oldVector)
            val newId = f.plant(newClaim, newVector)
            val oldSalienceBefore = f.rowState(oldId).second

            val judge = VerdictJudge(FakeChatProvider { _, _ -> throw IllegalStateException("ollama is down") }, 10.seconds)
            val report = MemoryReconciler(f.store, judge).reconcileInserted(listOf(newId), embedder.model, 30)

            assertThat(report.skipped).isEqualTo(1)
            assertThat(report.changes).isZero()
            assertThat(f.rowState(oldId).first).isFalse() // nothing invalidated
            assertThat(f.rowState(oldId).second).isCloseTo(oldSalienceBefore, within(1e-9)) // nothing reinforced
            assertThat(f.aliveIds()).containsExactlyInAnyOrder(oldId, newId) // nothing deleted
        }
    }

    @Test
    fun `no candidate in the band - no chat call is spent`() = runBlocking<Unit> {
        TestEnv.needPostgres()
        fixture("rec_nocandidate").use { f ->
            val (oldVector, newVector) = pairVectors(0.5) // below BAND_LOW: not a candidate
            val oldId = f.plant(oldClaim, oldVector)
            val newId = f.plant(newClaim, newVector)

            val chat = scriptedChat(oldClaim, "supersede")
            val judge = VerdictJudge(chat, 10.seconds)
            val report = MemoryReconciler(f.store, judge).reconcileInserted(listOf(newId), embedder.model, 30)

            assertThat(report.pairs).isEmpty()
            assertThat(report.changes).isZero()
            assertThat(chat.calls).isEmpty() // ONE call per PAIR, and no pair means no call
            assertThat(f.rowState(oldId).first).isFalse()
        }
    }
}
