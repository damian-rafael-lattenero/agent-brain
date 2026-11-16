package dev.lattenero.brain.eval

import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.Migrator
import dev.lattenero.brain.db.sq.Eval_runs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.sql.Connection
import java.util.UUID

/** One persisted run, in the shape the CLI and `/eval/latest` answer with. */
@Serializable
data class StoredRun(
    val runId: String? = null,
    val matrixId: String? = null,
    val suite: String,
    val kind: String,
    val axis: String,
    val model: String,
    val provider: String,
    val passed: Int,
    val total: Int,
    val gate: Double,
    val ok: Boolean,
    val p50Ms: Int? = null,
    val p95Ms: Int? = null,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val costUsd: Double = 0.0,
    val costKnown: Boolean = true,
    val startedAt: String? = null,
    val ts: String? = null,
    val detail: JsonElement? = null,
)

/**
 * The ledger: `eval_runs` in the MAIN database, where every other brain fact
 * lives. A run is one row (verdict, aggregates, the whole report as `detail`);
 * a matrix is the rows that share a `matrix_id`. Reads are plain SQL, so the
 * bot can ask "what did the last extraction matrix say?" without running an
 * eval.
 */
class EvalLedger(private val db: Database, private val embedDim: Int) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Applies pending migrations then inserts the run; the row is the durable artifact. */
    suspend fun record(report: EvalReport) {
        Migrator(db, embedDim).apply()
        db.withConnection { conn ->
            conn.prepareStatement(INSERT).use { ps ->
                var i = 1
                ps.setString(i++, report.suite)
                ps.setString(i++, report.model)
                ps.setString(i++, report.provider)
                ps.setInt(i++, report.passed)
                ps.setInt(i++, report.total)
                ps.setDouble(i++, report.metrics.costUsd)
                ps.setString(i++, json.encodeToString(EvalReport.serializer(), report))
                ps.setString(i++, report.runId)
                ps.setString(i++, report.matrixId)
                ps.setString(i++, report.kind.label)
                ps.setString(i++, report.axis.label)
                ps.setDouble(i++, report.gate)
                ps.setBoolean(i++, report.ok)
                ps.setLong(i++, report.metrics.promptTokens)
                ps.setLong(i++, report.metrics.completionTokens)
                ps.setBoolean(i++, report.metrics.costKnown)
                ps.setInt(i++, report.metrics.p50Ms.toInt())
                ps.setInt(i++, report.metrics.p95Ms.toInt())
                ps.setInt(i++, report.durationMs.toInt())
                ps.setString(i, report.startedAt)
                ps.executeUpdate()
            }
        }
    }

    /** The most recent runs, newest first; [matrix] answers the rows of the newest matrix instead. */
    suspend fun latest(suite: String? = null, matrix: Boolean = false, limit: Int = DEFAULT_LIMIT): List<StoredRun> {
        val capped = limit.coerceIn(1, MAX_LIMIT).toLong()
        return db.withDriver { brainDb ->
            if (matrix) {
                val matrixId = brainDb.evalRunsQueries.newestMatrixId().executeAsOneOrNull()
                    ?: return@withDriver emptyList()
                // ::uuid cast typed the bind as UUID: same value, same SQL semantics.
                brainDb.evalRunsQueries.latestOfMatrix(UUID.fromString(matrixId), capped).executeAsList().map(::row)
            } else if (suite.isNullOrBlank()) {
                brainDb.evalRunsQueries.latestAll(capped).executeAsList().map(::row)
            } else {
                brainDb.evalRunsQueries.latestOfSuite(suite, capped).executeAsList().map(::row)
            }
        }
    }

    private fun row(r: Eval_runs): StoredRun = StoredRun(
        runId = r.run_id,
        matrixId = r.matrix_id,
        suite = r.suite,
        kind = r.kind,
        axis = r.axis,
        model = r.model,
        provider = r.provider,
        passed = r.passed,
        total = r.total,
        gate = r.gate,
        ok = r.ok,
        p50Ms = r.p50_ms,
        p95Ms = r.p95_ms,
        promptTokens = r.tokens_prompt,
        completionTokens = r.tokens_completion,
        costUsd = r.cost_usd.toDouble(),
        costKnown = r.cost_known,
        startedAt = r.started_at?.toInstant()?.toString(),
        ts = r.ts.toInstant().toString(),
        detail = runCatching { json.parseToJsonElement(r.detail) }.getOrNull(),
    )

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 200

        private val INSERT = """
            INSERT INTO eval_runs (suite, model, provider, passed, total, cost_usd, detail,
              run_id, matrix_id, kind, axis, gate, ok, tokens_prompt, tokens_completion,
              cost_known, p50_ms, p95_ms, duration_ms, started_at)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz)
        """.trimIndent()
    }
}

/** The terminal view of the ledger: the same table the markdown artifacts show, for past runs. */
object EvalLedgerMarkdown {
    fun render(runs: List<StoredRun>): String = buildString {
        appendLine("| suite | model | pass/total | gate | p50 ms | p95 ms | tokens in | tokens out | cost USD | ts |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|")
        for (run in runs) {
            val cost = if (run.costKnown) formatUsd(run.costUsd) else "unknown"
            appendLine(
                "| ${run.suite} | ${run.model} | ${run.passed}/${run.total} | ${run.gate} | " +
                    "${run.p50Ms ?: "-"} | ${run.p95Ms ?: "-"} | ${run.promptTokens} | " +
                    "${run.completionTokens} | $cost | ${run.ts ?: "-"} |",
            )
        }
    }
}
