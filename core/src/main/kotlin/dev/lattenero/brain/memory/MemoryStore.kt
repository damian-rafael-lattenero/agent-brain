package dev.lattenero.brain.memory

import com.pgvector.PGvector
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.inTransaction
import dev.lattenero.brain.rag.Rrf
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
    val weight: Double? = null,
)

@Serializable
data class QuarantineRow(val id: Long, val eventId: String?, val sessionId: String?, val turnId: String?, val reason: String, val raw: String, val ts: String)

@Serializable
@KapTypeSafe
data class MemoryStatus(
    val pendingTurns: Long,
    val processedTurns: Long,
    val memories: Long,
    val active: Long,
    val cooling: Long,
    val archived: Long,
    val byKind: Map<String, Long>,
    val quarantined: Long,
    val breaker: String,
    val chatModel: String,
    val embedModel: String,
)

/**
 * memories, memory_quarantine and the worker's bookkeeping columns on bus_events.
 *
 * Inserts are dedup-aware ([insertAllDeduped], [saveManual]): a new memory
 * whose embedding is cosine-similar to an existing row at or above
 * [MemoryDedup.SIMILARITY] is NOT inserted — the existing row is reinforced
 * (salience and last_accessed, the decay system's own fields) and its
 * identity returned. The comparison spans ALL kinds and ALL sessions (the
 * measured duplicates cross kinds, `scripts/memory-dedup-evidence.sql`),
 * filtered by embed_model: cosine across embedding models is meaningless
 * (ADR-004). The store is small, so the exact per-insert scan is fine.
 */
class MemoryStore(private val db: Database) {
    data class PendingTurn(val eventId: String, val sessionId: String, val turnId: String, val source: String, val attempts: Int)

    data class MemoryRow(val kind: String, val content: String, val embedding: FloatArray)

    /** What one dedup-aware insert pass did: [inserted] new rows, [merged] identities of reinforced rows. */
    data class InsertReport(val inserted: Int, val merged: List<Long>, val insertedIds: List<Long> = emptyList())

    /**
     * One candidate a verdict pass (reconcile, dream) may judge: the row's
     * identity, its claim, its birth (the older side of the pair is the one
     * created earlier) and the cosine that put it in the band.
     */
    data class NeighborRow(val id: Long, val content: String, val createdAt: java.time.Instant, val cosine: Double)

    /**
     * The similarity window a verdict pass searches: cosine in [low, high)
     * plus the forgetting half-life that keeps archived rows out (B3). One
     * value so every pass hands the query the SAME knobs.
     */
    data class NeighborBand(val low: Double, val high: Double, val halfLifeDays: Int)

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

    /** The raw insert: exactly these rows, ON CONFLICT DO NOTHING, no dedup. */
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

    /**
     * The dedup-aware insert (one transaction per turn): per row, in order —
     * an exact replay of the same (event, kind, content) is absorbed silently
     * (a crash between insert and mark re-runs the turn; that is idempotence,
     * never a salience bump); a row cosine-similar to an existing row at or
     * above [MemoryDedup.SIMILARITY] reinforces the OLDEST such row instead of
     * landing (the same rule the backfill clusters with, so running the
     * backfill after insert-time dedup is a no-op); anything else is inserted.
     * Rows of one extraction see each other: a summary that restates this
     * turn's fact merges into it.
     */
    suspend fun insertAllDeduped(transcript: Transcript, embedModel: String, rows: List<MemoryRow>): InsertReport {
        if (rows.isEmpty()) return InsertReport(0, emptyList())
        return db.inTransaction { conn ->
            val merged = mutableListOf<Long>()
            val insertedIds = mutableListOf<Long>()
            var inserted = 0
            for (row in rows) {
                val replayed = findReplay(conn, transcript.eventId, row.kind, row.content)
                if (replayed != null) continue
                val duplicate = findDuplicate(conn, row.embedding, embedModel, MemoryDedup.SIMILARITY)
                if (duplicate != null) {
                    reinforce(conn, duplicate, REINFORCE_STEP)
                    merged += duplicate
                } else {
                    val id = insertRow(conn, transcript, embedModel, row)
                    if (id >= 0) {
                        insertedIds += id
                        inserted += 1
                    }
                }
            }
            InsertReport(inserted, merged, insertedIds)
        }
    }

