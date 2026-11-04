package dev.lattenero.brain.eval

import dev.lattenero.brain.Brain
import dev.lattenero.brain.chat.ChatException
import dev.lattenero.brain.config.Config
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.memory.MemoryExtractor
import dev.lattenero.brain.memory.Transcript
import dev.lattenero.brain.rag.Hit
import dev.lattenero.brain.rag.QueryRequest
import kap.Kap
import kap.Resource
import kap.bracket
import kap.evalGraph
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** One graded case: the verdict plus every number the report needs, flat so JSON and SQL agree. */
@Serializable
data class CaseResult(
    val id: String,
    val ok: Boolean,
    val durationMs: Long,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val tokensEstimated: Boolean = false,
    val question: String? = null,
    val expectedUri: String? = null,
    val rank: Int? = null,
    val topUri: String? = null,
    val supported: Boolean? = null,
    val citedUris: List<String> = emptyList(),
    val answer: String? = null,
    val facts: Int = 0,
    val decisions: Int = 0,
    val preferences: Int = 0,
    val summary: String? = null,
    val failure: String? = null,
)

/** One suite run against one model: the verdict, the aggregates and every case. */
@Serializable
data class EvalReport(
    val runId: String,
    val suite: String,
    val kind: EvalKind,
    val axis: EvalAxis,
    val model: String,
    val provider: String,
    val embedModel: String,
    val gate: Double,
    val passed: Int,
    val total: Int,
    val ok: Boolean,
    val metrics: SuiteMetrics,
    val cases: List<CaseResult>,
    val startedAt: String,
    val durationMs: Long,
    val matrixId: String? = null,
    val chatModel: String? = null,
)

/**
 * The knobs of one run that are not its config or its suite: the grade, the
 * price table, the ledger identity and the clock. Injected (plan E7), so a
 * test can freeze time and a matrix can share one matrix id.
 */
data class EvalOptions(
    val gate: Double,
    val prices: PriceTable = PriceTable.load(),
    val matrixId: String? = null,
    val runId: String = UUID.randomUUID().toString(),
    val timeSource: TimeSource = TimeSource.Monotonic,
    val wallClock: () -> Instant = Instant::now,
)

/**
 * One suite, one model, one fresh database when the suite needs vectors.
 * Every effect is an injected parameter (plan E7): the config, the suite, the
 * [EvalOptions] (grade, prices, ledger identity, clock) — nothing reads a clock
 * inside. The temp corpus is a [Resource] ([evalCorpus]), removed on release
 * whether the run succeeds, fails or is cancelled, and the throwaway database
 * too.
 */
