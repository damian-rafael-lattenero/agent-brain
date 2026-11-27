package dev.lattenero.brain.memory

import arrow.core.Either
import dev.lattenero.brain.chat.ChatProvider
import dev.lattenero.brain.chat.ChatResult
import dev.lattenero.brain.embeddings.ProviderResilience
import dev.lattenero.brain.embeddings.withProviderResilience
import kap.CircuitBreaker
import kap.Kap
import kap.KapTracer
import kap.map
import kap.traced
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

/**
 * The owner's correction (plan U8) as ONE attempt: either the model yielded
 * a rule sentence ([Either.Right], capped) or it did not ([Either.Left],
 * carrying an [ExtractionInvalid] with the reason and the raw answer for the
 * one-line evidence log). Total and pure: [parse] never throws, so a
 * rambling or degenerate judge can only move a correction between these two
 * bins, never break the worker.
 */
class CorrectionExtractor(
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
     * One chat call under the SAME resilience policy as the memory extractor
     * (timeout as failure, retried with jittered backoff, circuit breaker).
     * A blank correction text is decided BEFORE the attempt: there are no
     * words to turn into a rule, so no model call is spent on it.
     */
    fun extract(text: String): Kap<Either<ExtractionInvalid, String>> {
        if (text.isBlank()) {
            return Kap { Either.Left(ExtractionInvalid("blank correction text: nothing to turn into a rule", "")) }
        }
        return Kap { chat.completeJson(SYSTEM_PROMPT, render(text), SCHEMA) }
            .withProviderResilience(timeout, attempts, backoffBase, breaker) { attempt, error, delay ->
                log.warn(
                    "correction extraction retry {} in {} after {}: {}",
                    attempt, delay, error::class.simpleName, error.message,
                )
            }
            .traced(PHASE_EXTRACT, tracer)
            .map { parse(it) }
    }

    companion object {
        const val PHASE_EXTRACT = "correction-extract"
        val DEFAULT_BACKOFF_BASE = 500.milliseconds

        /** The owner's words bound in the prompt: corrections are short; 2 K is generous. */
        const val INPUT_CAP = 2_000

        /** The candidate sentence cap: the memory item cap, reused on purpose. */
        val MAX_CANDIDATE_CHARS: Int = MemoryExtractor.MAX_ITEM_CHARS

        const val SYSTEM_PROMPT = "You turn the owner's correction of a coding agent into ONE mechanical rule " +
            "sentence in English: the constraint the agent should have followed, as a law it can be held to. " +
            "Keep the owner's meaning; discard politeness, narrative and anything specific to the one " +
            "conversation; the rule must generalize beyond this turn. Code identifiers, file names and " +
            "numbers stay verbatim. Return JSON only: {\"rule\": \"<the one English rule sentence>\"}."

        val SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("rule") { put("type", "string") }
            }
            put("required", buildJsonArray { add(JsonPrimitive("rule")) })
        }

        private fun render(text: String): String = "THE OWNER'S CORRECTION:\n${text.take(INPUT_CAP)}"

        private val json = Json { ignoreUnknownKeys = true }

        /** The first non-blank STRING inside a JSON answer: the expected "rule"
         *  key first, then any string in the object or array. */
        private fun ruleInside(element: JsonElement): String? = when (element) {
            is JsonPrimitive -> if (element.isString) element.content.trim().takeIf { it.isNotEmpty() } else null
            is JsonObject -> element["rule"]?.let(::ruleInside)
                ?: element["sentence"]?.let(::ruleInside)
                ?: element.values.firstNotNullOfOrNull(::ruleInside)
            is JsonArray -> element.firstNotNullOfOrNull(::ruleInside)
        }

        /** The one-sentence face of a prose answer: text up to and including the first sentence terminator. */
        private fun firstSentence(text: String): String {
            val end: Int = text.indexOfFirst { it == '.' || it == '!' || it == '?' }
            return if (end >= 0) text.substring(0, end + 1).trim() else text.trim()
        }

        /**
         * The parse is TOTAL (MemoryExtractor.parseBins' law, applied to one
         * sentence): a blank answer is Invalid; a JSON answer yields the rule
         * string it carries or is Invalid as garbage; any other non-blank
         * answer is the candidate — one sentence, capped. The JSON face is
         * REAL, not defensive: the wired providers force JSON output
         * (response_format json_object / format=schema), so the schema's own
         * answer is the happy path and plain prose is the degraded one.
         */
        fun parse(result: ChatResult): Either<ExtractionInvalid, String> {
            val raw = result.text
            val trimmed = raw.trim()
            val face: Either<ExtractionInvalid, String> = when {
                trimmed.isEmpty() -> Either.Left(ExtractionInvalid("blank answer: the judge said nothing", raw))
                trimmed.startsWith("{") || trimmed.startsWith("[") -> jsonFace(raw)
                else -> Either.Right(trimmed)
            }
            return face.map { firstSentence(it).take(MAX_CANDIDATE_CHARS) }
        }

        /** The JSON face: the rule string the answer carries, or the garbage
         *  bin — with the parse failure named, never swallowed. */
        private fun jsonFace(raw: String): Either<ExtractionInvalid, String> = try {
            val inside = ruleInside(json.parseToJsonElement(raw))
            if (inside == null) {
                Either.Left(ExtractionInvalid("JSON garbage: no rule sentence inside", raw))
            } else {
                Either.Right(inside)
            }
        } catch (e: SerializationException) {
            Either.Left(
                ExtractionInvalid(
                    "JSON garbage: not parseable JSON (${e.message?.lineSequence()?.firstOrNull()})",
                    raw,
                ),
            )
        }
    }
}
