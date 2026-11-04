package dev.lattenero.brain.eval

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** The identity of what a run measured: the model under test plus the context it ran in. */
@Serializable
data class ModelIdentity(
    val model: String,
    val provider: String,
    val embedModel: String,
    val chatModel: String? = null,
)

/** USD with six decimals, locale-independent: the column every artifact shares. */
internal fun formatUsd(value: Double): String = String.format(Locale.ROOT, "%.6f", value)

/**
 * Nearest-rank percentiles over a case's measured durations: the value at
 * `ceil(p/100 * n)` in the sorted list, 0 for no samples. Pure and total, so
 * the latency columns of a report are reproducible from the same numbers.
 */
object Percentiles {
    const val PERCENT = 100.0

    fun of(values: List<Long>, percentile: Double): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val rank = if (percentile <= 0.0) 1 else ceilRank(percentile, sorted.size)
        return sorted[rank - 1]
    }

    private fun ceilRank(percentile: Double, size: Int): Int =
        kotlin.math.ceil(percentile / PERCENT * size).toInt().coerceIn(1, size)
}

/**
 * Token accounting. Chat providers report the real counts (ollama's
 * prompt_eval_count/eval_count); the embedding endpoint reports none, so the
 * estimated flag travels with the number instead of a made-up precision.
 */
object Tokens {
    const val CHARS_PER_TOKEN = 4L
    private const val ROUNDING_UP = CHARS_PER_TOKEN - 1

    /** A declared heuristic: four characters per token, ceiling. */
    fun estimate(text: String): Long = if (text.isEmpty()) 0 else (text.length + ROUNDING_UP) / CHARS_PER_TOKEN

    fun estimate(texts: Iterable<String>): Long = texts.sumOf { estimate(it) }
}

/** One price row: USD per 1M prompt/completion tokens; null means unknown, never zero. */
@Serializable
data class PriceEntry(
    val provider: String,
    val model: String,
    val promptPer1m: Double? = null,
    val completionPer1m: Double? = null,
    val note: String = "",
)

/** A price and whether it is known: an unpriceable provider reports unknown, not a free ride. */
data class Cost(val usd: Double, val known: Boolean)

/**
 * The declared price table. Resolution is exact (provider, model) first, then
 * the provider's wildcard row. Local inference is a declared 0.0 — the report
 * says so — while a paid provider with no declared price yields
 * `known = false` and a cost column that reads "unknown".
 */
@Serializable
data class PriceTable(
    val currency: String = "USD",
    val entries: List<PriceEntry> = emptyList(),
) {
    fun entryFor(provider: String, model: String): PriceEntry? =
        entries.firstOrNull { it.provider == provider && it.model == model }
            ?: entries.firstOrNull { it.provider == provider && it.model == WILDCARD }

    fun cost(provider: String, model: String, promptTokens: Long, completionTokens: Long): Cost {
        val entry = entryFor(provider, model)
        val prompt = entry?.promptPer1m
        val completion = entry?.completionPer1m
        return if (prompt == null || completion == null) {
            Cost(0.0, known = false)
        } else {
            Cost((promptTokens * prompt + completionTokens * completion) / TOKENS_PER_PRICE_UNIT, known = true)
        }
    }

    companion object {
        const val WILDCARD = "*"
        const val TOKENS_PER_PRICE_UNIT = 1_000_000.0
        const val RESOURCE = "eval/prices.json"
        const val DEFAULT_FILE = "evals/prices.json"

        private val json = Json { ignoreUnknownKeys = true }

        /** The declared table from the classpath, else from `evals/prices.json`, else empty (all unknown). */
        fun load(): PriceTable {
            val loader = PriceTable::class.java.classLoader
            val text = loader.getResourceAsStream(RESOURCE)?.bufferedReader()?.use { it.readText() }
                ?: Path.of(DEFAULT_FILE).takeIf { Files.isRegularFile(it) }?.let { Files.readString(it) }
                ?: return PriceTable()
            return parse(text)
        }

        fun parse(text: String): PriceTable = json.decodeFromString(PriceTable.serializer(), text)
    }
}

/** The aggregates of one suite run: pass/fail, latency, tokens, declared cost. */
@Serializable
data class SuiteMetrics(
    val p50Ms: Long,
    val p95Ms: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val costUsd: Double,
    val costKnown: Boolean,
    val durationMs: Long,
    /** Recall only: how many cases had a needle and how many were supported by the top hit. */
    val supportChecked: Int? = null,
    val supported: Int? = null,
)

/** Builds the suite aggregates from finished cases; pure, so a report is a function of its cases. */
object SuiteMetricsCalculator {
    private const val P50 = 50.0
    private const val P95 = 95.0

    fun of(cases: List<CaseResult>, prices: PriceTable, identity: ModelIdentity, durationMs: Long): SuiteMetrics {
        val promptTokens = cases.sumOf { it.promptTokens }
        val completionTokens = cases.sumOf { it.completionTokens }
        val cost = prices.cost(identity.provider, identity.model, promptTokens, completionTokens)
        val supportChecked = cases.count { it.supported != null }.takeIf { it > 0 }
        return SuiteMetrics(
            p50Ms = Percentiles.of(cases.map { it.durationMs }, P50),
            p95Ms = Percentiles.of(cases.map { it.durationMs }, P95),
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            costUsd = cost.usd,
            costKnown = cost.known,
            durationMs = durationMs,
            supportChecked = supportChecked,
            supported = supportChecked?.let { cases.count { case -> case.supported == true } },
        )
    }
}
