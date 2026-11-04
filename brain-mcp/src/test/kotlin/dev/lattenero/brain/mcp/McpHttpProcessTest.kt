package dev.lattenero.brain.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The INSTALLED binary as a SEPARATE PROCESS serving MCP over streamable HTTP:
 * argv parsing, the env token, the real ktor server and the real SDK routes.
 * The brain is faked (this tier proves the endpoint; core's tiers prove the
 * brain). The same flow, against the real brain, is the gate evidence in
 * `docs/mcp.md`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpHttpProcessTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val token = "process-token-abcdef-0123456789"
    private lateinit var launcher: File
    private var brainPort = 0
    private var port = 0
    private lateinit var brain: EmbeddedServer<*, *>
    private lateinit var process: Process
    private val client = HttpClient(CIO) { expectSuccess = false }

    @BeforeAll
    fun up() {
        launcher = File(System.getProperty("brainmcpt.dist") ?: error("brainmcpt.dist not set"))
        assertThat(launcher).exists()
        brainPort = ServerSocket(0).use { it.localPort }
        port = ServerSocket(0).use { it.localPort }
        brain = embeddedServer(ServerCIO, host = "127.0.0.1", port = brainPort) {
            routing {
                get("/ready") { call.respondText("""{"status":"ok"}""") }
                post("/rag/query") {
                    val query = json.parseToJsonElement(call.receiveText()).jsonObject["query"]!!.jsonPrimitive.content
                    val hit = buildJsonObject {
                        put("chunkId", 1); put("uri", "/c/tailer.md"); put("title", "tailer.md")
                        put("heading", "tailer.md > Truncation"); put("ordinal", 0)
                        put("content", "The tailer detects a shrinking file and resets its offset."); put("score", 0.04)
                    }
                    val ok = buildJsonObject {
                        put("query", query); put("embedModel", "fake")
                        put("hits", buildJsonArray { add(hit) })
                        put("citations", buildJsonArray { add(hit) })
                    }
                    call.respondText(ok.toString(), ContentType.Application.Json)
                }
                get("/bus/status") {
                    call.respondText("""{"transport":"file","dbEvents":7,"malformed":0}""", ContentType.Application.Json)
                }
            }
        }.start(wait = false)
        process = spawn(token = token)
        runBlocking { awaitHealth() }
    }

    @AfterAll
    fun down() {
        process.destroy()
        process.waitFor(15, TimeUnit.SECONDS)
        if (process.isAlive) process.destroyForcibly()
        brain.stop(1_000, 5_000)
        client.close()
    }

    @Test
    fun `without a token the endpoint answers 401 and leaks nothing`() = runBlocking<Unit> {
        val response = post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""", token = null)
        assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(response.headers[HttpHeaders.WWWAuthenticate]).contains("Bearer")
        assertThat(response.bodyAsText()).isEqualTo(McpHttp.UNAUTHORIZED_BODY)
    }

    @Test
    fun `a valid token initializes, lists the four tools and runs rag_query with citations`() = runBlocking<Unit> {
        val init = post(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"process-client","version":"0"}}}""",
            token,
        )
        assertThat(init.status).isEqualTo(HttpStatusCode.OK)
        val session = init.headers["Mcp-Session-Id"]
        assertThat(session).isNotBlank()
        assertThat(init.bodyAsText()).contains("brain-mcp")

        client.post(url(McpHttp.PATH)) {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("Mcp-Session-Id", session)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        }

        val list = post("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", token, session)
        val tools = json.parseToJsonElement(list.bodyAsText())
            .jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
        assertThat(tools.map { it.jsonObject["name"]!!.jsonPrimitive.content })
            .containsExactlyInAnyOrder("rag_query", "memory_search", "memory_recent", "bus_status")

        val call = post(
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"rag_query","arguments":{"query":"tailer truncation"}}}""",
            token,
            session,
        )
        val envelope = envelopeOf(call)
        assertThat(envelope["status"]!!.jsonPrimitive.content).isEqualTo("ok")
        assertThat(envelope["result"]!!.jsonObject["citations"]!!.jsonArray[0].jsonObject["uri"]!!.jsonPrimitive.content)
            .endsWith("tailer.md")
    }

    @Test
    fun `the endpoint never binds above loopback and a missing token refuses to start`() {
        val refusedBind = runProcess(listOf("serve", "--transport", "http", "--host", "0.0.0.0", "--port", freePort().toString()))
        assertThat(refusedBind.waitFor(30, TimeUnit.SECONDS)).isTrue
        assertThat(refusedBind.exitValue()).isEqualTo(2)
        assertThat(refusedBind.errorStream.bufferedReader().readText())
            .contains("refusing to bind 0.0.0.0")
            .contains("SSH tunnel")

        val noToken = runProcess(listOf("serve", "--transport", "http", "--port", freePort().toString()), token = null)
        assertThat(noToken.waitFor(30, TimeUnit.SECONDS)).isTrue
        assertThat(noToken.exitValue()).isEqualTo(2)
        assertThat(noToken.errorStream.bufferedReader().readText()).contains("AB_MCP_TOKEN")
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun url(path: String) = "http://127.0.0.1:$port$path"

    private fun runProcess(args: List<String>, token: String? = this.token): Process =
        ProcessBuilder(listOf(launcher.absolutePath) + args + listOf("--ab-base", tempBase()))
            .apply {
                environment()["AB_HTTP_PORT"] = brainPort.toString()
                environment()["AB_EMBED_PROVIDER"] = "fake"
                environment()["AB_CHAT_PROVIDER"] = "fake"
                if (token != null) environment()["AB_MCP_TOKEN"] = token
            }
            .start()

    private fun spawn(token: String?): Process =
        runProcess(listOf("serve", "--transport", "http", "--port", port.toString()), token)

    private fun tempBase(): String = Files.createTempDirectory("brain-mcp-http-e2e").toString()

    private suspend fun awaitHealth() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline) {
            val response = runCatching { client.get("http://127.0.0.1:$port${McpHttp.HEALTH_PATH}") }.getOrNull()
            if (response?.status == HttpStatusCode.OK) return
            Thread.sleep(100)
        }
        error("the endpoint never answered /health; stderr: ${process.errorStream.bufferedReader().readText().take(500)}")
    }

    private suspend fun post(body: String, token: String?, sessionId: String? = null): HttpResponse =
        client.post(url(McpHttp.PATH)) {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            sessionId?.let { header("Mcp-Session-Id", it) }
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun envelopeOf(response: HttpResponse): JsonObject = runBlocking {
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val text = body["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        json.parseToJsonElement(text).jsonObject
    }

}
