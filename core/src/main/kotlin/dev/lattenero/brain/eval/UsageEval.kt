package dev.lattenero.brain.eval

import dev.lattenero.brain.chat.ChatException
import dev.lattenero.brain.chat.ChatProvider
import dev.lattenero.brain.db.Database
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.time.TimeSource

/**
 * The application-specific eval (the real-questions suite's missing sibling):
 * the PRODUCTION rag_query log, judge-graded. Every case is a query a real
 * caller asked; relevance has no ground truth, so the judge decides which of
 * the served chunks genuinely help — and the case's rank feeds the SAME graded
 * retrieval columns the recall suite reports (MRR, recall@1/@5, nDCG@5).
 *
 * Laws carried over:
 *  - The gate never lies to pass (a low baseline is a finding, not a failure).
 *  - The latency columns are the PRODUCTION retrieval latencies the callers
 *    felt (the log's own `latency_ms`), not the grading call's.
 *  - Evidence is kept: each graded row stores the verdict verbatim.
 *  - A chunk pruned since the query grades on what remains; the logged
 *    top_uris are the query's history. A row whose chunks were ALL pruned is
 *    ungradeable, not a miss: it leaves the queue with [PRUNED] as its verdict
 *    and is not scored (measured 26/11: six of twenty rows failed the nightly
 *    after the day's corpus cleanup — none of them a retrieval the judge saw).
 */
