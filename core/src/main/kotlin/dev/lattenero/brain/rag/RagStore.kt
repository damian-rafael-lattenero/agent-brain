package dev.lattenero.brain.rag

import com.pgvector.PGvector
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.inTransaction
import dev.lattenero.brain.db.toVectorText
import kap.KapTypeSafe
import kap.evalGraph
import kotlinx.serialization.Serializable

@Serializable
data class Hit(
    val chunkId: Long,
    val docId: Long,
    val uri: String,
    val title: String,
    val heading: String,
    val ordinal: Int,
    val content: String,
    val score: Double,
    val vectorRank: Int?,
    val textRank: Int?,
    /** The vector cosine similarity — the relevance signal RRF cannot express; null on FTS-only hits. */
    val similarity: Double? = null,
)

@Serializable
@KapTypeSafe
data class RagStats(val documents: Long, val chunks: Long, val bySource: Map<String, Long>)

/**
 * documents + chunks in Postgres. Hybrid retrieval fuses two ranked lists
 * with Reciprocal Rank Fusion (k = [RRF_K]): cosine over pgvector and
 * full-text over the generated tsvector. Both legs filter by the current
 * embedding model so a mixed table (ADR-004) can never rank apples against
 * oranges. Every hit carries its citation: uri, title, heading, ordinal.
 */
class RagStore(private val db: Database) {
    data class ChunkRow(val ordinal: Int, val heading: String, val content: String, val embedding: FloatArray)

    sealed interface Upsert {
        data class Unchanged(val docId: Long) : Upsert
        data class Changed(val docId: Long, val inserted: Boolean) : Upsert
    }

    /** Inserts or updates by uri; unchanged content (same sha) is left alone and reported as such. */
    suspend fun upsertDocument(source: String, uri: String, title: String, content: String, sha: String): Upsert = db.withDriver { brainDb ->
        val existing = brainDb.ragQueries.existingDoc(uri).executeAsOneOrNull()
        if (existing != null && existing.content_sha == sha) return@withDriver Upsert.Unchanged(existing.id)
        val id = brainDb.ragQueries.upsertDocument(source, uri, title, content, sha).executeAsOne()
        Upsert.Changed(id, inserted = existing == null)
    }

    /** Replaces every chunk of a document in one transaction. */
    suspend fun replaceChunks(docId: Long, embedModel: String, rows: List<ChunkRow>): Int = db.withDriver { brainDb ->
        brainDb.transaction {
            brainDb.ragQueries.deleteChunksByDoc(docId)
            for (row in rows) {
                brainDb.ragQueries.insertChunk(
                    docId,
                    row.ordinal,
                    row.heading,
                    row.content,
                    embedModel,
                    row.embedding.toVectorText(),
                )
            }
        }
        rows.size
    }

    suspend fun deleteDocumentsNotIn(source: String, keptUris: Collection<String>): Int =
        db.withDriver { brainDb ->
            brainDb.ragQueries.deleteDocumentsNotIn(source, keptUris.toTypedArray()).value.toInt()
        }

    /** Deletes one document; its chunks cascade via the foreign key. For documents that chunk to nothing. */
    suspend fun deleteDocument(docId: Long) {
        db.withDriver { brainDb -> brainDb.ragQueries.deleteDocument(docId).executeAsOne() }
    }

    suspend fun search(queryEmbedding: FloatArray, queryText: String, embedModel: String, k: Int = DEFAULT_K): List<Hit> = db.withDriver { brainDb ->
        val candidates = maxOf(k * CANDIDATE_FACTOR, MIN_CANDIDATES)
        brainDb.ragQueries.searchRag(
            queryEmbedding.toVectorText(),
            embedModel,
            candidates,
            queryText,
            k.toLong(),
        ).executeAsList().map {
            Hit(
                chunkId = it.id,
                docId = it.doc_id,
                uri = it.uri,
                title = it.title,
                heading = it.heading,
                ordinal = it.ordinal,
                content = it.content,
                score = it.score?.toDouble() ?: 0.0,
                vectorRank = it.vec_rank?.toInt(),
                textRank = it.fts_rank?.toInt(),
                similarity = it.sim,
            )
        }
    }

    /** Three independent queries, three pooled connections, one typed graph. */
    suspend fun stats(): RagStats = kap(::RagStats)
        .with { documents from countDocuments() }
        .with { chunks from countChunks() }
        .with { bySource from bySource() }
        .evalGraph()

    private suspend fun countDocuments(): Long = db.withDriver { it.ragQueries.countDocuments().executeAsOne() }

    private suspend fun countChunks(): Long = db.withDriver { it.ragQueries.countChunks().executeAsOne() }

    private suspend fun bySource(): Map<String, Long> = db.withDriver { brainDb ->
        brainDb.ragQueries.documentsBySource().executeAsList().associate { it.source to it.n }
    }

    companion object {
        const val DEFAULT_K = 5
        const val CANDIDATE_FACTOR = 4
        const val MIN_CANDIDATES = 50

        /** The fusion constant lives in the pure core ([Rrf]); the SQL below is its executable twin. */
        const val RRF_K = Rrf.K

        // Two ranked legs, one fused list: 1/(RRF_K + rank) summed per chunk.
        // `sim` (1 - vec.dist) rides out with the hit — the confidence
        // floor's signal (see Rag.sq searchRag).
        // Parameters: 1 query vector, 2 embed model, 3 candidates per leg, 4 query text, 5 k.
        // The vector leg is an exact scan (the 1-row params CTE hides the constant from
        // the HNSW planner); exact beats approximate until the corpus outgrows it.
        val SEARCH = """
            WITH params AS (
              SELECT ?::vector AS qv, ?::text AS model, ?::int AS n, websearch_to_tsquery('simple', ?) AS tsq
            ), vec AS (
              SELECT id, row_number() OVER (ORDER BY dist, id) AS r FROM (
                SELECT c.id, c.embedding <=> p.qv AS dist
                FROM chunks c, params p WHERE c.embed_model = p.model
                ORDER BY dist, c.id LIMIT (SELECT n FROM params)
              ) top
            ), fts AS (
              SELECT id, row_number() OVER (ORDER BY rank DESC, id) AS r FROM (
                SELECT c.id, ts_rank_cd(c.content_tsv, p.tsq) AS rank
                FROM chunks c, params p
                WHERE c.embed_model = p.model AND c.content_tsv @@ p.tsq
                ORDER BY rank DESC, c.id LIMIT (SELECT n FROM params)
              ) top
            ), fused AS (
              SELECT id, SUM(1.0 / ($RRF_K + r)) AS score
              FROM (SELECT id, r FROM vec UNION ALL SELECT id, r FROM fts) legs
              GROUP BY id
            )
            SELECT c.id, c.doc_id, d.uri, d.title, c.heading, c.ordinal, c.content, f.score,
                   (SELECT r FROM vec WHERE vec.id = c.id) AS vec_rank,
                   (SELECT r FROM fts WHERE fts.id = c.id) AS fts_rank
            FROM fused f
            JOIN chunks c ON c.id = f.id
            JOIN documents d ON d.id = c.doc_id
            ORDER BY f.score DESC, c.id
            LIMIT ?
        """
    }
}
