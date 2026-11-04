package dev.lattenero.brain.eval

import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.db.Database
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant

/** The cheap gate against the real local model: a fresh database, the shipped corpus, the shipped suite. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EvalRunnerIT {

    @BeforeAll
    fun up() {
        TestEnv.needPostgres()
        TestEnv.needOllama()
    }

    @Test
    fun `the recall gate passes and lands in eval_runs with its metrics`() {
        val config = TestDatabase.config("evalrunner", provider = "ollama")
        // The clock is injected (plan E7): a fixed wall instant and a test source that never advances
        // measure zero — any real System.currentTimeMillis read inside would break both assertions.
        val startedAt = Instant.parse("2026-11-03T12:00:00Z")
        val suite = EvalSuites.load(EvalSuite.DEFAULT)
        val report = EvalRunner(
            config,
            suite,
            EvalOptions(gate = 0.9, timeSource = kotlin.time.TestTimeSource(), wallClock = { startedAt }),
        ).run()
        assertThat(report.suite).isEqualTo("recall-core")
        assertThat(report.kind).isEqualTo(EvalKind.RECALL)
        assertThat(report.axis).isEqualTo(EvalAxis.EMBED)
        assertThat(report.total).isEqualTo(20)
        assertThat(report.provider).isEqualTo("ollama")
        assertThat(report.model).isEqualTo(TestEnv.base.ollamaModel)
        assertThat(report.passed)
            .withFailMessage(report.cases.filter { !it.ok }.joinToString { it.id })
            .isGreaterThanOrEqualTo(18)
        assertThat(report.ok).isTrue()
        assertThat(report.startedAt).isEqualTo(startedAt.toString())
        assertThat(report.durationMs).isZero
        assertThat(report.cases).allSatisfy({ assertThat(it.tokensEstimated).isTrue() })
        assertThat(report.metrics.costKnown).isTrue()
        assertThat(report.metrics.costUsd).isZero()
        assertThat(report.metrics.supportChecked).isNotNull()

        // The ledger row landed in the run's database (the same table serve reads).
        val stored = storedRuns(config, report.runId)
        assertThat(stored).hasSize(1)
        assertThat(stored.single().suite).isEqualTo("recall-core")
        assertThat(stored.single().kind).isEqualTo("recall")
        assertThat(stored.single().axis).isEqualTo("embed")
        assertThat(stored.single().passed).isEqualTo(report.passed)
        assertThat(stored.single().promptTokens).isEqualTo(report.metrics.promptTokens)
        assertThat(stored.single().ok).isTrue()

        val markdown = EvalRunner.markdown(report)
        assertThat(markdown)
            .contains("# eval: recall-core (recall)")
            .contains("${report.passed}/20 passed")
            .contains("latency p50")
            .contains("| case | question | expected | rank@5 | top hit | support |")
    }

    @Test
    fun `the shipped suites cover the shipped corpus and parse from the classpath`() {
        val suites = EvalSuites.shipped()
        assertThat(suites.map { it.name }).containsExactlyElementsOf(EvalSuite.SHIPPED)
        val corpus = javaClass.classLoader.getResourceAsStream("eval/corpus/index.txt")!!.bufferedReader()
            .use { reader -> reader.readLines().filter { it.endsWith(".md") }.toSet() }
        val recall = suites.single { it.kind == EvalKind.RECALL }
        assertThat(recall.cases).hasSize(20)
        recall.cases.filterIsInstance<RecallCase>().forEach { case ->
            assertThat(case.uri.substringAfterLast('/')).isIn(corpus)
        }
        suites.single { it.kind == EvalKind.GROUNDED }.cases.filterIsInstance<GroundedCase>().forEach { case ->
            assertThat(case.uri.substringAfterLast('/')).isIn(corpus)
            assertThat(case.needles).isNotEmpty
        }
    }

    private fun storedRuns(config: dev.lattenero.brain.config.Config, runId: String): List<StoredRun> =
        Database(config).use { db ->
            runBlocking {
                EvalLedger(db, config.embedDim).latest(limit = EvalLedger.MAX_LIMIT).filter { it.runId == runId }
            }
        }
}
