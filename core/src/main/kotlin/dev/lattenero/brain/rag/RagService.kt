package dev.lattenero.brain.rag

import dev.lattenero.brain.chat.ChatException
import dev.lattenero.brain.chat.ChatProvider
import dev.lattenero.brain.embeddings.EmbeddingProvider
import kotlinx.serialization.Serializable

/**
 * `rerank` asks for the listwise second stage ([Rerank]): the fused order
 * stands when no chat is wired (a config error — ask loudly) while a chat
 * failure mid-run degrades honestly to the fused order. The bot's
 * memory-injection path never asks (a rerank spends a chat call per query);
 * the eval and the tools that can afford it do.
 */
@Serializable
data class QueryRequest(val query: String, val k: Int = RagStore.DEFAULT_K, val rerank: Boolean = false)

@Serializable
data class IndexRequest(val globs: List<String> = emptyList())

@Serializable
data class Citation(val uri: String, val title: String, val heading: String, val ordinal: Int, val score: Double)

/**
 * `reranked` says whether the second stage actually rode: a request that
 * asked for it but degraded (chat failure, unparsable verdict) answers with
 * the fused order AND `reranked = false` — the flag is the honest stage.
 */
@Serializable
data class QueryResponse(
    val query: String,
    val embedModel: String,
    val hits: List<Hit>,
    val citations: List<Citation>,
    val reranked: Boolean = false,
)

class RagQueryException(message: String) : IllegalArgumentException(message)

/** Retrieval only: the caller (harness, bot, eval) composes the answer from cited chunks. */
class RagService(
    private val provider: EmbeddingProvider,
    private val store: RagStore,
    private val chat: ChatProvider? = null,
) {
    suspend fun query(request: QueryRequest): QueryResponse {
        val text = request.query.trim()
        if (text.isEmpty()) throw RagQueryException("query must not be empty")
        if (request.k !in 1..MAX_K) throw RagQueryException("k must be between 1 and $MAX_K")
        val vector = provider.embed(listOf(text)).single()
        val fused = store.search(vector, text, provider.model, request.k)
        val (hits, reranked) = if (request.rerank) rerank(text, fused) else fused to false
        return QueryResponse(
            query = text,
            embedModel = provider.model,
            hits = hits,
            citations = hits.map { Citation(it.uri, it.title, it.heading, it.ordinal, it.score) },
            reranked = reranked,
        )
    }

    suspend fun stats(): RagStats = store.stats()

    /**
     * The second stage. No chat wired = a config error, thrown loudly — a
     * caller asking for a stage that cannot exist is a mistake, not weather.
     * A chat failure or an unparsable verdict mid-run degrades to the fused
     * order with `reranked = false` (the rerank is best-effort BY DESIGN: a
     * broken reranker must never break retrieval).
     */
    private suspend fun rerank(query: String, fused: List<Hit>): Pair<List<Hit>, Boolean> {
        val chat = this.chat ?: throw RagQueryException("rerank requested but no chat model is wired")
        val verdict = try {
            val result = chat.completeJson(
                Rerank.systemPrompt(fused.size),
                Rerank.userPrompt(query, fused),
                Rerank.SCHEMA,
            )
            Rerank.parseVerdict(result.text)
        } catch (e: ChatException) {
            System.err.println("rerank: the chat call failed (${e.message}) — the fused order stands")
            null
        }
        return Rerank.apply(verdict, fused) to (verdict != null)
    }

    companion object {
        const val MAX_K = 50
    }
}
