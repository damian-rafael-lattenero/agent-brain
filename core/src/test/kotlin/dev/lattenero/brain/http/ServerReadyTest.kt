package dev.lattenero.brain.http

import dev.lattenero.brain.Brain
import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.VERSION
import dev.lattenero.brain.embeddings.BaseProvider
import dev.lattenero.brain.embeddings.FakeProvider
import dev.lattenero.brain.embeddings.ProviderUnavailable
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** Real database, real routes, in-process server; only the embedding model is fake. */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServerReadyTest {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var sharedConfig: dev.lattenero.brain.config.Config

    @BeforeAll
    fun up() {
        TestEnv.needPostgres()
        sharedConfig = TestDatabase.config("server")
    }

    @Test
    fun `health answers with the version and a redacted config`() = testApplication {
        val secret = "s3cret-that-must-never-leak"
        Brain(sharedConfig.copy(pgPassword = secret, glmApiKey = "sk-$secret"), FakeProvider(768)).use { brain ->
            application { brainModule(brain.config, brain.readiness) }
            val response = client.get("/health")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertThat(body["version"]!!.jsonPrimitive.content).isEqualTo(VERSION)
            assertThat(body["config"]!!.jsonObject["pg_password_set"]!!.jsonPrimitive.content).isEqualTo("true")
            assertThat(body["config"]!!.jsonObject["glm"]!!.jsonObject["key_set"]!!.jsonPrimitive.content).isEqualTo("true")
            assertThat(response.bodyAsText()).doesNotContain(secret)
        }
    }

    @Test
    fun `ready is green once migrations are applied and the provider embeds`() = testApplication {
        Brain(sharedConfig, FakeProvider(768)).use { brain ->
            brain.migrator.apply()
            application { brainModule(brain.config, brain.readiness) }
            val response = client.get("/ready")
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val report = json.decodeFromString<ReadyResponse>(response.bodyAsText())
            assertThat(report.ok).isTrue
            assertThat(report.checks.db.applied).isGreaterThanOrEqualTo(1)
            assertThat(report.checks.db.pending).isEmpty()
            assertThat(report.checks.db.embedDim).isEqualTo(768)
            assertThat(report.checks.embed.provider).isEqualTo("fake")
            assertThat(report.checks.embed.ok).isTrue
        }
    }

    @Test
    fun `ready is 503 degraded when the provider is down, with the reason`() = testApplication {
        val down = object : BaseProvider() {
            override val name = "fake"
            override val model = "down"
            override val dim = 768
            override suspend fun embedAll(texts: List<String>): List<FloatArray> = throw ProviderUnavailable("model server offline")
        }
        Brain(sharedConfig, down).use { brain ->
            brain.migrator.apply()
            application { brainModule(brain.config, brain.readiness) }
            val response = client.get("/ready")
            assertThat(response.status).isEqualTo(HttpStatusCode.ServiceUnavailable)
            val report = json.decodeFromString<ReadyResponse>(response.bodyAsText())
            assertThat(report.status).isEqualTo(ReadyResponse.STATUS_DEGRADED)
            assertThat(report.checks.db.ok).isTrue
            assertThat(report.checks.embed.ok).isFalse
            assertThat(report.checks.embed.detail).contains("model server offline")
        }
    }

    @Test
    fun `ready is 503 when the database is unreachable, and says so`() = testApplication {
        val config = sharedConfig.copy(pgUrl = "jdbc:postgresql://127.0.0.1:1/nowhere?connectTimeout=1&loginTimeout=1")
        Brain(config, FakeProvider(768)).use { brain ->
            application { brainModule(brain.config, brain.readiness) }
            val response = client.get("/ready")
            assertThat(response.status).isEqualTo(HttpStatusCode.ServiceUnavailable)
            val report = json.decodeFromString<ReadyResponse>(response.bodyAsText())
            assertThat(report.checks.db.ok).isFalse
            assertThat(report.checks.db.detail).isNotBlank
            assertThat(report.checks.embed.ok).isTrue
        }
    }
}
