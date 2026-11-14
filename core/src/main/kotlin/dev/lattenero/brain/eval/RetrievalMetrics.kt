package dev.lattenero.brain.eval

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.log2

/**
 * The graded retrieval columns of one recall run, next to the binary gate:
 * a suite can pass rank@5 with a miserable ordering, and only these numbers
 * say so. The trend across releases and the rerank A/Bs (plan brujula v2,
 * B1/B2) are decided on these, not on pass/fail.
 */
@Serializable
data class RetrievalSummary(
    val mrr: Double,
    val recall1: Double,
    val recall5: Double,
    val ndcg5: Double,
    val cases: Int,
)

/** Score columns read to three decimals, locale-independent, like [formatUsd]. */
internal fun formatScore(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

/**
 * Graded retrieval metrics over the ranks the recall path already measures
 * ([CaseResult.rank]: the 1-based position of the expected document, null
 * when it did not land within the window). Pure and total, like
 * [Percentiles] — a report's retrieval columns are a function of its cases,
 * so a ranking change must BEAT these numbers or it does not ship.
 *
 * With one relevant document per query, per-case nDCG@k is
 * `1/log2(rank+1)` when the rank is within k (the ideal single hit sits at
 * rank 1: `1/log2(2)`), else 0; the suite value is the mean.
 */
object RetrievalMetrics {
    const val MIN_K = 1

    /** Mean of `1/rank` over the cases; a miss contributes 0. Empty input scores 0.0, never NaN. */
    fun mrr(ranks: List<Int?>): Double =
        if (ranks.isEmpty()) 0.0 else ranks.sumOf { rank -> rank?.let { 1.0 / it } ?: 0.0 } / ranks.size

    /** The fraction of cases whose expected document lands within the top [k]. Empty input scores 0.0. */
    fun recallAt(ranks: List<Int?>, k: Int): Double {
        require(k >= MIN_K) { "recall@k needs k >= $MIN_K, got $k" }
        if (ranks.isEmpty()) return 0.0
        return ranks.count { it != null && it <= k } / ranks.size.toDouble()
    }

    /** The mean binary-relevance nDCG@[k] over the cases. Empty input scores 0.0. */
    fun ndcgAt(ranks: List<Int?>, k: Int): Double {
        require(k >= MIN_K) { "nDCG@k needs k >= $MIN_K, got $k" }
        if (ranks.isEmpty()) return 0.0
        return ranks.map { rank ->
            if (rank != null && rank in MIN_K..k) 1.0 / log2(rank + 1.0) else 0.0
        }.average()
    }

    /** The summary a recall report carries: the gate's window [k] grades every column. */
    fun summary(ranks: List<Int?>, k: Int): RetrievalSummary = RetrievalSummary(
        mrr = mrr(ranks),
        recall1 = recallAt(ranks, 1),
        recall5 = recallAt(ranks, k),
        ndcg5 = ndcgAt(ranks, k),
        cases = ranks.size,
    )
}
