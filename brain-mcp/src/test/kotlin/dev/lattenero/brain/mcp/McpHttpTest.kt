package dev.lattenero.brain.mcp

import dev.lattenero.brain.config.Config
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.delete
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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.ServerSocket
import java.nio.file.Files

/**
 * The HTTP transport end to end, in process: a REAL ktor server with the REAL
 * SDK routes, a REAL HTTP client, and a fake brain (this tier proves the
 * endpoint and the auth gate; the brain itself is proved by core's tiers).
 *
 * What is asserted here is exactly what the external client will meet: 401
 * before any MCP byte, a session that does not replace the token, and the same
 * four tools with the same envelope as stdio.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpHttpTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val tokenValue = "test-token-c0ffee-0123456789"
    private lateinit var client: HttpClient
    private lateinit var brain: EmbeddedServer<*, *>
    private lateinit var server: EmbeddedServer<*, *>
    private var brainPort = 0
    private var port = 0

    private val initialize =
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"external-client","version":"0"}}}"""

    @BeforeAll
    fun up() {
        brainPort = ServerSocket(0).use { it.localPort }
        brain = fakeBrain().start(wait = false)
        port = ServerSocket(0).use { it.localPort }
        client = HttpClient(CIO) { expectSuccess = false }
        server = runBlocking {
            val config = Config.load(
                mapOf(
                    "AB_BASE" to Files.createTempDirectory("brain-mcp-http").toString(),
                    "AB_HTTP_PORT" to brainPort.toString(),
                    "AB_EMBED_PROVIDER" to "fake",
                    "AB_CHAT_PROVIDER" to "fake",
                ),
                Files.createTempDirectory("brain-mcp-home"),
            )
            McpHttp.start(config, "127.0.0.1", port, McpToken.of(tokenValue), client)
        }
    }

    @AfterAll
    fun down() {
        McpHttp.stop(server)
        brain.stop(1_000, 5_000)
        client.close()
    }

    // ── the gate ────────────────────────────────────────────────────────────

    @Test
    fun `no token is a 401 before any MCP byte, with the same body as a wrong one`() = runBlocking<Unit> {
        val missing = mcpPost("", token = null)
        assertThat(missing.status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(missing.headers[HttpHeaders.WWWAuthenticate]).contains("Bearer")
        assertThat(missing.headers["Mcp-Session-Id"]).isNull()
        assertThat(missing.bodyAsText()).isEqualTo(McpHttp.UNAUTHORIZED_BODY)

        val wrong = mcpPost("", token = "wrong-token-0123456789abcdef")
        assertThat(wrong.status).isEqualTo(HttpStatusCode.Unauthorized)
        assertThat(wrong.bodyAsText()).isEqualTo(missing.bodyAsText())
    }

    @Test
    fun `empty value, another scheme and a truncated token are all 401`() = runBlocking<Unit> {
        val candidates = listOf(
            "Bearer ",
            "Basic $tokenValue",
            "Bearer ${tokenValue.dropLast(1)}",
            "Bearer $tokenValue extra",
            tokenValue, // no scheme at all
        )
        candidates.forEach { header ->
            val response = client.post(url(McpHttp.PATH)) {
                header(HttpHeaders.Authorization, header)
                acceptMcp()
                contentType(ContentType.Application.Json)
                setBody(initialize)
            }
            assertThat(response.status).describedAs("Authorization: $header").isEqualTo(HttpStatusCode.Unauthorized)
        }
    }

    @Test
    fun `the GET and DELETE methods of the transport are gated too`() = runBlocking<Unit> {
        val get = client.get(url(McpHttp.PATH)) { acceptMcp() }
        assertThat(get.status).isEqualTo(HttpStatusCode.Unauthorized)
        val delete = client.delete(url(McpHttp.PATH)) { acceptMcp() }
        assertThat(delete.status).isEqualTo(HttpStatusCode.Unauthorized)
    }

    @Test
    fun `a session id is not a credential`() = runBlocking<Unit> {
        val session = initializedSession()
        val replay = mcpPost("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", token = null, sessionId = session)
        assertThat(replay.status).isEqualTo(HttpStatusCode.Unauthorized)
        val stillGood = mcpPost("""{"jsonrpc":"2.0","id":3,"method":"tools/list"}""", tokenValue, sessionId = session)
        assertThat(stillGood.status).isEqualTo(HttpStatusCode.OK)
    }

    @Test
    fun `an unknown path is a 404, not a guarded 401`() = runBlocking<Unit> {
        val response = client.get("http://127.0.0.1:$port/mcp-extra") { acceptMcp() }
        assertThat(response.status).isEqualTo(HttpStatusCode.NotFound)
    }

    @Test
    fun `health needs no token and carries no configuration`() = runBlocking<Unit> {
        val response = client.get("http://127.0.0.1:$port${McpHttp.HEALTH_PATH}")
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val body = response.bodyAsText()
        assertThat(body).contains(""""status":"ok"""").contains("streamable-http")
        assertThat(body).doesNotContain(tokenValue).doesNotContain("127.0.0.1:$brainPort")
    }

    // ── the rebinding guard on the unauthenticated route ────────────────────

    @Test
    fun `health answers only a loopback Host and Origin`() {
        // The shapes a real browser and a rebound page send, over a raw socket.
        listOf("127.0.0.1:$port", "localhost:$port", "[::1]:$port", "LOCALHOST:$port").forEach { host ->
            assertThat(rawStatus("/health", host, null)).describedAs("Host: $host").startsWith("HTTP/1.1 200")
        }
        assertThat(rawStatus("/health", "127.0.0.1:$port", "http://localhost:$port")).startsWith("HTTP/1.1 200")
        assertThat(rawStatus("/health", "127.0.0.1:$port", "http://127.0.0.1:$port")).startsWith("HTTP/1.1 200")

        listOf("evil.example", "127.0.0.1.evil.example", "evil.example:$port", "localhost.evil.example").forEach { host ->
            assertThat(rawStatus("/health", host, null)).describedAs("Host: $host").startsWith("HTTP/1.1 403")
        }
        listOf("https://evil.example", "http://evil.example:$port", "null", "not a url").forEach { origin ->
            assertThat(rawStatus("/health", "127.0.0.1:$port", origin)).describedAs("Origin: $origin").startsWith("HTTP/1.1 403")
        }
        // HTTP/1.0 has no Host header at all: fail closed, like the SDK does on /mcp.
        assertThat(rawStatus("/health", null, null)).startsWith("HTTP/1.1 403")
    }

    @Test
    fun `a rejected Host is never echoed back and the MCP path keeps the SDK guard`() {
        val (status, body) = rawResponse("/health", "evil.example", null)
        assertThat(status).isEqualTo("HTTP/1.1 403 Forbidden")
        assertThat(body).doesNotContain("evil.example").isEqualTo(McpHttp.FORBIDDEN_BODY)

        // /mcp is the SDK's guard, and the token still answers first (401, no oracle).
        assertThat(rawStatus(McpHttp.PATH, "evil.example", null)).startsWith("HTTP/1.1 401")
        assertThat(rawStatus(McpHttp.PATH, "127.0.0.1:$port", null)).startsWith("HTTP/1.1 401")
    }

    @Test
    fun `the Host and Origin parsers strip ports and refuse anything else`() {
        // The header forms a client can send. A bare `::1` is not one of them (IPv6 is bracketed).
        listOf("localhost", "127.0.0.1", "[::1]").forEach {
            assertThat(McpHttp.hostAllowed("$it:$port")).describedAs(it).isTrue()
        }
        assertThat(McpHttp.hostAllowed("::1")).isTrue()
        assertThat(McpHttp.hostAllowed("localhost")).isTrue()
        assertThat(McpHttp.hostAllowed(" 127.0.0.1:8767 ")).isTrue()
        assertThat(McpHttp.hostAllowed("")).isFalse()
        assertThat(McpHttp.hostAllowed(null)).isFalse()
        assertThat(McpHttp.hostAllowed("[]:8767")).isFalse()
        assertThat(McpHttp.hostAllowed("127.0.0.1:8767@evil.example")).isFalse()

        assertThat(McpHttp.originAllowed(null)).isTrue() // a non-browser client sends no Origin
        assertThat(McpHttp.originAllowed("")).isTrue()
        assertThat(McpHttp.originAllowed("http://localhost:8767")).isTrue()
        assertThat(McpHttp.originAllowed("http://[::1]:8767")).isTrue()
        assertThat(McpHttp.originAllowed("null")).isFalse()
        assertThat(McpHttp.originAllowed("https://evil.example")).isFalse()
    }

    // ── the contract, identical to stdio ────────────────────────────────────

    @Test
    fun `initialize answers the server identity and opens a session`() = runBlocking<Unit> {
        val response = mcpPost(initialize, tokenValue)
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        assertThat(response.headers["Mcp-Session-Id"]).isNotBlank()
        val result = json.parseToJsonElement(response.bodyAsText()).jsonObject["result"]!!.jsonObject
        assertThat(result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content).isEqualTo("brain-mcp")
        assertThat(result["capabilities"]!!.jsonObject).containsKey("tools")
    }

    @Test
    fun `tools list is the same four flat schemas as stdio`() = runBlocking<Unit> {
        val session = initializedSession()
        val response = mcpPost("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", tokenValue, session)
        val tools = json.parseToJsonElement(response.bodyAsText())
            .jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
        assertThat(tools.map { it.jsonObject["name"]!!.jsonPrimitive.content })
            .containsExactlyInAnyOrder("rag_query", "memory_search", "memory_recent", "bus_status")
        tools.forEach { tool ->
            val schema = tool.jsonObject["inputSchema"]!!.jsonObject
            assertThat(schema["type"]!!.jsonPrimitive.content).isEqualTo("object")
            assertThat(schema.toString()).doesNotContain("oneOf")
        }
    }

    @Test
    fun `every tool declares readOnlyHint so a client can enforce the read-only promise`() = runBlocking<Unit> {
        val session = initializedSession()
        val response = mcpPost("""{"jsonrpc":"2.0","id":7,"method":"tools/list"}""", tokenValue, session)
        val tools = json.parseToJsonElement(response.bodyAsText())
            .jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
        assertThat(tools).hasSize(BrainTools.all.size)
        tools.forEach { tool ->
            val name = tool.jsonObject["name"]!!.jsonPrimitive.content
            val annotations = tool.jsonObject["annotations"]?.jsonObject
            assertThat(annotations).describedAs("$name must carry annotations, not just prose").isNotNull
            assertThat(annotations!!["readOnlyHint"]!!.jsonPrimitive.boolean).describedAs(name).isTrue()
            assertThat(annotations["destructiveHint"]!!.jsonPrimitive.boolean).describedAs(name).isFalse()
            assertThat(annotations["openWorldHint"]!!.jsonPrimitive.boolean).describedAs(name).isFalse()
        }
    }

    @Test
    fun `rag_query over HTTP answers the envelope with citations`() = runBlocking<Unit> {
        val session = initializedSession()
        val body = """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"rag_query","arguments":{"query":"consumer groups"}}}"""
        val envelope = envelopeOf(mcpPost(body, tokenValue, session))
        assertThat(envelope["status"]!!.jsonPrimitive.content).isEqualTo("ok")
        assertThat(envelope["result"]!!.jsonObject["citations"]!!.jsonArray[0].jsonObject["uri"]!!.jsonPrimitive.content)
            .endsWith("kafka.md")
        assertThat(envelope["meta"]!!.jsonObject["trace_id"]!!.jsonPrimitive.content).startsWith("trace-")
        assertThat(envelope["nextStep"]!!.jsonPrimitive.content).contains("cite the uri")
    }

    @Test
    fun `invalid arguments and the other three tools answer over HTTP too`() = runBlocking<Unit> {
        val session = initializedSession()
        val invalid = envelopeOf(
            mcpPost(
                """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"rag_query","arguments":{"query":"x","k":999}}}""",
                tokenValue,
                session,
            ),
        )
        assertThat(invalid["error"]!!.jsonObject["kind"]!!.jsonPrimitive.content).isEqualTo(Envelope.KIND_INVALID_INPUT)

        val recent = envelopeOf(
            mcpPost("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"memory_recent","arguments":{}}}""", tokenValue, session),
        )
        assertThat(recent["result"].toString()).contains("Corretto")

        val bus = envelopeOf(
            mcpPost("""{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"bus_status","arguments":{}}}""", tokenValue, session),
        )
        assertThat(bus["result"].toString()).contains("dbEvents")
    }

    // ── the bind rule ───────────────────────────────────────────────────────

    @Test
    fun `only loopback hosts may be bound, anything else points at the tunnel`() {
        McpHttp.requireLoopback("127.0.0.1")
        McpHttp.requireLoopback("localhost")
        McpHttp.requireLoopback("::1")
        listOf("0.0.0.0", "::", "10.0.0.5").forEach { open ->
            assertThatThrownBy { McpHttp.requireLoopback(open) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("refusing to bind $open")
                .hasMessageContaining("SSH tunnel")
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun url(path: String) = "http://127.0.0.1:$port$path"

    /**
     * One request over a raw socket: the Host and Origin headers are then
     * exactly the bytes a rebound page sends, with no client engine deciding
     * which of the two ends up on the wire.
     */
    private fun rawResponse(path: String, host: String?, origin: String?): Pair<String, String> {
        val request = buildString {
            append("GET $path HTTP/1.1\r\n")
            host?.let { append("Host: $it\r\n") }
            origin?.let { append("Origin: $it\r\n") }
            append("Connection: close\r\n\r\n")
        }
        java.net.Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.UTF_8))
                flush()
            }
            val answer = String(socket.getInputStream().readBytes(), Charsets.UTF_8)
            return answer.lineSequence().first() to answer.substringAfter("\r\n\r\n", "")
        }
    }

    private fun rawStatus(path: String, host: String?, origin: String?): String = rawResponse(path, host, origin).first

    private fun io.ktor.client.request.HttpRequestBuilder.acceptMcp() {
        header(HttpHeaders.Accept, "application/json, text/event-stream")
    }

    private suspend fun mcpPost(body: String, token: String?, sessionId: String? = null): HttpResponse =
        client.post(url(McpHttp.PATH)) {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            sessionId?.let { header("Mcp-Session-Id", it) }
            acceptMcp()
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    /** initialize + notifications/initialized, returning the session id the transport issued. */
    private suspend fun initializedSession(): String {
        val response = mcpPost(initialize, tokenValue)
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val session = response.headers["Mcp-Session-Id"]
        assertThat(session).isNotBlank()
        val notify = mcpPost("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", tokenValue, session)
        assertThat(notify.status).isIn(HttpStatusCode.OK, HttpStatusCode.Accepted, HttpStatusCode.NoContent)
        return session!!
    }

    private fun envelopeOf(response: HttpResponse): JsonObject = runBlocking {
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val text = body["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        json.parseToJsonElement(text).jsonObject
    }

    /** The brain's four endpoints, faked: this tier is about the MCP surface, not the retrieval. */
    private fun fakeBrain(): EmbeddedServer<*, *> = embeddedServer(ServerCIO, host = "127.0.0.1", port = brainPort) {
        routing {
            get("/ready") { call.respondText("""{"status":"ok"}""") }
            post("/rag/query") {
                val query = json.parseToJsonElement(call.receiveText()).jsonObject["query"]!!.jsonPrimitive.content
                if (query.isBlank()) {
                    call.respondText("""{"error":"query must not be empty"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
                val hit = buildJsonObject {
                    put("chunkId", 1); put("docId", 1); put("uri", "/c/kafka.md"); put("title", "kafka.md")
                    put("heading", "kafka.md > Kafka"); put("ordinal", 0)
                    put("content", "Consumer groups rebalance partitions."); put("score", 0.03)
                }
                val ok = buildJsonObject {
                    put("query", query); put("embedModel", "fake")
                    put("hits", buildJsonArray { add(hit) })
                    put("citations", buildJsonArray { add(hit) })
                }
                call.respondText(ok.toString(), ContentType.Application.Json)
            }
            post("/memory/search") {
                call.respondText("""{"query":"q","embedModel":"fake","memories":[${memory(7)}]}""", ContentType.Application.Json)
            }
            get("/memory/recent") { call.respondText("[${memory(7)}]", ContentType.Application.Json) }
            get("/bus/status") {
                call.respondText(
                    """{"transport":"file","file":"/x","fileExists":true,"fileBytes":9,"offset":9,"lagBytes":0,"""
                        + """"polls":1,"linesRead":9,"malformed":0,"shrinks":0,"seen":9,"inserted":7,"duplicates":2,"""
                        + """"dbEvents":7,"lastTs":"t","lastKind":"turn.completed"}""",
                    ContentType.Application.Json,
                )
            }
        }
    }

    private fun memory(id: Int) =
        """{"id":$id,"kind":"preference","content":"Prefers Amazon Corretto JDKs.","sessionId":null,"turnId":null,""" +
            """"salience":1.0,"createdAt":"2026-10-28T00:00:00Z","score":0.02}"""
}
