package dev.lattenero.brain.memory

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("agent-brain.memory.dream")

/**
 * One dream cluster: a SEED (the oldest cooling row of a near-neighbor group)
 * and the members that joined it — every pair (seed, member) shares the seed's
 * embed model and sits at or above [Dream.NEIGHBOR_SIMILARITY].
 */
data class DreamCluster(val seedId: Long, val members: List<DedupRow>) {
    val ids: List<Long> get() = members.map { it.id }

    /** The rows that are NOT the seed: each of them is one judged pair. */
    val others: List<DedupRow> get() = members.filter { it.id != seedId }

    val seed: DedupRow? get() = members.firstOrNull { it.id == seedId }
}

/**
 * The dream pass's report — per-cluster verdict actions and counts, the same
 * [ReconciledPair] vocabulary the worker's reconcile pass reports. A dry-run
 * plan judges nothing: [judged]/[invalidated]/[merged] stay zero and the
 * clusters carry the pairs that WOULD be judged ([plannedPairs]).
 */
@Serializable
data class DreamClusterReport(
    val seedId: Long,
    val seed: String,
    val members: List<Long>,
    val memberContent: List<String>,
    val pairs: List<ReconciledPair> = emptyList(),
)

@Serializable
data class DreamReport(
    val dryRun: Boolean,
    val scanned: Int,
    val clusters: List<DreamClusterReport>,
    val plannedPairs: Int,
    val judged: Int = 0,
    val invalidated: Int = 0,
    val merged: Int = 0,
    val distinct: Int = 0,
    val skipped: Int = 0,
) {
    val changes: Int get() = invalidated + merged

    /** One judged cluster folded in: the pairs carry their own actions. */
    fun plus(cluster: DreamClusterReport): DreamReport {
        val decided = cluster.pairs.filter { it.verdict != null }
        return copy(
            clusters = clusters + cluster,
            judged = judged + decided.size,
            invalidated = invalidated + decided.count { it.action == ReconcileAction.InvalidateOld },
            merged = merged + decided.count { it.action == ReconcileAction.MergeIntoOld },
            distinct = distinct + decided.count { it.action == ReconcileAction.Nothing },
            skipped = skipped + cluster.pairs.count { it.verdict == null },
        )
    }
}

/**
 * The dream pass v1 (Zep's dream, verdict-driven): while the worker's
 * reconcile rides each new landing, the dream works the STORED memory —
 * clusters of COOLING memories ([Lifecycle.classify], via
 * [MemoryStore.dreamRows]) that are near-neighbors by direct embedding
 * similarity (NO edges table — another unit owns memory_edges), judged
 * PAIRWISE with the same [VerdictJudge] machinery:
 *
 * - Supersede -> the OLD claim (the seed, or whichever side was created
 *   earlier) is stamped invalid; the newer claim stands;
 * - Same -> merge as dedup does (oldest reinforced, restatement absorbed);
 * - Distinct -> nothing.
 *
 * The clustering is greedy oldest-first (the dedup backfill's own rule): the
 * oldest not-yet-clustered cooling row seeds a cluster of every later cooling
 * row within [NEIGHBOR_SIMILARITY] cosine of it; singletons judge nothing.
 * Within a cluster each member is judged against the CURRENT seed — a seed a
 * verdict already invalidated or absorbed is skipped (no double-invalidation,
 * no judging against a dead claim), which makes the pass idempotent per pair:
 * invalidated rows leave the input set ([MemoryStore.dreamRows] filters the
 * stamp), Same-merged rows are gone, and only Distinct pairs re-judge (and
 * change nothing again).
 *
 * --dry-run prints the plan (clusters and the pair count) and writes nothing,
 * and NO worker cadence calls this automatically — dreaming is an operator's
 * command (`agent-brain dream`), shipped off by default.
 */
object Dream {
    /** The cosine that joins a cooling row to a seed's cluster (the reconcile band's low edge). */
    const val NEIGHBOR_SIMILARITY: Double = MemoryReconcile.BAND_LOW

