package dev.lattenero.brain.rag

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The second query leg: one short chat call restates the query in English
 * search terms, because the corpus is English and the owner asks in Spanish —
 * measured 16/11 by the `real-questions` suite: Spanish queries were captured
 * by the Spanish distractor docs (6/8) while every English query landed.
 *
 * The restatement never replaces the original query: both run the hybrid
 * SEARCH and the ranked lists fuse with the same RRF the SQL uses. A blank or
 * identical restatement means "no second leg", so the expansion can only ADD
 * recall — it cannot degrade a query, and a chat failure degrades to the
 * single leg (retrieval is best-effort BY DESIGN, the rerank law's sibling).
 */
object QueryExpansion {

    private val json = Json { ignoreUnknownKeys = true }

    private const val MAX_QUERY_CHARS = 400

    val SCHEMA: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") { put("type", "string") }
        }
        put("required", buildJsonArray { add(JsonPrimitive("query")) })
    }

    fun systemPrompt(): String = "You restate a search query as 3-8 English keywords " +
        "for a documentation index. Reply with one JSON object {\"query\": \"...\"}: " +
        "the English restatement only, no explanation. Keep names and identifiers verbatim."

    fun userPrompt(query: String): String = "Query: ${query.take(MAX_QUERY_CHARS)}"

    /**
     * The model's answer → the restatement, or null for "no second leg": a
     * blank answer, an unparsable one, or one identical to the input modulo
     * case and whitespace (an English query restates to itself).
     */
    fun parse(raw: String, original: String): String? {
        val restated = runCatching {
            val parsed = json.parseToJsonElement(raw.trim()) as? JsonObject
            (parsed?.get("query") as? JsonPrimitive)?.content
        }.getOrNull()?.trim().orEmpty()
        if (restated.isEmpty()) return null
        return restated.takeUnless { normalize(it) == normalize(original) }
    }

    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("\\s+"), " ").trim().trimEnd('.', '?', '!')
}
