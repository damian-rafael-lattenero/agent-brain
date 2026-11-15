package dev.lattenero.brain.memory

import com.pgvector.PGvector
import dev.lattenero.brain.db.Database
import kotlinx.serialization.Serializable
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp

/**
 * The `memory_edges` table's IO edge (B4): writes edges idempotently (ON
 * CONFLICT DO NOTHING — building twice lands each edge once), and reads the
 * neighbors the builders and the traversal need. Every query excludes rows
 * search excludes too — forgotten ([MemoryStore.LIFE_WEIGHT_SQL] over
 * [Lifecycle]'s floors) and invalidated (`invalid_at`, the reconcile
 * verdict's stamp): an ARCHIVED or superseded row is never an edge source,
 * never an edge target, never a traversal result.
 *
 * The related band reuses [MemoryDedup.SIMILARITY] as its ceiling — a pair at
 * or above it is the same memory (dedup owns that boundary), never an edge.
 */
class MemoryGraphStore(private val db: Database) {
    /** One memory row as the builders read it: identity, session, and its embedding. */
    data class GraphRow(val id: Long, val sessionId: String?, val embedModel: String, val embedding: FloatArray)

    /** The two adjacent points a temporal edge may chain: the newest earlier row and the row itself. */
    data class TemporalPair(val prev: DatedMemory, val cur: DatedMemory)

    /** Batched insert of edge values; returns how many rows actually landed (idempotent skips excluded). */
    suspend fun insertEdges(edges: List<MemoryEdge>): Int = db.withConnection { conn ->
        if (edges.isEmpty()) return@withConnection 0
        conn.prepareStatement(INSERT_EDGE).use { ps ->
            for (edge in edges) {
                ps.bind(conn, edge.srcId, edge.dstId, edge.kind.wire, edge.weight)
                ps.addBatch()
            }
            ps.executeBatch().count { it > 0 }
        }
    }

    /**
     * The related candidates for one memory: same embed model (ADR-004), not
     * itself, cosine in `[RELATED_FLOOR, MemoryDedup.SIMILARITY)`, alive —
     * best first, capped at [MemoryEdges.MAX_RELATED].
     */
    suspend fun neighborsInBand(
        sourceId: Long,
        embedding: FloatArray,
        embedModel: String,
        halfLifeDays: Int,
    ): List<Neighbor> = db.withConnection { conn ->
        conn.prepareStatement(NEIGHBORS_IN_BAND).use { ps ->
            ps.bind(
                conn,
                halfLifeDays,
                Lifecycle.FORGET_FLOOR,
                PGvector(embedding),
                embedModel,
                sourceId,
                PGvector(embedding),
                MemoryEdges.RELATED_FLOOR,
                PGvector(embedding),
                MemoryDedup.SIMILARITY,
                MemoryEdges.MAX_RELATED,
            )
            readNeighbors(ps)
        }
    }

    /** The rows the builders need, by id (a missing id is already gone: CASCADE took its edges too). */
    suspend fun rows(ids: List<Long>): List<GraphRow> = db.withConnection { conn ->
        if (ids.isEmpty()) return@withConnection emptyList()
        conn.prepareStatement(
            "SELECT id, session_id, embed_model, embedding::text FROM memories WHERE id = ANY(?)",
        ).use { ps ->
            ps.bind(conn, ids)
            ps.executeQuery().use { rs ->
                generateSequence {
                    if (!rs.next()) null else GraphRow(
                        rs.getLong("id"),
                        rs.getString("session_id"),
                        rs.getString("embed_model"),
                        PGvector(rs.getString("embedding")).toArray(),
                    )
                }.toList()
            }
        }
    }

