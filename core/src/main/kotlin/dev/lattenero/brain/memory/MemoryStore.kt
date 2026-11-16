package dev.lattenero.brain.memory

import com.pgvector.PGvector
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.sq.LifecycleCounts as GeneratedLifecycleCounts
import dev.lattenero.brain.db.toVectorText
import kap.KapTypeSafe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.OffsetDateTime

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

/** One stored memory's keep-it-simple face: kind wire + content, insertion order. */
data class TurnMemory(val kind: String, val content: String)

@Serializable
@KapTypeSafe
data class MemoryStatus(
    val pendingTurns: Long,
    val processedTurns: Long,
    val memories: Long,
    val active: Long,
    val cooling: Long,
    val archived: Long,
    val superseded: Long,
    val byKind: Map<String, Long>,
    val quarantined: Long,
    val breaker: String,
    val chatModel: String,
    val embedModel: String,
    val edgesByKind: Map<String, Long> = emptyMap(),
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

    /**
     * What one dedup-aware insert pass did: [inserted] new rows (only real
     * insertions — a replay absorbed silently or a duplicate reinforced is
     * not one), [merged] identities of the reinforced rows, and the new rows'
     * identities ([insertedIds] — the reconcile pass seeds from them and B4's
     * edge builder walks exactly these).
     */
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

    suspend fun pendingTurns(limit: Int, maxAttempts: Int): List<PendingTurn> = db.withDriver { brainDb ->
        brainDb.busEventsQueries.pendingTurns(maxAttempts, limit.toLong()).executeAsList().map {
            PendingTurn(it.id, it.sid.orEmpty(), it.turn.orEmpty(), it.source, it.memory_attempts)
        }
    }

    /** Everything the bus knows about one turn, folded into a transcript. */
    suspend fun transcript(pending: PendingTurn): Transcript = db.withDriver { brainDb ->
        var prompt = ""
        var reply = ""
        var title = ""
        val tools = LinkedHashSet<String>()
        for (event in brainDb.busEventsQueries.turnEvents(pending.sessionId, pending.turnId).executeAsList()) {
            val payload = json.parseToJsonElement(event.payload_text).jsonObject
            fun str(key: String) = payload[key]?.jsonPrimitive?.content.orEmpty()
            when (event.kind) {
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

    /** Processed turns, newest first — the LoRA export's positive-pair source. */
    suspend fun processedTurns(limit: Int): List<PendingTurn> = db.withDriver { brainDb ->
        brainDb.busEventsQueries.processedTurns(limit.toLong()).executeAsList().map {
            PendingTurn(it.id, it.sid.orEmpty(), it.turn.orEmpty(), it.source, it.attempts)
        }
    }

    /** The memories one turn produced, in insertion order (the LoRA pair's target). */
    suspend fun memoriesOfTurn(turnId: String): List<TurnMemory> = db.withDriver { brainDb ->
        brainDb.memoriesQueries.memoriesOfTurn(turnId).executeAsList().map { TurnMemory(it.kind, it.content) }
    }

    /** The raw insert: exactly these rows, ON CONFLICT DO NOTHING, no dedup. */
    suspend fun insertAll(transcript: Transcript, embedModel: String, rows: List<MemoryRow>): Int = db.withDriver { brainDb ->
        if (rows.isEmpty()) return@withDriver 0
        var landed = 0
        for (row in rows) {
            landed += brainDb.memoriesQueries.insertMemoryNoReturn(
                transcript.sessionId,
                transcript.turnId,
                row.kind,
                row.content,
                embedModel,
                row.embedding.toVectorText(),
                transcript.eventId,
            ).value.toInt()
        }
        landed
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
        return db.withDriver { brainDb ->
            val merged = mutableListOf<Long>()
            val insertedIds = mutableListOf<Long>()
            brainDb.transaction {
                for (row in rows) {
                    val replayed = brainDb.memoriesQueries.findReplay(transcript.eventId, row.kind, row.content)
                        .executeAsOneOrNull()
                    if (replayed == null) {
                        val duplicate = brainDb.memoriesQueries.findDuplicate(
                            embedModel,
                            row.embedding.toVectorText(),
                            MemoryDedup.SIMILARITY,
                        ).executeAsOneOrNull()
                        if (duplicate != null) {
                            brainDb.memoriesQueries.reinforceOne(REINFORCE_STEP, duplicate)
                            merged += duplicate
                        } else {
                            insertedIds += brainDb.memoriesQueries.insertMemory(
                                transcript.sessionId,
                                transcript.turnId,
                                row.kind,
                                row.content,
                                embedModel,
                                row.embedding.toVectorText(),
                                transcript.eventId,
                            ).executeAsOne()
                        }
                    }
                }
            }
            InsertReport(insertedIds.size, merged, insertedIds)
        }
    }

    /** Manual saves dedup with the same rule and return the surviving row's id. */
    suspend fun saveManual(kind: String, content: String, embedModel: String, embedding: FloatArray, sessionId: String?): Long {
        val duplicate = db.withDriver { brainDb ->
            brainDb.memoriesQueries.findDuplicate(embedModel, embedding.toVectorText(), MemoryDedup.SIMILARITY)
                .executeAsOneOrNull()
        }
        if (duplicate != null) {
            db.withDriver { brainDb -> brainDb.memoriesQueries.reinforceOne(REINFORCE_STEP, duplicate) }
            return duplicate
        }
        return saveRaw(MemoryRow(kind, content, embedding), embedModel, sessionId)
    }

    /**
     * A RAW claim insert: no dedup probe. Near-duplicate pairs are the INPUT
     * of a verdict (the reconcile legs seed them to be judged) — merging them
     * at insert time would leave nothing to judge. The eval/IT faces use this;
     * the write path never does.
     */
    suspend fun saveRaw(row: MemoryRow, embedModel: String, sessionId: String?): Long = db.withDriver { brainDb ->
        brainDb.memoriesQueries.insertMemory(
            sessionId,
            null,
            row.kind,
            row.content,
            embedModel,
            row.embedding.toVectorText(),
            null,
        ).executeAsOne()
    }

    /**
     * Every row the dedup backfill reads, oldest first — invalidated rows
     * excluded (a live claim must never be absorbed by a dead one). The
     * backfill is a CLI command, never a migration: a destructive merge must
     * run when the operator asks for it, not at every startup.
     */
    suspend fun dedupRows(): List<DedupRow> = db.withDriver { brainDb ->
        brainDb.memoriesQueries.dedupRows().executeAsList().map {
            DedupRow(
                id = it.id,
                kind = it.kind,
                content = it.content,
                embedModel = it.embed_model,
                salience = it.salience,
                createdAt = it.created_at.toInstant(),
                embedding = PGvector(it.embedding_text).toArray(),
            )
        }
    }

    /**
     * Applies a [MemoryDedup.plan] in one transaction: each surviving row is
     * reinforced by its absorbed duplicates' count (exactly what insert-time
     * dedup would have accumulated for them) and the absorbed rows are
     * deleted. Returns the number of rows removed.
     */
    // Array<Long> boxing is inherent to the generated array bind; cluster
    // cardinalities are tiny — noise.
    @Suppress("ArrayPrimitive")
    suspend fun applyDedup(plan: Plan): Int = db.withDriver { brainDb ->
        brainDb.transaction {
            for (cluster in plan.clusters) {
                brainDb.memoriesQueries.reinforceOne(REINFORCE_STEP * cluster.reinforcements, cluster.keepId)
                brainDb.memoriesQueries.deleteAbsorbed(cluster.absorbedIds.toTypedArray())
            }
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
    suspend fun invalidate(id: Long): Boolean = db.withDriver { brainDb ->
        brainDb.memoriesQueries.invalidate(id).value > 0
    }

    /**
     * Dedup's own merge, one transaction, for the reconcile and dream passes:
     * the survivor is reinforced exactly as an insert-time dedup merge would
     * have reinforced it, and the absorbed row is deleted (the Same verdict
     * means one memory said twice, so the store holds it once).
     */
    // The generated signature binds arrays as Array<Long> (boxing inherent to
    // the JDBC array type); cardinalities here are single digits — noise.
    @Suppress("ArrayPrimitive")
    suspend fun mergeInto(survivorId: Long, absorbedId: Long): Unit = db.withDriver { brainDb ->
        if (survivorId != absorbedId) {
            brainDb.transaction {
                brainDb.memoriesQueries.reinforceOne(REINFORCE_STEP, survivorId)
                brainDb.memoriesQueries.deleteAbsorbed(arrayOf(absorbedId))
            }
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
    ): NeighborRow? = db.withDriver { brainDb ->
        brainDb.memoriesQueries.nearestNeighbor(
            band.halfLifeDays,
            Lifecycle.FORGET_FLOOR,
            embedding.toVectorText(),
            embedModel,
            selfId,
            band.low,
            band.high,
        ).executeAsOneOrNull()?.let {
            NeighborRow(id = it.id, content = it.content, createdAt = it.created_at.toInstant(), cosine = it.cosine)
        }
    }

    /** The row as a verdict-pass candidate ([NeighborRow]); cosine is the caller's business here. */
    suspend fun rowAsNeighbor(id: Long): NeighborRow? = db.withDriver { brainDb ->
        brainDb.memoriesQueries.rowAsNeighbor(id).executeAsOneOrNull()?.let {
            NeighborRow(id = it.id, content = it.content, createdAt = it.created_at.toInstant(), cosine = 0.0)
        }
    }

    /** The row's stored embedding, for passes that must compare neighbors without re-embedding. */
    suspend fun embeddingOf(id: Long): FloatArray? = db.withDriver { brainDb ->
        brainDb.memoriesQueries.embeddingOf(id).executeAsOneOrNull()?.let { PGvector(it).toArray() }
    }

    /** Whether the row is invalidated (a stamp exists) — the passes' idempotence guard. */
    suspend fun isInvalidated(id: Long): Boolean = db.withDriver { brainDb ->
        brainDb.memoriesQueries.isInvalidated(id).executeAsOneOrNull() ?: false
    }

    /**
     * Every COOLING row the dream pass reads, oldest first — B3's law as ONE
     * SQL classification (life-weight in [forget_floor, cooling_floor)), the
     * SAME expression the status buckets count with. Invalidated rows are not
     * dream inputs (a dead claim has nothing to judge), and neither are
     * forgotten (ARCHIVED) ones — the dream works on cooling memory only.
     */
    suspend fun dreamRows(halfLifeDays: Int, limit: Int): List<DedupRow> = db.withDriver { brainDb ->
        brainDb.memoriesQueries.dreamRows(
            halfLifeDays,
            Lifecycle.COOLING_FLOOR,
            Lifecycle.FORGET_FLOOR,
            limit.toLong(),
        ).executeAsList().map {
            DedupRow(
                id = it.id,
                kind = it.kind,
                content = it.content,
                embedModel = it.embed_model,
                salience = it.salience,
                createdAt = it.created_at.toInstant(),
                embedding = PGvector(it.embedding_text).toArray(),
            )
        }
    }

    suspend fun byId(id: Long): Memory? = db.withDriver { brainDb ->
        brainDb.memoriesQueries.byId(id).executeAsOneOrNull()?.let {
            MemoryFields(
                it.id, it.kind, it.content, it.session_id, it.turn_id,
                it.salience, it.created_at, it.score, it.weight,
            ).toMemory()
        }
    }

    suspend fun markProcessed(eventId: String) {
        db.withDriver { it.busEventsQueries.markProcessed(eventId) }
    }

    suspend fun bumpAttempts(eventId: String) {
        db.withDriver { it.busEventsQueries.bumpAttempts(eventId) }
    }

    suspend fun quarantine(transcript: Transcript?, eventId: String, reason: String, raw: String) {
        db.withDriver { brainDb ->
            brainDb.memoriesQueries.quarantineInsert(
                transcript?.turnId,
                reason.take(MAX_REASON_CHARS),
                raw,
                eventId,
                transcript?.sessionId,
            )
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
    ): List<Memory> = db.withDriver { brainDb ->
        val hits = brainDb.memoriesQueries.searchMemory(
            queryEmbedding.toVectorText(),
            embedModel,
            maxOf(k * CANDIDATE_FACTOR, MIN_CANDIDATES),
            queryText,
            lifecycle.halfLifeDays,
            lifecycle.forgetFloor,
            k.toLong(),
        ).executeAsList().map {
            MemoryFields(
                it.id, it.kind, it.content, it.session_id, it.turn_id,
                it.salience, it.created_at, it.score?.toDouble(), it.weight,
            ).toMemory()
        }
        if (hits.isNotEmpty()) {
            brainDb.memoriesQueries.reinforceSearch(SEARCH_REINFORCE_STEP, hits.map { it.id }.toTypedArray())
        }
        hits
    }

    suspend fun recent(limit: Int, kind: String? = null): List<Memory> = db.withDriver { brainDb ->
        if (kind == null) {
            brainDb.memoriesQueries.recent(limit.toLong()).executeAsList().map {
                MemoryFields(
                    it.id, it.kind, it.content, it.session_id, it.turn_id,
                    it.salience, it.created_at, it.score, it.weight,
                ).toMemory()
            }
        } else {
            brainDb.memoriesQueries.recentOfKind(kind, limit.toLong()).executeAsList().map {
                MemoryFields(
                    it.id, it.kind, it.content, it.session_id, it.turn_id,
                    it.salience, it.created_at, it.score, it.weight,
                ).toMemory()
            }
        }
    }

    suspend fun quarantined(limit: Int): List<QuarantineRow> = db.withDriver { brainDb ->
        brainDb.memoriesQueries.quarantined(limit.toLong()).executeAsList().map {
            QuarantineRow(
                it.id, it.event_id, it.session_id, it.turn_id,
                it.reason, it.raw, it.ts.toInstant().toString(),
            )
        }
    }

    suspend fun countPending(): Long = db.withDriver { it.busEventsQueries.countPending().executeAsOne() }

    suspend fun countProcessed(): Long = db.withDriver { it.busEventsQueries.countProcessed().executeAsOne() }

    suspend fun countMemories(): Long = db.withDriver { it.memoriesQueries.countMemories().executeAsOne() }

    /** Rows a verdict of Supersede stamped: the status bucket for invalidated claims. */
    suspend fun countSuperseded(): Long = db.withDriver { it.memoriesQueries.countSuperseded().executeAsOne() }

    suspend fun countQuarantined(): Long = db.withDriver { it.memoriesQueries.countQuarantined().executeAsOne() }

    /**
     * Quarantine reasons, BUCKETED (unit 0's placeholder-rate surface): the raw
     * reason strings carry detail ("invalid extraction: template-echo: ...",
     * "gave up after 5 attempts: ...") that no dashboard wants — the buckets are
     * the vocabulary: template-echo (the degraded-extractor marker), invalid,
     * gave-up, anything else its first word.
     */
    suspend fun quarantineReasons(): Map<String, Long> = db.withDriver { brainDb ->
        brainDb.memoriesQueries.quarantineBucketed().executeAsList().associate { it.bucket to it.n }
    }

    /**
     * The lifecycle buckets (B3): every row classified by [Lifecycle]'s law —
     * the SAME expression the search legs exclude with, so the report and the
     * retriever can never disagree about who is forgotten. The half-life days
     * come from the caller's knob (the same one the ranking decay uses).
     */
    suspend fun lifecycleCounts(halfLifeDays: Int): LifecycleCounts = db.withDriver { brainDb ->
        val counts: GeneratedLifecycleCounts = brainDb.memoriesQueries.lifecycleCounts(
            halfLifeDays,
            Lifecycle.COOLING_FLOOR,
            Lifecycle.FORGET_FLOOR,
        ).executeAsOne()
        LifecycleCounts(counts.active, counts.cooling, counts.archived)
    }

    suspend fun countByKind(): Map<String, Long> = db.withConnection { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT kind, count(*) FROM memories GROUP BY kind ORDER BY kind").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) to rs.getLong(2) else null }.toMap()
            }
        }
    }

    /** The generated rows' common face (Recent/RecentOfKind/ById/SearchMemory). */
    private data class MemoryFields(
        val id: Long,
        val kind: String,
        val content: String,
        val sessionId: String?,
        val turnId: String?,
        val salience: Double,
        val createdAt: OffsetDateTime,
        val score: Double?,
        val weight: Double?,
    )

    private fun MemoryFields.toMemory() = Memory(
        id,
        kind,
        content,
        sessionId,
        turnId,
        salience,
        createdAt.toInstant().toString(),
        score,
        weight,
    )

    companion object {
        const val CANDIDATE_FACTOR = 4
        const val MIN_CANDIDATES = 50
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
    }
}
