package dev.lattenero.brain.bb

import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.http.ReadyResponse
import dev.lattenero.brain.rag.IndexReport
import dev.lattenero.brain.rag.QueryResponse
import dev.lattenero.brain.rag.RagStats
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
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Tier 4, P2: the fixture corpus is indexed through the CLI of the installed
 * distribution with the real model, then questioned through the HTTP port
 * and the CLI. Nothing in-process.
 */
@Tag("bb-local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class P2RagBB {
    private val launcher: String = System.getProperty("ab.dist") ?: fail("ab.dist not set: run through `./gradlew bbLocal`")
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val port = ServerSocket(0).use { it.localPort }
    private val base: Path = Files.createTempDirectory("agent-brain-bb-p2")
    private val corpusDir: Path = Files.createTempDirectory("agent-brain-bb-p2-corpus")
    private lateinit var env: Map<String, String>
    private var server: Process? = null

    @BeforeAll
    fun up() {
        TestEnv.needPostgres()
        TestEnv.needOllama()
        val loader = javaClass.classLoader
        loader.getResourceAsStream("corpus")!!.bufferedReader().readLines().filter { it.endsWith(".md") }.forEach { name ->
            Files.write(corpusDir.resolve(name), loader.getResourceAsStream("corpus/$name")!!.readAllBytes())
        }
        env = mapOf(
            "AB_BASE" to base.toString(),
            "AB_PG_URL" to TestDatabase.fresh("bbp2"),
            "AB_PG_USER" to TestEnv.base.pgUser,
            "AB_PG_PASSWORD" to TestEnv.base.pgPassword,
            "AB_HTTP_PORT" to port.toString(),
            "AB_EMBED_PROVIDER" to "ollama",
            "AB_OLLAMA_BASE_URL" to TestEnv.base.ollamaBaseUrl,
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

    private fun cli(vararg args: String): Pair<Int, String> {
        val out = base.resolve("cli-${args.first()}-${System.nanoTime()}.out").toFile()
        val process = ProcessBuilder(launcher, *args).apply { environment().putAll(env) }
            .redirectOutput(out).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        assertThat(process.waitFor(180, TimeUnit.SECONDS)).withFailMessage("cli %s hung", args.toList()).isTrue
        return process.exitValue() to out.readText()
    }

    @Test
    fun `index through the cli, query through http and cli, re-index is a no-op`() {
        val (indexExit, indexOut) = cli("index", "$corpusDir/*.md")
        assertThat(indexExit).isZero
        val report = json.decodeFromString<IndexReport>(indexOut)
        assertThat(report.indexed).isEqualTo(13)
        assertThat(report.failed).isEmpty()
        assertThat(report.embedModel).isEqualTo("nomic-embed-text")
        assertThat(report.phases.keys).contains("upsert", "embed", "store")

        val response = post("/rag/query", """{"query": "which advisory lock id does the migration runner take?", "k": 3}""")
        assertThat(response.statusCode()).isEqualTo(200)
        val answer = json.decodeFromString<QueryResponse>(response.body())
        assertThat(answer.citations.first().uri).endsWith("migrator-lock.md")
        assertThat(answer.hits.first().content).contains("727001")

        val (queryExit, queryOut) = cli("query", "how many dimensions does nomic-embed-text produce", "--k", "2")
        assertThat(queryExit).isZero
        assertThat(queryOut.trimStart()).startsWith("{")
        val cliAnswer = json.decodeFromString<QueryResponse>(queryOut)
        assertThat(cliAnswer.citations.first().uri).endsWith("embedding-dimensions.md")
        assertThat(cliAnswer.hits).hasSize(2)

        val stats = json.decodeFromString<RagStats>(get("/rag/stats").body())
        assertThat(stats.documents).isEqualTo(13)

        val again = post("/rag/index", """{"globs": ["$corpusDir/*.md"]}""")
        assertThat(again.statusCode()).isEqualTo(200)
        val second = json.decodeFromString<IndexReport>(again.body())
        assertThat(second.unchanged).isEqualTo(13)
        assertThat(second.indexed).isZero
        assertThat(second.embedCalls).isZero
    }
}
