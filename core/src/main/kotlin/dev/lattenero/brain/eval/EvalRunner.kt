package dev.lattenero.brain.eval

import dev.lattenero.brain.Brain
import dev.lattenero.brain.config.Config
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.Migrator
import dev.lattenero.brain.rag.QueryRequest
import kap.Kap
import kap.Resource
import kap.bracket
import kap.evalGraph
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking

@Serializable
data class EvalQuestion(val question: String, val uri: String)

@Serializable
data class EvalRow(val question: String, val expectedUri: String, val rank: Int?, val topUri: String?, val ok: Boolean)

@Serializable
data class EvalReport(
    val suite: String,
    val provider: String,
    val embedModel: String,
    val passed: Int,
    val total: Int,
    val gate: Double,
    val ok: Boolean,
    val rows: List<EvalRow>,
    val startedAt: String,
    val durationMs: Long,
)

/**
 * The recall gate. Every effect is an injected parameter (plan E7): the
 * config, the questions, the clock ([timeSource] for the duration, [wallClock]
 * for the ledger's started-at) — nothing reads `System.currentTimeMillis`
 * inside — and the temp corpus is a [Resource] ([evalCorpus]), removed on
 * release whether the run succeeds, fails or is cancelled.
 */
class EvalRunner(
    private val config: Config,
    private val questions: List<EvalQuestion>,
    private val gate: Double,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val wallClock: () -> Instant = Instant::now,
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
     * vectors never leak between suites or providers. The drop is
     * deterministic (not a shutdown hook: test workers may never exit
     * cleanly) and quiet — a failed drop is a stderr line, never a masked
     * eval result.
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
        val startedAt = wallClock()
        val mark = timeSource.markNow()
        val report = runBlocking {
            freshEvalDatabase().use { dbName ->
                val evalConfig = config.copy(pgUrl = config.pgUrl.substringBeforeLast('/') + "/" + dbName)
                Brain.resource(evalConfig).use { brain -> evaluate(brain, startedAt, mark) }
            }
        }
        persist(config, report)
        return report
    }

    private suspend fun evaluate(brain: Brain, startedAt: Instant, mark: TimeMark): EvalReport {
        brain.migrator.apply()
        // The corpus lives in a bracketed temp tree: indexed inside the bracket, removed on release.
        // macOS hands out /var/folders (a symlink of /private/var/...): normalize or the glob
        // never matches the walked paths.
        val indexed = evalCorpus().use { corpusDir ->
            brain.indexer.indexGlobs(listOf("${corpusDir.toRealPath()}/*.md"))
        }
        check(indexed.failed.isEmpty() && indexed.indexed > 0) { "eval: the fixture corpus did not index (${indexed.failed})" }

        val rows = questions.map { q ->
            val uris = brain.rag.query(QueryRequest(q.question, k = 5)).hits.map { it.uri }
            val rank = uris.indexOfFirst { it.endsWith(q.uri) }.let { if (it >= 0) it + 1 else null }
            EvalRow(q.question, q.uri, rank, uris.firstOrNull(), rank != null)
        }
        val passed = rows.count { it.ok }
        return EvalReport(
            suite = "recall-fixture",
            provider = brain.provider.name,
            embedModel = brain.provider.model,
            passed = passed,
            total = rows.size,
            gate = gate,
            ok = passed.toDouble() / rows.size >= gate,
            rows = rows,
            startedAt = startedAt.toString(),
            durationMs = mark.elapsedNow().inWholeMilliseconds,
        )
    }

    /** The run's ledger lives in the MAIN database, where every other brain fact lives. */
    private fun persist(target: Config, report: EvalReport) {
        Database(target).use { db ->
            runBlocking {
                Migrator(db, target.embedDim).apply()   // the ledger table is idempotent to ensure
                db.withConnection { conn ->
                    conn.prepareStatement(
                        """INSERT INTO eval_runs (suite, model, provider, passed, total, cost_usd, detail)
                           VALUES (?, ?, ?, ?, ?, 0, ?::jsonb)""",
                    ).use { ps ->
                        ps.setString(1, report.suite)
                        ps.setString(2, report.embedModel)
                        ps.setString(3, report.provider)
                        ps.setInt(4, report.passed)
                        ps.setInt(5, report.total)
                        ps.setString(6, Json.encodeToString(ListSerializer(EvalRow.serializer()), report.rows))
                        ps.executeUpdate()
                    }
                }
            }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

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

        fun loadQuestions(path: Path): List<EvalQuestion> {
            val parsed = json.decodeFromString(ListSerializer(EvalQuestion.serializer()), Files.readString(path))
            require(parsed.isNotEmpty()) { "no eval questions in $path" }
            return parsed
        }

        fun markdown(report: EvalReport): String = buildString {
            appendLine("# eval: ${report.suite} · ${report.embedModel} (${report.provider})")
            appendLine()
            appendLine("- started: ${report.startedAt} · ${report.durationMs} ms")
            appendLine("- **${report.passed}/${report.total} passed (gate ${report.gate}) → ${if (report.ok) "PASS" else "FAIL"}**")
            appendLine()
            appendLine("| question | expected | rank@5 | top hit |")
            appendLine("|---|---|---|---|")
            for (row in report.rows) {
                appendLine("| ${row.question.replace("|", "\\|")} | ${row.expectedUri.substringAfterLast('/')} | ${row.rank ?: "-"} | ${(row.topUri ?: "-").substringAfterLast('/')} |")
            }
        }
    }
}

