package dev.lattenero.brain.memory

/**
 * The reconcile pass's PURE core — the band, the decision and the pair
 * ordering, all total functions of values; the passes (worker reconcile,
 * dream) are the effects around them.
 *
 * The BAND: after insert-time dedup, a new memory's nearest neighbor that
 * dedup did NOT merge sits at a cosine in [BAND_LOW, BAND_HIGH) — BAND_HIGH
 * reads [MemoryDedup.SIMILARITY] (at or above it, dedup already merged), so
 * the two thresholds can never drift apart. Inside the band the embeddings
 * alone cannot say "same" or "contradicting" (the measured evidence: true
 * duplicates start at 0.9123, merely-related pairs end at 0.8789 — the band
 * is the gray zone), which is exactly why a verdict call decides there.
 */
object MemoryReconcile {
    /** The band's low edge: below it, related-but-distinct claims are left alone. */
    const val BAND_LOW: Double = 0.80

    /** The band's high edge: the dedup constant itself — dedup owns everything at or above it. */
    val BAND_HIGH: Double = MemoryDedup.SIMILARITY

    /** The band as the store's query takes it, with the caller's forgetting half-life. */
    fun band(halfLifeDays: Int): MemoryStore.NeighborBand =
        MemoryStore.NeighborBand(BAND_LOW, BAND_HIGH, halfLifeDays)

    /** The band membership test: high edge EXCLUSIVE (dedup owns that side). */
    fun bandContains(cosine: Double): Boolean = cosine >= BAND_LOW && cosine < BAND_HIGH

    /**
     * The verdict-driven decision — the ONLY way a verdict changes state:
     * Same merges as dedup does (reinforce the oldest, absorb the
     * restatement), Supersede stamps the old claim invalid, Distinct changes
     * nothing. The model never rewrites content; it only chooses among these
     * three state changes.
     */
    fun decide(verdict: Verdict): ReconcileAction = when (verdict) {
        Verdict.Same -> ReconcileAction.MergeIntoOld
        Verdict.Supersede -> ReconcileAction.InvalidateOld
        Verdict.Distinct -> ReconcileAction.Nothing
    }

    /**
     * The pair in judging order: OLD first, NEW second — the earlier-created
     * row is the old claim (id breaks ties deterministically). Supersede
     * always means the OLD side is outdated, whoever inserted last.
     */
    fun order(
        a: MemoryStore.NeighborRow,
        b: MemoryStore.NeighborRow,
    ): Pair<MemoryStore.NeighborRow, MemoryStore.NeighborRow> =
        if (a.createdAt.isBefore(b.createdAt) || (a.createdAt == b.createdAt && a.id < b.id)) a to b else b to a
}

/**
 * What a decided verdict does to the rows — the complete vocabulary of state
 * changes the reconcile machinery may make (content is NEVER rewritten).
 */
sealed interface ReconcileAction {
    /** The old claim is outdated: stamp it invalid, the new claim stands. */
    data object InvalidateOld : ReconcileAction

    /** One memory said twice: reinforce the oldest, absorb the restatement (dedup's own merge). */
    data object MergeIntoOld : ReconcileAction

    /** Related but both true: nothing changes. */
    data object Nothing : ReconcileAction
}

/** One judged pair, as the report carries it: who was old, who was new, what happened. */
data class ReconciledPair(
    val oldId: Long,
    val newId: Long,
    val verdict: Verdict?,
    val action: ReconcileAction,
    val cosine: Double,
    val rationale: String = "",
) {
    companion object {
        /** A skipped verdict changes nothing — the honest report line for a chat outage. */
        fun skipped(old: MemoryStore.NeighborRow, new: MemoryStore.NeighborRow, cosine: Double): ReconciledPair =
            ReconciledPair(old.id, new.id, null, ReconcileAction.Nothing, cosine)
    }
}
