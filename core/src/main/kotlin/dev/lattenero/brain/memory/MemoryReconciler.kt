package dev.lattenero.brain.memory

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("agent-brain.memory.reconcile")

/**
 * The reconcile pass as ONE value: how many pairs were judged, what the
 * verdicts did (invalidations, merges), how many verdicts were skipped
 * (chat outage — a no-op by law) and the judged pairs themselves.
 */
@Serializable
data class ReconcileReport(
    val judged: Int = 0,
    val skipped: Int = 0,
    val invalidated: Int = 0,
    val merged: Int = 0,
    val distinct: Int = 0,
    val pairs: List<ReconciledPair> = emptyList(),
) {
    /** Verdict-driven state changes: what the pass actually did to the store. */
    val changes: Int get() = invalidated + merged

    /** One judged pair folded in. */
    fun plus(pair: ReconciledPair): ReconcileReport = when (pair.action) {
        ReconcileAction.InvalidateOld -> copy(judged = judged + 1, invalidated = invalidated + 1, pairs = pairs + pair)
        ReconcileAction.MergeIntoOld -> copy(judged = judged + 1, merged = merged + 1, pairs = pairs + pair)
        ReconcileAction.Nothing ->
            if (pair.verdict == null) {
                copy(skipped = skipped + 1, pairs = pairs + pair)
            } else {
                copy(judged = judged + 1, distinct = distinct + 1, pairs = pairs + pair)
            }
    }
}

/**
 * The reconcile pass (the worker's verdict-driven second stage): for each NEW
 * memory the dedup flow landed, its nearest neighbor that dedup did NOT merge
 * — cosine in the band ([MemoryReconcile.BAND_LOW], [MemoryDedup.SIMILARITY])
 * — gets ONE verdict call:
 *
 * - Supersede: the old claim is stamped invalid (Zep-style; content and
 *   salience untouched, NOT deletion) and the new claim stands;
 * - Same: merge as dedup does — the oldest row is reinforced, the
 *   restatement absorbed;
 * - Distinct: nothing changes.
 *
 * A chat outage or a timeout is [VerdictAttempt.Skipped]: the pass changes
 * NOTHING and never throws — the write path must never block on chat, so the
 * law "a chat failure degrades to a no-op" holds row by row. The pass never
 * rewrites content; the model only chooses among the three state changes.
 */
class MemoryReconciler(private val store: MemoryStore, private val judge: VerdictJudge) {

    /** Every inserted row of one turn, judged one by one (nearest neighbor each). */
    suspend fun reconcileInserted(insertedIds: List<Long>, embedModel: String, halfLifeDays: Int): ReconcileReport =
        insertedIds.fold(ReconcileReport()) { report, id ->
            val pair = reconcileOne(id, embedModel, halfLifeDays)
            if (pair == null) report else report.plus(pair)
        }

    /**
     * One new row: its ONE band candidate, its ONE verdict call, its ONE
     * state change. No stored embedding, no candidate in the band or missing
     * row (a concurrent writer deleted it) — honestly no pair to report.
     */
    suspend fun reconcileOne(newId: Long, embedModel: String, halfLifeDays: Int): ReconciledPair? {
        val embedding = store.embeddingOf(newId)
        val candidate = embedding?.let {
            store.nearestNeighbor(it, newId, embedModel, MemoryReconcile.band(halfLifeDays))
        }
        val newRow = candidate?.let { store.rowAsNeighbor(newId) }
        if (candidate == null || newRow == null) return null
        val (old, new) = MemoryReconcile.order(candidate, newRow)
        return when (val attempt = judge.judge(old.content, new.content)) {
            is VerdictAttempt.Skipped -> {
                log.info("verdict skipped for pair ({}, {}): {}", old.id, new.id, attempt.reason)
                ReconciledPair.skipped(old, new, candidate.cosine)
            }
            is VerdictAttempt.Decided -> apply(old, new, attempt, candidate.cosine)
        }
    }

    /** The decision's effect on the rows — the ONLY place a verdict touches the store. */
    private suspend fun apply(
        old: MemoryStore.NeighborRow,
        new: MemoryStore.NeighborRow,
        attempt: VerdictAttempt.Decided,
        cosine: Double,
    ): ReconciledPair {
        val action = MemoryReconcile.decide(attempt.verdict)
        when (action) {
            ReconcileAction.InvalidateOld -> {
                store.invalidate(old.id)
                log.info(
                    "supersede: {} invalidated by {} (cosine {}): {}",
                    old.id, new.id, cosine, attempt.rationale,
                )
            }
            ReconcileAction.MergeIntoOld -> {
                store.mergeInto(old.id, new.id)
                log.info(
                    "same: {} absorbed into {} (cosine {}): {}",
                    new.id, old.id, cosine, attempt.rationale,
                )
            }
            ReconcileAction.Nothing ->
                log.info("distinct: ({}, {}) stays (cosine {}): {}", old.id, new.id, cosine, attempt.rationale)
        }
        return ReconciledPair(old.id, new.id, attempt.verdict, action, cosine, attempt.rationale)
    }
}
