package dev.lattenero.brain.eval

import dev.lattenero.brain.config.Config
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID
import kotlin.time.TimeSource

/** A model on the matrix axis, with an optional embedding dimension (`model@dim`). */
data class ModelSpec(val id: String, val dim: Int? = null) {
    override fun toString(): String = if (dim == null) id else "$id@$dim"

    companion object {
        /**
         * `model@dim` takes the dimension of an embedding model; a plain
         * `model` keeps the configured one. The separator is `@`, not `:`,
         * because model tags are full of colons (`qwen2.5:3b`).
         */
        fun parse(spec: String): ModelSpec {
            val trimmed = spec.trim()
            require(trimmed.isNotEmpty()) { "empty model in the matrix" }
            val at = trimmed.lastIndexOf('@')
            val dim = if (at > 0) trimmed.substring(at + 1).toIntOrNull() else null
            if (dim != null) require(dim > 0) { "the dimension of $trimmed must be positive" }
            return if (dim == null) ModelSpec(trimmed) else ModelSpec(trimmed.substring(0, at), dim)
        }

        fun parseList(specs: String): List<ModelSpec> =
            specs.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { parse(it) }
    }
}

/** One line of the comparison: one model on one suite, with the numbers the decision needs. */
@Serializable
data class MatrixRow(
    val suite: String,
    val kind: EvalKind,
    val axis: EvalAxis,
    val model: String,
    val provider: String,
    val passed: Int,
    val total: Int,
    val gate: Double,
    val ok: Boolean,
    val p50Ms: Long,
    val p95Ms: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val costUsd: Double,
    val costKnown: Boolean,
    val durationMs: Long,
    val supportChecked: Int? = null,
    val supported: Int? = null,
) {
    val passRate: Double get() = if (total == 0) 0.0 else passed.toDouble() / total
}

/** What to use for a kind of turn, computed from the matrix and stated with its numbers. */
@Serializable
data class TurnRecommendation(
    val turn: String,
    val suite: String,
    val model: String,
    val reason: String,
)

/** The comparative artifact: a model × suite table, the recommendation per turn, and the runs behind it. */
@Serializable
data class EvalMatrixReport(
    val matrixId: String,
    val suites: List<String>,
    val axis: EvalAxis,
    val models: List<String>,
    val startedAt: String,
    val durationMs: Long,
    val rows: List<MatrixRow>,
    val recommendations: List<TurnRecommendation>,
    val runs: List<EvalReport>,
)

/**
 * `eval --matrix a,b --suite s`: the SAME suites against every model, one
 * fresh database per run, one `eval_runs` row per (suite, model) sharing a
 * `matrix_id`. The matrix reports, it does not gate: a model below the gate is
 * a finding in the table, not a red build — the cheap gate stays `make eval`.
 */
