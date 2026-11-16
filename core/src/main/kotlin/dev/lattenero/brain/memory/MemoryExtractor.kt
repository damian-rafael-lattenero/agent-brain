package dev.lattenero.brain.memory

import arrow.core.Either
import arrow.core.raise.either
import dev.lattenero.brain.chat.ChatProvider
import dev.lattenero.brain.chat.ChatResult
import dev.lattenero.brain.embeddings.ProviderResilience
import dev.lattenero.brain.embeddings.withProviderResilience
import kap.CircuitBreaker
import kap.Kap
import kap.KapTracer
import kap.andThen
import kap.map
import kap.traced
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val log = LoggerFactory.getLogger("agent-brain.memory")

/** What one turn looked like, assembled from its bus events. */
data class Transcript(
    val eventId: String,
    val sessionId: String,
    val turnId: String,
    val source: String,
    val prompt: String,
    val reply: String,
    val tools: List<String>,
    val title: String = "",
) {
    val isEmpty: Boolean get() = prompt.isBlank() && reply.isBlank()

    fun render(cap: Int): String = buildString {
        if (title.isNotBlank()) append("TITLE: ").append(title).append('\n')
        append("PROMPT:\n").append(prompt.take(cap)).append("\n\n")
        append("REPLY:\n").append(reply.take(cap)).append("\n\n")
        append("TOOLS: ").append(if (tools.isEmpty()) "none" else tools.joinToString(", "))
    }
}

data class Extraction(
    val facts: List<String>,
    val decisions: List<String>,
    val preferences: List<String>,
    val summary: String,
    val raw: String,
    val promptTokens: Int?,
    val completionTokens: Int?,
) {
    val items: List<Pair<String, String>>
        get() = facts.map { MemoryKind.FACT.wire to it } + decisions.map { MemoryKind.DECISION.wire to it } +
            preferences.map { MemoryKind.PREFERENCE.wire to it } +
            listOfNotNull(summary.takeIf { it.isNotBlank() }?.let { MemoryKind.SUMMARY.wire to it })
}

/** The model answered, but not with something we accept: goes to quarantine with the raw text. */
class ExtractionInvalid(val reason: String, val raw: String) : RuntimeException(reason)

/**
 * The parse bins as values (plan E5): the model answered with either an
 * acceptable [ParseOutcome.Parsed] extraction or nothing usable
 * ([ParseOutcome.Invalid], with the reason and the raw text for quarantine).
 * Total, pure, exception-free: the caps (MIN_ITEM_CHARS, MAX_ITEM_CHARS,
 * MAX_ITEMS_PER_KIND, MAX_SUMMARY_CHARS) are enforced here and pinned by
 * property laws in `MemoryExtractorParseBinsLawsTest`.
 * [MemoryExtractor.parse] is this function's throwing twin at the Kap edge —
 * the bins in a signature, the exception only where the attempt channel
 * demands one.
 */
sealed interface ParseOutcome {
    data class Parsed(val extraction: Extraction) : ParseOutcome
    data class Invalid(val reason: String, val raw: String) : ParseOutcome
}

/**
 * One LLM call per turn, under the same resilience policy as embeddings
 * (timeout converted, retried with jittered backoff, circuit breaker), then
 * a strict parse: the schema constrains shape, the validator enforces caps
 * and types, and anything else is an [ExtractionInvalid] for quarantine,
 * never a silently dropped turn.
 */
