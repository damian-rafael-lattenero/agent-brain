package dev.lattenero.brain.memory

import arrow.core.Either
import dev.lattenero.brain.embeddings.EmbeddingProvider
import dev.lattenero.brain.rag.EmbedPipeline
import kap.CircuitBreakerOpenException
import kap.Kap
import kap.Process
import kap.RestartPolicy
import kap.Schedule
import kap.attempt
import kap.evalGraph
import kap.map
import kap.process
import kap.repeat
import kap.traverseSettled
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("agent-brain.memory")

@Serializable
data class WorkerReport(
    val scanned: Int,
    val processed: Int,
    val memories: Int,
    val merged: Int = 0,
    val reconciled: Int = 0,
    val quarantined: Int,
    val deferred: Int,
    val emptyTurns: Int,
)

/**
 * Why one turn's extraction failed — the three bins of the report, as values
 * (plan C1). The turn that produced the failure is known at the edge, so the
 * bins carry only what the edge needs to write the right row.
 */
sealed interface ExtractionFailure {
    /** The model answered, but not with something we accept: quarantined with the raw output. */
    data class Invalid(val reason: String, val raw: String) : ExtractionFailure

    /** The provider (chat or embed) could not answer: deferred, attempt counted, retried next cycle. */
    data class ProviderDown(val attempts: Int, val reason: String) : ExtractionFailure

    /** Deferred past the attempt cap: quarantined with the reason. */
    data class GaveUp(val attempts: Int, val reason: String) : ExtractionFailure
}

/** What one turn yielded when nothing failed. */
sealed interface TurnOutcome {
    /** The transcript has neither prompt nor reply: nothing to extract, the turn is marked. */
    data object EmptyTurn : TurnOutcome

    /**
     * Extraction and embed landed and the turn is marked. [inserted] rows are
     * new; [merged] carries the identities of EXISTING rows that the insert
     * reinforced instead — a near-duplicate (cosine at or above
     * [MemoryDedup.SIMILARITY]) is a bump of the memory already stored, never
     * a pretended new row. [insertedIds] are the new rows' identities (B4's
     * edge builder walks exactly these, the reconcile pass seeds from them).
     * [reconciled] counts the verdict-driven state changes the reconcile pass
     * made on the new rows (invalidations plus Same-merges) — zero when no
     * reconciler is wired or every verdict skipped.
     */
    data class Stored(
        val inserted: Int,
        val merged: List<Long> = emptyList(),
        val insertedIds: List<Long> = emptyList(),
        val reconciled: Int = 0,
    ) : TurnOutcome
}

/**
 * The worker's knobs as ONE value: the batch size, the attempt cap and the
 * verdict half-life the reconcile band's forgetting exclusion reads (the same
 * knob [MemoryService] ranks with).
 */
data class WorkerParams(
    val batch: Int = DEFAULT_BATCH,
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val halfLifeDays: Int = DEFAULT_HALF_LIFE_DAYS,
) {
    companion object {
        const val DEFAULT_BATCH = 10
        const val DEFAULT_MAX_ATTEMPTS = 5
        const val DEFAULT_HALF_LIFE_DAYS = 30
    }
}

