package dev.lattenero.brain.memory

import kotlinx.coroutines.CancellationException

/**
 * What one edge-building pass did, as a value: the counts per kind, or the
 * failure. [EdgeOutcome.Failed] is the honest degrade made visible — the
 * caller logs it and the memory write stands regardless.
 */
sealed interface EdgeOutcome {
    data class Built(val related: Int, val cooccur: Int, val temporal: Int) : EdgeOutcome {
        val total: Int get() = related + cooccur + temporal
    }

    data class Failed(val reason: String) : EdgeOutcome
}

/**
 * Builds the edges a memory write implies (B4), on top of the pure
 * [MemoryEdges] laws and the [MemoryGraphStore] IO:
 *
 * - `related`: each new memory walks its cosine band
 *   `[MemoryEdges.RELATED_FLOOR, MemoryDedup.SIMILARITY)` — related, never a
 *   duplicate — and links its best neighbors;
 * - `cooccur`: memories of the same turn link pairwise;
 * - `temporal`: consecutive memories of a session within
 *   [MemoryEdges.TEMPORAL_WINDOW_SECONDS] chain.
 *
 * THE HONEST-DEGRADE LAW: edge building NEVER fails the memory write. Every
 * failure (embedding hiccup, database trouble) is folded into
 * [EdgeOutcome.Failed] here — error as a value, not an exception — and the
 * caller logs it. The builder is idempotent by construction: the store's
 * ON CONFLICT DO NOTHING lands each edge once, so a replayed turn or a
 * retried pass adds nothing new.
 */
class EdgeBuilder(private val graph: MemoryGraphStore, private val halfLifeDays: Int) {
    /**
     * The edges one turn's new memories earn. [sessionId] and [turnId] come
     * from the turn's transcript (absent for manual saves — the cooccur leg
     * needs both).
     */
    suspend fun forTurn(sessionId: String?, turnId: String?, insertedIds: List<Long>): EdgeOutcome = guard {
        if (insertedIds.isEmpty()) return@guard EdgeOutcome.Built(0, 0, 0)
        val rows = graph.rows(insertedIds)
        val related = graph.insertEdges(
            rows.flatMap { row ->
                MemoryEdges.related(row.id, graph.neighborsInBand(row.id, row.embedding, row.embedModel, halfLifeDays))
            },
        )
        val temporal = graph.insertEdges(rows.flatMap { row -> temporalFor(row) })
        val cooccur = if (sessionId != null && turnId != null) {
            graph.insertEdges(MemoryEdges.cooccur(graph.turnMemoryIds(sessionId, turnId, halfLifeDays)))
        } else {
            0
        }
        EdgeOutcome.Built(related = related, cooccur = cooccur, temporal = temporal)
    }

    /** The edges one manually saved memory earns: related and temporal, no cooccur (no turn). */
    suspend fun forMemory(id: Long): EdgeOutcome = guard {
        val row = graph.rows(listOf(id)).firstOrNull() ?: return@guard EdgeOutcome.Built(0, 0, 0)
        val related = graph.insertEdges(
            MemoryEdges.related(row.id, graph.neighborsInBand(row.id, row.embedding, row.embedModel, halfLifeDays)),
        )
        val temporal = graph.insertEdges(temporalFor(row))
        EdgeOutcome.Built(related = related, cooccur = 0, temporal = temporal)
    }

    /** The row's temporal chain against its session's newest earlier alive row. */
    private suspend fun temporalFor(row: MemoryGraphStore.GraphRow): List<MemoryEdge> {
        val pair = graph.temporalPair(row.sessionId, row.id, halfLifeDays) ?: return emptyList()
        return MemoryEdges.temporalChain(listOf(pair.prev, pair.cur))
    }

    /** The degrade seam: any failure of the pass becomes [EdgeOutcome.Failed], never an exception.
     *  Cancellation is NOT ours to swallow: it propagates untouched (fleet audit 17/11 —
     *  runCatching over suspend laundered CE into Failed; the CE-arm ladder is the law).
     *  The final Throwable arm is the doctrine's own Convert-to-value shape. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun guard(block: suspend () -> EdgeOutcome): EdgeOutcome = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        EdgeOutcome.Failed("${e::class.simpleName}: ${e.message}")
    }
}
