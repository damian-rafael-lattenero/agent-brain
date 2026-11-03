package dev.lattenero.brain.memory

import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.math.sqrt

/**
 * Cosine similarity and the dedup threshold — chosen from measurement, not
 * vibes (`scripts/memory-dedup-evidence.sql`, the read-only probe behind it).
 *
 * Evidence (2026-11-03, production store, 37 memories, nomic-embed-text):
 * - every pair that is a restatement of the same memory — across kinds —
 *   measures 0.9123..0.9845: fact 16 "Todos los subagentes están en estado
 *   'terminado'." vs summary 19 "Todos los subagentes han terminado y están
 *   en estado 'terminado'." = **0.9845**; fact 15 "Todos los subagentes han
 *   terminado." vs 16 = **0.9653**; preference 18 "concise and clear" vs 36
 *   "clear and direct" = **0.9447**; fact 20 "The subagents have completed
 *   their tasks." vs summary 24 "The subagents have finished their tasks..."
 *   = **0.9123** (the weakest true duplicate);
 * - the strongest pair that is merely RELATED, not the same memory (decision
 *   27 "planning to review and align the 5 concepts from the video" vs
 *   summary 29 "preparing to merge worktrees and review the 5 concepts...")
 *   = **0.8789**;
 * - cross-LINGUAL equivalents measure far below what one would expect
 *   (Spanish 15 "Todos los subagentes han terminado." vs English 20 "The
 *   subagents have completed their tasks." = **0.6524**): nomic-embed-text
 *   does not align the two languages at a mergeable similarity, so embedding
 *   dedup honestly cannot catch cross-lingual duplicates and the threshold
 *   must not chase them — 0.65 sits inside the merely-related band (0.60..0.88).
 *
 * [SIMILARITY] = 0.90 lands in the measured empty gap [0.8789, 0.9123]:
 * everything at or above it measured as the same memory, everything below as
 * distinct. The comparison scope is ALL kinds and ALL sessions — the
 * duplicates cross kinds (fact↔summary, fact↔decision, preference↔preference)
 * and sessions — filtered by embed_model, because cosine across different
 * embedding models is meaningless (ADR-004).
 */
object MemoryDedup {
    /** Two memories at or above this cosine similarity are the same memory. */
    const val SIMILARITY = 0.90

    /** Plain cosine over float arrays, matching pgvector's `1 - (a <=> b)`. */
    fun cosine(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "cosine needs equal dimensions, got ${a.size} and ${b.size}" }
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            normA += x * x
            normB += y * y
        }
        if (normA == 0.0 || normB == 0.0) return 0.0
        return dot / (sqrt(normA) * sqrt(normB))
    }

    /**
     * The backfill as values: [plan] clusters the existing rows with the SAME
     * rule insert-time dedup applies (same embed model, any kind, any
     * session, cosine at or above [threshold], OLDEST row wins), [report]
     * renders the plan for the operator, and the store applies it (reinforce
     * the survivor, delete the rest). Idempotent: after a plan is applied
     * every surviving pair is below the threshold, so re-planning yields an
     * empty plan.
     */
    fun plan(rows: List<DedupRow>, threshold: Double = SIMILARITY): Plan {
        val kept = mutableListOf<DedupRow>()
        val absorbedBy = LinkedHashMap<Long, MutableList<Long>>()
        for (row in rows.sortedWith(compareBy({ it.createdAt }, { it.id }))) {
            val survivor = kept.firstOrNull { candidate ->
                candidate.embedModel == row.embedModel && cosine(candidate.embedding, row.embedding) >= threshold
            }
            if (survivor == null) {
                kept += row
            } else {
                absorbedBy.getOrPut(survivor.id) { mutableListOf() } += row.id
            }
        }
        return Plan(absorbedBy.map { (keepId, absorbed) -> Cluster(keepId, absorbed) })
    }

    /** The operator's report: what would be (or was) merged, with the contents. */
    fun report(rows: List<DedupRow>, plan: Plan, dryRun: Boolean): DedupReport {
        val byId = rows.associateBy { it.id }
        return DedupReport(
            scanned = rows.size,
            dryRun = dryRun,
            removed = plan.removed,
            clusters = plan.clusters.map { cluster ->
                DedupClusterReport(
                    keepId = cluster.keepId,
                    keep = byId.getValue(cluster.keepId).content,
                    absorbed = cluster.absorbedIds,
                    absorbedContent = cluster.absorbedIds.map { byId.getValue(it).content },
                )
            },
        )
    }
}

/** One memory row as the dedup pass reads it. */
data class DedupRow(
    val id: Long,
    val kind: String,
    val content: String,
    val embedModel: String,
    val salience: Double,
    val createdAt: Instant,
    val embedding: FloatArray,
)

/** One duplicate cluster: [Cluster.keepId] survives, [Cluster.absorbedIds] are deleted. */
data class Cluster(val keepId: Long, val absorbedIds: List<Long>) {
    val reinforcements: Int get() = absorbedIds.size
}

/** A whole backfill pass: empty means nothing to merge. */
data class Plan(val clusters: List<Cluster>) {
    val removed: Int get() = clusters.sumOf { it.reinforcements }
}

@Serializable
data class DedupClusterReport(
    val keepId: Long,
    val keep: String,
    val absorbed: List<Long>,
    val absorbedContent: List<String>,
)

@Serializable
data class DedupReport(val scanned: Int, val dryRun: Boolean, val removed: Int, val clusters: List<DedupClusterReport>)
