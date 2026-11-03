package dev.lattenero.brain.rag

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/**
 * The laws of RRF fusion (plan E2), over generated inputs: bounded score,
 * rank monotonicity, no-recall => no-score, and the fusion being a fold
 * (leg order and grouping never change a score). A leg here is the ordered
 * candidate list one retrieval leg produces; the SQL twin is pinned
 * separately on fixtures (`RagStoreRrfEquivalenceIT`).
 */
class RrfLawsTest {

    /** A generated leg: 0..12 DISTINCT ids in rank order (best first). */
    private fun leg(): Arb<List<Long>> = Arb.list(Arb.long(), 0..12).map { it.distinct() }

    /** Per-leg rank maps for the generated legs: leg index -> (id -> 1-based rank). */
    private fun ranksPerLeg(legs: List<List<Long>>): List<Map<Long, Int>> =
        legs.map { ids -> ids.withIndex().associate { (position, id) -> id to position + 1 } }

    @Test
    fun `the formula is the law - one leg contributes exactly one over k plus rank`() {
        runBlocking {
            checkAll(Arb.int(1, 10_000)) { rank ->
                assertThat(Rrf.contribution(rank)).isEqualTo(1.0 / (Rrf.K + rank))
            }
        }
    }

    @Test
    fun `bounded score - every fused score is positive and at most legs over k+1`() {
        runBlocking {
            checkAll(Arb.list(leg(), 1..4)) { legs ->
                val fused = Rrf.fuse(legs)
                if (legs.any { it.isNotEmpty() }) assertThat(fused).isNotEmpty
                val cap = legs.size.toDouble() / (Rrf.K + 1)
                for (f in fused) {
                    assertThat(f.score).withFailMessage("id ${f.id} scored ${f.score}").isGreaterThan(0.0)
                    assertThat(f.score).withFailMessage("id ${f.id} scored ${f.score} > cap $cap").isLessThanOrEqualTo(cap + 1e-12)
                }
            }
        }
    }

    @Test
    fun `rank monotonicity - promoting an id one rank helps it and hurts exactly the demoted neighbour`() {
        runBlocking {
            checkAll(Arb.list(Arb.long(), 2..12)) { ids ->
                val leg = ids.distinct()
                if (leg.size >= 2) {
                    val before = Rrf.fuse(listOf(leg)).associateBy { it.id }
                    val swapped = leg.toMutableList().also { val top = it[0]; it[0] = it[1]; it[1] = top }
                    val after = Rrf.fuse(listOf(swapped)).associateBy { it.id }
                    assertThat(after.getValue(swapped[0]).score).isGreaterThan(before.getValue(swapped[0]).score)
                    assertThat(after.getValue(swapped[1]).score).isLessThan(before.getValue(swapped[1]).score)
                }
            }
        }
    }

    @Test
    fun `rank monotonicity - improving one id in one leg of many moves no other id's score`() {
        runBlocking {
            checkAll(Arb.list(leg(), 2..3)) { legs ->
                val swappable = legs.withIndex().filter { it.value.size >= 2 }
                if (swappable.isNotEmpty()) {
                    val (index, leg) = swappable.first()
                    val swapped = leg.toMutableList().also { val top = it[0]; it[0] = it[1]; it[1] = top }
                    val legsAfter = legs.toMutableList().also { it[index] = swapped }
                    val before = Rrf.fuse(legs).associateBy { it.id }
                    val after = Rrf.fuse(legsAfter).associateBy { it.id }
                    assertThat(after.getValue(swapped[0]).score).isGreaterThan(before.getValue(swapped[0]).score)
                    assertThat(after.getValue(swapped[1]).score).isLessThan(before.getValue(swapped[1]).score)
                    for ((id, f) in after) {
                        if (id != swapped[0] && id != swapped[1]) {
                            assertThat(f.score).isEqualTo(before.getValue(id).score)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `rank monotonicity - an id never worse in any leg never scores lower, pairwise over generated legs`() {
        runBlocking {
            checkAll(Arb.list(leg(), 1..3)) { legs ->
                val ranks = ranksPerLeg(legs)
                val fused = Rrf.fuse(legs)
                for (a in fused) {
                    for (b in fused) {
                        if (a.id == b.id) continue
                        val dominates = ranks.all { perLeg ->
                            val rankB = perLeg[b.id]
                            rankB == null || (perLeg[a.id] ?: Int.MAX_VALUE) <= rankB
                        }
                        if (dominates) {
                            assertThat(a.score)
                                .withFailMessage("id ${a.id} (ranks ${a.ranks}) vs id ${b.id} (ranks ${b.ranks})")
                                .isGreaterThanOrEqualTo(b.score - 1e-12)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `no recall means no score - an id absent from every leg never appears and nothing else does`() {
        runBlocking {
            checkAll(Arb.list(leg(), 1..4), Arb.long()) { legs, foreign ->
                val recalled = legs.flatten().toSet()
                val fused = Rrf.fuse(legs)
                if (foreign !in recalled) assertThat(fused.none { it.id == foreign }).isTrue
                assertThat(fused.map { it.id }).containsExactlyInAnyOrderElementsOf(recalled)
                if (recalled.isEmpty()) assertThat(fused).isEmpty()
            }
        }
    }

    @Test
    fun `the fusion is a fold - leg order and a trailing empty leg never change any score`() {
        runBlocking {
            checkAll(Arb.list(leg(), 1..4)) { legs ->
                val scores = Rrf.fuse(legs).associate { it.id to it.score }
                val reversed = Rrf.fuse(legs.reversed()).associate { it.id to it.score }
                val withEmpty = Rrf.fuse(legs + emptyList()).associate { it.id to it.score }
                assertThat(scores.keys).isEqualTo(reversed.keys)
                for (id in scores.keys) {
                    // Postgres aggregates in unspecified order too: equality up to sum-order rounding.
                    assertThat(reversed.getValue(id)).isCloseTo(scores.getValue(id), within(1e-12))
                    assertThat(withEmpty.getValue(id)).isCloseTo(scores.getValue(id), within(1e-12))
                }
            }
        }
    }

    @Test
    fun `the tie-break is id ascending - equal scores order by id like the SQL`() {
        val a = 100L
        val x = 101L
        val b = 102L
        // a: rank 1 of leg one; b: rank 1 of leg two; both score 1/61. x: rank 2, 1/62.
        val fused = Rrf.fuse(listOf(listOf(a, x), listOf(b)))
        assertThat(fused.map { it.id }).containsExactly(a, b, x)
        assertThat(fused[0].score).isCloseTo(fused[1].score, within(0.0))
        assertThat(fused[0].score).isCloseTo(1.0 / (Rrf.K + 1), within(1e-12))
        assertThat(fused[2].score).isCloseTo(1.0 / (Rrf.K + 2), within(1e-12))
    }
}
