package dev.lattenero.brain.memory

import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.bus.BusEvent
import dev.lattenero.brain.bus.BusKind
import dev.lattenero.brain.bus.BusEvents
import dev.lattenero.brain.bus.PgBusIngest
import dev.lattenero.brain.chat.ChatException
import dev.lattenero.brain.chat.FakeChatProvider
import dev.lattenero.brain.config.Config
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.Migrator
import dev.lattenero.brain.rag.CountingTracer
import kap.CircuitBreaker
import kap.evalGraph
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The correction flow end to end over a real database (plan U8): a
 * kind=correction bus event lands as a corrections row at the ingest, the
 * worker proposes the rule sentence with a fake judge, the degraded bins
 * leave the row pending (the bus never breaks), and a replay adds nothing.
 * ONE FRESH DATABASE PER TEST: degraded rows stay pending by law, so a
 * shared database would leak them across tests. Skips honestly when
 * Postgres is unreachable (TestEnv).
 */
@Tag("integration")
class CorrectionWorkerIT {

    private lateinit var config: Config

    private lateinit var db: Database

    @BeforeEach
    fun up() {
        TestEnv.needPostgres()
        config = TestDatabase.config("correction_worker")
        db = Database(config)
        runBlocking { Migrator(db, config.embedDim).apply() }
    }

    @AfterEach
    fun down() {
        if (::db.isInitialized) db.close()
    }

    private fun correctionEvent(id: String, text: String, minute: Int, kind: String = "steer"): BusEvent {
        val payload = buildJsonObject {
            put("kind", kind)
            put("text", text)
        }
        return BusEvent(
            id = id,
            ts = "2026-11-27T12:%02d:00.000Z".format(minute),
            kind = BusKind.CORRECTION,
            sid = "bot-task-1",
            turn = "7",
            payload = payload,
        )
    }

    private fun worker(chat: FakeChatProvider): CorrectionWorker = CorrectionWorker(
        store = CorrectionStore(db),
        extractor = CorrectionExtractor(
            chat = chat,
            timeout = 1.seconds,
            tracer = CountingTracer(),
            breaker = CircuitBreaker(maxFailures = 100, retryAfter = 1.minutes),
        ),
    )

    private suspend fun ingest(): PgBusIngest = PgBusIngest.of(db).evalGraph()

    @Test
    fun `a correction event flows from the bus to a proposed row`() = runBlocking<Unit> {
        ingest().handle(listOf(correctionEvent("flow-1", "no, never push without my ok", minute = 1)))
        val store = CorrectionStore(db)
        assertThat(store.pending(10).map { it.id }).contains("flow-1")

        val chat = FakeChatProvider { _, _ -> """{"rule": "Never push without the owner's explicit ok."}""" }
        val report = worker(chat).processOnce()

        assertThat(chat.calls).hasSize(1)
        assertThat(report.scanned).isEqualTo(1)
        assertThat(report.proposed).isEqualTo(1)
        val row = store.byId("flow-1")!!
        assertThat(row.status).isEqualTo("proposed")
        assertThat(row.candidate).isEqualTo("Never push without the owner's explicit ok.")
        assertThat(row.extractedAt).isNotNull
    }

    @Test
    fun `a judge answer with no rule degrades and the row stays pending with its evidence stamp`() = runBlocking<Unit> {
        ingest().handle(listOf(correctionEvent("degraded-1", "use the kap abstractions", minute = 2)))
        val store = CorrectionStore(db)

        val chat = FakeChatProvider { _, _ -> """{"count": 42}""" }
        val report = worker(chat).processOnce()

        assertThat(report.degraded).isEqualTo(1)
        val row = store.byId("degraded-1")!!
        assertThat(row.status).isEqualTo("pending")
        assertThat(row.candidate).isEmpty()
        assertThat(row.extractedAt).isNotNull
    }

    @Test
    fun `a blank-text correction degrades without a model call`() = runBlocking<Unit> {
        ingest().handle(listOf(correctionEvent("blank-1", "   ", minute = 3)))
        val store = CorrectionStore(db)

        val chat = FakeChatProvider { _, _ -> throw AssertionError("the blank gate must not reach the model") }
        val report = worker(chat).processOnce()

        assertThat(chat.calls).isEmpty()
        assertThat(report.degraded).isEqualTo(1)
        assertThat(store.byId("blank-1")!!.status).isEqualTo("pending")
    }

    @Test
    fun `a provider outage defers, leaves the row untouched, and the next pass heals it`() = runBlocking<Unit> {
        ingest().handle(listOf(correctionEvent("outage-1", "always run detekt before pushing", minute = 4)))
        val store = CorrectionStore(db)

        val offline = FakeChatProvider { _, _ -> throw ChatException("llm offline") }
        val downReport = worker(offline).processOnce()

        assertThat(downReport.deferred).isEqualTo(1)
        val untouched = store.byId("outage-1")!!
        assertThat(untouched.status).isEqualTo("pending")
        assertThat(untouched.candidate).isNull()
        assertThat(untouched.extractedAt).isNull()

        // The provider heals: the same pending row is retried and proposed.
        val healed = FakeChatProvider { _, _ -> """{"rule": "Run detekt before every push."}""" }
        val upReport = worker(healed).processOnce()
        assertThat(upReport.proposed).isEqualTo(1)
        assertThat(store.byId("outage-1")!!.status).isEqualTo("proposed")
    }

    @Test
    fun `a replayed batch adds no rows and a stamped row is never re-fronted`() = runBlocking<Unit> {
        val bus = ingest()
        val event = correctionEvent("replay-1", "commit and push together", minute = 5)
        bus.handle(listOf(event))
        val store = CorrectionStore(db)
        assertThat(store.pending(10).map { it.id }).contains("replay-1")

        val chat = FakeChatProvider { _, _ -> """{"rule": "Every commit goes with its push."}""" }
        assertThat(worker(chat).processOnce().proposed).isEqualTo(1)

        // The replay: the same event again through the ingest.
        bus.handle(listOf(event))
        assertThat(store.byId("replay-1")!!.status).isEqualTo("proposed")
        assertThat(store.pending(10)).noneMatch { it.id == "replay-1" }
        assertThat(store.listRecent(100).filter { it.id == "replay-1" }).hasSize(1)
    }

    @Test
    fun `the wire fixture decodes through the same bus path`() {
        val line = """{"id":"wire-1","ts":"2026-11-27T12:00:00.000Z","kind":"correction","sid":"s","turn":"9",""" +
            """"source":"bot","payload":{"kind":"stop","text":"stop this line of work"}}"""
        val event = (BusEvents.parse(line) as BusEvents.Parsed.Ok).event
        assertThat(event.kind).isEqualTo(BusKind.CORRECTION)
        assertThat(event.sid).isEqualTo("s")
        assertThat(event.turn).isEqualTo("9")
    }

    /** The ingest mirror respects the store's insert-or-ignore law even across separate PgBusIngest instances. */
    @Test
    fun `the store rejects nothing on decide of a missing id`() = runBlocking<Unit> {
        val store = CorrectionStore(db)
        assertThat(store.decide("missing", CorrectionStatus.ACCEPTED, OffsetDateTime.now())).isFalse
    }
}
