package dev.lattenero.brain.rag

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The agentic second stage (unit 3 of the arc): a question that spans two
 * concerns makes single-pass retrieval rank the neighbor's vocabulary first,
 * so the loop DECOMPOSES it into independent sub-queries, retrieves each one,
 * and fuses every leg with the same [Rrf] the SQL and the bilingual expansion
 * use.
 *
 * Laws (QueryExpansion's siblings — the loop can only ADD recall):
 *  - the parse reads `queries` and nothing else;
 *  - a blank, unparsable, or decomposition that reduces to nothing new is
 *    null → the original leg stands alone;
 *  - the caller's chat failure is caught by [RagService], never thrown into
 *    retrieval — a broken decomposer must not break the query it serves;
 *  - items identical to the original question (case-insensitive) are noise,
 *    dropped.
 */
object MultiHop {
    /** The loop's fan-out cap: more legs dilute the fusion and spend the caller's latency. */
    const val MAX_SUB_QUERIES = 4

    private val json = Json { ignoreUnknownKeys = true }

    val SCHEMA: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("queries") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("queries")) })
    }

    fun systemPrompt(): String =
        "You decompose a question into independent search queries for a hybrid retrieval engine. " +
            "Return JSON only: {\"queries\": [string]} — 2 to $MAX_SUB_QUERIES short queries, each " +
            "covering ONE concern of the question with its own vocabulary (identifiers verbatim). " +
            "No query may just restate the whole question."

    fun userPrompt(query: String): String = "QUESTION: $query"

    /** The sub-queries, or null when decomposition adds nothing. Never throws. */
    fun parse(text: String, original: String): List<String>? = runCatching {
        val root = json.parseToJsonElement(text).jsonObject
        val queries = root["queries"]?.jsonArray
            ?: throw IllegalArgumentException("missing 'queries'")
        val cleaned = queries.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.equals(original.trim(), ignoreCase = true) }
            .distinct()
            .take(MAX_SUB_QUERIES)
        cleaned.ifEmpty { null }
    }.getOrElse { null }
}
