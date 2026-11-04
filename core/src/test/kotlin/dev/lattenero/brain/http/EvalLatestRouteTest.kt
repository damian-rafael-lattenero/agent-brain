package dev.lattenero.brain.http

import dev.lattenero.brain.Brain
import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.embeddings.FakeProvider
import dev.lattenero.brain.eval.CaseResult
import dev.lattenero.brain.eval.EvalAxis
import dev.lattenero.brain.eval.EvalKind
import dev.lattenero.brain.eval.EvalLedger
import dev.lattenero.brain.eval.EvalReport
import dev.lattenero.brain.eval.SuiteMetrics
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID

/** `/eval/latest` is the bot's read path for "what did the last eval say?": no eval run, one query. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EvalLatestRouteTest {

    @BeforeAll
    fun up() = TestEnv.needPostgres()

    private fun report(model: String, matrixId: String? = null, passed: Int = 4) = EvalReport(
        runId = UUID.randomUUID().toString(),
        suite = "extraction-core",
        kind = EvalKind.EXTRACTION,
        axis = EvalAxis.CHAT,
        model = model,
        provider = "ollama",
        embedModel = "nomic-embed-text",
        gate = 0.6,
        passed = passed,
        total = 6,
        ok = passed.toDouble() / 6 >= 0.6,
        metrics = SuiteMetrics(
            p50Ms = 1_200, p95Ms = 3_000, promptTokens = 900, completionTokens = 80,
            costUsd = 0.0, costKnown = true, durationMs = 7_200,
        ),
        cases = listOf(CaseResult("one", true, 1_200)),
        startedAt = Instant.parse("2026-11-04T02:00:00Z").toString(),
        durationMs = 7_200,
        matrixId = matrixId,
        chatModel = model,
    )

    @Test
    fun `the route answers the last runs and the last matrix`() = testApplication {
        val config = TestDatabase.config("evalroute")
        val matrixId = UUID.randomUUID().toString()
        Brain.resource(config, FakeProvider(config.embedDim)).use { brain ->
            brain.migrator.apply()
            runBlocking {
                EvalLedger(brain.db, config.embedDim).record(report("qwen2.5:1.5b", matrixId = matrixId, passed = 3))
                EvalLedger(brain.db, config.embedDim).record(report("qwen2.5:3b", matrixId = matrixId, passed = 5))
            }
            application { brainModule(brain.config, brain.readiness, evalLedger = brain.evalLedger) }

            val latest = client.get("/eval/latest?suite=extraction-core")
            assertThat(latest.status).isEqualTo(HttpStatusCode.OK)
            val body = latest.bodyAsText()
            assertThat(body).contains("\"model\": \"qwen2.5:3b\"").contains("\"p50Ms\": 1200").contains("\"costKnown\": true")

            val matrix = client.get("/eval/latest?matrix=true")
            assertThat(matrix.status).isEqualTo(HttpStatusCode.OK)
            assertThat(matrix.bodyAsText()).contains("\"matrixId\": \"$matrixId\"").contains("qwen2.5:1.5b")
        }
    }

    @Test
    fun `the route is absent when nothing is wired`() = testApplication {
        val config = TestDatabase.config("evalroutenone")
        Brain.resource(config, FakeProvider(config.embedDim)).use { brain ->
            application { brainModule(brain.config, brain.readiness) }
            assertThat(client.get("/eval/latest").status).isEqualTo(HttpStatusCode.NotFound)
        }
    }
}
