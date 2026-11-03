package dev.lattenero.brain.memory

import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.chat.FakeChatProvider
import dev.lattenero.brain.config.Config
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.Migrator
import dev.lattenero.brain.embeddings.FakeProvider
import dev.lattenero.brain.rag.CountingTracer
import dev.lattenero.brain.rag.EmbedPipeline
import kap.CircuitBreaker
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The worker's honest outcome end-to-end (ADR-008 quality fix): two real
 * turns on the bus, a scripted chat that restates one memory verbatim, the
 * hash embedder making the restatement cosine 1.0. The second turn must NOT
 * add a new row for it: the stored memory is reinforced and the report says
 * merged, not memories. Distinct extractions still land (below threshold).
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryWorkerDedupIT {
    private val dim = 768
    private val embedder = FakeProvider(dim, FakeProvider.Mode.HASH)
    private val alphaFact = "The alpha project uses Postgres."

    private class Fixture(val config: Config, val db: Database) : AutoCloseable {
        val store = MemoryStore(db)
        val tracer = CountingTracer()
        override fun close() = db.close()
    }

    private fun fixture(tag: String): Fixture {
        val config = TestDatabase.config(tag)
        val db = Database(config)
        runBlocking { Migrator(db, dim).apply() }
        return Fixture(config, db)
    }

    /** A scripted chat: the alpha fact is restated verbatim on every turn; the summaries differ. */
    private fun chat() = FakeChatProvider { _, user ->
        FakeChatProvider.json(
            "facts" to FakeChatProvider.strings(alphaFact),
            "decisions" to FakeChatProvider.strings(),
            "preferences" to FakeChatProvider.strings(),
            "summary" to JsonPrimitive(if ("kickoff" in user) "The alpha project kickoff happened." else "The beta review wrapped up."),
        )
    }

    private fun worker(f: Fixture, chat: FakeChatProvider): MemoryWorker {
        val extractor = MemoryExtractor(chat, 10.seconds, f.tracer, CircuitBreaker(maxFailures = 100, retryAfter = 1.minutes))
        return MemoryWorker(f.store, extractor, EmbedPipeline(embedder, 8, 2, 10.seconds, f.tracer, CircuitBreaker(maxFailures = 100, retryAfter = 1.minutes)))
    }

    private suspend fun plantTurn(f: Fixture, eventId: String, sid: String, turn: String, prompt: String, reply: String) {
        f.db.withConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO bus_events (id, ts, kind, sid, turn, source, payload) VALUES (?, now(), 'user.prompt', ?, ?, 'test', ?::jsonb)",
            ).use { ps ->
                ps.setString(1, eventId + "a")
                ps.setString(2, sid)
                ps.setString(3, turn)
                ps.setString(4, """{"prompt": "$prompt"}""")
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO bus_events (id, ts, kind, sid, turn, source, payload) VALUES (?, now(), 'turn.completed', ?, ?, 'test', ?::jsonb)",
            ).use { ps ->
                ps.setString(1, eventId + "b")
                ps.setString(2, sid)
                ps.setString(3, turn)
                ps.setString(4, """{"reply": "$reply"}""")
                ps.executeUpdate()
            }
        }
    }

    private suspend fun rowFor(f: Fixture, content: String): Pair<Long, Double> = f.db.withConnection { conn ->
        conn.prepareStatement("SELECT id, salience FROM memories WHERE content = ?").use { ps ->
            ps.setString(1, content)
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) to rs.getDouble(2) }
        }
    }

    private suspend fun count(f: Fixture): Long = f.db.withConnection { conn ->
        conn.createStatement().use { st -> st.executeQuery("SELECT count(*) FROM memories").use { rs -> rs.next(); rs.getLong(1) } }
    }

    @Test
    fun `a restated memory across turns merges - no new row, salience bumped, the report says merged`() = runBlocking<Unit> {
        TestEnv.needPostgres()
        fixture("memworkerdedup").use { f ->
            plantTurn(f, "e1", "ses-a", "t1", "alpha project kickoff", "we set up the alpha project")
            val first = worker(f, chat()).processOnce()
            assertThat(first.scanned).isEqualTo(1)
            assertThat(first.processed).isEqualTo(1)
            assertThat(first.memories).isEqualTo(2)
            assertThat(first.merged).isZero
            assertThat(count(f)).isEqualTo(2)
            val (factId, factSalience) = rowFor(f, alphaFact)
            assertThat(factSalience).isCloseTo(1.0, within(1e-6))

            // The second turn restates the alpha fact verbatim: the hash
            // embedder makes it cosine 1.0, so it must reinforce, never land again.
            plantTurn(f, "e2", "ses-b", "t2", "beta review notes", "the beta review is done")
            val second = worker(f, chat()).processOnce()
            assertThat(second.scanned).isEqualTo(1)
            assertThat(second.processed).isEqualTo(1)
            assertThat(second.memories).isEqualTo(1) // only the distinct beta summary
            assertThat(second.merged).isEqualTo(1) // the restated alpha fact
            assertThat(count(f)).isEqualTo(3)
            val (factId2, factSalience2) = rowFor(f, alphaFact)
            assertThat(factId2).isEqualTo(factId)
            assertThat(factSalience2).isCloseTo(2.0, within(1e-6))
        }
    }

    @Test
    fun `a turn whose extraction is entirely new inserts everything - the worker stays honest above the threshold`() = runBlocking<Unit> {
        TestEnv.needPostgres()
        fixture("memworkerdedupnew").use { f ->
            plantTurn(f, "e1", "ses-a", "t1", "alpha project kickoff", "we set up the alpha project")
            val freshChat = FakeChatProvider { _, user ->
                FakeChatProvider.json(
                    "facts" to FakeChatProvider.strings(
                        if ("kickoff" in user) "The alpha project uses Postgres." else "The gamma cluster runs on three nodes.",
                    ),
                    "decisions" to FakeChatProvider.strings(),
                    "preferences" to FakeChatProvider.strings(),
                    "summary" to JsonPrimitive(if ("kickoff" in user) "The alpha project kickoff happened." else "The gamma cluster grew today."),
                )
            }
            val first = worker(f, freshChat).processOnce()
            assertThat(first.memories).isEqualTo(2)
            plantTurn(f, "e2", "ses-b", "t2", "gamma cluster notes", "the gamma cluster grew")
            val second = worker(f, freshChat).processOnce()
            assertThat(second.memories).isEqualTo(2)
            assertThat(second.merged).isZero
            assertThat(count(f)).isEqualTo(4)
        }
    }

    @Test
    fun `a replayed bus does not re-merge or double-reinforce - the replay stays silent`() = runBlocking<Unit> {
        TestEnv.needPostgres()
        fixture("memworkerdedupreplay").use { f ->
            plantTurn(f, "e1", "ses-a", "t1", "alpha project kickoff", "we set up the alpha project")
            val w = worker(f, chat())
            assertThat(w.processOnce().memories).isEqualTo(2)
            val (factId, factSalience) = rowFor(f, alphaFact)
            // The turn is already marked: nothing pending, nothing to merge.
            val rerun = w.processOnce()
            assertThat(rerun.scanned).isZero
            assertThat(rerun.merged).isZero
            assertThat(rowFor(f, alphaFact).second).isCloseTo(factSalience, within(1e-9))
            assertThat(rowFor(f, alphaFact).first).isEqualTo(factId)
        }
    }
}
