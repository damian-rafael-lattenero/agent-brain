package dev.lattenero.brain.eval

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class EvalMatrixTest {
    private fun report(
        model: String,
        passed: Int,
        total: Int = 6,
        p50: Long = 100,
        cost: Double = 0.0,
        costKnown: Boolean = true,
        suite: String = "extraction-core",
        kind: EvalKind = EvalKind.EXTRACTION,
        cases: List<CaseResult> = emptyList(),
    ) = EvalReport(
        runId = "run-$model",
        suite = suite,
        kind = kind,
        axis = if (kind == EvalKind.RECALL) EvalAxis.EMBED else EvalAxis.CHAT,
        model = model,
        provider = "ollama",
        embedModel = "nomic-embed-text",
        gate = 0.6,
        passed = passed,
        total = total,
        ok = passed.toDouble() / total >= 0.6,
        metrics = SuiteMetrics(
            p50Ms = p50,
            p95Ms = p50 + 10,
            promptTokens = 1_000,
            completionTokens = 100,
            costUsd = cost,
            costKnown = costKnown,
            durationMs = p50 * total,
        ),
        cases = cases,
        startedAt = "2026-11-04T00:00:00Z",
        durationMs = p50 * total,
    )

    @Test
    fun `a model spec keeps the tag and reads an explicit dimension`() {
        assertThat(ModelSpec.parse("qwen2.5:3b")).isEqualTo(ModelSpec("qwen2.5:3b"))
        assertThat(ModelSpec.parse("nomic-embed-text@768")).isEqualTo(ModelSpec("nomic-embed-text", 768))
        assertThat(ModelSpec.parseList("qwen2.5:1.5b, qwen2.5:3b"))
            .containsExactly(ModelSpec("qwen2.5:1.5b"), ModelSpec("qwen2.5:3b"))
        assertThatThrownBy { ModelSpec.parseList("a@0") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(ModelSpec.parseList(" ")).isEmpty()
    }

    @Test
    fun `quality wins, then declared cost, then latency, then the model name`() {
        val fourOfSix = report(model = "small", passed = 4, p50 = 100)
        val fiveOfSix = report(model = "big", passed = 5, p50 = 900)
        assertThat(EvalRecommendations.best(listOf(fourOfSix, fiveOfSix))!!.model).isEqualTo("big")

        val cheap = report(model = "cheap", passed = 5)
        val pricey = report(model = "pricey", passed = 5, cost = 0.50)
        assertThat(EvalRecommendations.best(listOf(pricey, cheap))!!.model).isEqualTo("cheap")

        val fast = report(model = "fast", passed = 5, p50 = 50)
        assertThat(EvalRecommendations.best(listOf(cheap, fast))!!.model).isEqualTo("fast")

        val a = report(model = "aaa", passed = 5, p50 = 50)
        assertThat(EvalRecommendations.best(listOf(fast, a))!!.model).isEqualTo("aaa")
    }

    @Test
    fun `a recommendation states the numbers of the winner and the runner-up`() {
        val suites = listOf(EvalSuites.load("extraction-core"))
        val recommendations = EvalRecommendations.forRuns(
            listOf(report(model = "small", passed = 4, p50 = 100), report(model = "big", passed = 5, p50 = 900)),
            suites,
        )
        assertThat(recommendations).hasSize(1)
        val recommendation = recommendations.single()
        assertThat(recommendation.model).isEqualTo("big")
        assertThat(recommendation.turn).contains("memory extraction")
        assertThat(recommendation.reason)
            .contains("5/6")
            .contains("small: 4/6")
            .contains("cost 0.000000 USD")
    }

    @Test
    fun `the matrix markdown is the comparison table, the recommendation and the per-case grid`() {
        val runs = listOf(
            report(
                model = "small",
                passed = 1,
                cases = listOf(CaseResult("case-1", true, 10), CaseResult("case-2", false, 20, failure = "missing fact")),
            ),
            report(
                model = "big",
                passed = 2,
                cases = listOf(CaseResult("case-1", true, 10), CaseResult("case-2", true, 30)),
            ),
        )
        val matrix = EvalMatrixReport(
            matrixId = "m-1",
            suites = listOf("extraction-core"),
            axis = EvalAxis.CHAT,
            models = listOf("small", "big"),
            startedAt = "2026-11-04T00:00:00Z",
            durationMs = 1_000,
            rows = runs.map {
                MatrixRow(
                    suite = it.suite, kind = it.kind, axis = it.axis, model = it.model, provider = it.provider,
                    passed = it.passed, total = it.total, gate = it.gate, ok = it.ok,
                    p50Ms = it.metrics.p50Ms, p95Ms = it.metrics.p95Ms,
                    promptTokens = it.metrics.promptTokens, completionTokens = it.metrics.completionTokens,
                    costUsd = it.metrics.costUsd, costKnown = it.metrics.costKnown, durationMs = it.durationMs,
                )
            },
            recommendations = EvalRecommendations.forRuns(runs, listOf(EvalSuites.load("extraction-core"))),
            runs = runs,
        )
        val markdown = EvalMatrixMarkdown.render(matrix)
        assertThat(markdown)
            .contains("# eval matrix: extraction-core (chat axis)")
            .contains("| suite | model | pass/total |")
            .contains("| extraction-core | small | 1/6 |")
            .contains("| extraction-core | big | 2/6 |")
            .contains("## Recommendation by turn")
            .contains("→ `big`")
            .contains("## Per case")
            .contains("| case | small | big |")
            .contains("FAIL (missing fact)")
        assertThat(EvalMatrixMarkdown.caseGrid(matrix, "extraction-core")).contains("case-2")
    }

    @Test
    fun `the ledger view renders the same columns for past runs`() {
        val runs = listOf(
            StoredRun(
                runId = "r-1", suite = "recall-core", kind = "recall", axis = "embed", model = "nomic-embed-text",
                provider = "ollama", passed = 20, total = 20, gate = 0.9, ok = true,
                p50Ms = 12, p95Ms = 30, promptTokens = 100, completionTokens = 0, ts = "2026-11-04T00:00:00Z",
            ),
        )
        assertThat(EvalLedgerMarkdown.render(runs))
            .contains("| suite | model | pass/total |")
            .contains("| recall-core | nomic-embed-text | 20/20 |")
            .contains("0.000000")
    }
}
