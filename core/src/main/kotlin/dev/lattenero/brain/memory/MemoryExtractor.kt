package dev.lattenero.brain.memory

import arrow.core.Either
import arrow.core.raise.either
import dev.lattenero.brain.chat.ChatProvider
import dev.lattenero.brain.chat.ChatResult
import dev.lattenero.brain.rag.EmbedPipeline
import kap.CircuitBreaker
import kap.Kap
import kap.KapTracer
import kap.Schedule
import kap.map
import kap.retry
import kap.timeoutAsFailure
import kap.traced
import kap.withCircuitBreaker
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
import kotlin.time.Duration.Companion.seconds

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
        get() = facts.map { KIND_FACT to it } + decisions.map { KIND_DECISION to it } +
            preferences.map { KIND_PREFERENCE to it } + listOfNotNull(summary.takeIf { it.isNotBlank() }?.let { KIND_SUMMARY to it })

    companion object {
        const val KIND_FACT = "fact"
        const val KIND_DECISION = "decision"
        const val KIND_PREFERENCE = "preference"
        const val KIND_SUMMARY = "summary"
    }
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
        maxFailures = DEFAULT_BREAKER_FAILURES,
        retryAfter = DEFAULT_BREAKER_RESET,
        // The Python F79e lesson (see EmbedPipeline.DEFAULT_BREAKER_SUCCESS_THRESHOLD).
        successThreshold = EmbedPipeline.DEFAULT_BREAKER_SUCCESS_THRESHOLD,
    ),
    private val attempts: Int = DEFAULT_ATTEMPTS,
    private val backoffBase: Duration = DEFAULT_BACKOFF_BASE,
) {
    val model: String get() = chat.model

    fun extract(transcript: Transcript): Kap<Extraction> =
        Kap { chat.completeJson(SYSTEM_PROMPT, transcript.render(TRANSCRIPT_CAP), SCHEMA) }
            .timeoutAsFailure(timeout)
            .retry({ Schedule.times<Throwable>(attempts - 1) and Schedule.exponential<Throwable>(backoffBase).jittered() }) { attempt, error, delay ->
                log.warn("extraction retry {} in {} after {}: {}", attempt, delay, error::class.simpleName, error.message)
            }
            .withCircuitBreaker(breaker)
            .traced(PHASE_EXTRACT, tracer)
            .map { parse(it) }

    companion object {
        const val PHASE_EXTRACT = "extract"
        const val DEFAULT_BREAKER_FAILURES = 5
        val DEFAULT_BREAKER_RESET = 30.seconds
        const val DEFAULT_ATTEMPTS = 3
        val DEFAULT_BACKOFF_BASE = 500.milliseconds
        const val TRANSCRIPT_CAP = 6_000
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
                ParseOutcome.Parsed(
                    Extraction(
                        kind(obj, "facts", raw).bind(),
                        kind(obj, "decisions", raw).bind(),
                        kind(obj, "preferences", raw).bind(),
                        summaryText,
                        raw,
                        null,
                        null,
                    ),
                )
            }
            return when (extraction) {
                is Either.Left -> extraction.value
                is Either.Right -> extraction.value
            }
        }

        /** The attempt channel's face of [parseBins]: the Invalid bin becomes [ExtractionInvalid]. */
        fun parse(result: ChatResult): Extraction = when (val outcome = parseBins(result.text)) {
            is ParseOutcome.Parsed -> outcome.extraction.copy(
                promptTokens = result.promptTokens,
                completionTokens = result.completionTokens,
            )
            is ParseOutcome.Invalid -> throw ExtractionInvalid(outcome.reason, outcome.raw)
        }
    }
}