    /**
     * The pure clustering: oldest-first seeds, each cluster closed to rows
     * already claimed by an earlier seed (disjoint clusters), same embed
     * model only (ADR-004), cosine at or above [NEIGHBOR_SIMILARITY],
     * singletons dropped — a lone cooling row has no pair to judge.
     */
    fun clusters(rows: List<DedupRow>, threshold: Double = NEIGHBOR_SIMILARITY): List<DreamCluster> {
        val ordered = rows.sortedWith(compareBy({ it.createdAt }, { it.id }))
        val claimed = mutableSetOf<Long>()
        val clusters = mutableListOf<DreamCluster>()
        for (seed in ordered) {
            if (seed.id in claimed) continue
            val members = ordered.filter {
                it.id != seed.id && it.embedModel == seed.embedModel && it.id !in claimed &&
                    MemoryDedup.cosine(seed.embedding, it.embedding) >= threshold
            }
            claimed += seed.id
            claimed += members.map { it.id }
            clusters += DreamCluster(seed.id, listOf(seed) + members)
        }
        return clusters.filter { it.members.size > 1 }
    }
}

/** The dream pass as effects: one store read, pure clustering, pairwise verdicts, applied state changes. */
class DreamPass(private val store: MemoryStore, private val judge: VerdictJudge) {
    companion object {
        /** How many COOLING rows one dream run considers, oldest first. */
        const val DEFAULT_LIMIT = 100
    }

    /**
     * One dream run over the [limit] oldest COOLING rows. [dryRun] plans and
     * reports, writes nothing, and spends no chat calls. Every judged pair is
     * folded into the report; a chat outage on one pair is a [Skipped] pair
     * and never stops the run.
     */
    suspend fun run(halfLifeDays: Int, limit: Int, dryRun: Boolean): DreamReport {
        val rows = store.dreamRows(halfLifeDays, limit)
        val clusters = Dream.clusters(rows)
        if (dryRun) {
            return DreamReport(
                dryRun = true,
                scanned = rows.size,
                clusters = clusters.map { it.report() },
                plannedPairs = clusters.sumOf { it.others.size },
            )
        }
        var report = DreamReport(dryRun = false, scanned = rows.size, clusters = emptyList(), plannedPairs = 0)
        for (cluster in clusters) report = report.plus(judgedCluster(cluster))
        log.info(
            "dream pass: {} cooling rows, {} clusters, {} judged ({} invalidated, {} merged, {} skipped)",
            report.scanned, report.clusters.size, report.judged, report.invalidated, report.merged, report.skipped,
        )
        return report
    }

    /**
     * One cluster judged pairwise. Rows this run already killed (invalidated
     * or absorbed) take no further verdict — no double-invalidation, no chat
     * call spent on a dead claim.
     */
    private suspend fun judgedCluster(cluster: DreamCluster): DreamClusterReport {
        val dead = mutableSetOf<Long>()
        val seedRow = cluster.seed
        if (seedRow == null) return cluster.report()
        var clusterReport = cluster.report()
        for (member in cluster.others) {
            if (seedRow.id in dead || member.id in dead) continue
            val pair = judgePair(seedRow, member)
            when (pair.action) {
                ReconcileAction.InvalidateOld -> dead += pair.oldId
                ReconcileAction.MergeIntoOld -> dead += pair.newId
                ReconcileAction.Nothing -> Unit
            }
            clusterReport = cluster.report().copy(pairs = clusterReport.pairs + pair)
        }
        return clusterReport
    }

    /** One (seed, member) pair judged and applied — the seed is the OLD side, the member the NEW side. */
    private suspend fun judgePair(seed: DedupRow, member: DedupRow): ReconciledPair {
        val (old, new) = listOf(seed, member)
            .map { MemoryStore.NeighborRow(it.id, it.content, it.createdAt, 0.0) }
            .let { MemoryReconcile.order(it[0], it[1]) }
        return when (val attempt = judge.judge(old.content, new.content)) {
            is VerdictAttempt.Skipped -> {
                log.info("dream skipped pair ({}, {}): {}", old.id, new.id, attempt.reason)
                ReconciledPair.skipped(old, new, 0.0)
            }
            is VerdictAttempt.Decided -> {
                val action = MemoryReconcile.decide(attempt.verdict)
                when (action) {
                    ReconcileAction.InvalidateOld -> store.invalidate(old.id)
                    ReconcileAction.MergeIntoOld -> store.mergeInto(old.id, new.id)
                    ReconcileAction.Nothing -> Unit
                }
                ReconciledPair(old.id, new.id, attempt.verdict, action, 0.0, attempt.rationale)
            }
        }
    }

    private fun DreamCluster.report(): DreamClusterReport {
        val byId = members.associateBy { it.id }
        val seedRow = byId.getValue(seedId)
        return DreamClusterReport(
            seedId = seedId,
            seed = seedRow.content,
            members = others.map { it.id },
            memberContent = others.map { it.content },
        )
    }
}
