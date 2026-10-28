package dev.lattenero.brain.memory

import dev.lattenero.brain.chat.ChatProvider
import dev.lattenero.brain.chat.ChatResult
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
    val breaker: CircuitBreaker = CircuitBreaker(maxFailures = DEFAULT_BREAKER_FAILURES, resetTimeout = DEFAULT_BREAKER_RESET),
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

        fun parse(result: ChatResult): Extraction {
            val raw = result.text
            val element = try {
                json.parseToJsonElement(raw)
            } catch (e: SerializationException) {
                throw ExtractionInvalid("not JSON: ${e.message?.lineSequence()?.firstOrNull()}", raw)
            }
            val obj = element as? JsonObject ?: throw ExtractionInvalid("not a JSON object", raw)
            fun list(key: String): List<String> {
                val array = obj[key] ?: return emptyList()
                if (array !is JsonArray) throw ExtractionInvalid("$key is not an array", raw)
                return array.map { item ->
                    val prim = item as? JsonPrimitive ?: throw ExtractionInvalid("$key contains a non-string", raw)
                    if (!prim.isString) throw ExtractionInvalid("$key contains a non-string", raw)
                    prim.content.trim()
                }.filter { it.length >= MIN_ITEM_CHARS }.map { it.take(MAX_ITEM_CHARS) }.distinct().take(MAX_ITEMS_PER_KIND)
            }
            val summaryElement = obj["summary"]
            val summary = when {
                summaryElement == null -> ""
                summaryElement is JsonPrimitive && summaryElement.isString -> summaryElement.content.trim().take(MAX_SUMMARY_CHARS)
                else -> throw ExtractionInvalid("summary is not a string", raw)
            }
            return Extraction(list("facts"), list("decisions"), list("preferences"), summary, raw, result.promptTokens, result.completionTokens)
        }
    }
}
