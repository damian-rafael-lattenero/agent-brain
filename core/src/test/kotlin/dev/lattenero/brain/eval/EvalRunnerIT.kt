package dev.lattenero.brain.eval

import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path

/** The eval gate against the real local model: a fresh database, the shipped corpus, the shipped questions. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EvalRunnerIT {
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeAll
    fun up() {
        TestEnv.needPostgres()
        TestEnv.needOllama()
    }

    @AfterAll
    fun down() = Unit

    private fun questions(): List<EvalQuestion> {
        val tmp: Path = Files.createTempFile("eval-questions", ".json")
        Files.copy(javaClass.classLoader.getResourceAsStream("eval/questions.json")!!, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return EvalRunner.loadQuestions(tmp)
    }

    @Test
    fun `the recall gate passes and lands in eval_runs`() {
        val config = TestDatabase.config("evalrunner", provider = "ollama")
        val report = EvalRunner(config, questions(), gate = 0.9).run()
        assertThat(report.total).isEqualTo(20)
        assertThat(report.provider).isEqualTo("ollama")
        assertThat(report.embedModel).isEqualTo(TestEnv.base.ollamaModel)
        assertThat(report.passed).withFailMessage(report.rows.filter { !it.ok }.joinToString { it.question }).isGreaterThanOrEqualTo(18)
        assertThat(report.ok).isTrue()

        // The ledger row landed in the run's database (the same table serve reads).
        val count = dev.lattenero.brain.db.Database(config).use { db ->
            kotlinx.coroutines.runBlocking {
                db.withConnection { conn ->
                    conn.createStatement().use { st ->
                        st.executeQuery("SELECT count(*) FROM eval_runs WHERE suite = 'recall-fixture' AND model = '${report.embedModel}'").use { rs ->
                            rs.next(); rs.getLong(1)
                        }
                    }
                }
            }
        }
        assertThat(count).isEqualTo(1)

        val markdown = EvalRunner.markdown(report)
        assertThat(markdown).contains("# eval: recall-fixture").contains("${report.passed}/20 passed").contains("| question |")
    }

    @Test
    fun `questions load from the shipped resource and are all answerable by the corpus`() {
        val qs = questions()
        assertThat(qs).hasSize(20)
        val names = javaClass.classLoader.getResourceAsStream("eval/corpus/index.txt")!!.bufferedReader().readLines().filter { it.endsWith(".md") }.toSet()
        qs.forEach { q -> assertThat(q.uri.substringAfterLast('/')).isIn(names) }
    }
}
