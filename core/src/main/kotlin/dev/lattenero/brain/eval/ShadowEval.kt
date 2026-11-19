package dev.lattenero.brain.eval

import dev.lattenero.brain.chat.ChatException
import dev.lattenero.brain.chat.ChatProvider
import dev.lattenero.brain.config.Config
import dev.lattenero.brain.db.Database
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.time.TimeSource

/**
 * The shadow judge (plan v2 T1): the bot's COMPLETED turns, judge-graded
 * daily on a rubric. The bus holds the real pairs — `user.prompt`'s prompt
 * and `turn.completed`'s reply — so quality finally has a number the way
 * the usage eval gave retrieval one. The trend is the signal: single scores
 * are flaky (measured across the judge literature), the daily ledger rows
 * are what reveals a quality regression.
 *
 * The 2026 judge laws, applied:
 *  - RUBRIC, not vibes: one 1-5 score over three named criteria with their
 *    definitions in the prompt; pass = 4+. Single-output scoring over
 *    pairwise — no position bias to manage (there is no second answer).
 *  - The judge is NOT the performer (the judge leg, deepseek; the bot
 *    replies come from the harness's model).
 *  - DETERMINISTIC checks stay code: empty replies never reach the judge
 *    (the SQL floor), rendered-format checks live in the bot's own tests.
 *  - SAMPLE, don't grade everything: [DEFAULT_LIMIT] newest turns past the
 *    cursor per run (a cursor state file, one honest line per day).
 */
