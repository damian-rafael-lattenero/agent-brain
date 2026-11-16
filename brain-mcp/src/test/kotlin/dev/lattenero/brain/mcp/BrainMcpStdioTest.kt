package dev.lattenero.brain.mcp

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * The REAL installed plugin binary over the REAL MCP stdio protocol:
 * JSON-RPC in on stdin, envelopes out on stdout, logs on stderr. The brain's
 * HTTP API is faked with ktor because this tier proves the PLUGIN (tiers 1-4
 * already proved the brain).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BrainMcpStdioTest {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var launcher: File
    private var port = 0
    @Volatile
    private var brainUp = true
    private lateinit var fake: EmbeddedServer<*, *>

    @BeforeAll
    fun up() {
        port = ServerSocket(0).use { it.localPort }
        fake = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            routing {
                get("/ready") { call.respondText("""{"status":"ok"}""") }
                post("/rag/query") {
                    if (!brainUp) {
                        call.respondText("{\"error\":\"down\"}", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                        return@post
                    }
                    val body = json.parseToJsonElement(call.receiveText())
                    val q = body.jsonObject["query"]!!.jsonPrimitive.content
                    if (q.isBlank()) {
                        call.respondText("{\"error\":\"query must not be empty\"}", ContentType.Application.Json, HttpStatusCode.BadRequest)
                        return@post
                    }
                    val hit = buildJsonObject {
                        put("chunkId", 1); put("docId", 1); put("uri", "/c/kafka.md"); put("title", "kafka.md")
                        put("heading", "kafka.md > Kafka"); put("ordinal", 0)
                        put("content", "Consumer groups rebalance partitions."); put("score", 0.03)
                    }
                    val ok = buildJsonObject {
                        put("query", q); put("embedModel", "fake")
                        put("hits", buildJsonArray { add(hit) })
                        put("citations", buildJsonArray { add(hit) })
                    }
                    call.respondText(ok.toString(), ContentType.Application.Json)
                }
                post("/memory/search") {
                    if (!brainUp) {
                        call.respondText("{\"error\":\"down\"}", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                        return@post
                    }
                    call.respondText(
                        """{"query":"q","embedModel":"fake","memories":[${memory(7)}]}""",
                        ContentType.Application.Json,
                    )
                }
                get("/memory/recent") { call.respondText("""[${memory(7)}]""", ContentType.Application.Json) }
                get("/memory/status") {
                    call.respondText(
                        """{"pendingTurns":2,"processedTurns":100,"memories":7,"active":5,"cooling":1,"archived":1,"superseded":0,"byKind":{"fact":4,"preference":3},"quarantined":1,"breaker":"Closed","chatModel":"fake","embedModel":"fake","edgesByKind":{"same_session":3}}""",
                        ContentType.Application.Json,
                    )
                }
                get("/bus/status") {
                    call.respondText("""{"transport":"file","file":"/x","fileExists":true,"fileBytes":9,"offset":9,"lagBytes":0,"polls":1,"linesRead":9,"malformed":0,"shrinks":0,"seen":9,"inserted":7,"duplicates":2,"dbEvents":7,"lastTs":"t","lastKind":"turn.completed"}""", ContentType.Application.Json)
                }
                get("/eval/latest") {
                    call.respondText(
                        buildJsonArray {
                            add(evalRun("recall-core", "run-retrieval", ok = true, passed = 9, total = 10, withRetrieval = true))
                            add(evalRun("grounded", "run-grounded", ok = false, passed = 3, total = 5, withRetrieval = false))
                        }.toString(),
                        ContentType.Application.Json,
                    )
                }
            }
        }.start(wait = false)
        launcher = File(System.getProperty("brainmcpt.dist") ?: fail("brainmcpt.dist not set"))
        assertThat(launcher).exists()
    }

    private fun memory(id: Int) =
        """{"id":$id,"kind":"preference","content":"Prefers Amazon Corretto JDKs.","sessionId":null,"turnId":null,"salience":1.0,"createdAt":"2026-10-28T00:00:00Z","score":0.02}"""

    /** One StoredRun as /eval/latest answers it; retrieval suites carry the graded columns inside detail. */
    private fun evalRun(suite: String, runId: String, ok: Boolean, passed: Int, total: Int, withRetrieval: Boolean): JsonObject =
        buildJsonObject {
            put("runId", runId)
            put("suite", suite)
            put("kind", "recall")
            put("axis", "embed")
            put("model", "fake-judge")
            put("provider", "fake")
            put("passed", passed)
            put("total", total)
            put("gate", 0.9)
            put("ok", ok)
            put("p50Ms", 120)
            put("p95Ms", 300)
            put("promptTokens", 100)
            put("completionTokens", 40)
            put("costUsd", 0.0)
            put("costKnown", false)
            put("startedAt", "2026-11-16T10:00:00Z")
            put("ts", "2026-11-16T10:00:05Z")
            put("detail", buildJsonObject {
                put("metrics", buildJsonObject {
                    if (withRetrieval) {
                        put("retrieval", buildJsonObject {
                            put("mrr", 0.812)
                            put("recall1", 0.7)
                            put("recall5", 0.9)
                            put("ndcg5", 0.83)
                            put("cases", total)
                        })
                    }
                })
            })
        }

    @AfterAll
    fun down() {
        fake.stop(1_000, 5_000)
    }

    /** Sends the requests; collects responses UNTIL every json-rpc id has an answer (notifications get none). */
    private fun session(requests: List<String>, @Suppress("UNUSED_PARAMETER") answers: Int): Map<Int, JsonObject> {
        val process = ProcessBuilder(launcher.absolutePath, "--ab-base", java.nio.file.Files.createTempDirectory("brain-mcp-t").toFile().absolutePath)
            .apply {
                environment()["AB_HTTP_PORT"] = port.toString()
                environment()["AB_EMBED_PROVIDER"] = "fake"
                environment()["AB_CHAT_PROVIDER"] = "fake"
            }
            .start()
        try {
            // stdin stays open while the responses are read, like a real MCP client:
            // EOF on stdin closes the transport's write channel and would discard any
            // response still in flight (the tool handlers suspend on the brain's HTTP).
            val writer = java.io.PrintWriter(process.outputStream, true)
            requests.forEach(writer::println)
            val wanted = requests.mapNotNull { r ->
                json.parseToJsonElement(r).jsonObject["id"]?.jsonPrimitive?.content?.toIntOrNull()
            }.toSet()
            val got = HashMap<Int, JsonObject>()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (got.keys != wanted) {
                if (System.nanoTime() > deadline) fail<String>("expected responses for $wanted, got ${got.keys}")
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val obj = json.parseToJsonElement(line).jsonObject
                obj["id"]?.jsonPrimitive?.content?.toIntOrNull()?.let { got[it] = obj }
            }
            writer.close()
            return got
        } finally {
            process.destroy()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private val initialize =
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"0"}}}"""
    private val initialized = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""

    private fun envelopeOf(response: JsonObject): JsonObject {
        val text = response["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        return json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun `initialize, tools list, and every tool answers with the envelope`() {
        val out = session(
            listOf(
                initialize,
                initialized,
                """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""",
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"rag_query","arguments":{"query":"consumer groups"}}}""",
                """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"memory_search","arguments":{"query":"jdk"}}}""",
                """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"memory_recent","arguments":{}}}""",
                """{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"bus_status","arguments":{}}}""",
                """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"memory_status","arguments":{}}}""",
                """{"jsonrpc":"2.0","id":8,"method":"resources/list"}""",
                """{"jsonrpc":"2.0","id":9,"method":"resources/read","params":{"uri":"ui://brain/panel.html"}}""",
                """{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"eval_latest","arguments":{}}}""",
            ),
            answers = 10,
        )
        val init = out.getValue(1)
        val toolsList = out.getValue(2)
        assertThat(init["result"]!!.jsonObject["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content).isEqualTo("brain-mcp")
        // The panel needs the resources capability advertised at initialize —
        // addResource refuses to register without it.
        assertThat(init["result"]!!.jsonObject["capabilities"]!!.jsonObject["resources"]).isNotNull()
        val tools = toolsList["result"]!!.jsonObject["tools"]!!.jsonArray
        assertThat(tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }).containsExactlyInAnyOrder(
            "rag_query", "memory_search", "memory_recent", "memory_status", "bus_status", "eval_latest",
        )
        tools.forEach { tool ->
            val schema = tool.jsonObject["inputSchema"]!!.jsonObject
            assertThat(schema["type"]!!.jsonPrimitive.content).isEqualTo("object")
            assertThat(schema.toString()).doesNotContain("oneOf")
        }

        val rag = envelopeOf(out.getValue(3))
        assertThat(rag["status"]!!.jsonPrimitive.content).isEqualTo("ok")
        assertThat(rag["result"]!!.jsonObject["citations"]!!.jsonArray[0].jsonObject["uri"]!!.jsonPrimitive.content).endsWith("kafka.md")
        assertThat(rag["meta"]!!.jsonObject["trace_id"]!!.jsonPrimitive.content).startsWith("trace-")
        assertThat(rag["meta"]!!.jsonObject["tool_version"]!!.jsonPrimitive.content).isEqualTo("1")
        assertThat(rag["nextStep"]!!.jsonPrimitive.content).contains("cite the uri")

        val recent = envelopeOf(out.getValue(5))
        assertThat(recent["result"].toString()).contains("Corretto").contains("preference")
        val searched = envelopeOf(out.getValue(4))
        assertThat(searched["result"].toString()).contains("Corretto")
        val bus = envelopeOf(out.getValue(6))
        assertThat(bus["result"].toString()).contains("dbEvents")

        val status = envelopeOf(out.getValue(7))
        assertThat(status["status"]!!.jsonPrimitive.content).isEqualTo("ok")
        assertThat(status["result"].toString()).contains("breaker").contains("\"memories\":7")

        val evals = envelopeOf(out.getValue(10))
        assertThat(evals["status"]!!.jsonPrimitive.content).isEqualTo("ok")
        assertThat(evals["result"].toString()).contains("recall-core").contains("run-retrieval").contains("\"passed\":9")
        assertThat(evals["result"].toString()).contains("grounded").contains("\"ok\":false")
        assertThat(evals["meta"]!!.jsonObject["trace_id"]!!.jsonPrimitive.content).startsWith("trace-")

        // The panel: listed with the MCP Apps mime, and its read returns the
        // EXPANDED page — the SDK bundle arrived as a data-URI and no build
        // placeholder survived.
        val resources = out.getValue(8)["result"]!!.jsonObject["resources"]!!.jsonArray
        assertThat(resources).hasSize(1)
        val panel = resources[0].jsonObject
        assertThat(panel["uri"]!!.jsonPrimitive.content).isEqualTo("ui://brain/panel.html")
        assertThat(panel["mimeType"]!!.jsonPrimitive.content).isEqualTo("text/html;profile=mcp-app")
        val read = out.getValue(9)["result"]!!.jsonObject["contents"]!!.jsonArray[0].jsonObject
        val html = read["text"]!!.jsonPrimitive.content
        assertThat(read["mimeType"]!!.jsonPrimitive.content).isEqualTo("text/html;profile=mcp-app")
        assertThat(html).contains("data:text/javascript;base64,")
        assertThat(html).contains("callServerTool")
        assertThat(html).doesNotContain("@@EXT_APPS_B64@@")
    }

    @Test
    fun `invalid input and a down brain answer with error envelopes, never a crash`() {
        brainUp = false
        try {
            val out = session(
                listOf(
                    initialize,
                    initialized,
                    """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"rag_query","arguments":{"query":""}}}""",
                    """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"rag_query","arguments":{"query":"x","k":999}}}""",
                ),
                answers = 4,
            )
            val first = envelopeOf(out.getValue(2))
            assertThat(first["status"]!!.jsonPrimitive.content).isEqualTo("error")
            assertThat(first["error"]!!.jsonObject["kind"]!!.jsonPrimitive.content).isEqualTo(Envelope.KIND_INVALID_INPUT)
            val second = envelopeOf(out.getValue(3))
            assertThat(second["error"]!!.jsonObject["kind"]!!.jsonPrimitive.content).isEqualTo(Envelope.KIND_INVALID_INPUT)
            assertThat(second["meta"]!!.jsonObject["trace_id"]!!.jsonPrimitive.content).startsWith("trace-")
        } finally {
            brainUp = true
        }
    }

    @Test
    fun `argv is validated, unknown flags refuse to start`() {
        val process = ProcessBuilder(launcher.absolutePath, "--wat").start()
        val err = process.errorStream.bufferedReader().readText()
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue
        assertThat(process.exitValue()).isNotZero()
        assertThat(err).contains("unknown arguments")
    }

    @Test
    fun `a stop signal unwinds the brain resource and exits without hanging`() {
        val process = ProcessBuilder(
            launcher.absolutePath,
            "--ab-base",
            java.nio.file.Files.createTempDirectory("brain-mcp-stop").toFile().absolutePath,
        ).apply {
            environment()["AB_HTTP_PORT"] = port.toString()
            environment()["AB_EMBED_PROVIDER"] = "fake"
            environment()["AB_CHAT_PROVIDER"] = "fake"
        }.start()
        try {
            // The park is armed only after initialize answers: signal after it.
            val writer = java.io.PrintWriter(process.outputStream, true)
            writer.println(initialize)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            var answered = false
            while (!answered) {
                if (System.nanoTime() > deadline) fail<String>("no initialize answer before the deadline")
                val line = reader.readLine() ?: break
                answered = line.contains("\"id\":1")
            }
            assertThat(answered).isTrue

            process.destroy()   // SIGTERM: the hook resumes the park, main releases the Brain
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue
        } finally {
            process.destroyForcibly()
        }
    }
}
