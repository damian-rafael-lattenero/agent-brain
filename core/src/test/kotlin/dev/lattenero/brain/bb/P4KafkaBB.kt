package dev.lattenero.brain.bb

import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.bus.BusStatus
import dev.lattenero.brain.http.ReadyResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Tier 4, P4: a real `serve` consuming from Redpanda, a real `bridge --once`
 * producing the bus file into the topic; the same events land in Postgres
 * through the Kafka transport, observed only through HTTP and the CLI.
 */
@Tag("bb-local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class P4KafkaBB {
    private val launcher: String = System.getProperty("ab.dist") ?: fail("ab.dist not set: run through `./gradlew bbLocal`")
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val port = ServerSocket(0).use { it.localPort }
    private val base: Path = Files.createTempDirectory("agent-brain-bb-p4")
    private val suffix = UUID.randomUUID().toString().take(8)
    private lateinit var env: Map<String, String>
    private var server: Process? = null

    @BeforeAll
    fun up() {
        TestEnv.needPostgres()
        TestEnv.needOllama()
        TestEnv.needKafka()
        env = mapOf(
            "AB_BASE" to base.toString(),
            "AB_PG_URL" to TestDatabase.fresh("bbp4"),
            "AB_PG_USER" to TestEnv.base.pgUser,
            "AB_PG_PASSWORD" to TestEnv.base.pgPassword,
            "AB_HTTP_PORT" to port.toString(),
            "AB_EMBED_PROVIDER" to "ollama",
            "AB_OLLAMA_BASE_URL" to TestEnv.base.ollamaBaseUrl,
            "AB_BUS_TRANSPORT" to "kafka",
            "AB_KAFKA_BOOTSTRAP" to TestEnv.base.kafkaBootstrap,
            "AB_KAFKA_TOPIC" to "agent-brain-bb-$suffix",
            "AB_KAFKA_GROUP" to "agent-brain-bb-$suffix",
            "AB_MEMORY_WORKER" to "false",
        )
        server = ProcessBuilder(launcher, "serve").apply { environment().putAll(env) }
            .redirectOutput(base.resolve("serve.log").toFile()).redirectErrorStream(true).start()
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).ignoreExceptions()
            .until({ get("/ready") }, { it.statusCode() == 200 })
        assertThat(json.decodeFromString<ReadyResponse>(get("/ready").body()).ok).isTrue
    }

    @AfterAll
    fun down() {
        server?.let { process ->
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    private fun get(path: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofString())

    @Test
    fun `the bus file bridged into kafka lands in the database through the consumer`() {
        val busFile = base.resolve("bus-events.jsonl")
        Files.write(busFile, javaClass.classLoader.getResourceAsStream("bus/sample-bot-publisher.jsonl")!!.readAllBytes())

        val out = base.resolve("bridge.out").toFile()
        val bridge = ProcessBuilder(launcher, "bridge", "--once").apply { environment().putAll(env) }
            .redirectOutput(out).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        assertThat(bridge.waitFor(120, TimeUnit.SECONDS)).isTrue
        assertThat(bridge.exitValue()).isZero
        val produced = json.parseToJsonElement(out.readText()).jsonObject
        assertThat(produced["produced"]!!.jsonPrimitive.content.toInt()).isEqualTo(6)
        assertThat(produced["topic"]!!.jsonPrimitive.content).isEqualTo("agent-brain-bb-$suffix")

        val status = await().alias("6 events consumed from kafka").atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).ignoreExceptions()
            .until({ json.decodeFromString<BusStatus>(get("/bus/status").body()) }, { it.dbEvents >= 6 })
        assertThat(status.transport).isEqualTo("kafka")
        assertThat(status.inserted).isEqualTo(6)
        assertThat(status.kafkaCommits).isGreaterThanOrEqualTo(1)
        // Kafka orders per KEY, not globally: no assertion on lastKind across partitions.
        assertThat(base.resolve("serve.log").toFile().readText()).contains("bus consumer on")
    }
}
