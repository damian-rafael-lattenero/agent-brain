package dev.lattenero.brain.memory

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.Migrator
import dev.lattenero.brain.db.sq.BrainDb
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.OffsetDateTime

/**
 * The trivial leg of the LoRA dataset (run 400's verdict: the empty-
 * extraction discipline is DATA): a processed short turn the pipeline kept
 * nothing from yields an EMPTY-extraction pair; a LONG zero-memory reply
 * stays out (an extraction miss has no honest label); positives keep their
 * canonical extraction.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoraExportIT {
    private lateinit var db: Database
    private lateinit var brainDb: BrainDb
    private lateinit var store: MemoryStore
    private lateinit var export: LoraExport

    @BeforeAll
    fun up() = runBlocking {
        TestEnv.needPostgres()
        db = Database(TestDatabase.config("lora"))
        Migrator(db, 4).apply()
        brainDb = BrainDb(db.pool.asJdbcDriver())
        store = MemoryStore(db)
        export = LoraExport(store)
    }

    @AfterAll
    fun down() {
        db.close()
    }

    /** One bus turn, marked processed (the export reads the processed pool). */
    /** One bus turn, marked processed (the export reads the processed pool). */
    private suspend fun seedTurn(eventId: String, prompt: String, reply: String) {
        val ts = OffsetDateTime.now()
        brainDb.busEventsQueries.ingestEvents(
            id = "$eventId-p", ts = ts, kind = "user.prompt", sid = "s-$eventId", turn = eventId,
            source = "telegram", payload = """{"prompt": ${kotlinx.serialization.json.JsonPrimitive(prompt)}}""",
        )
        brainDb.busEventsQueries.ingestEvents(
            id = "$eventId-c", ts = ts, kind = "turn.completed", sid = "s-$eventId", turn = eventId,
            source = "telegram", payload = """{"reply": ${kotlinx.serialization.json.JsonPrimitive(reply)}}""",
        )
        brainDb.busEventsQueries.markProcessed("$eventId-c")
    }

    @Test
    fun `a short zero-memory processed turn is a trivial pair with the empty extraction`() {
        runBlocking {
            seedTurn(
                "trivial1",
                "dale",
                "Listo, sigo con la próxima unidad.",
            )
            val trivials = export.trivialPairs(limit = 50, maxReplyChars = 200)
            val mine = trivials.filter { it.user.contains("Listo, sigo") }
            assertThat(mine).hasSize(1)
            val extraction = kotlinx.serialization.json.Json.parseToJsonElement(mine.single().assistant).jsonObject
            assertThat(extraction["facts"]?.toString()).isEqualTo("[]")
            assertThat(extraction["decisions"]?.toString()).isEqualTo("[]")
            assertThat(extraction["preferences"]?.toString()).isEqualTo("[]")
        }
    }

    @Test
    fun `a long zero-memory reply stays out - its label is unknown`() {
        runBlocking {
            val longReply = buildString { repeat(60) { append("esta linea no trivial llena el turno; ") } }
            seedTurn("miss1", "explica el deploy", longReply)
            val trivials = export.trivialPairs(limit = 50, maxReplyChars = 200)
            assertThat(trivials.filter { it.user.contains("esta linea no trivial") }).isEmpty()
        }
    }
}
