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
 * What the restatement is depends on the language of the question ([legs],
 * the English-input law of 26/11). For an English question it is a second leg
 * and never replaces the original: both run the hybrid SEARCH and the ranked
 * lists fuse with the same RRF the SQL uses. For a question in another
 * language it IS the query — the foreign text is never searched against the
 * store. A blank or identical restatement means "no restatement", so the
 * expansion can only ADD recall — it cannot degrade a query, and a chat
 * failure degrades to the single leg (retrieval is best-effort BY DESIGN, the
 * rerank law's sibling).
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
     * The deterministic third leg's terms: the corpus laws' CANONICAL
     * vocabulary for a paraphrase's concept words — measured 22/11, the
     * paraphrase "dispatch on data without if-else chains" lost to the
     * official docs' literal "if-else" while the law's own words (mode
     * table) win instantly. A trigger-word match yields the row's canonical
     * terms; null when nothing triggers. Pure data + a word match — no chat
     * call, and the leg can only ADD recall (the expansion law).
     */
    private val SYNONYMS: List<Pair<Regex, String>> = listOf(
        Regex("\\b(dispatch|if[- ]?else|else[- ]?if|branching)\\b") to
            "mode table predicate table dispatch data priority",
        Regex("\\b(accumulat\\w*|collecting errors?|error accumulation)\\b") to
            "validated traverse error accumulation applicative",
        Regex("\\b(nullable|null checks?|optional values?)\\b") to
            "option maybe some none parse don't validate tagged records",
    )

    /** The canonical terms for the query's concept words, or null when no trigger fires. */
    fun synonyms(query: String): String? {
        val low = query.lowercase()
        val terms = SYNONYMS.filter { it.first.containsMatchIn(low) }.map { it.second }.distinct()
        return terms.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

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

    /** What the store is searched with: the text of the first leg, and every leg added to it. */
    data class Legs(val primary: String, val extras: List<String>)

    /**
     * The legs of a query under the English-input law (26/11). An English
     * question is searched as asked and the restatement only adds a leg (the
     * expansion law of 16/11: a leg can only ADD recall). A question in another
     * language reaches the store ONLY as its English restatement — the foreign
     * text is never searched, so nothing in an English store is ever matched
     * against it. With no restatement to use (no chat wired, a failed call)
     * the question stands as asked: retrieval never breaks because a
     * translation could not run.
     */
    fun legs(text: String, restatement: String?): Legs {
        val foreign = English.foreignQuestion(text)
        if (!foreign || restatement == null) return Legs(text, listOfNotNull(restatement, synonyms(text)))
        return Legs(restatement, listOfNotNull(synonyms(text)))
    }
}