    /** Manual saves dedup with the same rule and return the surviving row's id. */
    suspend fun saveManual(kind: String, content: String, embedModel: String, embedding: FloatArray, sessionId: String?): Long = db.withConnection { conn ->
        val duplicate = findDuplicate(conn, embedding, embedModel, MemoryDedup.SIMILARITY)
        if (duplicate != null) {
            reinforce(conn, duplicate, REINFORCE_STEP)
            return@withConnection duplicate
        }
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

    /**
     * Every row the dedup backfill reads, oldest first — invalidated rows
     * excluded (a live claim must never be absorbed by a dead one). The
     * backfill is a CLI command, never a migration: a destructive merge must
     * run when the operator asks for it, not at every startup.
     */
    suspend fun dedupRows(): List<DedupRow> = db.withConnection { conn ->
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT id, kind, content, embed_model, salience, created_at, embedding::text " +
                    "FROM memories WHERE invalid_at IS NULL ORDER BY created_at, id",
            ).use { rs ->
                generateSequence {
                    if (!rs.next()) null else DedupRow(
                        id = rs.getLong(1),
                        kind = rs.getString(2),
                        content = rs.getString(3),
                        embedModel = rs.getString(4),
                        salience = rs.getDouble(5),
                        createdAt = (rs.getObject(6) as Timestamp).toInstant(),
                        embedding = PGvector(rs.getString(7)).toArray(),
                    )
                }.toList()
            }
        }
    }

    /**
     * Applies a [MemoryDedup.plan] in one transaction: each surviving row is
     * reinforced by its absorbed duplicates' count (exactly what insert-time
     * dedup would have accumulated for them) and the absorbed rows are
     * deleted. Returns the number of rows removed.
     */
    suspend fun applyDedup(plan: Plan): Int = db.inTransaction { conn ->
        for (cluster in plan.clusters) {
            reinforce(conn, cluster.keepId, REINFORCE_STEP * cluster.reinforcements)
            deleteMemories(conn, cluster.absorbedIds)
        }
        plan.removed
    }

    /**
     * Zep-style invalidation (the reconcile law): the old claim is stamped
     * with the current time and NOTHING else changes — content stays
     * byte-identical, salience is untouched, the row is NOT deleted. A row
     * already invalidated is left alone (idempotent: the first stamp is the
     * only stamp). Returns whether THIS call stamped it.
     */
    suspend fun invalidate(id: Long): Boolean = db.withConnection { conn ->
        invalidate(conn, id)
    }

    /**
     * Dedup's own merge, one transaction, for the reconcile and dream passes:
     * the survivor is reinforced exactly as an insert-time dedup merge would
     * have reinforced it, and the absorbed row is deleted (the Same verdict
     * means one memory said twice, so the store holds it once).
     */
    suspend fun mergeInto(survivorId: Long, absorbedId: Long): Unit = db.inTransaction { conn ->
        if (survivorId != absorbedId) {
            reinforce(conn, survivorId, REINFORCE_STEP)
            deleteMemories(conn, listOf(absorbedId))
        }
    }

    /**
     * The nearest neighbor of [embedding] that dedup did NOT merge — cosine
     * in the band (its high edge reads [MemoryDedup.SIMILARITY], so the band
     * ends exactly where dedup takes over), same embed model, never the row
     * itself, never an invalidated row (dead claims are not verdict inputs)
     * and never a forgotten one (B3: archived rows are not reconcile/dream
     * inputs). Nearest first — the ONE candidate a reconcile verdict sees.
     */
    suspend fun nearestNeighbor(
        embedding: FloatArray,
        selfId: Long,
        embedModel: String,
        band: NeighborBand,
    ): NeighborRow? = db.withConnection { conn ->
        conn.prepareStatement(
            """
            WITH params AS (SELECT ?::int AS half_life, ?::float8 AS forget_floor),
                 q AS (SELECT ?::vector AS v)
            SELECT m.id, m.content, m.created_at, 1 - (m.embedding <=> q.v) AS cosine
            FROM memories m, params p, q
            WHERE m.embed_model = ?
              AND m.id <> ?
              AND m.invalid_at IS NULL
              AND (1 - (m.embedding <=> q.v)) >= ?
              AND (1 - (m.embedding <=> q.v)) < ?
              AND $LIFE_WEIGHT_SQL >= p.forget_floor
            ORDER BY m.embedding <=> q.v, m.id
            LIMIT 1
            """.trimIndent(),
        ).use { ps ->
            ps.setInt(1, band.halfLifeDays)
            ps.setDouble(2, Lifecycle.FORGET_FLOOR)
            ps.setObject(3, PGvector(embedding))
            ps.setString(4, embedModel)
            ps.setLong(5, selfId)
            ps.setDouble(6, band.low)
            ps.setDouble(7, band.high)
            ps.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    NeighborRow(
                        id = rs.getLong(1),
                        content = rs.getString(2),
                        createdAt = (rs.getObject(3) as Timestamp).toInstant(),
                        cosine = rs.getDouble(4),
                    )
                }
            }
        }
    }

    /** The row as a verdict-pass candidate ([NeighborRow]); cosine is the caller's business here. */
    suspend fun rowAsNeighbor(id: Long): NeighborRow? = db.withConnection { conn ->
        conn.prepareStatement("SELECT id, content, created_at FROM memories WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    NeighborRow(
                        id = rs.getLong(1),
                        content = rs.getString(2),
                        createdAt = (rs.getObject(3) as Timestamp).toInstant(),
                        cosine = 0.0,
                    )
                }
            }
        }
    }

    /** The row's stored embedding, for passes that must compare neighbors without re-embedding. */
    suspend fun embeddingOf(id: Long): FloatArray? = db.withConnection { conn ->
        conn.prepareStatement("SELECT embedding::text FROM memories WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) PGvector(rs.getString(1)).toArray() else null }
        }
    }

    /** Whether the row is invalidated (a stamp exists) — the passes' idempotence guard. */
    suspend fun isInvalidated(id: Long): Boolean = db.withConnection { conn ->
        conn.prepareStatement("SELECT invalid_at IS NOT NULL FROM memories WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getBoolean(1) else false }
        }
    }

    /**
     * Every COOLING row the dream pass reads, oldest first — B3's law as ONE
     * SQL classification (life-weight in [forget_floor, cooling_floor)), the
     * SAME expression the status buckets count with. Invalidated rows are not
     * dream inputs (a dead claim has nothing to judge), and neither are
     * forgotten (ARCHIVED) ones — the dream works on cooling memory only.
     */
    suspend fun dreamRows(halfLifeDays: Int, limit: Int): List<DedupRow> = db.withConnection { conn ->
        conn.prepareStatement(
            """
            WITH params AS (SELECT ?::int AS half_life, ?::float8 AS cooling_floor, ?::float8 AS forget_floor)
            SELECT m.id, m.kind, m.content, m.embed_model, m.salience, m.created_at, m.embedding::text
            FROM memories m, params p
            WHERE m.invalid_at IS NULL
              AND $LIFE_WEIGHT_SQL >= p.forget_floor
              AND $LIFE_WEIGHT_SQL < p.cooling_floor
            ORDER BY m.created_at, m.id
            LIMIT ?
            """.trimIndent(),
        ).use { ps ->
            ps.setInt(1, halfLifeDays)
            ps.setDouble(2, Lifecycle.COOLING_FLOOR)
            ps.setDouble(3, Lifecycle.FORGET_FLOOR)
            ps.setInt(4, limit)
            ps.executeQuery().use { rs ->
                generateSequence {
                    if (!rs.next()) {
                        null
                    } else {
                        DedupRow(
                            id = rs.getLong(1),
                            kind = rs.getString(2),
                            content = rs.getString(3),
                            embedModel = rs.getString(4),
                            salience = rs.getDouble(5),
                            createdAt = (rs.getObject(6) as Timestamp).toInstant(),
                            embedding = PGvector(rs.getString(7)).toArray(),
                        )
                    }
                }.toList()
            }
        }
    }

    suspend fun byId(id: Long): Memory? = db.withConnection { conn ->
        conn.prepareStatement(
            """
            SELECT id, kind, content, session_id, turn_id, salience, created_at, NULL::float8 AS score, NULL::float8 AS weight
            FROM memories WHERE id = ?
            """,
        ).use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> readMemories(rs).singleOrNull() }
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

    /**
     * Hybrid RRF ([dev.lattenero.brain.rag.Rrf]) with relevance as the
     * dominant ranking key and salience times half-life decay ([Salience],
     * [RankKey]) as a tie-break only — the SQL below is the executable twin
     * of those pure cores, pinned equivalent on fixtures
     * (`MemorySearchEquivalenceIT`). `score` carries the fused relevance,
     * `weight` the salience-times-decay tie-break. Hits are the B3
     * access-reinforces signal: last_accessed refreshes AND salience grows by
     * [SEARCH_REINFORCE_STEP] (a weak step — a dedup merge lands a full 1.0),
     * so use keeps a memory alive against the forgetting law
     * ([Lifecycle]). Forgotten rows (life-weight below [Lifecycle.FORGET_FLOOR])
     * are excluded from BOTH candidate legs — they never fuse.
     */
    suspend fun search(
        queryEmbedding: FloatArray,
        queryText: String,
        embedModel: String,
        k: Int,
        lifecycle: Lifecycle.Params,
    ): List<Memory> = db.withConnection { conn ->
        val hits = conn.prepareStatement(SEARCH).use { ps ->
            ps.setObject(1, PGvector(queryEmbedding))
            ps.setString(2, embedModel)
            ps.setInt(3, maxOf(k * CANDIDATE_FACTOR, MIN_CANDIDATES))
            ps.setString(4, queryText)
            ps.setInt(5, lifecycle.halfLifeDays)
            ps.setDouble(6, lifecycle.forgetFloor)
            ps.setInt(7, k)
            ps.executeQuery().use { rs -> readMemories(rs) }
        }
        if (hits.isNotEmpty()) {
            conn.prepareStatement(
                "UPDATE memories SET salience = salience + ?, last_accessed = now() WHERE id = ANY(?)",
            ).use { ps ->
                ps.setDouble(1, SEARCH_REINFORCE_STEP)
                ps.setArray(2, conn.createArrayOf("bigint", hits.map { it.id }.toTypedArray()))
                ps.executeUpdate()
            }
        }
        hits
    }

    suspend fun recent(limit: Int, kind: String? = null): List<Memory> = db.withConnection { conn ->
        val sql = "SELECT id, kind, content, session_id, turn_id, salience, created_at, NULL::float8 AS score, NULL::float8 AS weight FROM memories " +
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

    /**
     * The lifecycle buckets (B3): every row classified by [Lifecycle]'s law —
     * the SAME expression the search legs exclude with, so the report and the
     * retriever can never disagree about who is forgotten. The half-life days
     * come from the caller's knob (the same one the ranking decay uses).
     */
    suspend fun lifecycleCounts(halfLifeDays: Int): LifecycleCounts = db.withConnection { conn ->
        conn.prepareStatement(
            """
            WITH params AS (SELECT ?::int AS half_life, ?::float8 AS cooling_floor, ?::float8 AS forget_floor),
            rows AS (
              SELECT m.salience * power(2.0, -extract(epoch FROM (now() - COALESCE(m.last_accessed, m.created_at)))
                     / (86400.0 * p.half_life)) AS w
              FROM memories m, params p
            )
            SELECT
              count(*) FILTER (WHERE w >= p.cooling_floor) AS active,
              count(*) FILTER (WHERE w < p.cooling_floor AND w >= p.forget_floor) AS cooling,
              count(*) FILTER (WHERE w < p.forget_floor) AS archived
            FROM rows, params p
            """,
        ).use { ps ->
            ps.setInt(1, halfLifeDays)
            ps.setDouble(2, Lifecycle.COOLING_FLOOR)
            ps.setDouble(3, Lifecycle.FORGET_FLOOR)
            ps.executeQuery().use { rs ->
                rs.next()
                LifecycleCounts(rs.getLong(1), rs.getLong(2), rs.getLong(3))
            }
        }
    }

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

    /** The same extraction landing again: (event, kind, content) already there. */
    private fun findReplay(conn: java.sql.Connection, eventId: String, kind: String, content: String): Long? =
        conn.prepareStatement(
            "SELECT id FROM memories WHERE source_event_id = ? AND kind = ? AND md5(content) = md5(?) LIMIT 1",
        ).use { ps ->
            ps.setString(1, eventId)
            ps.setString(2, kind)
            ps.setString(3, content)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }

    /**
     * The OLDEST row at or above [threshold] to [embedding] — same embed
     * model, any kind, any session (`<=>` is pgvector's cosine distance),
     * never an invalidated row (a dead claim must not absorb a new landing:
     * the restatement of the truth would vanish into the row that says it is
     * false). Oldest, not most-similar, so insert-time merges and the
     * backfill's oldest-first clusters agree on the survivor.
     */
    private fun findDuplicate(
        conn: java.sql.Connection,
        embedding: FloatArray,
        embedModel: String,
        threshold: Double,
    ): Long? = conn.prepareStatement(
        "SELECT id FROM memories WHERE embed_model = ? AND invalid_at IS NULL " +
            "AND (1 - (embedding <=> ?::vector)) >= ? " +
            "ORDER BY created_at, id LIMIT 1",
    ).use { ps ->
        ps.setString(1, embedModel)
        ps.setObject(2, PGvector(embedding))
        ps.setDouble(3, threshold)
        ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
    }

    /** The decay system's own reinforcement: salience up, last_accessed to now. */
    private fun reinforce(conn: java.sql.Connection, id: Long, by: Double) {
        conn.prepareStatement(
            "UPDATE memories SET salience = salience + ?, last_accessed = now() WHERE id = ?",
        ).use { ps ->
            ps.setDouble(1, by)
            ps.setLong(2, id)
            ps.executeUpdate()
        }
    }

    /** The stamp, guarded: only a live row (invalid_at IS NULL) takes a stamp. */
    private fun invalidate(conn: java.sql.Connection, id: Long): Boolean = conn.prepareStatement(
        "UPDATE memories SET invalid_at = now() WHERE id = ? AND invalid_at IS NULL",
    ).use { ps ->
        ps.setLong(1, id)
        ps.executeUpdate() > 0
    }

    private fun deleteMemories(conn: java.sql.Connection, ids: List<Long>) {
        conn.prepareStatement("DELETE FROM memories WHERE id = ANY(?)").use { ps ->
            ps.setArray(1, conn.createArrayOf("bigint", ids.toTypedArray()))
            ps.executeUpdate()
        }
    }

    private fun insertRow(conn: java.sql.Connection, transcript: Transcript, embedModel: String, row: MemoryRow): Long {
        conn.prepareStatement(
            """
            INSERT INTO memories (session_id, turn_id, kind, content, embed_model, embedding, source_event_id)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            RETURNING id
            """,
        ).use { ps ->
            ps.setString(1, transcript.sessionId)
            ps.setString(2, transcript.turnId)
            ps.setString(3, row.kind)
            ps.setString(4, row.content)
            ps.setString(5, embedModel)
            ps.setObject(6, PGvector(row.embedding))
            ps.setString(7, transcript.eventId)
            ps.executeQuery().use { rs ->
                if (rs.next()) return rs.getLong(1)
            }
        }
        // ON CONFLICT DO NOTHING swallowed the row (a concurrent writer landed
        // the same content first): there is no new row, hence no id.
        return -1L
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
            weight = rs.getObject("weight")?.let { (it as Number).toDouble() },
        )
    }.toList()

    companion object {
        const val CANDIDATE_FACTOR = 4
        const val MIN_CANDIDATES = 50

        /** The fusion constant lives in the pure core ([Rrf]); the SQL below is its executable twin. */
        const val RRF_K = Rrf.K
        const val MAX_REASON_CHARS = 500

        /** Each absorbed duplicate reinforces the survivor by this much salience. */
        const val REINFORCE_STEP = 1.0

        /**
         * Each search hit reinforces itself by this much salience (B3's
         * access-reinforces): a weak signal — one search hit must not weigh
         * like a dedup merge — whose accumulated effect the forgetting law
         * counterbalances.
         */
        const val SEARCH_REINFORCE_STEP = 0.1
        private val json = Json { ignoreUnknownKeys = true }

        // The SQL twin of Lifecycle's usefulness clock: salience decayed over the
        // LAST ACCESS (created_at at birth). One expression, three consumers: the
        // legs' forgetting exclusion, the status buckets, the lifecycle count.
        // `p.half_life` comes from the params CTE the caller joins in.
        const val LIFE_WEIGHT_SQL =
            "(m.salience * power(2.0, -extract(epoch FROM (now() - COALESCE(m.last_accessed, m.created_at)))" +
                " / (86400.0 * p.half_life)))"

        // Parameters: 1 query vector, 2 embed model, 3 candidates per leg, 4 query text,
        // 5 half-life days, 6 forget floor, 7 k.
        val SEARCH = """
            WITH params AS (
              SELECT ?::vector AS qv, ?::text AS model, ?::int AS n, websearch_to_tsquery('simple', ?) AS tsq,
                     ?::int AS half_life, ?::float8 AS forget_floor
            ), alive AS (
              SELECT id FROM memories m, params p
              WHERE m.embed_model = p.model
                AND m.invalid_at IS NULL
                AND $LIFE_WEIGHT_SQL >= p.forget_floor
            ), vec AS (
              SELECT id, row_number() OVER (ORDER BY dist, id) AS r FROM (
                SELECT m.id, m.embedding <=> p.qv AS dist FROM memories m, params p
                WHERE m.embed_model = p.model AND m.id IN (SELECT id FROM alive)
                ORDER BY dist, m.id LIMIT (SELECT n FROM params)
              ) top
            ), fts AS (
              SELECT id, row_number() OVER (ORDER BY rank DESC, id) AS r FROM (
                SELECT m.id, ts_rank_cd(m.content_tsv, p.tsq) AS rank FROM memories m, params p
                WHERE m.embed_model = p.model AND m.content_tsv @@ p.tsq AND m.id IN (SELECT id FROM alive)
                ORDER BY rank DESC, m.id LIMIT (SELECT n FROM params)
              ) top
            ), fused AS (
              SELECT id, SUM(1.0 / ($RRF_K + r)) AS rrf
              FROM (SELECT id, r FROM vec UNION ALL SELECT id, r FROM fts) legs GROUP BY id
            )
            SELECT m.id, m.kind, m.content, m.session_id, m.turn_id, m.salience, m.created_at,
                   f.rrf AS score,
                   m.salience * exp(-ln(2) * extract(epoch FROM (now() - m.created_at)) / (86400.0 * (SELECT half_life FROM params))) AS weight
            FROM fused f JOIN memories m ON m.id = f.id
            ORDER BY f.rrf DESC, weight DESC, m.id
            LIMIT ?
        """
    }
}
