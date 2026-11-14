package dev.lattenero.brain.eval

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.math.log2

/**
 * The laws of the graded retrieval metrics: pure and total, so a report's
 * retrieval columns are a function of its cases. The gate alone cannot see
 * ordering (rank@2 and rank@5 both "pass") — these numbers can, and the
 * rerank A/Bs of plan brujula v2 B2 are decided on them.
 */
class RetrievalMetricsLawsTest {

    @Test
    fun `empty input scores zero, never NaN`() {
        assertThat(RetrievalMetrics.mrr(emptyList())).isZero()
        assertThat(RetrievalMetrics.recallAt(emptyList(), 5)).isZero()
        assertThat(RetrievalMetrics.ndcgAt(emptyList(), 5)).isZero()
        val summary = RetrievalMetrics.summary(emptyList(), 5)
        assertThat(summary.mrr).isZero()
        assertThat(summary.cases).isZero()
    }

    @Test
    fun `a miss scores zero, a total failure reads zero - never absent`() {
        val ranks = listOf<Int?>(null, null, null)
        assertThat(RetrievalMetrics.mrr(ranks)).isZero()
        assertThat(RetrievalMetrics.recallAt(ranks, 5)).isZero()
        assertThat(RetrievalMetrics.ndcgAt(ranks, 5)).isZero()
    }

    @Test
    fun `every case at rank 1 is the perfect score on every metric`() {
        val ranks = listOf<Int?>(1, 1, 1, 1)
        assertThat(RetrievalMetrics.mrr(ranks)).isEqualTo(1.0)
        assertThat(RetrievalMetrics.recallAt(ranks, 1)).isEqualTo(1.0)
        assertThat(RetrievalMetrics.recallAt(ranks, 5)).isEqualTo(1.0)
        assertThat(RetrievalMetrics.ndcgAt(ranks, 5)).isEqualTo(1.0)
    }

    @Test
    fun `known values pin the formulas`() {
        // MRR: (1/1 + 1/3) / 2 = 2/3
        assertThat(RetrievalMetrics.mrr(listOf<Int?>(1, 3))).isEqualTo(2.0 / 3.0)
        // recall@1 vs recall@5 see different worlds
        val ranks = listOf<Int?>(1, 6, 2)
        assertThat(RetrievalMetrics.recallAt(ranks, 1)).isEqualTo(1.0 / 3.0)
        assertThat(RetrievalMetrics.recallAt(ranks, 5)).isEqualTo(2.0 / 3.0)
        // nDCG@5: rank 2 → 1/log2(3); rank 4 → 1/log2(5); rank 6 is outside the window → 0
        val ndcg = RetrievalMetrics.ndcgAt(listOf<Int?>(2, 4, 6), 5)
        assertThat(ndcg).isEqualTo((1.0 / log2(3.0) + 1.0 / log2(5.0) + 0.0) / 3.0)
    }

    @Test
    fun `improving a rank never lowers a metric, the monotonicity law`() {
        val worse = listOf<Int?>(3, 5, null, 2)
        val better = listOf<Int?>(1, 2, 4, 1)
        assertThat(RetrievalMetrics.mrr(better)).isGreaterThanOrEqualTo(RetrievalMetrics.mrr(worse))
        assertThat(RetrievalMetrics.recallAt(better, 5)).isGreaterThanOrEqualTo(RetrievalMetrics.recallAt(worse, 5))
        assertThat(RetrievalMetrics.ndcgAt(better, 5)).isGreaterThanOrEqualTo(RetrievalMetrics.ndcgAt(worse, 5))
        // and finding what was missing is never worse either
        val found = listOf<Int?>(3, 5, 7, 2)
        assertThat(RetrievalMetrics.mrr(found)).isGreaterThanOrEqualTo(RetrievalMetrics.mrr(worse))
        assertThat(RetrievalMetrics.recallAt(found, 5)).isGreaterThanOrEqualTo(RetrievalMetrics.recallAt(worse, 5))
    }

    @Test
    fun `nDCG discriminates ordering where the rank@5 gate sees a pass`() {
        val sharp = listOf<Int?>(1, 1)
        val blunt = listOf<Int?>(5, 5)
        assertThat(RetrievalMetrics.recallAt(sharp, 5)).isEqualTo(RetrievalMetrics.recallAt(blunt, 5))
        assertThat(RetrievalMetrics.ndcgAt(sharp, 5)).isGreaterThan(RetrievalMetrics.ndcgAt(blunt, 5))
    }

    @Test
    fun `the window k must be at least one`() {
        assertThatThrownBy { RetrievalMetrics.recallAt(listOf<Int?>(1), 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { RetrievalMetrics.ndcgAt(listOf<Int?>(1), 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the summary carries the case count and every column`() {
        val summary = RetrievalMetrics.summary(listOf<Int?>(1, 3), 5)
        assertThat(summary.cases).isEqualTo(2)
        assertThat(summary.mrr).isEqualTo(RetrievalMetrics.mrr(listOf<Int?>(1, 3)))
        assertThat(summary.recall5).isEqualTo(RetrievalMetrics.recallAt(listOf<Int?>(1, 3), 5))
        assertThat(summary.ndcg5).isEqualTo(RetrievalMetrics.ndcgAt(listOf<Int?>(1, 3), 5))
        assertThat(summary.recall1).isEqualTo(RetrievalMetrics.recallAt(listOf<Int?>(1, 3), 1))
    }
}
