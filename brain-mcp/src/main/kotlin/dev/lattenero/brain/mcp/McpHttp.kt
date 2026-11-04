package dev.lattenero.brain.mcp

import dev.lattenero.brain.VERSION
import dev.lattenero.brain.config.Config
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import kap.Ref
import kap.evalGraph
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("agent-brain.mcp.http")

/**
 * The MCP endpoint as a service: streamable HTTP on loopback, gated by a
 * bearer token, exposing exactly the four tools the stdio plugin exposes.
 *
 * Why this shape (the trade-off is written up in `docs/mcp.md`):
 *
 * - **A separate process, not a route on `agent-brain serve`.** The brain's
 *   HTTP API has unauthenticated mutating routes (`/memory/save`,
 *   `/rag/index`). A tunnel to the brain's port would expose all of them; a
 *   tunnel to THIS port exposes one narrow, token-gated, read-only surface.
 *   The module boundary also stays intact: core owns the brain, brain-mcp owns
 *   the MCP surface.
 * - **A facade, not a second brain.** Every tool is the same HTTP call the
 *   stdio plugin makes, so `bus_status` reports the ONE real tailer instead of
 *   an idle copy, there is no second connection pool nor a second embedding
 *   client, and `/mcp` cannot drift from the stdio contract.
 * - **Streamable HTTP with sessions, not SSE and not stateless.** The 2024 SSE
 *   transport is deprecated by the SDK; the stateless variant serves one
 *   request per session and gives up the GET stream clients may use. The
 *   sessions here are the SDK's, bounded ([MAX_SESSIONS]) and idle-expired.
 */
object McpHttp {
    const val PATH = "/mcp"
    const val HEALTH_PATH = "/health"
    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 8767

    /** SDK defaults are 10_000 open sessions; a personal brain never needs that many. */
    const val MAX_SESSIONS = 64

    /** An abandoned client stops holding a session after half an hour without requests. */
    val SESSION_IDLE: Duration = 30.minutes

    /** The one body a rejected request gets: no oracle for missing vs wrong vs other scheme. */
    const val UNAUTHORIZED_BODY = """{"error":"unauthorized","message":"missing or invalid bearer token"}"""

    /**
     * The one body a rejected Host/Origin gets. It deliberately does NOT echo the
     * offending header: the SDK's own 403 quotes it back (`"Invalid Host: X"`), and
     * a reflected value is text the caller chose written into our answer.
     */
    const val FORBIDDEN_BODY = """{"error":"forbidden","message":"Host and Origin must be loopback"}"""

    /** The loopback names a browser can legitimately use against this endpoint. */
    val ALLOWED_HOSTS: Set<String> = setOf("localhost", "127.0.0.1", "[::1]", "::1")

    private const val GRACE_MS = 1_000L
    private const val STOP_TIMEOUT_MS = 5_000L

    /**
     * The transport binds loopback only. `0.0.0.0`, `::` and any public name
     * are refused with the tunnel hint instead of a warning: the owner's rule
     * is that a remote client arrives through `ssh -L`, never through a rebind.
     */
    fun requireLoopback(host: String) {
        val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
        require(addresses.isNotEmpty() && addresses.all { it.isLoopbackAddress }) {
            "refusing to bind $host: the MCP endpoint stays on loopback ($DEFAULT_HOST, ::1, localhost). " +
                "For another machine use an SSH tunnel — " +
                "ssh -L $DEFAULT_PORT:$DEFAULT_HOST:$DEFAULT_PORT <host> — see docs/mcp.md"
        }
    }

    /**
     * True only for a loopback `Host` header. A missing or empty one is false:
     * HTTP/1.0 and a malformed request line must not skip the check (the SDK's
     * guard on `/mcp` is fail-closed the same way).
     */
    fun hostAllowed(hostHeader: String?): Boolean = hostOf(hostHeader)?.let { it in ALLOWED_HOSTS } == true

    /**
     * True for an absent `Origin` (a non-browser client) or one whose host is
     * loopback. `Origin: null`, an unparseable value and any foreign name are
     * false — the DNS-rebinding case is exactly a page whose own origin is the
     * rebound name.
     */
    fun originAllowed(origin: String?): Boolean {
        val raw = origin?.trim().orEmpty()
        return raw.isEmpty() || originHost(raw)?.let { it in ALLOWED_HOSTS } == true
    }

    /**
     * Builds and starts the endpoint. The caller owns [client] (and closes it
     * after [stop]); the returned server is started and not waited on.
     */
    suspend fun start(
        config: Config,
        host: String,
        port: Int,
        token: McpToken,
        client: HttpClient,
    ): EmbeddedServer<*, *> {
        val brain = HttpBrainClient(
            baseUrl = "http://${config.httpHost}:${config.httpPort}",
            client = client,
            timeout = config.httpTimeoutS.seconds,
        )
        val wiring = wire(brain)
        if (!wiring.brainReady) {
            log.warn("the brain's HTTP API is not reachable yet; tool calls will answer brain_unavailable until it is")
        }
        // The per-process state (trace ids, tool-call count) is kap Ref state, shared by
        // every session: one counter for the endpoint, not one per client.
        val traces = Traces.of().evalGraph()
        val served = Ref.of(0).evalGraph()
        val server = embeddedServer(CIO, host = host, port = port) {
            brainMcpHttp(token, brain, wiring, traces, served)
        }
        server.start(wait = false)
        log.info(
            "brain-mcp MCP over streamable HTTP on http://{}:{}{} ({} tool(s), token from {})",
            host, port, PATH, wiring.registry.size, token.source,
        )
        return server
    }

