package dev.lattenero.brain.bb

import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.http.ReadyResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Tier 4, P0: the installed distribution is a black box. A real `serve`
 * process against the real Postgres and the real Ollama, probed only
 * through its HTTP port and its CLI; nothing in-process, nothing mocked.
 */
@Tag("bb-local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class P0StackHealthcheckBB {
    private val launcher: String = System.getProperty("ab.dist") ?: fail("ab.dist not set: run through `./gradlew bbLocal`")
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val port = ServerSocket(0).use { it.localPort }
    private val base = Files.createTempDirectory("agent-brain-bb")
    private lateinit var env: Map<String, String>
    private var server: Process? = null
    private val serverLog = base.resolve("serve.log").toFile()

    @BeforeAll
    fun up() {
        TestEnv.needPostgres()
        TestEnv.needOllama()
        assertThat(File(launcher)).withFailMessage("launcher missing: %s", launcher).exists()
        env = mapOf(
            "AB_BASE" to base.toString(),
            "AB_PG_URL" to TestDatabase.fresh("bb"),
            "AB_PG_USER" to TestEnv.base.pgUser,
            "AB_PG_PASSWORD" to TestEnv.base.pgPassword,
            "AB_HTTP_PORT" to port.toString(),
            "AB_EMBED_PROVIDER" to "ollama",
            "AB_OLLAMA_BASE_URL" to TestEnv.base.ollamaBaseUrl,
        )
    }

    @AfterAll
    fun down() {
        server?.let { process ->
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    private fun command(vararg args: String): ProcessBuilder =
        ProcessBuilder(launcher, *args).apply { environment().putAll(env) }

    private fun runCli(vararg args: String): Pair<Int, String> {
        val stdout = base.resolve("cli-${args.first()}.out").toFile()
        val process = command(*args).redirectOutput(stdout).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        assertThat(process.waitFor(90, TimeUnit.SECONDS)).withFailMessage("cli %s hung", args.toList()).isTrue
        return process.exitValue() to stdout.readText()
    }

    private fun get(path: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofString())

    @Test
    fun `the empty stack comes up green end to end and the cli speaks clean json`() {
        server = command("serve").redirectOutput(serverLog).redirectErrorStream(true).start()

        val ready = await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).ignoreExceptions().until(
            { get("/ready") },
            { it.statusCode() == 200 },
        )
        val report = json.decodeFromString<ReadyResponse>(ready.body())
        assertThat(report.ok).isTrue
        assertThat(report.checks.db.applied).isGreaterThanOrEqualTo(1)
        assertThat(report.checks.db.pending).isEmpty()
        assertThat(report.checks.db.embedDim).isEqualTo(768)
        assertThat(report.checks.embed.provider).isEqualTo("ollama")
        assertThat(report.checks.embed.model).isEqualTo("nomic-embed-text")
        assertThat(report.checks.embed.ok).withFailMessage(report.checks.embed.detail).isTrue
        assertThat(serverLog.readText()).contains("migrations applied: 0001_init.sql")

        val (healthExit, healthOut) = runCli("health", "--url", "http://127.0.0.1:$port/ready")
        assertThat(healthExit).isZero
        assertThat(healthOut.trimStart()).startsWith("{")

        val (embedExit, embedOut) = runCli("embed", "hello from the black box")
        assertThat(embedExit).isZero
        assertThat(embedOut.trimStart()).withFailMessage("stdout must be pure JSON, got: %s", embedOut.take(80)).startsWith("{")
        val embedded = json.parseToJsonElement(embedOut).jsonObject
        assertThat(embedded["provider"]!!.jsonPrimitive.content).isEqualTo("ollama")
        val vector = embedded["vectors"]!!.jsonArray.single().jsonObject
        assertThat(vector["dim"]!!.jsonPrimitive.content).isEqualTo("768")

        val (statusExit, statusOut) = runCli("migrate", "--status")
        assertThat(statusExit).isZero
        assertThat(json.parseToJsonElement(statusOut).jsonObject["pending"]!!.jsonArray).isEmpty()
    }
}