class EvalMatrixRunner(
    private val config: Config,
    private val suites: List<EvalSuite>,
    private val models: List<ModelSpec>,
    private val prices: PriceTable = PriceTable.load(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val wallClock: () -> Instant = Instant::now,
) {
    fun run(): EvalMatrixReport {
        require(models.size >= 2) { "a matrix needs at least two models, got ${models.size}" }
        require(suites.isNotEmpty()) { "a matrix needs at least one suite" }
        val axes = suites.map { it.axis }.distinct()
        require(axes.size == 1) {
            "a matrix compares one axis at a time: ${suites.joinToString { "${it.name}(${it.axis.label})" }}"
        }
        val axis = axes.single()
        suites.forEach { it.validate() }
        val matrixId = UUID.randomUUID().toString()
        val startedAt = wallClock()
        val mark = timeSource.markNow()
        val runs = suites.flatMap { suite ->
            models.map { spec ->
                EvalRunner(
                    configFor(spec, axis),
                    suite,
                    EvalOptions(
                        gate = suite.gate,
                        prices = prices,
                        matrixId = matrixId,
                        timeSource = timeSource,
                        wallClock = wallClock,
                    ),
                    // The embedding dimension of an eval database follows the
                    // model under test; the ledger stays in the deployment's.
                    ledger = config,
                ).run()
            }
        }
        return EvalMatrixReport(
            matrixId = matrixId,
            suites = suites.map { it.name },
            axis = axis,
            models = models.map { it.toString() },
            startedAt = startedAt.toString(),
            durationMs = mark.elapsedNow().inWholeMilliseconds,
            rows = runs.map { row(it) },
            recommendations = EvalRecommendations.forRuns(runs, suites),
            runs = runs,
        )
    }

    private fun configFor(spec: ModelSpec, axis: EvalAxis): Config = when (axis) {
        EvalAxis.CHAT -> {
            require(spec.dim == null) { "the chat axis takes no dimension, but ${spec.id} declares @${spec.dim}" }
            config.copy(chatModel = spec.id)
        }
        EvalAxis.EMBED -> {
            require(config.embedProvider == "ollama") {
                "the embed matrix swaps the ollama embedding model, but embed_provider is '${config.embedProvider}'"
            }
            config.copy(ollamaModel = spec.id, embedDim = spec.dim ?: config.embedDim)
        }
    }

    private fun row(report: EvalReport): MatrixRow = MatrixRow(
        suite = report.suite,
        kind = report.kind,
        axis = report.axis,
        model = report.model,
        provider = report.provider,
        passed = report.passed,
        total = report.total,
        gate = report.gate,
        ok = report.ok,
        p50Ms = report.metrics.p50Ms,
        p95Ms = report.metrics.p95Ms,
        promptTokens = report.metrics.promptTokens,
        completionTokens = report.metrics.completionTokens,
        costUsd = report.metrics.costUsd,
        costKnown = report.metrics.costKnown,
        durationMs = report.durationMs,
        supportChecked = report.metrics.supportChecked,
        supported = report.metrics.supported,
    )
}

/**
 * The own-use part: pick the model per kind of turn from measured numbers.
 * Order: the gate (quality first), then declared cost, then p50 latency, then
 * the model id so the choice is deterministic. Pure — a function of the runs.
 */
object EvalRecommendations {
    fun forRuns(runs: List<EvalReport>, suites: List<EvalSuite>): List<TurnRecommendation> = suites.map { suite ->
        val candidates = runs.filter { it.suite == suite.name }
        val winner = best(candidates)
        TurnRecommendation(
            turn = suite.kind.turn,
            suite = suite.name,
            model = winner?.model ?: "none",
            reason = reason(suite, winner, candidates.filter { it !== winner }),
        )
    }

    fun best(candidates: List<EvalReport>): EvalReport? = candidates.minWithOrNull(
        compareByDescending<EvalReport> { it.passed.toDouble() / it.total.coerceAtLeast(1) }
            .thenBy { if (it.metrics.costKnown) it.metrics.costUsd else Double.MAX_VALUE }
            .thenBy { it.metrics.p50Ms }
            .thenBy { it.model },
    )

    private fun reason(suite: EvalSuite, winner: EvalReport?, others: List<EvalReport>): String {
        if (winner == null) return "no run for ${suite.name}"
        val head = "${winner.model}: ${winner.passed}/${winner.total} at gate ${suite.gate} " +
            "(p50 ${winner.metrics.p50Ms} ms, " +
            "${winner.metrics.promptTokens} in / ${winner.metrics.completionTokens} out tokens, ${cost(winner)})"
        val tail = if (others.isEmpty()) {
            "no other model to compare"
        } else {
            "beats " + others.joinToString("; ") {
                "${it.model}: ${it.passed}/${it.total}, p50 ${it.metrics.p50Ms} ms, ${cost(it)}"
            }
        }
        return "$head; $tail. ${tieBreak(suite.kind)}"
    }

    private fun cost(report: EvalReport): String =
        if (report.metrics.costKnown) "cost ${formatUsd(report.metrics.costUsd)} USD" else "cost unknown"

    private fun tieBreak(kind: EvalKind): String = when (kind) {
        EvalKind.EXTRACTION ->
            "Every miss is a memory the bot never gets back, so quality wins over a slower call."
        EvalKind.GROUNDED ->
            "An answer the question can be checked against first; among models that clear the gate, the cheapest one."
        EvalKind.RECALL ->
            "Retrieval runs on every query: quality first, then latency and declared cost."
    }
}

/** The matrix artifact: the comparison table, the recommendation per turn and the per-case grid. */
object EvalMatrixMarkdown {
    fun render(report: EvalMatrixReport): String = buildString {
        appendLine("# eval matrix: ${report.suites.joinToString(" + ")} (${report.axis.label} axis)")
        appendLine()
        appendLine("- matrix: ${report.matrixId}")
        appendLine("- started: ${report.startedAt} · ${report.durationMs} ms")
        appendLine("- models: ${report.models.joinToString(", ")}")
        appendLine("- suites: ${report.suites.joinToString(", ")}")
        appendLine()
        appendLine(comparisonTable(report))
        appendLine("## Recommendation by turn")
        appendLine()
        for (recommendation in report.recommendations) {
            appendLine("- **${recommendation.turn}** → `${recommendation.model}` — ${recommendation.reason}")
        }
        appendLine()
        appendLine("## Per case")
        appendLine()
        for (suite in report.suites) {
            appendLine("### $suite")
            appendLine()
            appendLine(caseGrid(report, suite))
        }
    }

    fun comparisonTable(report: EvalMatrixReport): String = buildString {
        val header = listOf(
            "suite", "model", "pass/total", "gate", "p50 ms", "p95 ms",
            "tokens in", "tokens out", "cost USD", "support",
        )
        appendLine("| ${header.joinToString(" | ")} |")
        appendLine("|${header.joinToString("|") { "---" }}|")
        for (row in report.rows) {
            val cost = if (row.costKnown) formatUsd(row.costUsd) else "unknown"
            val support = row.supported?.let { "$it/${row.supportChecked}" } ?: "-"
            appendLine(
                "| ${row.suite} | ${row.model} | ${row.passed}/${row.total} | ${row.gate} | " +
                    "${row.p50Ms} | ${row.p95Ms} | ${row.promptTokens} | ${row.completionTokens} | $cost | $support |",
            )
        }
    }

    private fun verdict(run: EvalReport, caseId: String): String {
        val case = run.cases.firstOrNull { it.id == caseId }
        val why = case?.failure?.replace("|", "/")?.take(60)
        return when {
            case == null -> "-"
            case.ok -> "ok"
            why == null -> "FAIL"
            else -> "FAIL ($why)"
        }
    }

    fun caseGrid(report: EvalMatrixReport, suite: String): String {
        val runs = report.runs.filter { it.suite == suite }
        val caseIds = runs.flatMap { run -> run.cases.map { it.id } }.distinct()
        return buildString {
            appendLine("| case | ${runs.joinToString(" | ") { it.model }} |")
            appendLine("|---|${runs.joinToString("|") { "---" }}|")
            for (caseId in caseIds) {
                val verdicts = runs.map { run -> verdict(run, caseId) }
                appendLine("| $caseId | ${verdicts.joinToString(" | ")} |")
            }
        }
    }
}
