package dev.lattenero.brain.memory

import com.pgvector.PGvector
import dev.lattenero.brain.db.Database
import kap.KapTypeSafe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Timestamp

@Serializable
data class Memory(
    val id: Long,
    val kind: String,
    val content: String,
    val sessionId: String?,
    val turnId: String?,
    val salience: Double,
    val createdAt: String,
    val score: Double? = null,
)

@Serializable
data class QuarantineRow(val id: Long, val eventId: String?, val sessionId: String?, val turnId: String?, val reason: String, val raw: String, val ts: String)

@Serializable
@KapTypeSafe
data class MemoryStatus(
    val pendingTurns: Long,
    val processedTurns: Long,
    val memories: Long,
    val byKind: Map<String, Long>,
    val quarantined: Long,
    val breaker: String,
    val chatModel: String,
    val embedModel: String,
)

/** memories, memory_quarantine and the worker's bookkeeping columns on bus_events. */
class MemoryStore(private val db: Database) {
    data class PendingTurn(val eventId: String, val sessionId: String, val turnId: String, val source: String, val attempts: Int)

    data class MemoryRow(val kind: String, val content: String, val embedding: FloatArray)

    suspend fun pendingTurns(limit: Int, maxAttempts: Int): List<PendingTurn> = db.withConnection { conn ->
        conn.prepareStatement(
            """
            SELECT id, sid, turn, source, memory_attempts FROM bus_events
            WHERE kind = 'turn.completed' AND memory_at IS NULL AND memory_attempts < ?
            ORDER BY ts LIMIT ?
            """,
        ).use { ps ->
            ps.setInt(1, maxAttempts)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                generateSequence { if (rs.next()) PendingTurn(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5)) else null }.toList()
            }
        }
    }

    /** Everything the bus knows about one turn, folded into a transcript. */
    suspend fun transcript(pending: PendingTurn): Transcript = db.withConnection { conn ->
        conn.prepareStatement("SELECT kind, payload::text FROM bus_events WHERE sid = ? AND turn = ? ORDER BY ts, id").use { ps ->
            ps.setString(1, pending.sessionId)
            ps.setString(2, pending.turnId)
            ps.executeQuery().use { rs ->
                var prompt = ""
                var reply = ""
                var title = ""
                val tools = LinkedHashSet<String>()
                while (rs.next()) {
                    val kind = rs.getString(1)
                    val payload = json.parseToJsonElement(rs.getString(2)).jsonObject
                    fun str(key: String) = payload[key]?.jsonPrimitive?.content ?: ""
                    when (kind) {
                        "turn.started" -> {
                            prompt = str("prompt")
                            title = str("title")
                        }
                        "user.prompt" -> prompt = str("prompt")
                        "tool.call" -> str("tool").takeIf { it.isNotBlank() }?.let { tools += it }
                        "turn.completed" -> reply = str("reply").ifBlank { str("response") }
                    }
                }
                Transcript(pending.eventId, pending.sessionId, pending.turnId, pending.source, prompt, reply, tools.toList(), title)
            }
        }
    }

    suspend fun insertAll(transcript: Transcript, embedModel: String, rows: List<MemoryRow>): Int = db.withConnection { conn ->
        if (rows.isEmpty()) return@withConnection 0
        conn.prepareStatement(
            """
            INSERT INTO memories (session_id, turn_id, kind, content, embed_model, embedding, source_event_id)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """,
        ).use { ps ->
            for (row in rows) {
                ps.setString(1, transcript.sessionId)
                ps.setString(2, transcript.turnId)
                ps.setString(3, row.kind)
                ps.setString(4, row.content)
                ps.setString(5, embedModel)
                ps.setObject(6, PGvector(row.embedding))
                ps.setString(7, transcript.eventId)
                ps.addBatch()
            }
            ps.executeBatch().count { it > 0 }
        }
    }

    suspend fun saveManual(kind: String, content: String, embedModel: String, embedding: FloatArray, sessionId: String?): Long = db.withConnection { conn ->
        conn.prepareStatement(
            "INSERT INTO memories (session_id, turn_id, kind, content, embed_model, embedding, source_event_id) VALUES (?, NULL, ?, ?, ?, ?, NULL) RETURNING id",
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.setString(2, kind)
            ps.setString(3, content)
            ps.setString(4, embedModel)
            ps.setObject(5, PGvector(embedding))
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    suspend fun markProcessed(eventId: String) = db.withConnection { conn ->
        conn.prepareStatement("UPDATE bus_events SET memory_at = now() WHERE id = ?").use { ps ->
            ps.setString(1, eventId)
            ps.executeUpdate()
        }
    }

    suspend fun bumpAttempts(eventId: String) = db.withConnection { conn ->
        conn.prepareStatement("UPDATE bus_events SET memory_attempts = memory_attempts + 1 WHERE id = ?").use { ps ->
            ps.setString(1, eventId)
            ps.executeUpdate()
        }
    }

    suspend fun quarantine(transcript: Transcript?, eventId: String, reason: String, raw: String) = db.withConnection { conn ->
        conn.prepareStatement("INSERT INTO memory_quarantine (turn_id, reason, raw, event_id, session_id) VALUES (?, ?, ?, ?, ?)").use { ps ->
            ps.setString(1, transcript?.turnId)
            ps.setString(2, reason.take(MAX_REASON_CHARS))
            ps.setString(3, raw)
            ps.setString(4, eventId)
            ps.setString(5, transcript?.sessionId)
            ps.executeUpdate()
        }
    }

    /** Hybrid RRF like the RAG store, then a half-life decay by age and the row's salience; hits bump last_accessed. */
    suspend fun search(queryEmbedding: FloatArray, queryText: String, embedModel: String, k: Int, halfLifeDays: Int): List<Memory> = db.withConnection { conn ->
        val hits = conn.prepareStatement(SEARCH).use { ps ->
            ps.setObject(1, PGvector(queryEmbedding))
            ps.setString(2, embedModel)
            ps.setInt(3, maxOf(k * CANDIDATE_FACTOR, MIN_CANDIDATES))
            ps.setString(4, queryText)
            ps.setInt(5, halfLifeDays)
            ps.setInt(6, k)
            ps.executeQuery().use { rs -> readMemories(rs) }
        }
        if (hits.isNotEmpty()) {
            conn.prepareStatement("UPDATE memories SET last_accessed = now() WHERE id = ANY(?)").use { ps ->
                ps.setArray(1, conn.createArrayOf("bigint", hits.map { it.id }.toTypedArray()))
                ps.executeUpdate()
            }
        }
        hits
    }

    suspend fun recent(limit: Int, kind: String? = null): List<Memory> = db.withConnection { conn ->
        val sql = "SELECT id, kind, content, session_id, turn_id, salience, created_at, NULL::float8 AS score FROM memories " +
            (if (kind != null) "WHERE kind = ? " else "") + "ORDER BY created_at DESC, id DESC LIMIT ?"
        conn.prepareStatement(sql).use { ps ->
            var i = 1
            if (kind != null) ps.setString(i++, kind)
            ps.setInt(i, limit)
            ps.executeQuery().use { rs -> readMemories(rs) }
        }
    }

    suspend fun quarantined(limit: Int): List<QuarantineRow> = db.withConnection { conn ->
        conn.prepareStatement("SELECT id, event_id, session_id, turn_id, reason, raw, ts FROM memory_quarantine ORDER BY ts DESC, id DESC LIMIT ?").use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                generateSequence {
                    if (!rs.next()) null else QuarantineRow(
                        rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getTimestamp(7).toInstant().toString(),
                    )
                }.toList()
            }
        }
    }

    suspend fun countPending(): Long = scalar("SELECT count(*) FROM bus_events WHERE kind = 'turn.completed' AND memory_at IS NULL")

    suspend fun countProcessed(): Long = scalar("SELECT count(*) FROM bus_events WHERE kind = 'turn.completed' AND memory_at IS NOT NULL")

    suspend fun countMemories(): Long = scalar("SELECT count(*) FROM memories")

    suspend fun countQuarantined(): Long = scalar("SELECT count(*) FROM memory_quarantine")

    suspend fun countByKind(): Map<String, Long> = db.withConnection { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT kind, count(*) FROM memories GROUP BY kind ORDER BY kind").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) to rs.getLong(2) else null }.toMap()
            }
        }
    }

    private suspend fun scalar(sql: String): Long = db.withConnection { conn ->
        conn.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } }
    }

    private fun readMemories(rs: java.sql.ResultSet): List<Memory> = generateSequence {
        if (!rs.next()) null else Memory(
            id = rs.getLong("id"),
            kind = rs.getString("kind"),
            content = rs.getString("content"),
            sessionId = rs.getString("session_id"),
            turnId = rs.getString("turn_id"),
            salience = rs.getDouble("salience"),
            createdAt = (rs.getObject("created_at") as Timestamp).toInstant().toString(),
            score = rs.getObject("score")?.let { (it as Number).toDouble() },
        )
    }.toList()

    companion object {
        const val CANDIDATE_FACTOR = 4
        const val MIN_CANDIDATES = 50
        const val RRF_K = 60
        const val MAX_REASON_CHARS = 500
        private val json = Json { ignoreUnknownKeys = true }

        // Parameters: 1 query vector, 2 embed model, 3 candidates per leg, 4 query text, 5 half-life days, 6 k.
        val SEARCH = """
            WITH params AS (
              SELECT ?::vector AS qv, ?::text AS model, ?::int AS n, websearch_to_tsquery('simple', ?) AS tsq, ?::int AS half_life
            ), vec AS (
              SELECT id, row_number() OVER (ORDER BY dist, id) AS r FROM (
                SELECT m.id, m.embedding <=> p.qv AS dist FROM memories m, params p
                WHERE m.embed_model = p.model ORDER BY dist, m.id LIMIT (SELECT n FROM params)
              ) top
            ), fts AS (
              SELECT id, row_number() OVER (ORDER BY rank DESC, id) AS r FROM (
                SELECT m.id, ts_rank_cd(m.content_tsv, p.tsq) AS rank FROM memories m, params p
                WHERE m.embed_model = p.model AND m.content_tsv @@ p.tsq
                ORDER BY rank DESC, m.id LIMIT (SELECT n FROM params)
              ) top
            ), fused AS (
              SELECT id, SUM(1.0 / ($RRF_K + r)) AS rrf
              FROM (SELECT id, r FROM vec UNION ALL SELECT id, r FROM fts) legs GROUP BY id
            )
            SELECT m.id, m.kind, m.content, m.session_id, m.turn_id, m.salience, m.created_at,
                   f.rrf * m.salience * exp(-ln(2) * extract(epoch FROM (now() - m.created_at)) / (86400.0 * (SELECT half_life FROM params))) AS score
            FROM fused f JOIN memories m ON m.id = f.id
            ORDER BY score DESC, m.id
            LIMIT ?
        """
    }
}
