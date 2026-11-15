package dev.lattenero.brain.memory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.math.sqrt

/**
 * The dream clustering's law battery (pure): near-neighbors join a seed's
 * cluster, clusters are disjoint, the seed is the oldest, cross-model rows
 * never join, singletons judge nothing — and the clustering is a function of
 * the ROWS, not of their order (oldest-first is enforced inside).
 */
class DreamClusteringLawsTest {
    private val dim = 64
    private val t0 = Instant.parse("2026-10-01T00:00:00Z")
    private val t1 = Instant.parse("2026-10-02T00:00:00Z")
    private val t2 = Instant.parse("2026-10-03T00:00:00Z")

    /** A unit vector along basis slot [slot]. */
    private fun unit(slot: Int): FloatArray = FloatArray(dim).also { it[slot] = 1f }

    /** A vector at exactly cosine [c] from [unit(baseSlot)] (the rest of the mass on an orthogonal slot). */
    private fun tilted(baseSlot: Int, epsSlot: Int, c: Double): FloatArray = FloatArray(dim).also {
        it[baseSlot] = c.toFloat()
        it[epsSlot] = sqrt(1.0 - c * c).toFloat()
    }

    private fun row(id: Long, content: String, vector: FloatArray, createdAt: Instant, model: String = "fake-hash") =
        DedupRow(id, "fact", content, model, 1.0, createdAt, vector)

    /** LAW 1 — only rows at or above the threshold join; a far row never clusters. */
    @Test
    fun `law 1 - only near neighbors join the cluster`() {
        val rows = listOf(
            row(1, "seed claim", unit(0), t0),
            row(2, "near claim", tilted(0, 1, 0.85), t1),
            row(3, "far claim", tilted(0, 2, 0.5), t2),
        )
        val clusters = Dream.clusters(rows)
        assertThat(clusters).hasSize(1)
        assertThat(clusters[0].ids).containsExactly(1L, 2L)
    }

    /** LAW 2 — clusters are disjoint: two orthogonal near-families give two closed clusters. */
    @Test
    fun `law 2 - clusters are disjoint`() {
        val rows = listOf(
            row(1, "seed a", unit(0), t0),
            row(2, "member a", tilted(0, 1, 0.85), t1),
            row(3, "seed b", unit(2), t2),
            row(4, "member b", tilted(2, 3, 0.85), t2.plusSeconds(60)),
        )
        val clusters = Dream.clusters(rows)
        val allIds = clusters.flatMap { it.ids }
        assertThat(allIds).doesNotHaveDuplicates()
        assertThat(allIds).containsExactlyInAnyOrder(1L, 2L, 3L, 4L)
        assertThat(clusters.map { it.seedId }).containsExactly(1L, 3L) // oldest first
    }

    /** LAW 3 — the seed is the OLDEST row of its cluster, whatever the input order. */
    @Test
    fun `law 3 - the seed is the oldest and order does not matter`() {
        val rows = listOf(
            row(1, "young claim", tilted(0, 2, 0.85), t2),
            row(2, "old claim", unit(0), t0),
            row(3, "middle claim", tilted(0, 1, 0.85), t1),
        )
        val first = Dream.clusters(rows)
        val reversed = Dream.clusters(rows.reversed())
        assertThat(first).isEqualTo(reversed) // a function of the rows, not their order
        assertThat(first).hasSize(1)
        assertThat(first[0].seedId).isEqualTo(2L) // the oldest
    }

    /** LAW 4 — cross-model rows never join (ADR-004: cosine across models is meaningless). */
    @Test
    fun `law 4 - another embed model never joins`() {
        val rows = listOf(
            row(1, "nomic claim", unit(0), t0),
            row(2, "minilm claim", tilted(0, 1, 0.85), t1, model = "fake-bow"),
        )
        assertThat(Dream.clusters(rows)).isEmpty()
    }

    /** LAW 5 — a lone cooling row judges nothing: singleton clusters are dropped. */
    @Test
    fun `law 5 - singletons judge nothing`() {
        val rows = listOf(
            row(1, "lone claim", unit(0), t0),
            row(2, "unrelated claim", tilted(0, 1, 0.3), t1),
        )
        assertThat(Dream.clusters(rows)).isEmpty()
    }
}