class MemoryExtractor(
    private val chat: ChatProvider,
    private val timeout: Duration,
    private val tracer: KapTracer,
    val breaker: CircuitBreaker = CircuitBreaker(
        maxFailures = ProviderResilience.DEFAULT_BREAKER_FAILURES,
        retryAfter = ProviderResilience.DEFAULT_BREAKER_RESET,
        successThreshold = ProviderResilience.DEFAULT_BREAKER_SUCCESS_THRESHOLD,
    ),
    private val attempts: Int = ProviderResilience.DEFAULT_ATTEMPTS,
    private val backoffBase: Duration = DEFAULT_BACKOFF_BASE,
) {
    val model: String get() = chat.model

    /**
     * One turn, ONE extraction — unless the turn is GIANT. The 16/11 replay
     * measured the tail: a 6 K-char transcript can demand a near-ceiling
     * output (~9.5 KB of JSON), which no output cap should have to cover and
     * whose generation time threatens the timeout. So a rendered transcript
     * longer than [CHUNK_CAP] is split at a line boundary and extracted in
     * TWO sequential passes (`andThen` — never concurrent: the shared ollama
     * runner must not take two giant prompts at once), each small enough that
     * its output fits every cap, and the bins merge under the same caps.
     * Small turns are byte-identical to the single-call path.
     */
    fun extract(transcript: Transcript): Kap<Extraction> {
        val rendered = transcript.render(TRANSCRIPT_CAP)
        return if (rendered.length <= CHUNK_CAP) {
            single(rendered)
        } else {
            val (first, second) = splitForExtraction(rendered)
            single(first).andThen { a -> single(second).map { b -> merge(a, b) } }
        }
    }

    /** The single-pass extraction pipeline (resilience + trace + parse). */
    private fun single(chunk: String): Kap<Extraction> =
        Kap { chat.completeJson(SYSTEM_PROMPT, chunk, SCHEMA) }
            .withProviderResilience(timeout, attempts, backoffBase, breaker) { attempt, error, delay ->
                log.warn("extraction retry {} in {} after {}: {}", attempt, delay, error::class.simpleName, error.message)
            }
            .traced(PHASE_EXTRACT, tracer)
            .map { parse(it) }

    companion object {
        const val PHASE_EXTRACT = "extract"
        val DEFAULT_BACKOFF_BASE = 500.milliseconds
        const val TRANSCRIPT_CAP = 6_000

        /** A chunk's extraction output (~10 items x 300 chars per kind + summary)
         *  fits every output cap with margin; 6000/3500 = at most two chunks. */
        const val CHUNK_CAP = 3_500
        const val MAX_ITEMS_PER_KIND = 10
        const val MAX_ITEM_CHARS = 300
        const val MAX_SUMMARY_CHARS = 500
        const val MIN_ITEM_CHARS = 8

        const val SYSTEM_PROMPT = "You extract durable memory from ONE turn between a developer (the user) and a coding agent. " +
            "Return JSON only with keys facts, decisions, preferences, summary. " +
            "facts: verifiable statements about the user, their projects, tools or environment that will still be true next week. " +
            "decisions: choices the user made in this turn. " +
            "preferences: how the user likes things done (style, tools, language, process). " +
            "summary: one sentence about the turn. " +
            "Omit transient details, greetings, and anything only relevant to this turn. Empty arrays are fine. " +
            "Write each item as a short standalone sentence in English that makes sense without the conversation."

        val SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                for (key in listOf("facts", "decisions", "preferences")) {
                    putJsonObject(key) {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                    }
                }
                putJsonObject("summary") { put("type", "string") }
            }
            put("required", buildJsonArray { listOf("facts", "decisions", "preferences", "summary").forEach { add(JsonPrimitive(it)) } })
        }

        private val json = Json { ignoreUnknownKeys = true }

        /** One kind's array, or the bin it invalidates into: a missing key is empty, everything else is checked. */
        private fun kind(
            obj: JsonObject,
            key: String,
            raw: String,
        ): Either<ParseOutcome.Invalid, List<String>> = either {
            when (val array = obj[key]) {
                null -> emptyList()
                is JsonArray -> array
                    .map { item ->
                        val prim = item as? JsonPrimitive
                            ?: raise(ParseOutcome.Invalid("$key contains a non-string", raw))
                        if (!prim.isString) raise(ParseOutcome.Invalid("$key contains a non-string", raw))
                        prim.content.trim()
                    }
                    .filter { it.length >= MIN_ITEM_CHARS }
                    .map { it.take(MAX_ITEM_CHARS) }
                    .distinct()
                    .take(MAX_ITEMS_PER_KIND)
                else -> raise(ParseOutcome.Invalid("$key is not an array", raw))
            }
        }

        fun parseBins(raw: String): ParseOutcome {
            val extraction: Either<ParseOutcome.Invalid, ParseOutcome.Parsed> = either {
                val element = try {
                    json.parseToJsonElement(raw)
                } catch (e: SerializationException) {
                    raise(ParseOutcome.Invalid("not JSON: ${e.message?.lineSequence()?.firstOrNull()}", raw))
                }
                val obj = element as? JsonObject ?: raise(ParseOutcome.Invalid("not a JSON object", raw))
                val summaryText = when (val summaryElement = obj["summary"]) {
                    null -> ""
                    is JsonPrimitive ->
                        if (summaryElement.isString) summaryElement.content.trim().take(MAX_SUMMARY_CHARS)
                        else raise(ParseOutcome.Invalid("summary is not a string", raw))
                    else -> raise(ParseOutcome.Invalid("summary is not a string", raw))
                }
                val parsed = Extraction(
                    kind(obj, "facts", raw).bind(),
                    kind(obj, "decisions", raw).bind(),
                    kind(obj, "preferences", raw).bind(),
                    summaryText,
                    raw,
                    null,
                    null,
                )
                // Template-echo law (15/11 incident): a degraded model answers with
                // the prompt's OWN field descriptions as the values — shape-valid,
                // content-empty. One echo means the whole extraction is degenerate:
                // quarantine it all, never store the echoes as real memories.
                val texts = parsed.items.map { it.second } + summaryText
                val echo = texts.firstOrNull { normalize(it) in TEMPLATE_ECHOES }
                if (echo != null) {
                    val reason = "template-echo: the model echoed the prompt's own fields: \"$echo\""
                    raise(ParseOutcome.Invalid(reason, raw))
                }
                ParseOutcome.Parsed(parsed)
            }
            return when (extraction) {
                is Either.Left -> extraction.value
                is Either.Right -> extraction.value
            }
        }

        /** Lowercase, punctuation and whitespace folded: the echo comparison surface. */
        private fun normalize(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

        /**
         * The degenerate answers observed live on 15/11 (memory ids 3384/3382 et al.):
         * the model returned the schema's field descriptions as the values. Stored
         * as salience-1.0 "memories" they polluted recall until manually noticed.
         */
        private val TEMPLATE_ECHOES: Set<String> = setOf(
            "A one-sentence summary of the turn.",
            "The user's decisions in this turn.",
            "The user's decisions.",
            "Choices the user made in this turn.",
            "Verifiable statements about the user, their projects, tools or environment " +
                "that will still be true next week.",
            "One sentence about the turn.",
            "How the user likes things done.",
        ).map(::normalize).toSet()

        /** The attempt channel's face of [parseBins]: the Invalid bin becomes [ExtractionInvalid]. */
        fun parse(result: ChatResult): Extraction = when (val outcome = parseBins(result.text)) {
            is ParseOutcome.Parsed -> outcome.extraction.copy(
                promptTokens = result.promptTokens,
                completionTokens = result.completionTokens,
            )
            // The provider said it stopped at the output cap (done_reason:
            // length). A cut generation is not a model that answered badly —
            // quarantining it as "not JSON" hides the real cause (the 16/11
            // audit: 13 turns in two days, every raw ending mid-word).
            is ParseOutcome.Invalid -> throw ExtractionInvalid(
                if (result.truncated) "truncated: the output cap cut the JSON before it closed" else outcome.reason,
                outcome.raw,
            )
        }

        /** Split a rendered transcript at the line boundary nearest the middle;
         *  a candidate boundary is valid only when BOTH halves carry real
         *  content (measured 16/11: a 2 K-char single prompt line made the
         *  nearest boundary the 26-char "PROMPT:" header — chunk one would
         *  have been an empty-feeling stub the model answers uselessly). */
        internal fun splitForExtraction(rendered: String): Pair<String, String> {
            val mid: Int = rendered.length / 2
            val minHalf: Int = minOf(CHUNK_CAP, rendered.length) / 4
            val before: Int = rendered.lastIndexOf('\n', mid)
            val after: Int = rendered.indexOf('\n', mid)
            val at: Int = when {
                before > minHalf && (rendered.length - before) > minHalf -> before
                after > minHalf && (rendered.length - after) > minHalf -> after
                else -> mid
            }
            return rendered.substring(0, at).trim() to rendered.substring(at).trim()
        }

        /** The two chunk extractions merge under the SAME caps the single pass
         *  enforces; the summary keeps the first non-empty; the raw carries
         *  both chunks (quarantine evidence stays complete). */
        private fun merge(a: Extraction, b: Extraction): Extraction = Extraction(
            facts = (a.facts + b.facts).distinct().take(MAX_ITEMS_PER_KIND),
            decisions = (a.decisions + b.decisions).distinct().take(MAX_ITEMS_PER_KIND),
            preferences = (a.preferences + b.preferences).distinct().take(MAX_ITEMS_PER_KIND),
            summary = a.summary.ifBlank { b.summary },
            raw = a.raw + "\n-- chunk --\n" + b.raw,
            promptTokens = sumTokens(a.promptTokens, b.promptTokens),
            completionTokens = sumTokens(a.completionTokens, b.completionTokens),
        )

        private fun sumTokens(x: Int?, y: Int?): Int? = if (x == null && y == null) null else (x ?: 0) + (y ?: 0)
    }
}