/**
 * Polls the bus table for completed turns the memory has not seen, builds
 * the transcript, extracts, embeds, stores, marks. Idempotent by event id:
 * replaying the bus adds no turns (dedup) and a crash between insert and
 * mark is absorbed — the exact (event, kind, content) is skipped silently
 * by [MemoryStore.insertAllDeduped], and the unique index on
 * (event, kind, content) backs the raw insert path. Near-duplicates (cosine
 * at or above [MemoryDedup.SIMILARITY]) never land as new rows: the stored
 * memory is reinforced and [TurnOutcome.Stored] says merged honestly.
 * Failures are classified into the [ExtractionFailure] bins as values and
 * the edge writes the rows: invalid output goes to quarantine and the turn
 * is marked; a provider failure defers the turn (attempt counted, retried
 * next cycle); too many deferrals quarantine it with the reason.
 *
 * The batch runs through `traverseSettled`: every turn is attempted to
 * completion, no sibling cancels another, and the report is a fold over the
 * settled [Either]s. An unexpected settled failure (only the turn's own
 * bookkeeping can produce one, e.g. the transcript read) still crashes the
 * cycle, as before — the process restarts it.
 *
 * The early abort on an open circuit breaker (the old
 * `if (e is CircuitBreakerOpenException) break`) is a supervision policy
 * without a home: kap 5.0's Supervisor (OneForOne/AllForOne) is its
 * destination (plan C2). The interim, until the batching loop can host one:
 * an open breaker is just a fast provider failure — the turn lands in
 * [ExtractionFailure.ProviderDown] like any other outage and no exception
 * steers the loop.
 *
 * Phase G (2026-11-03) evaluated consuming [kap.Supervisor] here with kap
 * 5.0.0 actually on the classpath, and the verdict is that the documentation
 * above stands, not the consumption: the worker is ONE process whose body is
 * the whole batch — there are no per-turn children to watch — and a turn's
 * failure is a VALUE ([ExtractionFailure] as `Either.Left`), not a crashing
 * child. A Supervisor restarts bodies; restarting a turn whose provider is
 * down re-runs it against the same open breaker (a restart cannot fix it),
 * and raising the bins into exceptions to make them "supervisable" would
 * undo plan C1. If a batch-wide short-circuit is ever wanted again, its
 * lawful home is a fold decision over the settled Eithers (a stop predicate,
 * as a value), not supervision — the old `break` was exactly the
 * exception-as-control-flow R4 bans.
 */
