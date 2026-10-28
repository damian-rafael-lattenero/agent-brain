package dev.lattenero.brain.rag

import com.pgvector.PGvector
import dev.lattenero.brain.db.Database
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
)

@Serializable
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
    suspend fun upsertDocument(source: String, uri: String, title: String, content: String, sha: String): Upsert = db.withConnection { conn ->
        val existing = conn.prepareStatement("SELECT id, content_sha FROM documents WHERE uri = ?").use { ps ->
            ps.setString(1, uri)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) to rs.getString(2) else null }
        }
        if (existing != null && existing.second == sha) return@withConnection Upsert.Unchanged(existing.first)
        val id = conn.prepareStatement(
            """
            INSERT INTO documents (source, uri, title, content, content_sha)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (uri) DO UPDATE SET source = EXCLUDED.source, title = EXCLUDED.title,
              content = EXCLUDED.content, content_sha = EXCLUDED.content_sha, updated_at = now()
            RETURNING id
            """,
        ).use { ps ->
            ps.setString(1, source)
            ps.setString(2, uri)
            ps.setString(3, title)
            ps.setString(4, content)
            ps.setString(5, sha)
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
        Upsert.Changed(id, inserted = existing == null)
    }

    /** Replaces every chunk of a document in one transaction. */
    suspend fun replaceChunks(docId: Long, embedModel: String, rows: List<ChunkRow>): Int = db.withConnection { conn ->
        conn.autoCommit = false
        try {
            conn.prepareStatement("DELETE FROM chunks WHERE doc_id = ?").use { ps ->
                ps.setLong(1, docId)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO chunks (doc_id, ordinal, heading, content, embed_model, embedding) VALUES (?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                for (row in rows) {
                    ps.setLong(1, docId)
                    ps.setInt(2, row.ordinal)
                    ps.setString(3, row.heading)
                    ps.setString(4, row.content)
                    ps.setString(5, embedModel)
                    ps.setObject(6, PGvector(row.embedding))
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            conn.commit()
            rows.size
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    suspend fun deleteDocumentsNotIn(source: String, keptUris: Collection<String>): Int = db.withConnection { conn ->
        conn.prepareStatement("DELETE FROM documents WHERE source = ? AND NOT (uri = ANY(?))").use { ps ->
            ps.setString(1, source)
            ps.setArray(2, conn.createArrayOf("text", keptUris.toTypedArray()))
            ps.executeUpdate()
        }
    }

    suspend fun search(queryEmbedding: FloatArray, queryText: String, embedModel: String, k: Int = DEFAULT_K): List<Hit> = db.withConnection { conn ->
        val candidates = maxOf(k * CANDIDATE_FACTOR, MIN_CANDIDATES)
        conn.prepareStatement(SEARCH).use { ps ->
            val vector = PGvector(queryEmbedding)
            ps.setObject(1, vector)
            ps.setString(2, embedModel)
            ps.setInt(3, candidates)
            ps.setString(4, queryText)
            ps.setInt(5, k)
            ps.executeQuery().use { rs ->
                generateSequence {
                    if (!rs.next()) null else Hit(
                        chunkId = rs.getLong("id"),
                        docId = rs.getLong("doc_id"),
                        uri = rs.getString("uri"),
                        title = rs.getString("title"),
                        heading = rs.getString("heading"),
                        ordinal = rs.getInt("ordinal"),
                        content = rs.getString("content"),
                        score = rs.getDouble("score"),
                        vectorRank = rs.getObject("vec_rank")?.let { (it as Number).toInt() },
                        textRank = rs.getObject("fts_rank")?.let { (it as Number).toInt() },
                    )
                }.toList()
            }
        }
    }

    suspend fun stats(): RagStats = db.withConnection { conn ->
        val documents = conn.createStatement().use { st -> st.executeQuery("SELECT count(*) FROM documents").use { rs -> rs.next(); rs.getLong(1) } }
        val chunks = conn.createStatement().use { st -> st.executeQuery("SELECT count(*) FROM chunks").use { rs -> rs.next(); rs.getLong(1) } }
        val bySource = conn.createStatement().use { st ->
            st.executeQuery("SELECT source, count(*) FROM documents GROUP BY source ORDER BY source").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) to rs.getLong(2) else null }.toMap()
            }
        }
        RagStats(documents, chunks, bySource)
    }

    companion object {
        const val DEFAULT_K = 5
        const val CANDIDATE_FACTOR = 4
        const val MIN_CANDIDATES = 50
        const val RRF_K = 60

        // Two ranked legs, one fused list: 1/(RRF_K + rank) summed per chunk.
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
