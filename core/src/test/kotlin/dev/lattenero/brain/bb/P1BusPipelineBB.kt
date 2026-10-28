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
import org.junit.jupiter.api.Assumptions.assumeTrue
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
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Tier 4, P1: a real `serve` process tails a real bus file into the real
 * database. The producer is the bot's own `bus.py` when the bot repo is at
 * hand (AB_BOT_REPO, default ~/Projects/bot), otherwise the recorded
 * output of that same publisher. Everything is observed through the
 * service's HTTP port and CLI; nothing in-process.
 */
@Tag("bb-local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class P1BusPipelineBB {
    private val launcher: String = System.getProperty("ab.dist") ?: fail("ab.dist not set: run through `./gradlew bbLocal`")
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val port = ServerSocket(0).use { it.localPort }
    private val base: Path = Files.createTempDirectory("agent-brain-bb-p1")
    private val busFile: Path = base.resolve("bus-events.jsonl")
    private lateinit var env: Map<String, String>
    private var server: Process? = null

    @BeforeAll
    fun up() {
        TestEnv.needPostgres()
        TestEnv.needOllama()
        env = mapOf(
            "AB_BASE" to base.toString(),
            "AB_PG_URL" to TestDatabase.fresh("bbp1"),
            "AB_PG_USER" to TestEnv.base.pgUser,
            "AB_PG_PASSWORD" to TestEnv.base.pgPassword,
            "AB_HTTP_PORT" to port.toString(),
            "AB_EMBED_PROVIDER" to "ollama",
            "AB_OLLAMA_BASE_URL" to TestEnv.base.ollamaBaseUrl,
            "AB_BUS_POLL_MS" to "200",
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

    private fun status(): BusStatus = json.decodeFromString(get("/bus/status").body())

    private fun awaitStatus(what: String, condition: (BusStatus) -> Boolean): BusStatus =
        await().alias(what).atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).ignoreExceptions()
            .until({ status() }, { condition(it) })

    private fun botSrc(): File? {
        val repo = File(System.getenv("AB_BOT_REPO") ?: (System.getProperty("user.home") + "/Projects/bot"))
        val src = File(repo, "src")
        return if (File(src, "zcode_bot/bus.py").isFile) src else null
    }

    /** The bot's real publisher writes one phone turn and one Mac turn (7 events) into OUR bus file. */
    private fun publishWithTheBot(src: File) {
        val script = """
            import sys, time
            sys.path.insert(0, ${'"'}${src.absolutePath}${'"'})
            from zcode_bot import bus, config
            config.BUS_FILE = ${'"'}${busFile.toAbsolutePath()}${'"'}
            cfg = {"event_bus": True}
            t0 = time.time(); turn = bus.turn_id(t0); sid = "ses_bb_phone"
            assert bus.publish(cfg, "turn.started", sid, turn=turn, payload={"title": "BB", "model": "m", "ws": "/w", "prompt_chars": 5, "prompt": "hello"})
            assert bus.publish(cfg, "tool.call", sid, turn=turn, payload={"toolCallId": "t1", "tool": "Read", "input": "{}"})
            assert bus.publish(cfg, "tool.result", sid, turn=turn, payload={"toolCallId": "t1", "success": True, "chars": 10})
            assert bus.publish(cfg, "turn.completed", sid, turn=turn, payload={"duration_s": 1.5, "reply_chars": 2, "reply": "ok", "cot_chars": 0})
            assert bus.publish(cfg, "user.prompt", "ses_bb_mac", turn="42", source="hook", payload={"prompt": "mac turn", "prompt_chars": 8, "cwd": "/w"})
            assert bus.publish(cfg, "tool.call", "ses_bb_mac", turn="42", source="hook", payload={"toolCallId": "t2", "tool": "Bash", "input": "{}"})
            assert bus.publish(cfg, "turn.completed", "ses_bb_mac", turn="42", source="hook", payload={"duration_s": 2.0, "response_chars": 4, "response": "done", "cwd": "/w"})
            print("published")
        """.trimIndent()
        val process = ProcessBuilder("python3", "-c", script).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue
        assertThat(process.exitValue()).withFailMessage("bot publisher failed:\n%s", output).isZero
        assertThat(output).contains("published")
    }

    private fun publishRecordedFixture() {
        val fixture = javaClass.classLoader.getResourceAsStream("bus/sample-bot-publisher.jsonl")!!.readAllBytes()
        Files.createDirectories(busFile.parent)
        Files.write(busFile, fixture, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    @Test
    fun `events published by the bot's own bus py land in the database and a replay adds nothing`() {
        val src = botSrc()
        assumeTrue(src != null, "bot repo with src/zcode_bot/bus.py not found (set AB_BOT_REPO); the recorded-fixture scenario still runs")
        val before = status()
        publishWithTheBot(src!!)
        val landed = awaitStatus("7 bot events ingested") { it.dbEvents >= before.dbEvents + 7 }
        assertThat(landed.inserted - before.inserted).isEqualTo(7)
        assertThat(landed.malformed).isEqualTo(before.malformed)
        assertThat(landed.lastKind).isEqualTo("turn.completed")

        // Replay: append the very same bytes again; dedup by id must absorb them all.
        val bytes = Files.readAllBytes(busFile)
        Files.write(busFile, bytes, StandardOpenOption.APPEND)
        val replayed = awaitStatus("replay consumed") { it.fileBytes == bytes.size * 2L && it.lagBytes == 0L }
        assertThat(replayed.dbEvents).isEqualTo(landed.dbEvents)
        assertThat(replayed.duplicates).isGreaterThanOrEqualTo(7)
    }

    @Test
    fun `the recorded publisher output lands and the cli reports the same numbers`() {
        val before = status()
        publishRecordedFixture()
        val landed = awaitStatus("6 fixture events ingested") { it.seen >= before.seen + 6 && it.lagBytes == 0L }
        assertThat(landed.dbEvents).isGreaterThanOrEqualTo(6)
        assertThat(landed.malformed).isEqualTo(before.malformed)

        val out = base.resolve("bus-status.out").toFile()
        val cli = ProcessBuilder(launcher, "bus", "status").apply { environment().putAll(env) }
            .redirectOutput(out).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        assertThat(cli.waitFor(60, TimeUnit.SECONDS)).isTrue
        assertThat(cli.exitValue()).isZero
        val text = out.readText()
        assertThat(text.trimStart()).startsWith("{")
        val parsed = json.parseToJsonElement(text).jsonObject
        assertThat(parsed["dbEvents"]!!.jsonPrimitive.content.toLong()).isEqualTo(landed.dbEvents)
        assertThat(parsed["fileBytes"]!!.jsonPrimitive.content.toLong()).isEqualTo(Files.size(busFile))
    }

    @Test
    fun `ingest once drains a bus into a fresh base and exits with the counters`() {
        val ownBase = Files.createTempDirectory("agent-brain-bb-p1-once")
        val ownBus = ownBase.resolve("bus-events.jsonl")
        Files.write(ownBus, javaClass.classLoader.getResourceAsStream("bus/sample-bot-publisher.jsonl")!!.readAllBytes())
        val out = ownBase.resolve("ingest.out").toFile()
        val cli = ProcessBuilder(launcher, "ingest", "--once").apply {
            environment().putAll(env)
            environment()["AB_BASE"] = ownBase.toString()
            environment()["AB_PG_URL"] = TestDatabase.fresh("bbonce")
        }.redirectOutput(out).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        assertThat(cli.waitFor(120, TimeUnit.SECONDS)).isTrue
        assertThat(cli.exitValue()).isZero
        val parsed = json.decodeFromString<BusStatus>(out.readText())
        assertThat(parsed.inserted).isEqualTo(6)
        assertThat(parsed.dbEvents).isEqualTo(6)
        assertThat(parsed.lagBytes).isZero
        assertThat(Files.exists(ownBase.resolve("state").resolve("bus-offset.json"))).isTrue
    }
}
