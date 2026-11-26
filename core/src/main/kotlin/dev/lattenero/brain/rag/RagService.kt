package dev.lattenero.brain.rag

import dev.lattenero.brain.chat.ChatException
import dev.lattenero.brain.chat.ChatProvider
import dev.lattenero.brain.embeddings.EmbeddingProvider
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * `rerank` asks for the listwise second stage ([Rerank]): the fused order
 * stands when no chat is wired (a config error — ask loudly) while a chat
 * failure mid-run degrades honestly to the fused order. The bot's
 * memory-injection path never asks (a rerank spends a chat call per query);
 * the eval and the tools that can afford it do.
 *
 * `iterate` asks for the agentic loop ([MultiHop]): the question is decomposed
 * into independent sub-queries, each one retrieved, and every leg fuses with
 * the same RRF — for questions that span two concerns, which single-pass
 * retrieval ranks by the dominant vocabulary alone. Opt-in like `rerank`: a
 * decomposition spends a chat call per query, so the caller decides.
 */
@Serializable
data class QueryRequest(
    val query: String,
    val k: Int = RagStore.DEFAULT_K,
    val rerank: Boolean = false,
    val iterate: Boolean = false,
)

@Serializable
data class IndexRequest(val globs: List<String> = emptyList())

@Serializable
data class Citation(val uri: String, val title: String, val heading: String, val ordinal: Int, val score: Double)

/**
 * `reranked` says whether the second stage actually rode: a request that
 * asked for it but degraded (chat failure, unparsable verdict) answers with
 * the fused order AND `reranked = false` — the flag is the honest stage.
 *
 * `topSimilarity`/`relevant` are the confidence floor: RRF scores are
 * rank-based and saturate (~0.03 for hits and noise alike, measured 22/11),
 * so the top hit's VECTOR similarity is the only signal that separates
 * "the corpus is about this" from garbage served with an answer's face.
 *
 * `searched` is the English text the store was searched with: the query as
 * asked, or its restatement when the question came in another language (the
 * English-input law of 26/11). The log records this one, never the foreign
 * text — the database, like the store, is English.
 */
@Serializable
data class QueryResponse(
    val query: String,
    val embedModel: String,
    val hits: List<Hit>,
    val citations: List<Citation>,
    val reranked: Boolean = false,
    val topSimilarity: Double? = null,
    val relevant: Boolean = true,
    val searched: String? = null,
)

class RagQueryException(message: String) : IllegalArgumentException(message)

