package dev.lattenero.brain.rag

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The listwise rerank: a SECOND stage over the hybrid fusion's ordering. The
 * fused RRF order comes from two legs that never read the query's words next
 * to the chunk's words; the rerank shows the chat model the query and the
 * fused top hits and asks it to order them — the recall gate's graded columns
 * (plan brujula v2 B1: MRR/recall@1/nDCG@5) are what decides whether it
 * ships. A rerank can RE-ORDER, never drop: [apply]'s output is always a
 * permutation of its input, whatever the model says.
 */
object Rerank {
    /** The rerank stage is a value: the 1-based context numbers the model wants first. */
    private val json = Json { ignoreUnknownKeys = true }

    /** One snippet rides this many characters; a rerank reads shapes, not whole documents. */
    const val SNIPPET_CHARS = 280

    /**
     * The verdict as a value → the reordered hits. A valid index moves to its
     * verdict position; an out-of-range or repeated index is ignored; anything
     * the verdict never named keeps its fused order after the ranked prefix.
     * Pure and total: empty or null verdict = the fused order stands.
     */
    fun <T> apply(verdict: List<Int>?, hits: List<T>): List<T> {
        if (verdict.isNullOrEmpty()) return hits
        val named = mutableSetOf<Int>()
        val valid = verdict.filter { it in 1..hits.size && named.add(it) }
        val head = valid.map { hits[it - 1] }
        val rest = hits.filterIndexed { index, _ -> (index + 1) !in named }
        return head + rest
    }

    /** The system prompt: order the numbered contexts by how well they answer the question. */
    fun systemPrompt(n: Int): String =
        "You rerank search results. You get one question and $n numbered context snippets. " +
            "Return JSON only: {\"order\": [int]} — the context numbers, BEST first, all $n of them, " +
            "each number exactly once. Order by how well the snippet answers the question; " +
            "when two are equally relevant, keep their given order."

    /** The user turn: the question and the numbered snippets, in the fused order. */
    fun userPrompt(query: String, hits: List<Hit>): String = buildString {
        append("QUESTION: ").append(query).append("\n\nCONTEXT:\n")
        hits.forEachIndexed { index, hit ->
            append(index + 1).append(". ").append(hit.content.replace("\n", " ").take(SNIPPET_CHARS)).append('\n')
        }
    }

    val SCHEMA: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("order") {
                put("type", "array")
                putJsonObject("items") { put("type", "integer") }
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("order")) })
    }

    /** The model's answer → the raw 1-based numbers it named; anything else is a null verdict. */
    fun parseVerdict(raw: String): List<Int>? = runCatching {
        val parsed = json.parseToJsonElement(raw.trim())
        val order = (parsed as? JsonObject)?.get("order") as? kotlinx.serialization.json.JsonArray
            ?: return null
        order.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
    }.getOrNull()
}
