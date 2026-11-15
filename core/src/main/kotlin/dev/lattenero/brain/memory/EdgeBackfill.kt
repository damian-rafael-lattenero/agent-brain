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
        val scanned = countMemories().toInt()
        var offset = 0
        var related = 0
        while (offset < scanned) {
            related += relatedBatch(halfLifeDays, batchSize, offset)
            offset += batchSize
        }
        val cooccur = setStatement(COOCUR, halfLifeDays)
        val temporal = setStatement(TEMPORAL, halfLifeDays)
        return EdgeBackfillReport(scanned = scanned, related = related, cooccur = cooccur, temporal = temporal)
    }

    private suspend fun countMemories(): Long = db.withConnection { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM memories").use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    private suspend fun relatedBatch(halfLifeDays: Int, batchSize: Int, offset: Int): Int =
        db.withConnection { conn ->
            conn.prepareStatement(BACKFILL_RELATED).use { ps ->
                ps.bind(
                    conn,
                    batchSize,
                    offset,
                    halfLifeDays,
                    Lifecycle.FORGET_FLOOR,
                    MemoryEdges.RELATED_FLOOR,
                    MemoryDedup.SIMILARITY,
                    MemoryEdges.MAX_RELATED,
                )
                ps.executeUpdate()
            }
        }

    private suspend fun setStatement(sql: String, halfLifeDays: Int): Int = db.withConnection { conn ->
        conn.prepareStatement(sql).use { ps ->
            ps.bind(conn, halfLifeDays, Lifecycle.FORGET_FLOOR)
            ps.executeUpdate()
        }
    }

    companion object {
        /** Rows per related-vector-scan batch. */
        const val BATCH = 200

        // Related backfill, one batch: the top band neighbors per memory, alive on both
        // ends. Binds 1 batch limit, 2 batch offset, 3 half_life, 4 forget_floor, 5 floor,
        // 6 ceiling, 7 cap.
        private val BACKFILL_RELATED = """
            INSERT INTO memory_edges (src_id, dst_id, kind, weight)
            WITH batch AS (SELECT id FROM memories ORDER BY id LIMIT ? OFFSET ?),
            alive AS (${MemoryGraphStore.ALIVE_CTE}),
            ranked AS (
              SELECT m.id AS src, n.id AS dst, 1 - (m.embedding <=> n.embedding) AS sim,
                     row_number() OVER (PARTITION BY m.id ORDER BY 1 - (m.embedding <=> n.embedding) DESC, n.id) AS rn
              FROM memories m JOIN memories n ON n.embed_model = m.embed_model AND n.id <> m.id
              WHERE m.id IN (SELECT id FROM batch)
                AND m.id IN (SELECT id FROM alive) AND n.id IN (SELECT id FROM alive)
                AND (1 - (m.embedding <=> n.embedding)) >= ?
                AND (1 - (m.embedding <=> n.embedding)) < ?
            )
            SELECT src, dst, '${EdgeKind.RELATED.wire}', sim FROM ranked WHERE rn <= ?
            ON CONFLICT (src_id, dst_id, kind) DO NOTHING
        """.trimIndent()

        // Cooccur backfill: same (session, turn) alive memories, pairwise, once per
        // unordered pair. Binds 1 half_life, 2 forget_floor.
        private val COOCUR = """
            INSERT INTO memory_edges (src_id, dst_id, kind, weight)
            WITH alive AS (${MemoryGraphStore.ALIVE_CTE}),
            pairs AS (
              SELECT a.id AS src, b.id AS dst
              FROM memories a JOIN memories b
                ON a.session_id IS NOT NULL AND a.session_id = b.session_id
               AND a.turn_id IS NOT NULL AND a.turn_id = b.turn_id
               AND a.id < b.id
              WHERE a.id IN (SELECT id FROM alive) AND b.id IN (SELECT id FROM alive)
            )
            SELECT src, dst, '${EdgeKind.COOCUR.wire}', 1.0 FROM pairs
            ON CONFLICT (src_id, dst_id, kind) DO NOTHING
        """.trimIndent()

        // Temporal backfill: each session's alive rows in (created_at, id) order,
        // consecutive pairs within the window chain once. Binds 1 half_life, 2 forget_floor.
        private val TEMPORAL = """
            INSERT INTO memory_edges (src_id, dst_id, kind, weight)
            WITH alive AS (${MemoryGraphStore.ALIVE_CTE}),
            ordered AS (
              SELECT id, created_at,
                     lag(id) OVER w AS prev_id, lag(created_at) OVER w AS prev_at
              FROM memories
              WHERE session_id IS NOT NULL AND id IN (SELECT id FROM alive)
              WINDOW w AS (PARTITION BY session_id ORDER BY created_at, id)
            )
            SELECT prev_id, id, '${EdgeKind.TEMPORAL.wire}', 1.0 FROM ordered
            WHERE prev_id IS NOT NULL AND prev_id IN (SELECT id FROM alive)
              AND extract(epoch FROM (created_at - prev_at)) <= ${MemoryEdges.TEMPORAL_WINDOW_SECONDS}
            ON CONFLICT (src_id, dst_id, kind) DO NOTHING
        """.trimIndent()
    }
}
