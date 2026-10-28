package dev.lattenero.brain.memory

import dev.lattenero.brain.embeddings.EmbeddingProvider
import dev.lattenero.brain.rag.EmbedPipeline
import kap.CircuitBreakerOpenException
import kap.evalGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import kotlin.time.Duration

private val log = LoggerFactory.getLogger("agent-brain.memory")

@Serializable
data class WorkerReport(val scanned: Int, val processed: Int, val memories: Int, val quarantined: Int, val deferred: Int, val emptyTurns: Int)

/**
 * Polls the bus table for completed turns the memory has not seen, builds
 * the transcript, extracts, embeds, stores, marks. Idempotent by event id:
 * replaying the bus adds no turns (dedup) and a crash between insert and
 * mark is absorbed by the unique index on (event, kind, content). Failures
 * are sorted into three bins: invalid output goes to quarantine and the
 * turn is marked; a provider failure defers the turn (attempt counted,
 * retried next cycle); too many deferrals quarantine it with the reason.
 */
class MemoryWorker(
    private val store: MemoryStore,
    private val extractor: MemoryExtractor,
    private val embed: EmbedPipeline,
    private val batch: Int = DEFAULT_BATCH,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    suspend fun processOnce(): WorkerReport {
        val pending = store.pendingTurns(batch, maxAttempts)
        var processed = 0
        var memories = 0
        var quarantined = 0
        var deferred = 0
        var empty = 0
        for (turn in pending) {
            val transcript = store.transcript(turn)
            if (transcript.isEmpty) {
                store.markProcessed(turn.eventId)
                empty += 1
                continue
            }
            try {
                val extraction = extractor.extract(transcript).evalGraph()
                val items = extraction.items
                val vectors = if (items.isEmpty()) emptyList() else embed.embedAll(items.map { it.second }).evalGraph()
                val rows = items.zip(vectors) { (kind, content), vector -> MemoryStore.MemoryRow(kind, content, vector) }
                memories += store.insertAll(transcript, embed.model, rows)
                store.markProcessed(turn.eventId)
                processed += 1
            } catch (e: CancellationException) {
                throw e
            } catch (e: ExtractionInvalid) {
                store.quarantine(transcript, turn.eventId, "invalid extraction: ${e.reason}", e.raw)
                store.markProcessed(turn.eventId)
                quarantined += 1
            } catch (e: Exception) {
                val reason = "${e::class.simpleName}: ${e.message}"
                if (turn.attempts + 1 >= maxAttempts) {
                    store.quarantine(transcript, turn.eventId, "gave up after ${turn.attempts + 1} attempts: $reason", "")
                    store.markProcessed(turn.eventId)
                    quarantined += 1
                } else {
                    store.bumpAttempts(turn.eventId)
                    deferred += 1
                    log.warn("memory extraction deferred for {} (attempt {}): {}", turn.eventId, turn.attempts + 1, reason)
                }
                if (e is CircuitBreakerOpenException) break
            }
        }
        return WorkerReport(pending.size, processed, memories, quarantined, deferred, empty)
    }

    suspend fun run(interval: Duration) {
        while (true) {
            try {
                processOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("memory worker cycle failed, will retry: {}: {}", e::class.simpleName, e.message)
            }
            delay(interval)
        }
    }

    companion object {
        const val DEFAULT_BATCH = 10
        const val DEFAULT_MAX_ATTEMPTS = 5
    }
}

@Serializable
data class MemorySearchRequest(val query: String, val k: Int = MemoryService.DEFAULT_K)

@Serializable
data class MemorySaveRequest(val kind: String, val content: String, val sessionId: String? = null)

@Serializable
data class MemorySearchResponse(val query: String, val embedModel: String, val memories: List<Memory>)

class MemoryRequestException(message: String) : IllegalArgumentException(message)

class MemoryService(
    private val provider: EmbeddingProvider,
    private val store: MemoryStore,
    private val extractor: MemoryExtractor,
    private val halfLifeDays: Int,
) {
    suspend fun search(request: MemorySearchRequest): MemorySearchResponse {
        val text = request.query.trim()
        if (text.isEmpty()) throw MemoryRequestException("query must not be empty")
        if (request.k !in 1..MAX_K) throw MemoryRequestException("k must be between 1 and $MAX_K")
        val vector = provider.embed(listOf(text)).single()
        return MemorySearchResponse(text, provider.model, store.search(vector, text, provider.model, request.k, halfLifeDays))
    }

    suspend fun save(request: MemorySaveRequest): Memory {
        if (request.kind !in KINDS) throw MemoryRequestException("kind must be one of ${KINDS.joinToString()}")
        val content = request.content.trim()
        if (content.length < MemoryExtractor.MIN_ITEM_CHARS) throw MemoryRequestException("content too short")
        val vector = provider.embed(listOf(content)).single()
        val id = store.saveManual(request.kind, content.take(MemoryExtractor.MAX_ITEM_CHARS), provider.model, vector, request.sessionId)
        return store.recent(1).firstOrNull { it.id == id } ?: Memory(id, request.kind, content, request.sessionId, null, 1.0, "")
    }

    suspend fun recent(limit: Int, kind: String?): List<Memory> {
        if (limit !in 1..MAX_K) throw MemoryRequestException("limit must be between 1 and $MAX_K")
        if (kind != null && kind !in KINDS) throw MemoryRequestException("kind must be one of ${KINDS.joinToString()}")
        return store.recent(limit, kind)
    }

    suspend fun quarantined(limit: Int): List<QuarantineRow> = store.quarantined(limit.coerceIn(1, MAX_K))

    /** Five independent counts in parallel, three plain values: one typed graph. */
    suspend fun status(): MemoryStatus = kap(::MemoryStatus)
        .with { pendingTurns from store.countPending() }
        .with { processedTurns from store.countProcessed() }
        .with { memories from store.countMemories() }
        .with { byKind from store.countByKind() }
        .with { quarantined from store.countQuarantined() }
        .with { breaker from extractor.breaker.currentState.name }
        .with { chatModel from "" }
        .with { embedModel from provider.model }
        .evalGraph()

    companion object {
        const val DEFAULT_K = 5
        const val MAX_K = 50
        val KINDS = setOf(Extraction.KIND_FACT, Extraction.KIND_DECISION, Extraction.KIND_PREFERENCE, Extraction.KIND_SUMMARY)
    }
}
