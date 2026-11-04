package dev.lattenero.brain.http

import dev.lattenero.brain.Brain
import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.embeddings.FakeProvider
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files

/**
 * The endpoint is the same live path Prometheus scrapes: the real routes,
 * the real tailer/ingest counters, a real database; only the embedding
 * model is fake. The drain uses the bot publisher's fixture so the numbers
 * asserted below are the fixture's own (6 events, 0 malformed).
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MetricsEndpointTest {

    @BeforeAll
    fun up() = TestEnv.needPostgres()

    @Test
    fun `metrics projects the live bus, rag and memory snapshots`() = testApplication {
        val config = TestDatabase.config("metrics")
        Brain.resource(config, FakeProvider(config.embedDim)).use { brain ->
            brain.migrator.apply()
            Files.createDirectories(config.busFile.parent)
            val fixture = javaClass.classLoader.getResourceAsStream("bus/sample-bot-publisher.jsonl")!!.readAllBytes()
            Files.write(config.busFile, fixture)
            brain.bus.drain()
            application { brainModule(brain.config, brain.readiness, brain.bus, brain.rag, null, brain.memory) }

            val response = client.get("/metrics")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.headers[HttpHeaders.ContentType]).startsWith("text/plain; version=0.0.4")
            val body = response.bodyAsText()
            assertThat(body).contains("brain_bus_events_seen_total{transport=\"file\"} 6")
            assertThat(body).contains("brain_bus_events_inserted_total{transport=\"file\"} 6")
            assertThat(body).contains("brain_bus_events_duplicates_total{transport=\"file\"} 0")
            assertThat(body).contains("brain_bus_lines_malformed_total{transport=\"file\"} 0")
            assertThat(body).contains("brain_bus_file_present{transport=\"file\"} 1")
            assertThat(body).contains("brain_bus_file_bytes{transport=\"file\"} ${fixture.size}")
            assertThat(body).contains("brain_bus_offset_bytes{transport=\"file\"} ${fixture.size}")
            assertThat(body).contains("brain_bus_lag_bytes{transport=\"file\"} 0")
            assertThat(body).contains("brain_bus_db_events{transport=\"file\"} 6")
            // no bus process was started in-process: liveness is UNKNOWN, not zero
            assertThat(body).doesNotContain("brain_bus_restarts_total")
            assertThat(body).doesNotContain("brain_bus_heartbeat_age_seconds")
            assertThat(body).contains("brain_bus_last_event_timestamp_seconds{transport=\"file\"} ")
            assertThat(body).contains("brain_rag_documents 0")
            assertThat(body).contains("brain_rag_chunks 0")
            assertThat(body).contains("brain_memory_memories 0")
            // the fixture's own turn.completed is pending: the extraction never ran in-process
            assertThat(body).contains("brain_memory_pending_turns 1")
            assertThat(body).contains("brain_memory_breaker_state{state=\"Closed\"} 1")
        }
    }

    @Test
    fun `metrics answers with an empty exposition when nothing is wired`() = testApplication {
        val config = TestDatabase.config("metricsbare")
        Brain.resource(config, FakeProvider(config.embedDim)).use { brain ->
            application { brainModule(brain.config, brain.readiness) }
            val response = client.get("/metrics")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.bodyAsText()).isEmpty()
        }
    }
}
