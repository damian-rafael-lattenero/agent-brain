package dev.lattenero.brain.memory

import com.pgvector.PGvector
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.toVectorText
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
    suspend fun insertEdges(edges: List<MemoryEdge>): Int = db.withDriver { brainDb ->
        if (edges.isEmpty()) return@withDriver 0
        var landed = 0
        brainDb.transaction {
            for (edge in edges) {
                landed += brainDb.memoryEdgesQueries.insertEdge(
                    edge.srcId,
                    edge.dstId,
                    edge.kind.wire,
                    edge.weight,
                ).value.toInt()
            }
        }
        landed
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
    ): List<Neighbor> = db.withDriver { brainDb ->
        brainDb.memoryEdgesQueries.neighborsInBand(
            halfLifeDays,
            Lifecycle.FORGET_FLOOR,
            embedding.toVectorText(),
            embedModel,
            sourceId,
            MemoryEdges.RELATED_FLOOR,
            MemoryDedup.SIMILARITY,
            MemoryEdges.MAX_RELATED.toLong(),
        ).executeAsList().map { Neighbor(it.id, it.sim) }
    }

    /** The rows the builders need, by id (a missing id is already gone: CASCADE took its edges too). */
    suspend fun rows(ids: List<Long>): List<GraphRow> = db.withDriver { brainDb ->
        if (ids.isEmpty()) return@withDriver emptyList()
        brainDb.memoryEdgesQueries.graphRows(ids.toTypedArray()).executeAsList().map {
            GraphRow(it.id, it.session_id, it.embed_model, PGvector(it.embedding_text).toArray())
        }
    }

    /** The alive memories of one turn (session and turn present) — the cooccur domain. */
    suspend fun turnMemoryIds(sessionId: String, turnId: String, halfLifeDays: Int): List<Long> =
        db.withDriver { brainDb ->
            brainDb.memoryEdgesQueries.turnAlive(halfLifeDays, Lifecycle.FORGET_FLOOR, sessionId, turnId)
                .executeAsList()
        }

    /**
     * The temporal chain's local shape for one row: its session's newest
     * earlier ALIVE row, plus the row's own instant. Null when the row has no
     * session or no earlier row in it.
     */
    suspend fun temporalPair(sessionId: String?, id: Long, halfLifeDays: Int): TemporalPair? =
        db.withDriver { brainDb ->
            if (sessionId == null) return@withDriver null
            brainDb.memoryEdgesQueries.temporalPair(halfLifeDays, Lifecycle.FORGET_FLOOR, id)
                .executeAsOneOrNull()?.let {
                    if (it.prev_id == null || it.prev_at == null) {
                        null
                    } else {
                        TemporalPair(
                            prev = DatedMemory(it.prev_id, it.prev_at.toInstant()),
                            cur = DatedMemory(it.cur_id, it.cur_at.toInstant()),
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
    ): List<NeighborCandidate> = db.withDriver { brainDb ->
        brainDb.memoryEdgesQueries.traverse(
            queryEmbedding.toVectorText(),
            embedModel,
            lifecycle.halfLifeDays,
            lifecycle.forgetFloor,
            hits.map { it.id }.toTypedArray(),
        ).executeAsList().mapNotNull { r ->
            val kind = EdgeKind.entries.firstOrNull { it.wire == r.kind } ?: return@mapNotNull null
            NeighborCandidate(
                viaId = r.via_id,
                edgeKind = kind,
                edgeWeight = r.edge_weight,
                memory = Memory(
                    id = r.id,
                    kind = r.mkind,
                    content = r.content,
                    sessionId = r.session_id,
                    turnId = null,
                    salience = r.salience,
                    createdAt = r.created_at.toInstant().toString(),
                ),
                relevance = r.relevance,
                salienceWeight = r.weight,
            )
        }
    }

    /** Edge counts by kind — the status surface's additive `edgesByKind`. */
    suspend fun countByKind(): Map<String, Long> = db.withDriver { brainDb ->
        brainDb.memoryEdgesQueries.countByKind().executeAsList().associate { it.kind to it.n }
    }
}

/** The backfill report: rows scanned and edges landed per kind (0 after the second run). */
@Serializable
data class EdgeBackfillReport(val scanned: Int, val related: Int, val cooccur: Int, val temporal: Int) {
    val total: Int get() = related + cooccur + temporal
}
