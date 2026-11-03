package dev.lattenero.brain.memory

import dev.lattenero.brain.embeddings.FakeProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import java.util.Random

/**
 * The measurement behind `MemoryDedup.SIMILARITY` (0.90), reproduced over a
 * fake store with controlled embeddings so it runs everywhere.
 *
 * The decisive numbers come from the production probe
 * (`scripts/memory-dedup-evidence.sql`, 2026-11-03, 37 memories,
 * nomic-embed-text): true duplicates — the same memory restated, across
 * kinds — measure 0.9123..0.9845, the strongest merely-related pair measures
 * 0.8789, and the gap [0.8789, 0.9123] is empty. 0.90 sits inside it. The
 * same probe shows cross-lingual equivalents (Spanish vs English restatements
 * of one turn) at 0.55..0.65, inside the related band: nomic-embed-text does
 * not align the languages at a mergeable similarity, and the threshold must
 * not chase them.
 *
 * The vectors here are built in 2D planes ([cos, sin, 0, ...]) so each pair's
 * cosine is exact by construction, and the fake store mirrors the geometry of
 * the cited production cluster: a Spanish trio, an English pair and one
 * distinct row.
 */
class MemoryDedupEvidenceTest {

    /** A unit vector whose cosine with the given axis is exactly [c]. */
    private fun vectorAt(c: Double, axis: Int, dim: Int = 3): FloatArray {
        val v = FloatArray(dim)
        val angle = acos(c)
        v[axis] = cos(angle).toFloat()
        v[(axis + 1) % dim] = sin(angle).toFloat()
        return v
    }

    @Test
    fun `the duplicate band measures at or above the threshold, the related and distinct bands below`() {
        // Measured production duplicates (the same memory restated): 0.9123..0.9845.
        val duplicateBand = listOf(0.9845, 0.9653, 0.9601, 0.9549, 0.9129, 0.9123)
        // Measured merely-related pairs (strongest 0.8789) and a distinct pair (~0).
        val belowBand = listOf(0.8789, 0.8672, 0.8591, 0.0)
        for (expected in duplicateBand) {
            val axis0 = vectorAt(1.0, axis = 0)
            val samePlane = vectorAt(expected, axis = 0)
            val measured = MemoryDedup.cosine(axis0, samePlane)
            assertThat(measured).isCloseTo(expected, within(1e-3))
            assertThat(measured).isGreaterThanOrEqualTo(MemoryDedup.SIMILARITY)
            // A vector in another plane is a distinct memory however it tilts.
            assertThat(MemoryDedup.cosine(axis0, vectorAt(expected, axis = 1))).isLessThan(MemoryDedup.SIMILARITY)
        }
        for (expected in belowBand) {
            val measured = MemoryDedup.cosine(vectorAt(1.0, axis = 0), vectorAt(expected, axis = 0))
            assertThat(measured).isCloseTo(expected, within(1e-3))
            assertThat(measured).isLessThan(MemoryDedup.SIMILARITY)
        }
    }

    @Test
    fun `the fake store reproducing the cited cluster separates duplicates from distinct rows at 0_90`() {
        // The production cluster (ids from the probe): Spanish restatements
        // 15/16/19 (0.9653..0.9845), English restatements 20/24 (0.9123),
        // 21 a different fact; cross-lingual pairs measure 0.55..0.65.
        val store = listOf(
            Row(15, "fact", vectorAt(0.9994, axis = 0)), // ~2 degrees off 16
            Row(16, "fact", vectorAt(1.0, axis = 0)),
            Row(19, "summary", vectorAt(0.9986, axis = 0)), // ~3 degrees off 16
            Row(20, "fact", vectorAt(0.9998, axis = 1)),
            Row(24, "summary", vectorAt(1.0, axis = 1)),
            Row(21, "fact", vectorAt(1.0, axis = 2)),
        )
        // The merge rule (oldest above threshold wins) collapses the Spanish
        // trio into 15 and the English pair into 20; 21 stays alone and every
        // cross-language pair stays apart — exactly the measured decision.
        val clusters = store.map { row ->
            store.firstOrNull { it.id < row.id && MemoryDedup.cosine(row.vector, it.vector) >= MemoryDedup.SIMILARITY }
                ?.id ?: row.id
        }
        val row21 = store.indexOfFirst { it.id == 21L }
        val strongestTo21 = store.filter { it.id != 21L }.maxOf { MemoryDedup.cosine(store[row21].vector, it.vector) }
        assertThat(strongestTo21).isLessThan(MemoryDedup.SIMILARITY)
        assertThat(clusters).containsExactly(15L, 15L, 15L, 20L, 20L, 21L)
    }

    @Test
    fun `cosine laws hold - identity, symmetry, bounds, zero vector`() {
        assertThat(MemoryDedup.cosine(floatArrayOf(1f, 2f, 3f), floatArrayOf(1f, 2f, 3f))).isCloseTo(1.0, within(1e-12))
        val a = floatArrayOf(0.5f, -1f, 2f)
        val b = floatArrayOf(3f, 0.25f, -0.75f)
        assertThat(MemoryDedup.cosine(a, b)).isCloseTo(MemoryDedup.cosine(b, a), within(1e-12))
        assertThat(MemoryDedup.cosine(a, b)).isBetween(-1.0, 1.0)
        assertThat(MemoryDedup.cosine(floatArrayOf(0f, 0f, 0f), b)).isEqualTo(0.0)
        assertThat(MemoryDedup.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f))).isCloseTo(0.0, within(1e-12))
    }

    @Test
    fun `the threshold separates at the real embedding dimension too`() {
        // 768 dims (nomic-embed-text): random unit gaussians are near-orthogonal,
        // a constructed 0.95 pair stays above the threshold.
        val rng = Random(42)
        val x = FakeProvider.normalize(FloatArray(768) { rng.nextGaussian().toFloat() })
        val y = FakeProvider.normalize(FloatArray(768) { rng.nextGaussian().toFloat() })
        assertThat(abs(MemoryDedup.cosine(x, y))).isLessThan(MemoryDedup.SIMILARITY)
        val parallel = vectorAt(0.95, axis = 0, dim = 768)
        assertThat(MemoryDedup.cosine(vectorAt(1.0, axis = 0, dim = 768), parallel)).isCloseTo(0.95, within(1e-3))
        assertThat(MemoryDedup.cosine(vectorAt(1.0, axis = 0, dim = 768), parallel)).isGreaterThanOrEqualTo(MemoryDedup.SIMILARITY)
    }

    private data class Row(val id: Long, val kind: String, val vector: FloatArray)
}
