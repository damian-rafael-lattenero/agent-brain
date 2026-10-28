package dev.lattenero.brain.rag

import dev.lattenero.brain.embeddings.EmbeddingProvider
import kotlinx.serialization.Serializable

@Serializable
data class QueryRequest(val query: String, val k: Int = RagStore.DEFAULT_K)

@Serializable
data class IndexRequest(val globs: List<String> = emptyList())

@Serializable
data class Citation(val uri: String, val title: String, val heading: String, val ordinal: Int, val score: Double)

@Serializable
data class QueryResponse(val query: String, val embedModel: String, val hits: List<Hit>, val citations: List<Citation>)

class RagQueryException(message: String) : IllegalArgumentException(message)

/** Retrieval only: the caller (harness, bot, eval) composes the answer from cited chunks. */
class RagService(private val provider: EmbeddingProvider, private val store: RagStore) {
    suspend fun query(request: QueryRequest): QueryResponse {
        val text = request.query.trim()
        if (text.isEmpty()) throw RagQueryException("query must not be empty")
        if (request.k !in 1..MAX_K) throw RagQueryException("k must be between 1 and $MAX_K")
        val vector = provider.embed(listOf(text)).single()
        val hits = store.search(vector, text, provider.model, request.k)
        return QueryResponse(
            query = text,
            embedModel = provider.model,
            hits = hits,
            citations = hits.map { Citation(it.uri, it.title, it.heading, it.ordinal, it.score) },
        )
    }

    suspend fun stats(): RagStats = store.stats()

    companion object {
        const val MAX_K = 50
    }
}
