package dev.lattenero.brain.bus

import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.config.Config
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.Migrator
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kap.evalGraph
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant

/**
 * The ingest dedup idempotence law (plan E4; fp-refactor R3's "bus dedup by
 * id"), over generated batches against the real SQL in a disposable schema:
 * ingesting a batch TWICE leaves exactly the rows of ingesting it ONCE —
 * `PgBusIngest`'s `ON CONFLICT (id) DO NOTHING` absorbs the replay, the
 * counters say so, and the stored state is identical row for row (the
 * replay golden replays the whole bus; this is the same law with generated
 * inputs). Overlapping batches pin the corollary: an id's FIRST delivery
 * wins — the bus is append-only, a re-delivered id never rewrites history.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BusIngestIdempotenceIT {
    private lateinit var config: Config
    private lateinit var db: Database

    @BeforeAll
    fun up() {
        TestEnv.needPostgres()
        config = TestDatabase.config("ingestlaw")
        db = Database(config)
        runBlocking { Migrator(db, config.embedDim).apply() }
    }

    @AfterAll
    fun down() {
        if (::db.isInitialized) db.close()
    }

    /** Every event row as the law sees it: id plus all seven contract columns (payload as canonical jsonb text). */
    private suspend fun rows(): List<List<String?>> = db.withConnection { conn ->
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT id, ts::text, kind, sid, turn, source, payload::text FROM bus_events ORDER BY id",
            ).use { rs ->
                generateSequence {
                    if (!rs.next()) null else (1..7).map { rs.getString(it) }
                }.toList()
            }
        }
    }

    private suspend fun reset() {
        db.withConnection { conn -> conn.createStatement().use { it.executeUpdate("DELETE FROM bus_events") } }
    }

    private fun event(n: Int, salt: String): BusEvent = BusEvent(
        id = "ev-$n-$salt",
        ts = Instant.ofEpochSecond(1_700_000_000L + n).toString(),
        kind = BusEvents.KINDS.elementAt(n % BusEvents.KINDS.size),
        sid = "ses-${n % 7}",
        turn = "turn-${n % 3}",
        source = BusEvents.SOURCES.elementAt(n % BusEvents.SOURCES.size),
        payload = JsonObject(mapOf("prompt" to JsonPrimitive("generated prompt $n ($salt)"))),
    )

    private fun batch(salt: String): Arb<List<BusEvent>> =
        Arb.int(0, 8).map { size -> (0 until size).map { event(it, salt) } }

    @Test
    fun `ingesting a batch twice leaves exactly the rows of ingesting it once`() {
        runBlocking {
            checkAll(batch("a"), batch("b"), Arb.int(0, 8)) { first, second, tail ->
                reset()
                val ingest = PgBusIngest.of(db).evalGraph()

                ingest.handle(first)
                ingest.handle(second + (first.take(tail).map { it.copy(payload = JsonObject(mapOf("prompt" to JsonPrimitive("REWRITE")))) }))
                // A shared id re-delivered with a DIFFERENT payload must not rewrite the stored row:
                // everything ingested so far is the identity the twice-law starts from.
                val once = rows()
                val insertedOnce = ingest.stats.snapshot().inserted

                ingest.handle(first)
                ingest.handle(second + first.take(tail))
                val twice = rows()
                val counters = ingest.stats.snapshot()

                assertThat(twice).withFailMessage("state drifted on a re-ingest").isEqualTo(once)
                // Every re-delivered id was absorbed: the inserts stopped at the distinct ids.
                val distinct = (first + second).map { it.id }.toSet().size
                assertThat(counters.inserted).isEqualTo(insertedOnce)
                assertThat(twice.size).isEqualTo(distinct)
            }
        }
    }

    @Test
    fun `a batch replayed on a fresh schema inserts once and counts the replay as duplicates`() {
        runBlocking {
            checkAll(batch("c")) { events ->
                reset()
                val ingest = PgBusIngest.of(db).evalGraph()
                ingest.handle(events)
                val countersAfterOnce = ingest.stats.snapshot()
                assertThat(countersAfterOnce.inserted).isEqualTo(events.size.toLong())
                assertThat(countersAfterOnce.duplicates).isEqualTo(0L)
                val once = rows()

                ingest.handle(events)
                val counters = ingest.stats.snapshot()
                assertThat(counters.seen).isEqualTo(events.size * 2L)
                assertThat(counters.inserted).isEqualTo(events.size.toLong())
                assertThat(counters.duplicates).isEqualTo(events.size.toLong())
                assertThat(rows()).withFailMessage("state drifted on a re-ingest").isEqualTo(once)
            }
        }
    }
}