class MemoryWorker(
    private val store: MemoryStore,
    private val extractor: MemoryExtractor,
    private val embed: EmbedPipeline,
    private val reconciler: MemoryReconciler? = null,
    private val params: WorkerParams = WorkerParams(),
    private val edges: EdgeBuilder? = null,
) {
    suspend fun processOnce(): WorkerReport {
        val pending = store.pendingTurns(params.batch, params.maxAttempts)
        val settled: List<Result<Either<ExtractionFailure, TurnOutcome>>> =
            pending.traverseSettled { turn -> Kap { processTurn(turn) } }.evalGraph()
        val start = WorkerReport(
            scanned = pending.size,
            processed = 0,
            memories = 0,
            merged = 0,
            quarantined = 0,
            deferred = 0,
            emptyTurns = 0,
        )
        return settled.fold(start) { report, result ->
            when (val either = result.getOrThrow()) {
                is Either.Left -> report.binned(either.value)
                is Either.Right -> report.completed(either.value)
            }
        }
    }

    /**
     * One turn, one settled outcome — the bins are the signature (plan C1).
     * An empty transcript is decided before the attempt; everything the
     * attempt raises is classified into [ExtractionFailure] at this edge and
     * the edge writes the rows ([quarantineOrDefer]); a failure of the
     * bookkeeping itself (transcript read, mark of an empty turn) is not a
     * bin — it propagates and the cycle crashes, as before.
     */
    private suspend fun processTurn(turn: MemoryStore.PendingTurn): Either<ExtractionFailure, TurnOutcome> {
        val transcript = store.transcript(turn)
        if (transcript.isEmpty) {
            store.markProcessed(turn.eventId)
            return Either.Right(TurnOutcome.EmptyTurn)
        }
        val settled = attempt(transcript, turn).evalGraph()
        settled.onLeft { quarantineOrDefer(it, transcript, turn) }
        settled.onRight { buildEdges(transcript, it) }
        return settled
    }

    /** The attempt: extract, embed, insert (dedup-aware), reconcile; failures converted at this boundary. */
    private fun attempt(
        transcript: Transcript,
        turn: MemoryStore.PendingTurn,
    ): Kap<Either<ExtractionFailure, TurnOutcome>> =
        Kap {
            val extraction = extractor.extract(transcript).evalGraph()
            val items = extraction.items
            val vectors = if (items.isEmpty()) emptyList() else embed.embedAll(items.map { it.second }).evalGraph()
            val rows = items.zip(vectors) { (kind, content), vector -> MemoryStore.MemoryRow(kind, content, vector) }
            val report = store.insertAllDeduped(transcript, embed.model, rows)
            store.markProcessed(turn.eventId)
            // The verdict-driven second stage: the turn is already marked, so a
            // skipped verdict (chat outage — the reconciler never throws) loses
            // nothing; the next similar landing re-proposes the pair.
            val reconciled = reconciler
                ?.reconcileInserted(report.insertedIds, embed.model, params.halfLifeDays)
                ?.changes
                ?: 0
            StoredTurn(report, reconciled)
        }
            .attempt()
            .map { settled ->
                val outcome: Either<ExtractionFailure, TurnOutcome> = when (settled) {
                    is Either.Left -> Either.Left(classify(settled.value, turn.attempts, params.maxAttempts))
                    is Either.Right -> {
                        val stored = settled.value
                        Either.Right(
                            TurnOutcome.Stored(
                                stored.report.inserted,
                                stored.report.merged,
                                stored.report.insertedIds,
                                stored.reconciled,
                            ),
                        )
                    }
                }
                outcome
            }

    /**
     * B4's honest degrade: the turn's edges are built AFTER the write landed,
     * and their failure is logged, never raised — the memory write stands
     * regardless of what the graph pass did.
     */
    private suspend fun buildEdges(transcript: Transcript, outcome: TurnOutcome) {
        val builder = edges ?: return
        if (outcome !is TurnOutcome.Stored || outcome.insertedIds.isEmpty()) return
        when (val result = builder.forTurn(transcript.sessionId, transcript.turnId, outcome.insertedIds)) {
            is EdgeOutcome.Built -> if (result.total > 0) log.debug("memory edges built: {}", result)
            is EdgeOutcome.Failed -> log.warn("memory edge building degraded: {}", result.reason)
        }
    }

    /**
     * The edge where the bins turn back into rows — the same rows in the same
     * cases as the try/catch this replaced. Quarantine is a write, not a
     * value: it lives here and nowhere in the taxonomy.
     */
    private suspend fun quarantineOrDefer(
        failure: ExtractionFailure,
        transcript: Transcript,
        turn: MemoryStore.PendingTurn,
    ) {
        when (failure) {
            is ExtractionFailure.Invalid -> {
                store.quarantine(transcript, turn.eventId, "invalid extraction: ${failure.reason}", failure.raw)
                store.markProcessed(turn.eventId)
            }
            is ExtractionFailure.GaveUp -> {
                val reason = "gave up after ${failure.attempts} attempts: ${failure.reason}"
                store.quarantine(transcript, turn.eventId, reason, "")
                store.markProcessed(turn.eventId)
            }
            is ExtractionFailure.ProviderDown -> {
                store.bumpAttempts(turn.eventId)
                log.warn(
                    "memory extraction deferred for {} (attempt {}): {}",
                    turn.eventId, failure.attempts, failure.reason,
                )
            }
        }
    }

    /**
     * The supervised worker: one cycle per cadence, and a [RestartPolicy] — not
     * a `recover` around the body — owns failures, so a crash anywhere in the
     * cycle restarts the loop instead of silently ending it.
     */
    fun process(interval: Duration, restart: RestartPolicy = defaultRestart(interval)): Process<WorkerReport> {
        require(interval > Duration.ZERO) { "poll interval must be strictly positive, was $interval" }
        return Kap { processOnce() }
            .repeat(Schedule.spaced(interval))
            .process(
                name = PROCESS_NAME,
                restart = restart,
                heartbeatEvery = HEARTBEAT_EVERY,
                onRestart = { attempt, error, nextDelay ->
                    log.warn(
                        "memory worker failed (restart {} in {}): {}: {}",
                        attempt, nextDelay, error::class.simpleName, error.message,
                    )
                },
            )
    }

    /** Runs the process for the caller's lifetime (CLI `memory run`); cancellation stops it. */
    suspend fun run(interval: Duration) {
        coroutineScope { process(interval).startIn(this).await() }
    }

    companion object {
        const val PROCESS_NAME = "memory-worker"

        /** One stored turn as the attempt graph carries it: the insert report plus the reconcile count. */
        private data class StoredTurn(val report: MemoryStore.InsertReport, val reconciled: Int)

        /**
         * The taxonomy, pure and total: any failure of one attempt lands in
         * exactly one bin. Invalid output is its own bin; any other failure
         * defers until the attempt cap, then gives up. An open circuit
         * breaker needs no case of its own (plan C2): it is the provider
         * failing fast, i.e. [ExtractionFailure.ProviderDown].
         *
         * 16/11 measured correction to the interim: a breaker-open rejection
         * must NOT consume the turn's attempt budget. It is the PROVIDER's
         * state (self-healing within the reset window), not the turn's — one
         * giant turn opening the breaker burned 12 queued turns to gave-up
         * without a single model call reaching the provider. The breaker-open
         * failure defers for free, at the CURRENT attempt count, for as long
         * as the breaker stays open.
         */
        internal fun classify(e: Throwable, attemptsSoFar: Int, maxAttempts: Int): ExtractionFailure {
            if (e is ExtractionInvalid) return ExtractionFailure.Invalid(e.reason, e.raw)
            val reason = "${e::class.simpleName}: ${e.message}"
            // A breaker-open rejection defers FOR FREE — the provider's state,
            // never the turn's budget (16/11 measured cascade: one giant turn
            // opened the breaker and 12 queued turns burned to gave-up without
            // a single model call). It never reaches the cap: attemptsSoFar
            // does not move while the breaker stays open.
            val attempts: Int = when (e) {
                is CircuitBreakerOpenException -> attemptsSoFar
                else -> attemptsSoFar + 1
            }
            return if (attempts >= maxAttempts) {
                ExtractionFailure.GaveUp(attempts, reason)
            } else {
                ExtractionFailure.ProviderDown(attempts, reason)
            }
        }

        /** The heartbeat answers "is the process there", never "did the cycle finish". */
        val HEARTBEAT_EVERY = 10.seconds

        /** Positive base (the cadence), exponential backoff, finite cap: the convergence law. */
        fun defaultRestart(interval: Duration): RestartPolicy =
            RestartPolicy.exponential(base = interval, max = RESTART_MAX_DELAY, maxRestarts = RESTART_MAX)

        val RESTART_MAX_DELAY = 60.seconds
        const val RESTART_MAX = 8
    }

    /** The failure bins, counted: invalid and gave-up quarantined, provider-down deferred. */
    private fun WorkerReport.binned(failure: ExtractionFailure): WorkerReport = when (failure) {
        is ExtractionFailure.Invalid -> copy(quarantined = quarantined + 1)
        is ExtractionFailure.GaveUp -> copy(quarantined = quarantined + 1)
        is ExtractionFailure.ProviderDown -> copy(deferred = deferred + 1)
    }

    /** The outcomes, counted: empty turns alone; stored turns with rows, merges and verdict changes. */
    private fun WorkerReport.completed(outcome: TurnOutcome): WorkerReport = when (outcome) {
        is TurnOutcome.EmptyTurn -> copy(emptyTurns = emptyTurns + 1)
        is TurnOutcome.Stored -> copy(
            processed = processed + 1,
            memories = memories + outcome.inserted,
            merged = merged + outcome.merged.size,
            reconciled = reconciled + outcome.reconciled,
        )
    }
}

