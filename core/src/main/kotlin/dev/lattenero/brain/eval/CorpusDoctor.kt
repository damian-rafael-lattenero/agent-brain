package dev.lattenero.brain.eval

import dev.lattenero.brain.chat.ChatException
import dev.lattenero.brain.chat.ChatProvider
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.sq.FailedGraded
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.OffsetDateTime
import kotlin.time.TimeSource

/**
 * The corpus doctor (plan v2 B6): the graded FAILURES of the production
 * rag_query log, one judge call per failure, classified into WHY the
 * retrieval failed — the closing half of the usage eval's loop. The usage
 * eval measures production quality and marks the failures; nobody consumed
 * them. The doctor consumes them and proposes repairs.
 *
 * Laws carried over:
 *  - READ-ONLY by design: the doctor proposes (report + artifacts), it never
 *    writes the corpus. The owner or an agent session applies; the daily
 *    nightly (real-questions + usage gate) verifies for free.
 *  - The diagnosis parser is TOTAL: any judge string decides or degrades to
 *    UNCLASSIFIED, never throws (VerdictParser's law).
 *  - A degraded case keeps its evidence — a chat failure or pruned chunks
 *    surface in the report instead of disappearing.
 *  - `UNANSWERABLE` exists so honesty wins: not every failure is a corpus
 *    gap — some questions the corpus should not answer at all.
 */
/**
 * WHY the retrieval failed, on the doctor's fixed vocabulary. `parse` is
 * TOTAL: a null/unknown wire degrades to UNCLASSIFIED (the honest bucket —
 * an undiagnosed failure is a finding too, never a crash).
 */
@Serializable
enum class DoctorCause(val wire: String) {
    CORPUS_GAP("corpus_gap"),
    PHRASING_MISMATCH("phrasing_mismatch"),
    STALE_CHUNK("stale_chunk"),
    UNANSWERABLE("unanswerable"),
    UNCLASSIFIED("unclassified");

    companion object {
        private val byWire: Map<String, DoctorCause> = entries.associateBy(DoctorCause::wire)

        fun parse(wire: String?): DoctorCause = wire?.trim()?.lowercase()?.let(byWire::get) ?: UNCLASSIFIED
    }
}

class CorpusDoctor(private val db: Database, private val judge: ChatProvider) {

    private val json = Json { ignoreUnknownKeys = true }

    /** One graded failure, diagnosed. `evidence` explains a degraded (UNCLASSIFIED) case. */
    @Serializable
    data class DoctorCase(
        val query: String,
        val at: String,
        val servedUris: List<String>,
        val cause: DoctorCause,
        val targetUri: String? = null,
        val proposal: String? = null,
        val evidence: String? = null,
    )

    @Serializable
    data class DoctorReport(
        val windowDays: Int,
        val gradedTotal: Int,
        val failedTotal: Int,
        val truncated: Boolean,
        val causes: Map<String, Int>,
        val cases: List<DoctorCase>,
        val model: String,
        val provider: String,
        val startedAt: String,
        val durationMs: Long,
    ) {
        /** The one number the weekly trend rides: failures over graded, in the window. */
        val failureRate: Double
            get() = if (gradedTotal == 0) 0.0 else failedTotal.toDouble() / gradedTotal

        /** The human face: window stats first, then every diagnosed case. */
        fun markdown(): String = buildString {
            append("# Corpus doctor — ${windowDays}d window\n\n")
            append("Graded ").append(gradedTotal).append(" production queries · ")
                .append(failedTotal).append(" failures (")
                .append("%.1f".format(failureRate * PERCENT_SCALE)).append("%)\n\n")
            if (truncated) {
                append("_(capped at ").append(cases.size)
                    .append(" diagnosed cases — older failures beyond the cap)_\n\n")
            }
            if (cases.isEmpty()) {
                append("No failures in the window — the corpus answered everything the judge graded.\n")
                return@buildString
            }
            append("| cause | query | target | proposal |\n|---|---|---|---|\n")
            cases.forEach { case ->
                append("| ").append(case.cause.wire)
                    .append(" | ").append(case.query.replace("|", "\\|").take(QUERY_CELL_CAP))
                    .append(" | ").append(case.targetUri?.take(URI_CELL_CAP) ?: "—")
                    .append(" | ").append(
                        (case.evidence ?: case.proposal ?: "—").replace("|", "\\|").take(PROPOSAL_CELL_CAP),
                    )
                    .append(" |\n")
            }
        }.trimEnd() + "\n"

        companion object {
            private const val PERCENT_SCALE = 100.0
            private const val QUERY_CELL_CAP = 80
            private const val URI_CELL_CAP = 60
            private const val PROPOSAL_CELL_CAP = 120
        }
    }

