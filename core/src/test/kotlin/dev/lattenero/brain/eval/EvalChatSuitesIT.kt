package dev.lattenero.brain.eval

import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.config.Config
import dev.lattenero.brain.db.Database
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The product suites against the real local models: extraction, the composed
 * answer with a citation, and the matrix. Every assertion is about the harness
 * (graded, measured, persisted), not about a model's quality — quality is what
 * the report shows, and a model below the gate is a finding, not a broken test.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EvalChatSuitesIT {

    @BeforeAll
    fun up() {
        TestEnv.needPostgres()
        TestEnv.needOllama()
    }

    @Test
    fun `the extraction suite is graded end-to-end and persists tokens and latency`() {
        val config = TestDatabase.config("evalx", provider = "ollama")
        val suite = EvalSuites.load("extraction-core")
        val report = EvalRunner(config, suite).run()

        assertThat(report.kind).isEqualTo(EvalKind.EXTRACTION)
        assertThat(report.axis).isEqualTo(EvalAxis.CHAT)
        assertThat(report.model).isEqualTo(config.chatModel)
        assertThat(report.total).isEqualTo(suite.cases.size)
        assertThat(report.metrics.promptTokens).isGreaterThan(0)
        assertThat(report.metrics.completionTokens).isGreaterThan(0)
        assertThat(report.metrics.costKnown).isTrue()
        assertThat(report.metrics.costUsd).isZero()
        assertThat(report.metrics.p95Ms).isGreaterThanOrEqualTo(report.metrics.p50Ms)
        assertThat(report.cases).allSatisfy { case ->
            assertThat(case.ok).isEqualTo(case.failure == null)
            assertThat(case.durationMs).isGreaterThanOrEqualTo(0)
        }
        // The factual turns are extractable by the configured local model; the
        // judgement cases are allowed to fail and are what the matrix reports.
        assertThat(report.cases.count { it.ok }).isGreaterThanOrEqualTo(2)

        val stored = storedRuns(config).filter { it.runId == report.runId }
        assertThat(stored).hasSize(1)
        assertThat(stored.single().kind).isEqualTo("extraction")
        assertThat(stored.single().axis).isEqualTo("chat")
        assertThat(stored.single().promptTokens).isEqualTo(report.metrics.promptTokens)
        assertThat(stored.single().p50Ms).isNotNull
    }

    @Test
    fun `the grounded suite cites the expected document and states the fact`() {
        val config = TestDatabase.config("evalg", provider = "ollama")
        val suite = EvalSuites.load("grounded-citation")
        val report = EvalRunner(config, suite).run()

        assertThat(report.kind).isEqualTo(EvalKind.GROUNDED)
        assertThat(report.model).isEqualTo(config.chatModel)
        assertThat(report.embedModel).isEqualTo(config.ollamaModel)
        assertThat(report.total).isEqualTo(suite.cases.size)
        assertThat(report.cases).allSatisfy { case ->
            assertThat(case.answer).isNotNull()
            assertThat(case.citedUris).isNotEmpty()
        }
        // gate 0.8 over 5 cases: the local model is expected to clear at least 3.
        assertThat(report.passed).isGreaterThanOrEqualTo(3)

        val markdown = EvalRunner.markdown(report)
        assertThat(markdown).contains("# eval: grounded-citation (grounded)").contains("| case | question | expected | cited | answer |")
    }

    @Test
    fun `the matrix compares two chat models and shares one matrix id in the ledger`() {
        TestEnv.needOllamaModel(SECOND_MODEL)
        val config = TestDatabase.config("evalm", provider = "ollama")
        val suite = EvalSuites.load("extraction-core")
        val report = EvalMatrixRunner(
            config,
            listOf(suite),
            ModelSpec.parseList("${config.chatModel},$SECOND_MODEL"),
        ).run()

        assertThat(report.rows).hasSize(2)
        assertThat(report.rows.map { it.model }).containsExactly(config.chatModel, SECOND_MODEL)
        assertThat(report.rows.map { it.suite }).containsOnly("extraction-core")
        assertThat(report.rows).allSatisfy { row ->
            assertThat(row.promptTokens).isGreaterThan(0)
            assertThat(row.costKnown).isTrue()
        }
        assertThat(report.recommendations).hasSize(1)
        assertThat(report.recommendations.single().model).isIn(config.chatModel, SECOND_MODEL)
        assertThat(report.runs).hasSize(2)

        val stored = storedRuns(config, matrix = true)
        assertThat(stored).hasSize(2)
        assertThat(stored.map { it.matrixId }).containsOnly(report.matrixId)
        assertThat(EvalMatrixMarkdown.render(report))
            .contains("## Recommendation by turn")
            .contains("| suite | model | pass/total |")
    }

    private fun storedRuns(config: Config, matrix: Boolean = false): List<StoredRun> = Database(config).use { db ->
        runBlocking {
            EvalLedger(db, config.embedDim).latest(matrix = matrix, limit = EvalLedger.MAX_LIMIT)
        }
    }

    private companion object {
        const val SECOND_MODEL = "qwen2.5:3b"
    }
}
