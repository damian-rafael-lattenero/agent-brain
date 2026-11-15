package dev.lattenero.brain.memory

import dev.lattenero.brain.chat.ChatProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import kotlin.time.Duration

private val log = LoggerFactory.getLogger("agent-brain.memory.verdict")

/**
 * The relation between two memory statements, as the model judges it — the
 * ONLY thing the LLM decides here. The verdict drives state changes; the
 * model NEVER rewrites content (the reconcile law: verdict-driven state
 * changes only).
 *
 * - [Same]: the two statements say the same thing — merge as dedup does
 *   (reinforce the oldest, absorb the restatement).
 * - [Supersede]: the new statement updates or contradicts the old one — the
 *   old claim is stamped `invalid_at` (Zep-style invalidation; the row stays,
 *   invalidation is NOT deletion).
 * - [Distinct]: related but both can be true — nothing changes.
 */
@Serializable
sealed interface Verdict {
    data object Same : Verdict
    data object Supersede : Verdict
    data object Distinct : Verdict
}

/**
 * What one verdict call yielded. [Decided] carries the verdict the model
 * gave (an unparseable answer degrades to [Verdict.Distinct] — parse, don't
 * validate); [Skipped] means the provider never answered in time or at all —
 * the write path must never block on chat, so a skipped verdict is a no-op,
 * never a state change and never a retry storm.
 */
sealed interface VerdictAttempt {
    data class Decided(val verdict: Verdict, val rationale: String) : VerdictAttempt
    data class Skipped(val reason: String) : VerdictAttempt
}

/**
 * The TOTAL parser at the chat edge (parse, don't validate): any string the
 * model produced parses into a [Verdict] — an answer whose shape is wrong
 * degrades honestly to [Verdict.Distinct] with a log line, never a throw.
 * `Distinct` is the safe degradation: an unreadable judgment must not delete
 * or invalidate anything.
 */
object VerdictParser {
    /** The verdict wire words the schema demands. */
    const val SAME = "same"
    const val SUPERSEDE = "supersede"
    const val DISTINCT = "distinct"

    private val json = Json { ignoreUnknownKeys = true }

    /** Total over any string: JSON, not-JSON, empty — always a [Verdict]. */
    fun parse(raw: String): Verdict = parseElement(element(raw))

    /** The raw answer as a JSON element, or null when it is not JSON (null degrades to Distinct). */
    fun element(raw: String): JsonElement? = runCatching { json.parseToJsonElement(raw) }.getOrNull()

    /**
     * The element face of [parse]: an object with a string `verdict` field in
     * the schema's vocabulary decides; anything else is a logged degradation
     * to [Verdict.Distinct].
     */
    fun parseElement(element: JsonElement?): Verdict {
        val wire = (element as? JsonObject)
            ?.get("verdict")?.let { it as? JsonPrimitive }
            ?.takeIf { it.isString }?.content?.trim()?.lowercase()
        return when (wire) {
            SAME -> Verdict.Same
            SUPERSEDE -> Verdict.Supersede
            DISTINCT -> Verdict.Distinct
            else -> {
                log.warn("verdict answer did not match the schema, degrading to distinct: {}", preview(element))
                Verdict.Distinct
            }
        }
    }

    private fun preview(element: JsonElement?): String = element?.toString()?.take(PREVIEW_CHARS) ?: "null"

    private const val PREVIEW_CHARS = 200
}

/**
 * The chat adapter of the verdict machinery: ONE call per pair on the same
 * chat client the extractor uses, asking for same | supersede | distinct plus
 * a one-line rationale, with the JSON schema in the prompt (Ollama's
 * structured outputs enforce the shape; [VerdictParser] still owns the
 * truth). One attempt, one timeout, NO resilience policy: a chat outage or a
 * timeout yields [VerdictAttempt.Skipped] — the reconcile and dream passes
 * must never block the write path on chat, so a provider failure is a silent
 * no-op, never a retry and never an exception.
 */
class VerdictJudge(private val chat: ChatProvider, private val timeout: Duration) {
    /** ONE verdict call per pair. Never throws (structured-concurrency cancellation excepted). */
    suspend fun judge(old: String, new: String): VerdictAttempt = runCatching {
        withTimeout(timeout) {
            val result = chat.completeJson(SYSTEM_PROMPT, userMessage(old, new), SCHEMA)
            val element = VerdictParser.element(result.text)
            VerdictAttempt.Decided(VerdictParser.parseElement(element), rationale(element))
        }
    }.fold(
        onSuccess = { it },
        onFailure = { error ->
            when {
                // A timeout is a skip like any provider failure (the provider hung);
                // a cancellation is NOT ours to swallow — structured concurrency owns it.
                error is TimeoutCancellationException -> VerdictAttempt.Skipped("verdict call timed out after $timeout")
                error is CancellationException -> throw error
                else -> VerdictAttempt.Skipped("${error::class.simpleName}: ${error.message}")
            }
        },
    )

    /** Best-effort rationale for the report; an absent or non-string field degrades to empty. */
    private fun rationale(element: JsonElement?): String = (element as? JsonObject)
        ?.get("rationale")?.let { it as? JsonPrimitive }
        ?.takeIf { it.isString }?.content?.trim()
        .orEmpty()
        .take(MAX_RATIONALE_CHARS)

    companion object {
        const val MAX_RATIONALE_CHARS = 300

        const val SYSTEM_PROMPT =
            "You compare two stored memory statements about the same topic and decide their relation. " +
                "Answer with JSON only, matching exactly this schema: " +
                "{\"verdict\": one of \"same\"|\"supersede\"|\"distinct\", \"rationale\": one short sentence}. " +
                "- same: the two statements say the same thing (a restatement or paraphrase of one memory). " +
                "- supersede: the NEW statement updates, replaces or contradicts the OLD one " +
                "(a state change like pending vs completed, planned vs done, failing vs passing); " +
                "the old claim is outdated. " +
                "- distinct: same topic but both statements can be true at once, or neither replaces the other. " +
                "Judge only what the statements say; never invent content."

        /** One pair, one user message: the OLD claim first, the NEW claim second — order is part of the meaning. */
        fun userMessage(old: String, new: String): String = "OLD: $old\nNEW: $new\nWhich verdict?"

        val SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("verdict") {
                    put("type", "string")
                    putJsonArray("enum") {
                        listOf(VerdictParser.SAME, VerdictParser.SUPERSEDE, VerdictParser.DISTINCT).forEach {
                            add(JsonPrimitive(it))
                        }
                    }
                }
                putJsonObject("rationale") { put("type", "string") }
            }
            putJsonArray("required") {
                listOf("verdict", "rationale").forEach { add(JsonPrimitive(it)) }
            }
        }
    }
}