    /**
     * Diagnoses the window's graded failures. Returns null when the judge
     * graded NOTHING in the window — a report over zero graded queries would
     * lie; "0 failures over N" is a real report and returns normally.
     */
    suspend fun diagnose(days: Int = DEFAULT_DAYS, limit: Int = DEFAULT_LIMIT): DoctorReport? {
        val since = OffsetDateTime.now().minusDays(days.toLong())
        val gradedTotal: Int = db.withDriver { brainDb ->
            brainDb.ragQueryLogQueries.countGraded(since).executeAsOne().toInt()
        }
        if (gradedTotal == 0) return null
        val rows = db.withDriver { brainDb ->
            brainDb.ragQueryLogQueries.failedGraded(since, limit.toLong()).executeAsList()
        }
        val startedAt = OffsetDateTime.now()
        val mark = TimeSource.Monotonic.markNow()
        val cases = rows.distinctBy { it.query }.map { row -> diagnoseRow(row) }
        val report = DoctorReport(
            windowDays = days,
            gradedTotal = gradedTotal,
            failedTotal = rows.size,
            truncated = rows.size >= limit,
            causes = DoctorCause.entries.associate { cause ->
                cause.wire to cases.count { case -> case.cause == cause }
            },
            cases = cases,
            model = judge.model,
            provider = judge.name,
            startedAt = startedAt.toString(),
            durationMs = mark.elapsedNow().inWholeMilliseconds,
        )
        return report
    }

    /** One logged failure → one judge call → one diagnosed case (the UsageEval.gradeRow shape). */
    private suspend fun diagnoseRow(row: FailedGraded): DoctorCase {
        val chunkIds = decodeLongs(row.chunk_ids)
        val topUris = decodeStrings(row.top_uris)
        val chunks: List<DiagnosedChunk> = if (chunkIds.isEmpty()) emptyList() else db.withDriver { brainDb ->
            brainDb.ragQueries.chunksByIds(chunkIds.toTypedArray()).executeAsList()
                .map { DiagnosedChunk(it.id, it.uri, it.content) }
        }
        val degraded: String? = when {
            chunkIds.isEmpty() -> "the query was served zero chunks (no hits at retrieval)"
            chunks.isEmpty() -> "none of the logged chunks survived re-materialization (pruned?)"
            else -> null
        }
        if (degraded != null) {
            return DoctorCase(row.query, row.at.toString(), topUris, DoctorCause.UNCLASSIFIED, evidence = degraded)
        }
        return try {
            val result = judge.completeJson(SYSTEM, renderContext(row.query, chunks), SCHEMA)
            val verdict = DoctorVerdict.parse(result.text)
            DoctorCase(
                query = row.query,
                at = row.at.toString(),
                servedUris = topUris,
                cause = verdict.cause,
                targetUri = verdict.targetUri.ifEmpty { null },
                proposal = verdict.proposal.ifEmpty { null },
            )
        } catch (e: ChatException) {
            DoctorCase(row.query, row.at.toString(), topUris, DoctorCause.UNCLASSIFIED,
                evidence = "judge call failed: ${e.message}")
        }
    }

