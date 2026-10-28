package dev.lattenero.brain.http

import dev.lattenero.brain.Brain
import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.bus.BusStatus
import dev.lattenero.brain.embeddings.FakeProvider
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files

@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BusStatusTest {
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeAll
    fun up() = TestEnv.needPostgres()

    @Test
    fun `bus status reflects the file, the offset and the database after a drain`() = testApplication {
        val config = TestDatabase.config("busstatus")
        Brain(config, FakeProvider(config.embedDim)).use { brain ->
            brain.migrator.apply()
            Files.createDirectories(config.busFile.parent)
            val fixture = javaClass.classLoader.getResourceAsStream("bus/sample-bot-publisher.jsonl")!!.readAllBytes()
            Files.write(config.busFile, fixture)
            brain.bus.drain()
            application { brainModule(brain.config, brain.readiness, brain.bus) }

            val response = client.get("/bus/status")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val status = json.decodeFromString<BusStatus>(response.bodyAsText())
            assertThat(status.transport).isEqualTo("file")
            assertThat(status.fileExists).isTrue
            assertThat(status.fileBytes).isEqualTo(fixture.size.toLong())
            assertThat(status.offset).isEqualTo(fixture.size.toLong())
            assertThat(status.lagBytes).isZero
            assertThat(status.inserted).isEqualTo(6)
            assertThat(status.dbEvents).isEqualTo(6)
            assertThat(status.lastKind).isEqualTo("turn.failed")
        }
    }
}