/**
 * The memory taxonomy as a type (was: Extraction.KIND_* strings plus a
 * runtime KINDS set the service validated by hand): the wire string rides
 * [SerialName] and [wire], so a JSON body with an off-taxonomy kind fails
 * the decode (the 400 seam) and a string edge converts through [parse].
 * The database keeps storing and returning the wire string, so rows written
 * before this type (or by any other writer) still read back untouched.
 */
@Serializable
enum class MemoryKind(val wire: String) {
    @SerialName("fact") FACT("fact"),
    @SerialName("decision") DECISION("decision"),
    @SerialName("preference") PREFERENCE("preference"),
    @SerialName("summary") SUMMARY("summary");

    companion object {
        /** The taxonomy as a message ("fact, decision, preference, summary"). */
        val WIRES: String = entries.joinToString(", ") { it.wire }

        /**
         * The typed conversion at a string edge: an off-taxonomy wire is a
         * [MemoryRequestException], whose caller's seam (the HTTP 400 route,
         * the CLI dispatcher) answers the error.
         */
        fun parse(wire: String): MemoryKind = entries.firstOrNull { it.wire == wire }
            ?: throw MemoryRequestException("kind must be one of $WIRES")
    }
}

@Serializable
data class MemorySearchRequest(val query: String, val k: Int = MemoryService.DEFAULT_K)

@Serializable
data class MemorySaveRequest(val kind: MemoryKind, val content: String, val sessionId: String? = null)

/**
 * The search response with B4's ADDITIVE graph surface: [related] hangs 1-hop
 * neighbors off the top hits through memory_edges (deduped, edge-weight first
 * in the ranking, capped per hit and globally — the pure [traverse]'s law).
 * Existing fields are untouched.
 */