    /**
     * The ktor module: the token guard FIRST (so it runs in the Plugins phase,
     * before routing resolves and before any MCP handler sees a byte), then the
     * health probe, then the SDK's streamable HTTP routes.
     */
    internal fun Application.brainMcpHttp(
        token: McpToken,
        brain: HttpBrainClient,
        wiring: ToolWiring,
        traces: Traces,
        served: Ref<Int>,
    ) {
        installTokenGuard(token)
        installRebindingGuard()
        routing {
            // Unauthenticated on purpose: it answers "is the process up?" without a
            // token, and it is free of configuration (no base URL, no token, no paths).
            get(HEALTH_PATH) { call.respondText(healthJson(served), ContentType.Application.Json) }
        }
        mcpStreamableHttp(
            path = PATH,
            sessionIdleTimeout = SESSION_IDLE,
            maxSessions = MAX_SESSIONS,
        ) {
            // One Server per session, all over the same registry, traces and counter:
            // stdio and HTTP cannot drift, they build the same server from the same list.
            newServer(wiring, brain, traces, served)
        }
    }

    /**
     * Every request to the MCP path carries a token — including one that reuses
     * a valid `Mcp-Session-Id`: a session id is not a credential. The comparison
     * is unconditional (`matches` also runs for a missing header), so "no
     * header" and "wrong token" cost the same and answer the same body.
     */
    private fun Application.installTokenGuard(token: McpToken) {
        intercept(ApplicationCallPipeline.Plugins) {
            val path = call.request.path()
            if (path != PATH && !path.startsWith("$PATH/")) return@intercept
            if (token.matches(Bearer.credential(call.request.header(HttpHeaders.Authorization)))) return@intercept
            log.warn(
                "rejected an MCP request from {} ({}) without a valid token",
                call.request.origin.remoteHost,
                call.request.httpMethod.value,
            )
            call.response.header(HttpHeaders.WWWAuthenticate, """Bearer realm="brain-mcp"""")
            call.respondText(UNAUTHORIZED_BODY, ContentType.Application.Json, HttpStatusCode.Unauthorized)
            finish()
        }
    }

    private suspend fun healthJson(served: Ref<Int>): String = buildJsonObject {
        put("status", "ok")
        put("server", "brain-mcp")
        put("version", VERSION)
        put("transport", "streamable-http")
        put("endpoint", PATH)
        put("tools", BrainTools.all.size)
        // A kap Ref has no non-suspend read; this route is a suspend context.
        put("calls", served.get().evalGraph())
    }.toString()

    /** Graceful stop: in-flight tool calls finish, then the port closes. */
    fun stop(server: EmbeddedServer<*, *>) {
        server.stop(GRACE_MS, STOP_TIMEOUT_MS)
    }
}

/**
 * The Host/Origin allowlist for everything that is NOT `/mcp`: the SDK installs
 * its DNS-rebinding guard on its own route only, so `/health` — unauthenticated
 * by design — used to answer `Host: evil.example` with 200. Under rebinding that
 * is a readable cross-origin response (version, tool count and the live tool-call
 * counter), which is the one thing the guard exists to prevent. Same rule, same
 * 403, minus the SDK's reflection of the offending value.
 *
 * `/mcp` is skipped: its guard is the SDK's, and duplicating it there would mean
 * two allowlists that can drift.
 */
private fun Application.installRebindingGuard() {
    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        val allowed = path == McpHttp.PATH ||
            (
                McpHttp.hostAllowed(call.request.header(HttpHeaders.Host)) &&
                    McpHttp.originAllowed(call.request.header(HttpHeaders.Origin))
                )
        if (!allowed) {
            log.warn("rejected a request to {} from a non-loopback Host or Origin", path)
            call.respondText(McpHttp.FORBIDDEN_BODY, ContentType.Application.Json, HttpStatusCode.Forbidden)
            finish()
        }
    }
}

/**
 * `Host` without its port, lowercased: "127.0.0.1:8767" → "127.0.0.1",
 * "[::1]:8767" → "[::1]". A non-numeric port makes the header malformed and is
 * rejected ("127.0.0.1:80@evil.example" must not read as "127.0.0.1").
 */
private fun hostOf(hostHeader: String?): String? {
    val raw = hostHeader?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val host = when {
        raw.startsWith("[") -> raw.substringBefore(']') + "]"
        raw.count { it == ':' } == 1 -> raw.substringBefore(':')
        else -> raw
    }
    val port = raw.removePrefix(host).removePrefix(":")
    return host.lowercase().takeIf { port.isEmpty() || port.all { c -> c.isDigit() } }
}

/** The host of an `Origin` value, or null when it does not parse (includes `null`). */
private fun originHost(origin: String): String? = runCatching { URI(origin).host }.getOrNull()?.lowercase()