class ShadowEval(
    private val db: Database,
    private val judge: ChatProvider,
    private val cursorFile: Path,
    private val embedModel: String,
    private val embedDim: Int,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The cursor persisted between runs: the newest turn ts already graded. */
    @Serializable
    private data class Cursor(val lastTs: String)

    private fun readCursor(): OffsetDateTime = runCatching {
        OffsetDateTime.parse(json.decodeFromString(Cursor.serializer(), Files.readString(cursorFile)).lastTs)
    }.getOrDefault(OffsetDateTime.now().minusHours(INITIAL_LOOKBACK_HOURS))

    private fun writeCursor(ts: OffsetDateTime) {
        Files.createDirectories(cursorFile.parent)
        Files.writeString(cursorFile, json.encodeToString(Cursor.serializer(), Cursor(ts.toString())))
    }

    suspend fun grade(
        limit: Int = DEFAULT_LIMIT,
        gate: Double = SHADOW_GATE,
        minReplyLen: Int = MIN_REPLY_LEN,
        prices: PriceTable = PriceTable.load(),
        runId: String = UUID.randomUUID().toString(),
    ): EvalReport? {
        val cursor = readCursor()
        // Raw rows first; the reply extraction and the length floor live
        // Kotlin-side (the fork's grammar has no ->>, and this is where the
        // house does its JSON anyway).
        val rows = db.withDriver { brainDb ->
            brainDb.busEventsQueries.shadowCandidates(cursor = cursor, lim = limit.toLong())
                .executeAsList()
        }
        val replies: List<ShadowRow> = rows.mapNotNull { row ->
            val reply = runCatching {
                json.parseToJsonElement(row.payload_text).jsonObject["reply"]?.jsonPrimitive?.content
            }.getOrNull() ?: return@mapNotNull null
            if (reply.length < minReplyLen) return@mapNotNull null
            ShadowRow(row.id, row.sid, row.turn, row.ts, reply)
        }
        if (replies.isEmpty()) return null
        val startedAt = OffsetDateTime.now()
        val mark = TimeSource.Monotonic.markNow()
        val cases = replies.map { row -> gradeRow(row.id, row.sid, row.turn, row.reply) }
        val durationMs = mark.elapsedNow().inWholeMilliseconds
        // The cursor advances to the NEWEST judged turn only after every case
        // finished: a crashed run re-grades (the judge is idempotent enough at
        // daily sample size), a completed run never repeats.
        replies.lastOrNull()?.ts?.let { writeCursor(it) }
        val passed = cases.count { it.ok }
        val report = EvalReport(
            runId = runId,
            suite = SUITE,
            kind = EvalKind.SHADOW,
            axis = EvalAxis.CHAT,
            model = judge.model,
            provider = judge.name,
            embedModel = embedModel,
            chatModel = judge.model,
            gate = gate,
            passed = passed,
            total = cases.size,
            ok = cases.isNotEmpty() && passed.toDouble() / cases.size >= gate,
            metrics = SuiteMetricsCalculator.of(
                cases, EvalKind.SHADOW, prices,
                ModelIdentity(model = judge.model, provider = judge.name, embedModel = embedModel),
                durationMs,
            ),
            cases = cases,
            startedAt = startedAt.toString(),
            durationMs = durationMs,
        )
        EvalLedger(db, embedDim).record(report)
        return report
    }

    /** One candidate row: the completed turn with its extracted reply. */
    private data class ShadowRow(
        val id: String,
        val sid: String?,
        val turn: String?,
        val ts: OffsetDateTime,
        val reply: String,
    )

    /** One completed turn → one judge call → one rubric case. */
    private suspend fun gradeRow(id: String, sid: String?, turn: String?, reply: String): CaseResult {
        val promptRow: String? = db.withDriver { brainDb ->
            brainDb.busEventsQueries.shadowPrompt(sid = sid, turn = turn).executeAsOneOrNull()
        }
        val prompt: String = promptRow.orEmpty().let { row ->
            runCatching {
                json.parseToJsonElement(row).jsonObject["prompt"]?.jsonPrimitive?.content
            }.getOrNull()
        }.orEmpty()
        val user = renderTurn(prompt, reply)
        val mark = TimeSource.Monotonic.markNow()
        return try {
            val result = judge.completeJson(SYSTEM, user, SCHEMA)
            val verdict = ShadowVerdict.parse(result.text)
            CaseResult(
                id = id,
                ok = verdict.score >= PASS_SCORE,
                durationMs = mark.elapsedNow().inWholeMilliseconds,
                promptTokens = result.promptTokens?.toLong() ?: Tokens.estimate(SYSTEM) + Tokens.estimate(user),
                completionTokens = result.completionTokens?.toLong() ?: Tokens.estimate(result.text),
                tokensEstimated = result.promptTokens == null || result.completionTokens == null,
                question = prompt.ifBlank { null },
                answer = reply.take(REPLY_CAP),
                verdict = verdict.score.toString(),
                summary = verdict.reason.take(REASON_CAP),
                failure = null,
            )
        } catch (e: ChatException) {
            CaseResult(
                id = id,
                ok = false,
                durationMs = mark.elapsedNow().inWholeMilliseconds,
                tokensEstimated = true,
                question = prompt.ifBlank { null },
                answer = reply.take(REPLY_CAP),
                failure = "judge call failed: ${e.message}",
            )
        } catch (e: IllegalArgumentException) {
            CaseResult(
                id = id,
                ok = false,
                durationMs = mark.elapsedNow().inWholeMilliseconds,
                tokensEstimated = true,
                question = prompt.ifBlank { null },
                answer = reply.take(REPLY_CAP),
                failure = "unparsable verdict: ${e.message}",
            )
        }
    }

    private fun renderTurn(prompt: String, reply: String): String = buildString {
        append("USER PROMPT:\n").append(prompt.ifBlank { "(no prompt recorded)" })
        append("\n\nBOT REPLY:\n").append(reply)
    }

    /** The judge's verdict: one rubric score over the named criteria + a one-line reason. */
    data class ShadowVerdict(val score: Int, val reason: String) {
        companion object {
            private val json = Json { ignoreUnknownKeys = true }

            fun parse(text: String): ShadowVerdict {
                val root = json.parseToJsonElement(text).jsonObject
                val score = root["score"]?.jsonPrimitive?.intOrNull
                    ?: throw IllegalArgumentException("missing 'score'")
                // require: the caller grades this case FAILED on the message.
                require(score in MIN_SCORE..MAX_SCORE) { "score $score outside $MIN_SCORE..$MAX_SCORE" }
                val reason = (root["reason"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                return ShadowVerdict(score, reason)
            }
        }
    }

    companion object {
        const val SUITE = "shadow"
        const val DEFAULT_LIMIT = 10
        const val MIN_REPLY_LEN = 240
        const val MIN_SCORE = 1
        const val MAX_SCORE = 5
        const val PASS_SCORE = 4
        const val SHADOW_GATE = 0.7
        const val INITIAL_LOOKBACK_HOURS = 24L
        private const val REASON_CAP = 240
        private const val REPLY_CAP = 600

        /**
         * The rubric, in the prompt: three criteria with their 1-5 anchors.
         * The replies are Telegram messages in Spanish for a technical owner —
         * conciseness is part of the job, not a style preference.
         */
        private const val SYSTEM =
            "You grade a coding-agent assistant's Telegram replies (Spanish, technical owner). " +
                "Score 1-5 on this rubric: 5 = answers exactly what was asked, correct, concise, " +
                "Telegram-clean (short, structured, no filler); 4 = correct and useful, minor noise; " +
                "3 = partially answers or noticeably padded; 2 = mostly misses the ask or wrong; " +
                "1 = wrong or useless. Judge content over wording; the reply's tone may be direct. " +
                "Return JSON only: {\"score\": int (1-5), \"reason\": string (one short line, Spanish)}. " +
                "No score outside 1-5, no extra keys."

        private val SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("score") { put("type", "integer") }
                putJsonObject("reason") { put("type", "string") }
            }
            put("required", buildJsonArray {
                add(JsonPrimitive("score"))
                add(JsonPrimitive("reason"))
            })
        }

        /** The cursor file the runs share (state dir, next to the other honest state). */
        fun cursorFile(config: Config): Path = config.stateDir.resolve("shadow-cursor.json")
    }
}
