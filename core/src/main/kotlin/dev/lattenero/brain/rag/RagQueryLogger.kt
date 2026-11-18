package dev.lattenero.brain.rag

import dev.lattenero.brain.db.Database
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime

/**
 * The application-specific eval's raw material: every REAL query the HTTP
 * boundary serves lands here (query, served chunk ids, the latency the caller
 * felt). The in-process eval CLI never touches this logger — its queries grade
 * against ground truth; production queries have no ground truth and are graded
 * by the judge over this evidence ([dev.lattenero.brain.eval.UsageEval]).
 *
 * Logging is best-effort BY DESIGN: a failed insert is a stderr line and the
 * query still answers — an observability write must never break the serve
 * path it observes.
 */
class RagQueryLogger(private val db: Database) {
    private val json = Json

    /**
     * The catch is generic ON PURPOSE: the law is "never break the serve path",
     * and any failure shape (SQL state, pool exhaustion, serialization) must
     * degrade to a stderr line. Cancellation still rethrows above.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun log(query: String, hits: List<Hit>, latencyMs: Long) {
        if (hits.isEmpty()) return
        try {
            val chunkIds: String = json.encodeToString(ListSerializer(Long.serializer()), hits.map { it.chunkId })
            val topUris: String = json.encodeToString(ListSerializer(String.serializer()), hits.map { it.uri })
            db.withDriver { brainDb ->
                brainDb.ragQueryLogQueries.insertRagQueryLog(
                    query = query,
                    chunkIds = chunkIds,
                    topUris = topUris,
                    latencyMs = latencyMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    at = OffsetDateTime.now(),
                )
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            System.err.println("rag query log: the insert failed (${e.message}) — the query was still served")
        }
    }
}
