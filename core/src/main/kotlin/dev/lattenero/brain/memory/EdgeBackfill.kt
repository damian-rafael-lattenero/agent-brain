package dev.lattenero.brain.memory

import dev.lattenero.brain.db.Database

/**
 * The one-shot edge backfill over EXISTING rows (`agent-brain backfill-edges`):
 * the related band per batch of memories (the vector scan is the expensive
 * leg — paginated by id), the turn cliques and the temporal chains as set
 * statements over the whole table (the store is small; the joins are bounded
 * by turn size and session chains). The SAME laws as the insert-time builder
 * apply, in SQL: the related band's ceiling is [MemoryDedup.SIMILARITY], and
 * forgotten rows are excluded on BOTH ends of every candidate pair.
 *
 * Idempotent via ON CONFLICT DO NOTHING: a second run lands nothing new, so
 * the report's per-kind counts are the honest "what this run added".
 */
class EdgeBackfill(private val db: Database) {
    suspend fun backfill(halfLifeDays: Int, batchSize: Int = BATCH): EdgeBackfillReport {
        val scanned = db.withDriver { it.edgeBackfillQueries.backfillCount().executeAsOne() }.toInt()
        var offset = 0
        var related = 0
        while (offset < scanned) {
            related += relatedBatch(halfLifeDays, batchSize, offset)
            offset += batchSize
        }
        val cooccur = db.withDriver {
            it.edgeBackfillQueries.backfillCoocur(halfLifeDays, Lifecycle.FORGET_FLOOR).value.toInt()
        }
        val temporal = db.withDriver {
            it.edgeBackfillQueries.backfillTemporal(halfLifeDays, Lifecycle.FORGET_FLOOR).value.toInt()
        }
        return EdgeBackfillReport(scanned = scanned, related = related, cooccur = cooccur, temporal = temporal)
    }

    private suspend fun relatedBatch(halfLifeDays: Int, batchSize: Int, offset: Int): Int =
        db.withDriver { brainDb ->
            brainDb.edgeBackfillQueries.backfillRelated(
                batchSize.toLong(),
                offset.toLong(),
                halfLifeDays,
                Lifecycle.FORGET_FLOOR,
                MemoryEdges.RELATED_FLOOR,
                MemoryDedup.SIMILARITY,
                MemoryEdges.MAX_RELATED.toLong(),
            ).value.toInt()
        }

    companion object {
        /** Rows per related-vector-scan batch. */
        const val BATCH = 200
    }
}