    /** The alive memories of one turn (session and turn present) — the cooccur domain. */
    suspend fun turnMemoryIds(sessionId: String, turnId: String, halfLifeDays: Int): List<Long> =
        db.withConnection { conn ->
            conn.prepareStatement(TURN_ALIVE).use { ps ->
                ps.bind(conn, halfLifeDays, Lifecycle.FORGET_FLOOR, sessionId, turnId)
                ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getLong(1) else null }.toList() }
            }
        }

    /**
     * The temporal chain's local shape for one row: its session's newest
     * earlier ALIVE row, plus the row's own instant. Null when the row has no
     * session or no earlier row in it.
     */
    suspend fun temporalPair(sessionId: String?, id: Long, halfLifeDays: Int): TemporalPair? =
        db.withConnection { conn ->
            if (sessionId == null) return@withConnection null
            conn.prepareStatement(TEMPORAL_PAIR).use { ps ->
                ps.bind(conn, halfLifeDays, Lifecycle.FORGET_FLOOR, id)
                ps.executeQuery().use { rs ->
                    if (!rs.next() || rs.getObject("prev_id") == null) null else TemporalPair(
                        prev = DatedMemory(
                            rs.getLong("prev_id"),
                            (rs.getObject("prev_at") as Timestamp).toInstant(),
                        ),
                        cur = DatedMemory(rs.getLong("cur_id"), (rs.getObject("cur_at") as Timestamp).toInstant()),
                    )
                }
            }
        }

    /**
     * The traversal fetch: every 1-hop neighbor of the hits through
     * `memory_edges` (an edge row serves BOTH endpoints — symmetric), alive on
     * both ends, each candidate carrying its edge, its relevance to the query
     * (null across embedding models — cosine is meaningless there, ADR-004)
     * and its reinforcement tie-break. The ranking and the caps are the pure
     * [traverse]'s.
     */
    suspend fun relatedCandidates(
        hits: List<Memory>,
        queryEmbedding: FloatArray,
        embedModel: String,
        lifecycle: Lifecycle.Params,
    ): List<NeighborCandidate> = db.withConnection { conn ->
        conn.prepareStatement(TRAVERSAL).use { ps ->
            ps.bind(
                conn,
                PGvector(queryEmbedding),
                embedModel,
                lifecycle.halfLifeDays,
                lifecycle.forgetFloor,
                hits.map { it.id },
            )
            ps.executeQuery().use { rs ->
                generateSequence { if (!rs.next()) null else readCandidate(rs) }.filterNotNull().toList()
            }
        }
    }

    /** Edge counts by kind — the status surface's additive `edgesByKind`. */
    suspend fun countByKind(): Map<String, Long> = db.withConnection { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT kind, count(*) FROM memory_edges GROUP BY kind ORDER BY kind").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) to rs.getLong(2) else null }.toMap()
            }
        }
    }

    private fun readNeighbors(ps: PreparedStatement): List<Neighbor> = ps.executeQuery().use { rs ->
        generateSequence { if (!rs.next()) null else Neighbor(rs.getLong(1), rs.getDouble(2)) }.toList()
    }

    /** One traversal-fetch row into a typed candidate; an impossible kind row is skipped, not thrown. */
    private fun readCandidate(rs: ResultSet): NeighborCandidate? {
        val kind = EdgeKind.entries.firstOrNull { it.wire == rs.getString("kind") } ?: return null
        return NeighborCandidate(
            viaId = rs.getLong("via_id"),
            edgeKind = kind,
            edgeWeight = rs.getDouble("edge_weight"),
            memory = Memory(
                id = rs.getLong("id"),
                kind = rs.getString("mkind"),
                content = rs.getString("content"),
                sessionId = rs.getString("session_id"),
                turnId = null,
                salience = rs.getDouble("salience"),
                createdAt = (rs.getObject("created_at") as Timestamp).toInstant().toString(),
            ),
            relevance = rs.getObject("relevance")?.let { (it as Number).toDouble() },
            salienceWeight = rs.getDouble("weight"),
        )
    }

    companion object {
        private const val INSERT_EDGE =
            "INSERT INTO memory_edges (src_id, dst_id, kind, weight) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (src_id, dst_id, kind) DO NOTHING"

        /**
         * The SQL twin of [Salience.weight] over the freshness clock
         * (created_at) — the same rendering `MemoryStore.SEARCH` uses for its
         * `weight` column, here for the traversal's tie-break. `params` must
         * carry `half_life`.
         */
        private const val RANK_WEIGHT_SQL =
            "(m.salience * exp(-ln(2) * extract(epoch FROM (now() - m.created_at))" +
                " / (86400.0 * (SELECT half_life FROM params))))"

        /**
         * The exclusion law every graph query shares: a row is alive only if
         * it is neither forgotten (B3's floor over [MemoryStore.LIFE_WEIGHT_SQL])
         * NOR invalidated (`invalid_at IS NULL` — the reconcile verdict's
         * stamp). The twin is the `alive` CTE of [MemoryStore.SEARCH]: a row
         * excluded from search must be excluded from the graph too, so recall
         * surfaces can never disagree. Duplicated rather than factored: search
         * additionally binds the embed model, which this CTE does not.
         * Binds: 1 half_life, 2 forget_floor. Shared with [EdgeBackfill]'s
         * set statements — one exclusion law, every graph query.
         */
        internal const val ALIVE_CTE =
            "SELECT id FROM memories m, (SELECT ?::int AS half_life, ?::float8 AS forget_floor) p " +
                "WHERE m.invalid_at IS NULL AND ${MemoryStore.LIFE_WEIGHT_SQL} >= p.forget_floor"

        // Band candidates for one memory: binds 1 half_life, 2 forget_floor, 3 vector,
        // 4 embed model, 5 source id, 6 vector, 7 floor, 8 vector, 9 ceiling (dedup), 10 limit.
        private val NEIGHBORS_IN_BAND = """
            WITH alive AS ($ALIVE_CTE)
            SELECT m.id, 1 - (m.embedding <=> ?::vector) AS sim
            FROM memories m
            WHERE m.embed_model = ? AND m.id <> ?
              AND (1 - (m.embedding <=> ?::vector)) >= ?
              AND (1 - (m.embedding <=> ?::vector)) < ?
              AND m.id IN (SELECT id FROM alive)
            ORDER BY sim DESC, m.id LIMIT ?
        """.trimIndent()

        // The temporal pair for one row: its session's newest earlier alive row.
        // Binds: 1 half_life, 2 forget_floor, 3 the row's id.
        private val TEMPORAL_PAIR = """
            WITH alive AS ($ALIVE_CTE),
            cur AS (SELECT id, session_id, created_at FROM memories WHERE id = ?)
            SELECT cur.id AS cur_id, cur.created_at AS cur_at, prev.id AS prev_id, prev.created_at AS prev_at
            FROM cur
            LEFT JOIN LATERAL (
              SELECT m.id, m.created_at FROM memories m
              WHERE m.session_id IS NOT NULL AND m.session_id = cur.session_id
                AND m.id <> cur.id
                AND (m.created_at, m.id) < (cur.created_at, cur.id)
                AND m.id IN (SELECT id FROM alive)
              ORDER BY m.created_at DESC, m.id DESC LIMIT 1
            ) prev ON true
        """.trimIndent()

        // The alive memories of one turn. Binds: 1 half_life, 2 forget_floor, 3 sid, 4 turn.
        private val TURN_ALIVE = """
            WITH alive AS ($ALIVE_CTE)
            SELECT id FROM memories WHERE session_id = ? AND turn_id = ?
              AND id IN (SELECT id FROM alive) ORDER BY id
        """.trimIndent()

        // 1-hop traversal candidates. Binds: 1 query vector, 2 embed model, 3 half_life,
        // 4 forget_floor, 5 hit ids.
        private val TRAVERSAL = """
            WITH params AS (
              SELECT ?::vector AS qv, ?::text AS model, ?::int AS half_life, ?::float8 AS forget_floor
            ), alive AS (
              -- Twin of the graph ALIVE_CTE / MemoryStore.SEARCH's alive CTE:
              -- forgotten AND invalidated rows leave traversal too.
              SELECT id FROM memories m, params p
              WHERE m.invalid_at IS NULL
                AND ${MemoryStore.LIFE_WEIGHT_SQL} >= p.forget_floor
            ), hits AS (SELECT unnest(?::bigint[]) AS hid)
            SELECT e.kind, e.weight AS edge_weight, h.hid AS via_id, m.id, m.kind AS mkind, m.content,
                   m.session_id, m.created_at, m.salience, $RANK_WEIGHT_SQL AS weight,
                   CASE WHEN m.embed_model = (SELECT model FROM params)
                        THEN 1 - (m.embedding <=> (SELECT qv FROM params)) END AS relevance
            FROM hits h
            JOIN memory_edges e ON e.src_id = h.hid OR e.dst_id = h.hid
            JOIN memories m ON m.id = CASE WHEN e.src_id = h.hid THEN e.dst_id ELSE e.src_id END
            WHERE m.id <> h.hid
              AND m.id IN (SELECT id FROM alive)
              AND e.src_id IN (SELECT id FROM alive)
              AND e.dst_id IN (SELECT id FROM alive)
        """.trimIndent()
    }
}

/**
 * Positional parameter binder: the "which index is this" arithmetic lives in
 * ONE place, and each call site reads as its SQL's bind list in order.
 */
internal fun PreparedStatement.bind(conn: java.sql.Connection, vararg values: Any) {
    values.forEachIndexed { index, value ->
        val position = index + 1
        when (value) {
            is Int -> setInt(position, value)
            is Long -> setLong(position, value)
            is Double -> setDouble(position, value)
            is String -> setString(position, value)
            is PGvector -> setObject(position, value)
            is List<*> -> setArray(position, conn.createArrayOf("bigint", value.toTypedArray()))
            else -> throw IllegalArgumentException("unbindable parameter: ${value::class.simpleName}")
        }
    }
}

/** The backfill report: rows scanned and edges landed per kind (0 after the second run). */
@Serializable
data class EdgeBackfillReport(val scanned: Int, val related: Int, val cooccur: Int, val temporal: Int) {
    val total: Int get() = related + cooccur + temporal
}