@Serializable
data class MemorySearchResponse(
    val query: String,
    val embedModel: String,
    val memories: List<Memory>,
    // ALWAYS on the wire — the graph leg is additive but its CONTRACT is not
    // optional: an empty graph reads as `"related": []`, never as an absent key.
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.ALWAYS)
    val related: List<RelatedMemory> = emptyList(),
)

class MemoryRequestException(message: String) : IllegalArgumentException(message)

class MemoryService(
    private val provider: EmbeddingProvider,
    private val store: MemoryStore,
    private val extractor: MemoryExtractor,
    private val halfLifeDays: Int,
    private val graph: MemoryGraphStore? = null,
    private val edges: EdgeBuilder? = null,
) {
    suspend fun search(request: MemorySearchRequest): MemorySearchResponse {
        val text = request.query.trim()
        if (text.isEmpty()) throw MemoryRequestException("query must not be empty")
        if (request.k !in 1..MAX_K) throw MemoryRequestException("k must be between 1 and $MAX_K")
        val vector = provider.embed(listOf(text)).single()
        val lifecycle = Lifecycle.Params(halfLifeDays)
        val ranked: List<Memory> = store.search(vector, text, provider.model, request.k, lifecycle)
        return MemorySearchResponse(text, provider.model, ranked, related(ranked, vector))
    }

    /**
     * B4's traversal over the hits: 1-hop neighbors through memory_edges,
     * fetched alive on both ends, ranked and capped by the pure [traverse].
     * The graph leg is additive and honest — unwired (or no hits), the field
     * is empty.
     */
    private suspend fun related(hits: List<Memory>, queryEmbedding: FloatArray): List<RelatedMemory> {
        if (graph == null || hits.isEmpty()) return emptyList()
        val candidates = graph.relatedCandidates(hits, queryEmbedding, provider.model, Lifecycle.Params(halfLifeDays))
        return traverse(hits.map { it.id }, candidates)
    }

    /** The manual save's edge pass — the same honest degrade as the worker's: log, never raise. */
    private suspend fun buildEdges(id: Long) {
        val builder = edges ?: return
        when (val outcome = builder.forMemory(id)) {
            is EdgeOutcome.Built -> if (outcome.total > 0) log.debug("memory edges built: {}", outcome)
            is EdgeOutcome.Failed -> log.warn("memory edge building degraded: {}", outcome.reason)
        }
    }

    suspend fun save(request: MemorySaveRequest): Memory {
        val content = request.content.trim()
        if (content.length < MemoryExtractor.MIN_ITEM_CHARS) throw MemoryRequestException("content too short")
        val vector = provider.embed(listOf(content)).single()
        val id = store.saveManual(
            request.kind.wire,
            content.take(MemoryExtractor.MAX_ITEM_CHARS),
            provider.model,
            vector,
            request.sessionId,
        )
        buildEdges(id)
        // The id is the surviving row: a near-duplicate save returns the existing memory, reinforced.
        return store.byId(id) ?: Memory(id, request.kind.wire, content, request.sessionId, null, 1.0, "")
    }

    suspend fun recent(limit: Int, kind: MemoryKind?): List<Memory> {
        if (limit !in 1..MAX_K) throw MemoryRequestException("limit must be between 1 and $MAX_K")
        return store.recent(limit, kind?.wire)
    }

    suspend fun quarantined(limit: Int): List<QuarantineRow> = store.quarantined(limit.coerceIn(1, MAX_K))

    /** Independent counts in parallel, three plain values: one typed graph. */
    suspend fun status(): MemoryStatus {
        val lifecycle: LifecycleCounts = store.lifecycleCounts(halfLifeDays)
        return kap(::MemoryStatus)
            .with { pendingTurns from store.countPending() }
            .with { processedTurns from store.countProcessed() }
            .with { memories from store.countMemories() }
            .with { active from lifecycle.active }
            .with { cooling from lifecycle.cooling }
            .with { archived from lifecycle.archived }
            .with { superseded from store.countSuperseded() }
            .with { byKind from store.countByKind() }
            .with { quarantined from store.countQuarantined() }
            .with { breaker from extractor.breaker.currentState.name }
            .with { chatModel from extractor.model }
            .with { embedModel from provider.model }
            .with { edgesByKind from graph?.countByKind().orEmpty() }
            .evalGraph()
    }

    companion object {
        const val DEFAULT_K = 5
        const val MAX_K = 50
    }
}
