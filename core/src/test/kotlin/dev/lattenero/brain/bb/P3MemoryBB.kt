package dev.lattenero.brain.bb

import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.http.ReadyResponse
import dev.lattenero.brain.memory.MemorySearchResponse
import dev.lattenero.brain.memory.MemoryStatus
import kotlinx.serialization.json.Json
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
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Tier 4, P3: a real `serve` (tailer + memory worker + real local chat
 * model) watches a bus file; a turn in which the user states a preference
 * lands as a searchable memory, observed only through HTTP and the CLI.
 */
@Tag("bb-local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class P3MemoryBB {
    private val launcher: String = System.getProperty("ab.dist") ?: fail("ab.dist not set: run through `./gradlew bbLocal`")
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val port = ServerSocket(0).use { it.localPort }
    private val base: Path = Files.createTempDirectory("agent-brain-bb-p3")
    private val busFile: Path = base.resolve("bus-events.jsonl")
    private lateinit var env: Map<String, String>
    private var server: Process? = null

    @BeforeAll
    fun up() {
        TestEnv.needPostgres()
        TestEnv.needOllama()
        env = mapOf(
            "AB_BASE" to base.toString(),
            "AB_PG_URL" to TestDatabase.fresh("bbp3"),
            "AB_PG_USER" to TestEnv.base.pgUser,
            "AB_PG_PASSWORD" to TestEnv.base.pgPassword,
            "AB_HTTP_PORT" to port.toString(),
            "AB_EMBED_PROVIDER" to "ollama",
            "AB_CHAT_PROVIDER" to "ollama",
            "AB_OLLAMA_BASE_URL" to TestEnv.base.ollamaBaseUrl,
            "AB_BUS_POLL_MS" to "200",
            "AB_MEMORY_POLL_MS" to "500",
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

    private fun post(path: String, body: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun event(id: String, kind: String, sid: String, turn: String, source: String, payload: String) =
        """{"id":"$id","ts":"2026-10-28T21:00:00.000Z","kind":"$kind","sid":"$sid","turn":"$turn","source":"$source","payload":$payload}""" + "\n"

    @Test
    fun `a turn stating a preference becomes a memory the brain can recall`() {
        val prompt = "Remember this: I only want Amazon Corretto JDKs installed through brew casks, never openjdk or temurin. Also keep answers in Spanish."
        val reply = "Noted. I will install Amazon Corretto JDKs only, through brew casks, and keep my answers in Spanish."
        Files.createDirectories(busFile.parent)
        Files.write(
            busFile,
            (event("bb-p3-prompt", "user.prompt", "ses_bb_p3", "900", "hook", """{"prompt": ${Json.encodeToString(kotlinx.serialization.serializer<String>(), prompt)}, "prompt_chars": ${prompt.length}, "cwd": "/w"}""") +
                event("bb-p3-done", "turn.completed", "ses_bb_p3", "900", "hook", """{"duration_s": 3.0, "response_chars": ${reply.length}, "response": ${Json.encodeToString(kotlinx.serialization.serializer<String>(), reply)}, "cwd": "/w"}""")).toByteArray(),
            StandardOpenOption.CREATE, StandardOpenOption.APPEND,
        )

        val status = await().alias("turn processed by the memory worker").atMost(Duration.ofSeconds(240)).pollInterval(Duration.ofSeconds(1)).ignoreExceptions()
            .until({ json.decodeFromString<MemoryStatus>(get("/memory/status").body()) }, { it.processedTurns >= 1 })
        assertThat(status.quarantined).withFailMessage("the real model's output was quarantined: %s", get("/memory/quarantine").body()).isZero
        assertThat(status.memories).isGreaterThanOrEqualTo(1)
        assertThat(status.chatModel).isEqualTo(TestEnv.base.chatModel)

        val found = json.decodeFromString<MemorySearchResponse>(post("/memory/search", """{"query": "which JDK distribution does the user want", "k": 5}""").body())
        assertThat(found.memories).isNotEmpty
        val all = found.memories.joinToString(" ") { it.content }.lowercase()
        assertThat(all).containsAnyOf("corretto", "jdk", "brew")
        assertThat(found.memories.first().sessionId).isEqualTo("ses_bb_p3")

        val out = base.resolve("memory-status.out").toFile()
        val cli = ProcessBuilder(launcher, "memory", "status").apply { environment().putAll(env) }
            .redirectOutput(out).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        assertThat(cli.waitFor(60, TimeUnit.SECONDS)).isTrue
        assertThat(cli.exitValue()).isZero
        val cliStatus = json.decodeFromString<MemoryStatus>(out.readText())
        assertThat(cliStatus.memories).isEqualTo(status.memories)
    }
}