    private data class DiagnosedChunk(val id: Long, val uri: String, val content: String)

    /** One served chunk as the diagnosing judge sees it, content bounded (weekly job, bounded prompts). */
    private fun renderContext(question: String, chunks: List<DiagnosedChunk>): String = buildString {
        append("QUESTION: ").append(question).append("\n\nSERVED (all judged irrelevant):\n")
        chunks.forEachIndexed { index, chunk ->
            append(index + 1).append(". [").append(chunk.uri).append("] ")
                .append(chunk.content.replace("\n", " ").take(CHUNK_CAP)).append('\n')
        }
    }

    private fun decodeLongs(text: String): List<Long> = runCatching {
        json.decodeFromString(ListSerializer(Long.serializer()), text)
    }.getOrElse { emptyList() }

    private fun decodeStrings(text: String): List<String> = runCatching {
        json.decodeFromString(ListSerializer(String.serializer()), text)
    }.getOrElse { emptyList() }

    /**
     * The judge's diagnosis. TOTAL: any string decides or degrades to
     * UNCLASSIFIED — the weekly report must never crash on a rambling judge.
     */
    data class DoctorVerdict(val cause: DoctorCause, val targetUri: String, val proposal: String) {
        companion object {
            private val json = Json { ignoreUnknownKeys = true }

            fun parse(text: String): DoctorVerdict = runCatching {
                val root = json.parseToJsonElement(text).jsonObject
                // Non-string fields are NOISE, dropped (the UsageVerdict
                // out-of-range precedent): the cause that DID parse keeps
                // its signal, the junk field degrades to empty.
                DoctorVerdict(
                    cause = DoctorCause.parse(
                        (root["cause"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content,
                    ),
                    targetUri = (root["target_uri"] as? JsonPrimitive)
                        ?.takeIf(JsonPrimitive::isString)?.content?.trim().orEmpty(),
                    proposal = (root["proposal"] as? JsonPrimitive)
                        ?.takeIf(JsonPrimitive::isString)?.content?.trim().orEmpty(),
                )
            }.getOrElse { DoctorVerdict(DoctorCause.UNCLASSIFIED, "", "") }
        }
    }

    companion object {
        const val DEFAULT_DAYS = 7
        const val DEFAULT_LIMIT = 30

        /** One chunk's content bound in the diagnosis prompt. */
        private const val CHUNK_CAP = 600

        private const val SYSTEM =
            "You diagnose retrieval failures for a developer's personal knowledge RAG whose corpus is " +
                "ENGLISH (markdown notes, code docs); the failed question may be SPANISH or English — " +
                "a language mismatch alone is NOT a corpus gap. Given the question and the numbered " +
                "chunks that were served and judged irrelevant, classify WHY the retrieval failed and " +
                "return JSON only: {\"cause\": \"corpus_gap\" | \"phrasing_mismatch\" | \"stale_chunk\" | " +
                "\"unanswerable\" | \"unclassified\", \"target_uri\": \"\", \"proposal\": \"one concrete " +
                "repair sentence\"}. corpus_gap = the knowledge needed does not exist in the corpus; " +
                "phrasing_mismatch = a served chunk is on-topic but the answer is buried under other " +
                "wording (target_uri = that chunk's uri); stale_chunk = the chunk is outdated for this " +
                "question (target_uri = the chunk to refresh); unanswerable = the question is outside " +
                "what this personal corpus should answer. If unsure, use \"unclassified\" with an empty proposal."

        private val SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("cause") { put("type", "string") }
                putJsonObject("target_uri") { put("type", "string") }
                putJsonObject("proposal") { put("type", "string") }
            }
            put(
                "required",
                buildJsonArray { listOf("cause", "target_uri", "proposal").forEach { add(JsonPrimitive(it)) } },
            )
        }
    }
}
