// lang-ok: detector input — the Spanish rows are the translation pass's input.
package dev.lattenero.brain.memory

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.chat.FakeChatProvider
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.Migrator
import dev.lattenero.brain.db.sq.BrainDb
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * The English-corpus migration's laws: legacy rows translate IN PLACE
 * (identity kept — edges and turn linkage survive), the translation is
 * re-embedded with the CURRENT embed model, the originals live in the backup
 * table, `translated_at` marks the rows so an at-least-once replay is a
 * no-op, and an unparsable batch stays untranslated (never lies as done).
 * Each test owns a FRESH database — JUnit's order is unspecified, and the
 * migration's queue is exactly the shared state a test must not inherit.
 */
@Tag("integration")
class LegacyTranslationIT {

    private class Fixture {
        val db: Database
        val brainDb: BrainDb
        val migration: LegacyTranslation

        constructor(judge: FakeChatProvider, tag: String) {
            db = Database(TestDatabase.config(tag))
            runBlocking {
                Migrator(db, 768).apply()
            }
            brainDb = BrainDb(db.pool.asJdbcDriver())
            migration = LegacyTranslation(
                db, judge,
                embed = { _ -> FloatArray(768) },
                embedModel = "nomic-test",
            )
        }

        fun seedMemory(content: String) {
            // RETURNING query: executeAsOne is what RUNS the insert.
            val vector = "[" + (1..768).joinToString(",") { "0.1" } + "]"
            brainDb.memoriesQueries.insertMemory(
                sessionId = "s", turnId = "t-${content.take(12)}", kind = "fact", content = content,
                embedModel = "nomic-test", embedding = vector, sourceEventId = null,
            ).executeAsOne()
        }

        fun untranslated(): List<String> = brainDb.memoriesQueries
            .untranslatedBatch(100).executeAsList().map { it.content }
    }

    private val spanish = listOf(
        "El cutover de LoRA espera el gate, no la intuición",
        "El judge shadow corre cada noche a las 04:00",
        "La cola drena sola al terminar el turno",
    )

    private fun translatingJudge(): FakeChatProvider = FakeChatProvider { _, user ->
        val items = user.lines().filter { it.matches(Regex("\\d+\\. .*")) }.mapIndexed { idx, line ->
            """{"n":${idx + 1},"en":"EN: ${line.substringAfter(". ").trim()}"}"""
        }
        """{"items":[${items.joinToString(",")}]}"""
    }

    @Test
    fun `the migration translates in place, backs up the originals and never repeats`() {
        val f = Fixture(translatingJudge(), "trans")
        runBlocking {
            spanish.forEach { f.seedMemory(it) }

            val report = f.migration.run(maxItems = 10)

            assertThat(report.batches).isEqualTo(1)
            assertThat(report.translated).isEqualTo(3)
            assertThat(report.failedBatches).isZero()

            // The rows are English now — the untranslated queue is empty.
            assertThat(f.untranslated()).isEmpty()

            // The originals live in the backup table (the report names it).
            val backup = f.db.pool.connection.use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT content FROM ${report.backupTable} ORDER BY id").use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1)) }
                    }
                }
            }
            assertThat(backup).containsExactlyElementsOf(spanish)

            // A second run has nothing to do: the marker is the cursor.
            assertThat(f.migration.run(maxItems = 10).translated).isZero()
        }
        f.db.close()
    }

    @Test
    fun `an unparsable batch stays untranslated and fails loudly`() {
        val f = Fixture(FakeChatProvider { _, _ -> "the judge rambled" }, "unparsed")
        runBlocking {
            f.seedMemory("El runbook explica el deploy del daemon")

            val report = f.migration.run(maxItems = 10)

            assertThat(report.translated).isZero()
            assertThat(report.failedBatches).isEqualTo(1)
            // Evidence law: the row stays untranslated, ready for the next run.
            assertThat(f.untranslated()).hasSize(1)
        }
        f.db.close()
    }

    @Test
    fun `the verdict parse keeps positional numbers and rejects a missing items array`() {
        val parsed = LegacyTranslation.TranslationBatch.parse(
            """{"items":[{"n":1,"en":"a"},{"n":4,"en":"out of band"}]}""", 2,
        )
        // Out-of-band positions are noise, dropped.
        assertThat(parsed.keys).containsExactly(1)
        assertThat(parsed[1]).isEqualTo("a")

        org.assertj.core.api.Assertions.assertThatThrownBy {
            LegacyTranslation.TranslationBatch.parse("rambled", 2)
        }.isInstanceOf(IllegalArgumentException::class.java)
        org.assertj.core.api.Assertions.assertThatThrownBy {
            LegacyTranslation.TranslationBatch.parse("""{"other":[]}""", 2)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