class EvalRunner(
    private val config: Config,
    private val suite: EvalSuite,
    private val options: EvalOptions = EvalOptions(gate = suite.gate),
) {

    /** The admin connection through the same kap bracket as everything else (plan B2): close in NonCancellable. */
    private suspend fun <T> withAdminConnection(block: (Connection) -> T): T =
        bracket(
            acquire = { DriverManager.getConnection(config.pgUrl, config.pgUser, config.pgPassword) },
            use = { conn -> Kap { block(conn) } },
            release = { it.close() },
        ).evalGraph()

    /**
     * A fresh database per run, as a [Resource] (plan B2): the corpus and its
     * vectors never leak between suites or models. The drop is deterministic
     * (not a shutdown hook: test workers may never exit cleanly) and quiet — a
     * failed drop is a stderr line, never a masked eval result.
     */
    private fun freshEvalDatabase(): Resource<String> = Resource(
        acquire = {
            val name = "agent_brain_eval_" + UUID.randomUUID().toString().replace("-", "").take(8)
            withAdminConnection { conn -> conn.createStatement().use { it.execute("CREATE DATABASE $name") } }
            name
        },
        release = { name ->
            runCatching {
                withAdminConnection { conn ->
                    conn.createStatement().use { it.execute("DROP DATABASE IF EXISTS $name WITH (FORCE)") }
                }
            }.onFailure { System.err.println("eval: could not drop $name: ${it.message}") }
        },
    )

    fun run(): EvalReport {
        suite.validate()
        val startedAt = options.wallClock()
        val mark = options.timeSource.markNow()
        val report = runBlocking {
            if (suite.kind.needsCorpus) {
                freshEvalDatabase().use { name ->
                    val evalConfig = config.copy(pgUrl = config.pgUrl.substringBeforeLast('/') + "/" + name)
                    Brain.resource(evalConfig).use { brain -> retrievalReport(brain, startedAt, mark) }
                }
            } else {
                Brain.resource(config).use { brain -> extractionReport(brain, startedAt, mark) }
            }
        }
        Database(config).use { db ->
            runBlocking { EvalLedger(db, config.embedDim).record(report) }
        }
        return report
    }

    private suspend fun retrievalReport(brain: Brain, startedAt: Instant, mark: TimeMark): EvalReport {
        brain.migrator.apply()
        // The corpus lives in a bracketed temp tree: indexed inside the bracket, removed on release.
        // macOS hands out /var/folders (a symlink of /private/var/...): normalize or the glob
        // never matches the walked paths.
        val indexed = evalCorpus().use { corpusDir ->
            brain.indexer.indexGlobs(listOf("${corpusDir.toRealPath()}/*.md"))
        }
        check(indexed.failed.isEmpty() && indexed.indexed > 0) {
            "eval: the fixture corpus did not index (${indexed.failed})"
        }
        val cases = when (suite.kind) {
            EvalKind.RECALL -> recallCases(brain)
            EvalKind.GROUNDED -> groundedCases(brain)
            EvalKind.EXTRACTION -> error("an extraction suite does not index a corpus")
        }
        // The model under test is the one the axis replaces: embeddings for
        // recall, the chat model for a composed answer (the embed model is
        // context, reported separately).
        val chatAxis = suite.kind == EvalKind.GROUNDED
        val identity = ModelIdentity(
            model = if (chatAxis) brain.chat.model else brain.provider.model,
            provider = if (chatAxis) brain.chat.name else brain.provider.name,
            embedModel = brain.provider.model,
            chatModel = brain.chat.model,
        )
        return report(cases, startedAt, mark, identity)
    }

    private suspend fun extractionReport(brain: Brain, startedAt: Instant, mark: TimeMark): EvalReport {
        val extractionCases = suite.cases.filterIsInstance<ExtractionCase>()
        val cases = extractionCases.map { extractionCase(brain, it) }
        val identity = ModelIdentity(
            model = brain.chat.model,
            provider = brain.chat.name,
            embedModel = brain.provider.model,
            chatModel = brain.chat.model,
        )
        return report(cases, startedAt, mark, identity)
    }

    /** Rank@5 under hybrid retrieval, with the top hit's support as an informational column. */
    private suspend fun recallCases(brain: Brain): List<CaseResult> =
        suite.cases.filterIsInstance<RecallCase>().map { case ->
        val mark = options.timeSource.markNow()
        val hits = brain.rag.query(QueryRequest(case.question, k = RECALL_K)).hits
        val durationMs = mark.elapsedNow().inWholeMilliseconds
        val rank = hits.indexOfFirst { it.uri.endsWith(case.uri) }.let { if (it >= 0) it + 1 else null }
        val top = hits.firstOrNull()
        val supported = if (case.needles.isEmpty()) {
            null
        } else {
            top?.let { EvalAssertions.supports(it.content, case.needles) }
        }
        CaseResult(
            id = case.id,
            ok = EvalAssertions.recall(rank),
            durationMs = durationMs,
            promptTokens = Tokens.estimate(case.question),
            tokensEstimated = true,
            question = case.question,
            expectedUri = case.uri,
            rank = rank,
            topUri = top?.uri,
            supported = supported,
            failure = if (rank == null) {
                "expected ${case.uri} in the top $RECALL_K, top hit was ${top?.uri ?: "none"}"
            } else {
                null
            },
        )
    }

    /** Retrieval plus one composed answer: a turn as the bot's caller would spend it. */
    private suspend fun groundedCases(brain: Brain): List<CaseResult> =
        suite.cases.filterIsInstance<GroundedCase>().map { case ->
        val mark = options.timeSource.markNow()
        val hits = brain.rag.query(QueryRequest(case.question, k = case.k)).hits
        val user = renderContext(case.question, hits)
        val result = try {
            brain.chat.completeJson(GROUNDED_SYSTEM, user, GROUNDED_SCHEMA)
        } catch (e: ChatException) {
            return@map CaseResult(
                id = case.id,
                ok = false,
                durationMs = mark.elapsedNow().inWholeMilliseconds,
                tokensEstimated = true,
                question = case.question,
                expectedUri = case.uri,
                failure = "chat call failed: ${e.message}",
            )
        }
        val answer = EvalAssertions.parseGrounded(result.text)
        val check = EvalAssertions.grounded(case, hits, answer)
        CaseResult(
            id = case.id,
            ok = check.ok,
            durationMs = mark.elapsedNow().inWholeMilliseconds,
            promptTokens = result.promptTokens?.toLong()
                ?: Tokens.estimate(GROUNDED_SYSTEM) + Tokens.estimate(user),
            completionTokens = result.completionTokens?.toLong() ?: Tokens.estimate(result.text),
            tokensEstimated = result.promptTokens == null || result.completionTokens == null,
            question = case.question,
            expectedUri = case.uri,
            supported = check.answerSupports,
            citedUris = check.citedUris,
            answer = answer?.answer,
            failure = check.failure,
        )
    }

    /** The production prompt, schema and parse bins: one raw call per case, no retries, so latency is the call's. */
    private suspend fun extractionCase(brain: Brain, case: ExtractionCase): CaseResult {
        val transcript = Transcript(
            eventId = "eval-${case.id}",
            sessionId = "eval",
            turnId = case.id,
            source = "eval",
            prompt = case.prompt,
            reply = case.reply,
            tools = case.tools,
            title = case.title,
        )
        val user = transcript.render(MemoryExtractor.TRANSCRIPT_CAP)
        val mark = options.timeSource.markNow()
        val result = try {
            brain.chat.completeJson(MemoryExtractor.SYSTEM_PROMPT, user, MemoryExtractor.SCHEMA)
        } catch (e: ChatException) {
            return CaseResult(
                id = case.id,
                ok = false,
                durationMs = mark.elapsedNow().inWholeMilliseconds,
                tokensEstimated = true,
                failure = "chat call failed: ${e.message}",
            )
        }
        val check = EvalAssertions.extraction(case.expect, MemoryExtractor.parseBins(result.text))
        return CaseResult(
            id = case.id,
            ok = check.ok,
            durationMs = mark.elapsedNow().inWholeMilliseconds,
            promptTokens = result.promptTokens?.toLong()
                ?: Tokens.estimate(MemoryExtractor.SYSTEM_PROMPT) + Tokens.estimate(user),
            completionTokens = result.completionTokens?.toLong() ?: Tokens.estimate(result.text),
            tokensEstimated = result.promptTokens == null || result.completionTokens == null,
            facts = check.facts,
            decisions = check.decisions,
            preferences = check.preferences,
            summary = check.summary,
            failure = check.failure,
        )
    }

    private fun report(
        cases: List<CaseResult>,
        startedAt: Instant,
        mark: TimeMark,
        identity: ModelIdentity,
    ): EvalReport {
        val durationMs = mark.elapsedNow().inWholeMilliseconds
        val passed = cases.count { it.ok }
        return EvalReport(
            runId = options.runId,
            suite = suite.name,
            kind = suite.kind,
            axis = suite.axis,
            model = identity.model,
            provider = identity.provider,
            embedModel = identity.embedModel,
            chatModel = identity.chatModel,
            gate = options.gate,
            passed = passed,
            total = cases.size,
            ok = cases.isNotEmpty() && passed.toDouble() / cases.size >= options.gate,
            metrics = SuiteMetricsCalculator.of(cases, options.prices, identity, durationMs),
            cases = cases,
            startedAt = startedAt.toString(),
            durationMs = durationMs,
            matrixId = options.matrixId,
        )
    }

    companion object {
        const val RECALL_K = 5
        const val GROUNDED_SYSTEM =
            "You answer questions about the developer's own system using ONLY the numbered context. " +
                "Return JSON only: {\"answer\": string, \"citations\": [int]}. " +
                "citations are the context numbers you actually used; never cite a number outside the context. " +
                "Answer in one short sentence. If the context lacks the answer, answer \"unknown\" and cite nothing."

        val GROUNDED_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("answer") { put("type", "string") }
                putJsonObject("citations") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "integer") }
                }
            }
            put("required", buildJsonArray { listOf("answer", "citations").forEach { add(JsonPrimitive(it)) } })
        }

        /** The numbered context the model cites by index; the index is the hit order, 1-based. */
        fun renderContext(question: String, hits: List<Hit>): String = buildString {
            append("QUESTION: ").append(question).append("\n\nCONTEXT:\n")
            if (hits.isEmpty()) append("(no context)\n")
            hits.forEachIndexed { index, hit ->
                append(index + 1).append(". [").append(hit.uri).append("] ")
                append(hit.content.replace("\n", " ")).append('\n')
            }
        }

        /**
         * The fixture corpus as a [Resource] (plan E7): the temp tree is created
         * and filled on acquire, and removed on release — success, failure or
         * cancellation — so an eval run never leaks corpus files into the
         * system temp directory. A jar cannot list a directory: the corpus
         * ships with an explicit index (same rule as migrations).
         */
        internal fun evalCorpus(): Resource<Path> = Resource(
            acquire = {
                val dir = Files.createTempDirectory("agent-brain-eval-corpus")
                val loader = EvalRunner::class.java.classLoader
                loader.getResourceAsStream("eval/corpus/index.txt")?.bufferedReader()?.use { reader ->
                    reader.readLines().filter { it.endsWith(".md") }.forEach { name ->
                        val content = loader.getResourceAsStream("eval/corpus/$name")
                            ?: error("eval: the corpus file $name is missing from the classpath")
                        content.use { Files.write(dir.resolve(name), it.readAllBytes()) }
                    }
                } ?: error("eval: the corpus index eval/corpus/index.txt is missing from the classpath")
                dir
            },
            release = { dir ->
                val removed = runCatching { dir.toFile().deleteRecursively() }.getOrElse { e ->
                    System.err.println("eval: could not remove the corpus tree $dir: ${e.message}")
                    false
                }
                if (!removed) System.err.println("eval: corpus tree $dir was not fully removed")
            },
        )

        /** The single-run artifact: header, gate, aggregates and the per-case table that matches the suite kind. */
        fun markdown(report: EvalReport): String = buildString {
            appendLine("# eval: ${report.suite} (${report.kind.label}) · ${report.model} (${report.provider})")
            appendLine()
            val matrix = report.matrixId?.let { " · matrix $it" }.orEmpty()
            appendLine("- run: ${report.runId}$matrix")
            appendLine("- started: ${report.startedAt} · ${report.durationMs} ms")
            val verdict = if (report.ok) "PASS" else "FAIL"
            appendLine("- **${report.passed}/${report.total} passed (gate ${report.gate}) → $verdict**")
            appendLine("- ${metricsLine(report)}")
            report.metrics.supportChecked?.let {
                appendLine("- citation support@1: ${report.metrics.supported}/$it top hits contain a needle")
            }
            appendLine()
            appendLine(casesMarkdown(report))
        }

        /** The latency/tokens/cost line shared by the single-run and matrix artifacts. */
        fun metricsLine(report: EvalReport): String {
            val metrics = report.metrics
            val tokens = "tokens ${metrics.promptTokens} in / ${metrics.completionTokens} out"
            val cost = if (metrics.costKnown) {
                "cost ${formatUsd(metrics.costUsd)} USD"
            } else {
                "cost unknown (no declared price for ${report.provider}:${report.model})"
            }
            val estimated = if (report.cases.any { it.tokensEstimated }) " (embedding tokens estimated)" else ""
            return "latency p50 ${metrics.p50Ms} ms · p95 ${metrics.p95Ms} ms · $tokens$estimated · $cost"
        }

        fun casesMarkdown(report: EvalReport): String = when (report.kind) {
            EvalKind.RECALL -> table(
                listOf("case", "question", "expected", "rank@5", "top hit", "support"),
                report.cases.map { row ->
                    listOf(
                        row.id,
                        row.question,
                        row.expectedUri?.substringAfterLast('/'),
                        row.rank?.toString(),
                        row.topUri?.substringAfterLast('/'),
                        row.supported?.let { if (it) "yes" else "no" },
                    )
                },
            )
            EvalKind.GROUNDED -> table(
                listOf("case", "question", "expected", "cited", "answer"),
                report.cases.map { row ->
                    listOf(
                        row.id,
                        row.question,
                        row.expectedUri?.substringAfterLast('/'),
                        row.citedUris.joinToString(",") { it.substringAfterLast('/') },
                        row.answer ?: row.failure,
                    )
                },
            )
            EvalKind.EXTRACTION -> table(
                listOf("case", "facts", "decisions", "preferences", "summary", "failure"),
                report.cases.map { row ->
                    listOf(
                        row.id,
                        row.facts.toString(),
                        row.decisions.toString(),
                        row.preferences.toString(),
                        row.summary,
                        row.failure,
                    )
                },
            )
        }

        private fun table(header: List<String>, rows: List<List<String?>>): String = buildString {
            appendLine("| ${header.joinToString(" | ")} |")
            appendLine("|${header.joinToString("|") { "---" }}|")
            for (row in rows) {
                appendLine("| ${row.joinToString(" | ") { cell(it) }} |")
            }
        }

        private fun cell(value: String?): String =
            (value ?: "-").replace("|", "\\|").replace("\n", " ").take(MAX_CELL)
    }
}

private const val MAX_CELL = 160
