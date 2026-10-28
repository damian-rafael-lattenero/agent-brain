package dev.lattenero.brain.memory

import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.bus.BusPipeline
import dev.lattenero.brain.chat.ChatProvider
import dev.lattenero.brain.chat.ChatResult
import dev.lattenero.brain.chat.FakeChatProvider
import dev.lattenero.brain.config.Config
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.Migrator
import dev.lattenero.brain.embeddings.FakeProvider
import dev.lattenero.brain.embeddings.ProviderUnavailable
import dev.lattenero.brain.rag.CountingTracer
import dev.lattenero.brain.rag.EmbedPipeline
import kap.CircuitBreaker
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Real Postgres, the recorded bot bus as input, scripted chat, fake embeddings. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryWorkerIT {
    private val dim = 768
    private val embedder = FakeProvider(dim, FakeProvider.Mode.BOW)

    @BeforeAll
    fun up() = TestEnv.needPostgres()

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

    private fun pipeline(f: Fixture) = EmbedPipeline(embedder, 8, 2, 10.seconds, f.tracer, CircuitBreaker(100, 1.minutes))

    private fun worker(f: Fixture, chat: ChatProvider, attempts: Int = 3, maxAttempts: Int = 3): MemoryWorker {
        val extractor = MemoryExtractor(chat, 10.seconds, f.tracer, CircuitBreaker(100, 1.minutes), attempts, backoffBase = 10.seconds.div(1000))
        return MemoryWorker(f.store, extractor, pipeline(f), batch = 10, maxAttempts = maxAttempts)
    }

    /** Loads the recorded bot bus (one phone turn with prompt+reply, one Mac turn that failed). */
    private suspend fun loadBus(f: Fixture): Int {
        Files.createDirectories(f.config.busFile.parent)
        Files.write(f.config.busFile, javaClass.classLoader.getResourceAsStream("bus/sample-bot-publisher.jsonl")!!.readAllBytes())
        return BusPipeline(f.config, f.db).drain()
    }

    private val goodChat = FakeChatProvider { _, user ->
        FakeChatProvider.json(
            "facts" to FakeChatProvider.strings("The user keeps a CV project at /Users/x/Projects/cv."),
            "decisions" to FakeChatProvider.strings("The CV was reordered this turn."),
            "preferences" to FakeChatProvider.strings(if ("ñandú" in user) "The user writes prompts mixing Spanish and English." else "unknown preference"),
            "summary" to JsonPrimitive("The CV got reordered."),
        )
    }

    private suspend fun snapshot(f: Fixture): String = f.db.withConnection { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT source_event_id, kind, content FROM memories ORDER BY source_event_id, kind, content").use { rs ->
                val digest = MessageDigest.getInstance("SHA-256")
                var n = 0
                while (rs.next()) {
                    n += 1
                    for (i in 1..3) digest.update((rs.getString(i) ?: "").toByteArray())
                }
                "$n:" + digest.digest().joinToString("") { "%02x".format(it) }
            }
        }
    }

    @Test
    fun `completed turns with a transcript become memories, marked once, searchable with provenance`() = runBlocking<Unit> {
        fixture("memworker").use { f ->
            assertThat(loadBus(f)).isEqualTo(6)
            val report = worker(f, goodChat).processOnce()
            // one phone turn.completed with reply; the Mac turn only has a turn.failed, so it is not pending
            assertThat(report.scanned).isEqualTo(1)
            assertThat(report.processed).isEqualTo(1)
            assertThat(report.memories).isEqualTo(4)
            assertThat(report.quarantined).isZero
            assertThat(goodChat.calls.last().second).contains("reorder my cv").contains("TOOLS: Read")

            val again = worker(f, goodChat).processOnce()
            assertThat(again.scanned).isZero

            val service = MemoryService(embedder, f.store, MemoryExtractor(goodChat, 10.seconds, f.tracer), 30)
            val found = service.search(MemorySearchRequest("spanish english prompts", k = 3)).memories
            assertThat(found.first().kind).isEqualTo("preference")
            assertThat(found.first().sessionId).isEqualTo("ses_fixture01")
            assertThat(found.first().turnId).isEqualTo("1761685200123")
            assertThat(found.first().score).isGreaterThan(0.0)
            val status = service.status()
            assertThat(status.processedTurns).isEqualTo(1)
            assertThat(status.memories).isEqualTo(4)
            assertThat(status.byKind).containsEntry("preference", 1L)
        }
    }

    @Test
    fun `golden - replaying the bus and re-running the worker leaves the memories identical`() = runBlocking<Unit> {
        fixture("memgolden").use { f ->
            loadBus(f)
            worker(f, goodChat).processOnce()
            val first = snapshot(f)
            assertThat(first).startsWith("4:")

            Files.deleteIfExists(f.config.stateDir.resolve("bus-offset.json"))
            assertThat(BusPipeline(f.config, f.db).drain()).isEqualTo(6)
            val rerun = worker(f, goodChat).processOnce()
            assertThat(rerun.scanned).isZero
            assertThat(snapshot(f)).isEqualTo(first)

            // a crash between insert and mark: the same extraction inserted again is absorbed by the unique index
            val pending = MemoryStore.PendingTurn("8e53b6a72ee6429ab9217a924bcc1de3", "ses_fixture01", "1761685200123", "bot", 0)
            val transcript = f.store.transcript(pending)
            val inserted = f.store.insertAll(transcript, embedder.model, listOf(MemoryStore.MemoryRow("decision", "The CV was reordered this turn.", embedder.embed(listOf("x")).single())))
            assertThat(inserted).isZero
            assertThat(snapshot(f)).isEqualTo(first)
        }
    }

    @Test
    fun `invalid model output is quarantined with the raw text and the turn is marked`() = runBlocking<Unit> {
        fixture("memquarantine").use { f ->
            loadBus(f)
            val garbage = FakeChatProvider { _, _ -> "I refuse to answer in JSON" }
            val report = worker(f, garbage).processOnce()
            assertThat(report.quarantined).isEqualTo(1)
            assertThat(report.processed).isZero
            val rows = f.store.quarantined(5)
            assertThat(rows).hasSize(1)
            assertThat(rows.single().reason).contains("invalid extraction").contains("not JSON")
            assertThat(rows.single().raw).isEqualTo("I refuse to answer in JSON")
            assertThat(rows.single().eventId).isEqualTo("8e53b6a72ee6429ab9217a924bcc1de3")
            assertThat(f.store.pendingTurns(10, 5)).isEmpty()
        }
    }

    @Test
    fun `a provider outage defers the turn, and repeated outages quarantine it with the reason`() = runBlocking<Unit> {
        fixture("memdefer").use { f ->
            loadBus(f)
            val down = object : ChatProvider {
                override val name = "down"
                override val model = "down-model"
                override suspend fun completeJson(system: String, user: String, schema: JsonObject): ChatResult = throw ProviderUnavailable("llm offline")
            }
            val first = worker(f, down, attempts = 1, maxAttempts = 2).processOnce()
            assertThat(first.deferred).isEqualTo(1)
            assertThat(f.store.pendingTurns(10, 5).single().attempts).isEqualTo(1)
            val second = worker(f, down, attempts = 1, maxAttempts = 2).processOnce()
            assertThat(second.quarantined).isEqualTo(1)
            assertThat(f.store.quarantined(5).single().reason).contains("gave up after 2 attempts").contains("llm offline")
            assertThat(f.store.pendingTurns(10, 5)).isEmpty()
        }
    }

    @Test
    fun `manual saves and recent listing work without the worker`() = runBlocking<Unit> {
        fixture("memmanual").use { f ->
            val service = MemoryService(embedder, f.store, MemoryExtractor(goodChat, 10.seconds, f.tracer), 30)
            val saved = service.save(MemorySaveRequest("preference", "Always answer in Spanish in the Telegram chat."))
            assertThat(saved.kind).isEqualTo("preference")
            assertThat(service.recent(5, "preference").map { it.id }).contains(saved.id)
            assertThat(service.search(MemorySearchRequest("telegram spanish answers")).memories.first().id).isEqualTo(saved.id)
        }
    }
}