/** Retrieval only: the caller (harness, bot, eval) composes the answer from cited chunks. */
class RagService(
    private val provider: EmbeddingProvider,
    private val store: RagStore,
    private val chat: ChatProvider? = null,
) {
    private val log = LoggerFactory.getLogger(RagService::class.java)

    /**
     * One degradation shape for every OPTIONAL chat stage (expansion,
     * decomposition, rerank): the block runs, a [ChatException] logs through
     * the logger and collapses to null — the stage's leg simply does not
     * exist, retrieval never breaks because an enhancement could not ask
     * (the rerank law, stated once instead of three times).
     */
    private suspend fun <T> bestEffort(stage: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: ChatException) {
        log.warn("{}: the chat call failed ({}) — the stage degrades, the original query stands", stage, e.message)
        null
    }

    suspend fun query(request: QueryRequest): QueryResponse {
        val text = request.query.trim()
        if (text.isEmpty()) throw RagQueryException("query must not be empty")
        if (request.k !in 1..MAX_K) throw RagQueryException("k must be between 1 and $MAX_K")
        val legs = QueryExpansion.legs(text, restate(text))
        val fused = if (request.iterate) searchIterated(legs, text, request.k) else searchFused(legs, request.k)
        val (hits, reranked) = if (request.rerank) rerank(text, fused) else fused to false
        val topSim = hits.firstOrNull()?.similarity
        return QueryResponse(
            query = text,
            embedModel = provider.model,
            hits = hits,
            citations = hits.map { Citation(it.uri, it.title, it.heading, it.ordinal, it.score) },
            reranked = reranked,
            topSimilarity = topSim,
            relevant = topSim != null && topSim >= CONFIDENCE_FLOOR,
            searched = legs.primary,
        )
    }

    suspend fun stats(): RagStats = store.stats()

    /**
     * The retrieval stage with its legs ([QueryExpansion.legs]): the primary
     * text runs first, and each extra leg — the English restatement of an
     * English question, the deterministic synonym terms
     * ([QueryExpansion.synonyms], the corpus laws' canonical vocabulary for a
     * paraphrase's concept words) — adds a ranked list when it has something
     * to add; every leg fuses with the same [Rrf] the SQL uses. The primary
     * leg alone when nothing adds, retrieval never degrades because an
     * expansion could not run (each leg can only ADD recall).
     */
    private suspend fun searchFused(legs: QueryExpansion.Legs, k: Int): List<Hit> {
        val text = legs.primary
        val original = store.search(provider.embed(listOf(text)).single(), text, provider.model, k)
        if (legs.extras.isEmpty()) return original
        val ranked = mutableListOf(original.map { it.chunkId })
        val byId = original.associateBy { it.chunkId }.toMutableMap()
        for (extra in legs.extras) {
            val hits = store.search(provider.embed(listOf(extra)).single(), extra, provider.model, k)
            ranked.add(hits.map { it.chunkId })
            hits.forEach { byId.putIfAbsent(it.chunkId, it) }
        }
        return Rrf.fuse(ranked).mapNotNull { entry -> byId[entry.id]?.copy(score = entry.score) }.take(k)
    }

    /** The English restatement, or null when there is nothing to add. Never throws. */
    private suspend fun restate(text: String): String? {
        val chat = this.chat ?: return null
        return bestEffort("query expansion") {
            QueryExpansion.parse(
                chat.completeJson(
                    QueryExpansion.systemPrompt(),
                    QueryExpansion.userPrompt(text),
                    QueryExpansion.SCHEMA,
                ).text,
                text,
            )
        }
    }

    /**
     * The agentic loop, COMPOSED with the bilingual stage: the fused order
     * (original + English restatement) is the first leg, and each sub-query
     * the decomposer produced adds one more leg — all fused by [Rrf]. No chat,
     * a failed decomposition, or a failed leg degrades to fewer legs —
     * retrieval never breaks because the loop could not ask (the rerank's
     * law, restated for the loop).
     */
    private suspend fun searchIterated(legs: QueryExpansion.Legs, text: String, k: Int): List<Hit> {
        val fused = searchFused(legs, k)
        val subQueries = decompose(text)
        val ranked = mutableListOf(fused.map { it.chunkId })
        val byId: MutableMap<Long, Hit> = fused.associateBy { it.chunkId }.toMutableMap()
        for (sub in subQueries) {
            val hits = try {
                store.search(provider.embed(listOf(sub)).single(), sub, provider.model, k)
            } catch (e: dev.lattenero.brain.embeddings.ProviderException) {
                System.err.println("multi-hop: the sub-query leg failed (${e.message}) — the other legs stand")
                emptyList()
            } catch (e: java.sql.SQLException) {
                System.err.println("multi-hop: the sub-query leg failed (${e.message}) — the other legs stand")
                emptyList()
            }
            ranked.add(hits.map { it.chunkId })
            hits.forEach { byId.putIfAbsent(it.chunkId, it) }
        }
        return Rrf.fuse(ranked).mapNotNull { entry -> byId[entry.id]?.copy(score = entry.score) }.take(k)
    }

    /** The independent sub-queries, or an empty list when there is nothing to add. Never throws. */
    private suspend fun decompose(text: String): List<String> {
        val chat = this.chat ?: return emptyList()
        val result = bestEffort("multi-hop") {
            chat.completeJson(MultiHop.systemPrompt(), MultiHop.userPrompt(text), MultiHop.SCHEMA)
        }
        return result?.let { MultiHop.parse(it.text, text) }.orEmpty()
    }

    /**
     * The second stage. No chat wired = a config error, thrown loudly — a
     * caller asking for a stage that cannot exist is a mistake, not weather.
     * A chat failure or an unparsable verdict mid-run degrades to the fused
     * order with `reranked = false` (the rerank is best-effort BY DESIGN: a
     * broken reranker must never break retrieval).
     */
    private suspend fun rerank(query: String, fused: List<Hit>): Pair<List<Hit>, Boolean> {
        val chat = this.chat ?: throw RagQueryException("rerank requested but no chat model is wired")
        val verdict = bestEffort("rerank") {
            Rerank.parseVerdict(
                chat.completeJson(
                    Rerank.systemPrompt(fused.size),
                    Rerank.userPrompt(query, fused),
                    Rerank.SCHEMA,
                ).text,
            )
        }
        return Rerank.apply(verdict, fused) to (verdict != null)
    }

    companion object {
        const val MAX_K = 50

        /**
         * The confidence floor, measured 22/11 on the live corpus with
         * nomic-embed-text: known-relevant queries' top-1 cosine similarity
         * spanned 0.5686–0.7904, noise queries 0.4341–0.5667. 0.55 cuts the
         * clear garbage (≤ 0.50) with margin and keeps every relevant probe
         * with margin — the boundary band passes by design: a false
         * "nothing relevant" on a real query costs more than a borderline
         * hit served on noise (the safe error direction).
         */
        const val CONFIDENCE_FLOOR = 0.55
    }
}
