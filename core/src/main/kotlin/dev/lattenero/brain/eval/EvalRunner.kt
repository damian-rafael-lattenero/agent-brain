package dev.lattenero.brain.eval

import dev.lattenero.brain.Brain
import dev.lattenero.brain.config.Config
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.Migrator
import dev.lattenero.brain.rag.QueryRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
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

class EvalRunner(private val config: Config, private val questions: List<EvalQuestion>, private val gate: Double) {
    /** A fresh database per run: the corpus and its vectors never leak between suites or providers. */
    private fun freshEvalDatabase(): String {
        val name = "agent_brain_eval_" + UUID.randomUUID().toString().replace("-", "").take(8)
        DriverManager.getConnection(config.pgUrl, config.pgUser, config.pgPassword).use { conn ->
            conn.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        return name
    }

    private fun dropQuietly(name: String) {
        try {
            DriverManager.getConnection(config.pgUrl, config.pgUser, config.pgPassword).use { conn ->
                conn.createStatement().use { it.execute("DROP DATABASE IF EXISTS $name WITH (FORCE)") }
            }
        } catch (e: Exception) {
            System.err.println("eval: could not drop $name: ${e.message}")
        }
    }

    fun run(): EvalReport {
        val started = System.currentTimeMillis()
        val dbName = freshEvalDatabase()
        val evalConfig = config.copy(pgUrl = config.pgUrl.substringBeforeLast('/') + "/" + dbName)
        val report = try {
            Brain(evalConfig).use { brain -> runBlocking { evaluate(brain, started) } }
        } finally {
            dropQuietly(dbName)   // deterministic, not a shutdown hook: test workers may never exit cleanly
        }
        persist(config, report)
        return report
    }

    private suspend fun evaluate(brain: Brain, started: Long): EvalReport {
        brain.migrator.apply()
        val corpusDir = Files.createTempDirectory("agent-brain-eval-corpus")
        val loader = javaClass.classLoader
        // A jar cannot list a directory: the corpus ships with an explicit index (same rule as migrations).
        loader.getResourceAsStream("eval/corpus/index.txt")!!.bufferedReader().readLines().filter { it.endsWith(".md") }.forEach { name ->
            Files.write(corpusDir.resolve(name), loader.getResourceAsStream("eval/corpus/$name")!!.readAllBytes())
        }
        // macOS hands out /var/folders (a symlink of /private/var/...): normalize or the glob never matches the walked paths.
        val indexed = brain.indexer.indexGlobs(listOf("${corpusDir.toRealPath()}/*.md"))
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
            startedAt = Instant.ofEpochMilli(started).toString(),
            durationMs = System.currentTimeMillis() - started,
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