class UsageEval(
    private val db: Database,
    private val judge: ChatProvider,
    private val embedModel: String,
    private val embedDim: Int,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** How much grading is owed (the CLI's and the nightly's "is there work" probe). */
    suspend fun ungradedCount(): Long = db.withDriver { brainDb ->
        brainDb.ragQueryLogQueries.countUngraded().executeAsOne()
    }

    /**
     * Grades up to [sampleSize] ungraded rows and records the run in the
     * ledger. Returns null when there is nothing to grade — a zero-case run
     * would be a ledger row that lies.
     */
    suspend fun grade(
        sampleSize: Int = DEFAULT_SAMPLE,
        gate: Double = USAGE_GATE,
        prices: PriceTable = PriceTable.load(),
        runId: String = UUID.randomUUID().toString(),
    ): EvalReport? {
        val rows = db.withDriver { brainDb ->
            brainDb.ragQueryLogQueries.ungradedSample(sampleSize.toLong()).executeAsList()
        }
        val startedAt = OffsetDateTime.now()
        val mark = TimeSource.Monotonic.markNow()
        val cases = rows.map { row ->
            gradeRow(row.id, row.query, decodeLongs(row.chunk_ids), decodeStrings(row.top_uris), row.latency_ms)
        }
        val durationMs = mark.elapsedNow().inWholeMilliseconds
        // Evidence first, then the ledger: a replayed grade is a no-op
        // (`AND NOT graded`), an at-least-once caller stays honest. A row whose
        // evidence was pruned leaves the queue too, with PRUNED as its verdict.
        cases.forEach { case -> markGraded(case) }
        val graded = cases.filter { it.verdictText != PRUNED }
        // Nothing gradeable is nothing to grade: an ungradeable row is not a miss.
        if (graded.isEmpty()) return null
        val passed = graded.count { it.ok }
        val report = EvalReport(
            runId = runId,
            suite = SUITE,
            kind = EvalKind.USAGE,
            axis = EvalAxis.CHAT,
            model = judge.model,
            provider = judge.name,
            embedModel = embedModel,
            chatModel = judge.model,
            gate = gate,
            passed = passed,
            total = graded.size,
            ok = passed.toDouble() / graded.size >= gate,
            metrics = SuiteMetricsCalculator.of(
                graded, EvalKind.USAGE, prices,
                ModelIdentity(model = judge.model, provider = judge.name, embedModel = embedModel),
                durationMs,
            ),
            cases = graded,
            startedAt = startedAt.toString(),
            durationMs = durationMs,
        )
        EvalLedger(db, embedDim).record(report)
        return report
    }

    /** One logged query → one judge call → one case. The case's rank feeds the graded retrieval columns. */
    private suspend fun gradeRow(
        rowId: Long,
        query: String,
        chunkIds: List<Long>,
        topUris: List<String>,
        latencyMs: Int,
    ): CaseResult {
        val byId: Map<Long, GradedChunk> = db.withDriver { brainDb ->
            brainDb.ragQueries.chunksByIds(chunkIds.toTypedArray()).executeAsList()
                .associate { it.id to GradedChunk(it.id, it.uri, it.content) }
        }
        val chunks = chunkIds.mapNotNull(byId::get)
        if (chunks.isEmpty()) {
            return CaseResult(
                id = rowId.toString(),
                ok = false,
                durationMs = latencyMs.toLong(),
                tokensEstimated = true,
                question = query,
                verdictText = PRUNED,
                failure = PRUNED,
            )
        }
        val user = renderGradingContext(query, chunks)
        return try {
            val result = judge.completeJson(SYSTEM, user, SCHEMA)
            val verdict = UsageVerdict.parse(result.text, chunks.size)
            CaseResult(
                id = rowId.toString(),
                ok = verdict.ranks.isNotEmpty(),
                durationMs = latencyMs.toLong(),
                promptTokens = result.promptTokens?.toLong() ?: Tokens.estimate(SYSTEM) + Tokens.estimate(user),
                completionTokens = result.completionTokens?.toLong() ?: Tokens.estimate(result.text),
                tokensEstimated = result.promptTokens == null || result.completionTokens == null,
                question = query,
                topUri = topUris.firstOrNull(),
                rank = verdict.ranks.firstOrNull(),
                relevantCount = verdict.ranks.size,
                verdictText = result.text.take(VERDICT_EVIDENCE_CAP),
                failure = if (verdict.ranks.isEmpty()) "no served chunk graded relevant" else null,
            )
        } catch (e: ChatException) {
            CaseResult(
                id = rowId.toString(),
                ok = false,
                durationMs = latencyMs.toLong(),
                tokensEstimated = true,
                question = query,
                failure = "judge call failed: ${e.message}",
            )
        } catch (e: IllegalArgumentException) {
            CaseResult(
                id = rowId.toString(),
                ok = false,
                durationMs = latencyMs.toLong(),
                tokensEstimated = true,
                question = query,
                failure = "unparsable verdict: ${e.message}",
            )
        }
    }

    private suspend fun markGraded(case: CaseResult) {
        val id = case.id.toLongOrNull() ?: return
        db.withDriver { brainDb ->
            brainDb.ragQueryLogQueries.markGraded(
                id = id,
                relevantCount = case.relevantCount,
                firstRelevantRank = case.rank,
                verdicts = case.verdictText,
                judgedAt = OffsetDateTime.now(),
            )
        }
    }

    /** One served chunk as the judge sees it. */
    private data class GradedChunk(val id: Long, val uri: String, val content: String)

    private fun renderGradingContext(question: String, chunks: List<GradedChunk>): String = buildString {
        append("QUESTION: ").append(question).append("\n\nCONTEXT:\n")
        chunks.forEachIndexed { index, chunk ->
            append(index + 1).append(". [").append(chunk.uri).append("] ")
            append(chunk.content.replace("\n", " ")).append('\n')
        }
    }

    private fun decodeLongs(text: String): List<Long> = runCatching {
        json.decodeFromString(ListSerializer(Long.serializer()), text)
    }.getOrElse { emptyList() }

    private fun decodeStrings(text: String): List<String> = runCatching {
        json.decodeFromString(ListSerializer(String.serializer()), text)
    }.getOrElse { emptyList() }

    /** The judge's verdict: which 1-based chunks genuinely help. Out-of-range indices are noise, dropped. */
    data class UsageVerdict(val ranks: List<Int>) {
        companion object {
            private val json = Json { ignoreUnknownKeys = true }

            fun parse(text: String, k: Int): UsageVerdict {
                val root = json.parseToJsonElement(text).jsonObject
                val relevant = root["relevant"]?.jsonArray ?: throw IllegalArgumentException("missing 'relevant'")
                val ranks = relevant.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                    .filter { it in 1..k }
                    .distinct()
                    .sorted()
                return UsageVerdict(ranks)
            }
        }
    }

    companion object {
        const val SUITE = "usage"
        const val DEFAULT_SAMPLE = 20

        /**
         * The baseline gate, chosen where an honest judge-graded hit-rate
         * should sit (the curated suites sit at 0.8–0.9 under ground truth).
         * The first live baseline may move it — as a reviewable diff, never a
         * silent relaxation.
         */
        const val USAGE_GATE = 0.7

        /** The verdict kept on a row whose served chunks were all pruned: ungradeable, never a miss. */
        const val PRUNED = "pruned: none of the logged chunks survived re-materialization"

        private const val VERDICT_EVIDENCE_CAP = 400

        private const val SYSTEM =
            "You grade retrieval relevance for a developer's personal knowledge RAG. " +
                "Given a question and the numbered chunks the system served, return JSON only: " +
                "{\"relevant\": [int]} — the 1-based indices of the chunks that genuinely help answer " +
                "the question. Judge content, not wording; an empty array is a valid verdict."

        private val SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("relevant") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "integer") }
                }
            }
            put("required", buildJsonArray { add(JsonPrimitive("relevant")) })
        }
    }
}
