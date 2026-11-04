package dev.lattenero.brain.mcp

import kap.Kap
import kap.Ref
import kap.evalGraph
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

/**
 * The ToolSpec registry (the haskell-rules convention): every tool declared
 * once with its flat schema, and every response the SAME status envelope:
 * {status, result|error{kind}, warnings, nextStep, meta{trace_id,
 * tool_version}}. Flat schemas only (no top-level oneOf), path parameters
 * never interpolated into SQL or paths.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val properties: JsonObject,
    val required: List<String>,
    val version: Int,
    val nextStep: String,
) {
    val inputSchema: JsonObject
        get() = buildJsonObject {
            put("type", "object")
            put("properties", properties)
            if (required.isNotEmpty()) put("required", kotlinx.serialization.json.JsonArray(required.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }
}

sealed interface Envelope {
    data class Ok(
        val result: JsonElement,
        val warnings: List<String>,
        val nextStep: String,
        val traceId: String,
        val toolVersion: Int,
        val meta: Map<String, JsonElement> = emptyMap(),
    ) : Envelope {
        fun toJson(): JsonObject = buildJsonObject {
            put("status", "ok")
            put("result", result)
            put("warnings", kotlinx.serialization.json.JsonArray(warnings.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("nextStep", nextStep)
            putJsonObject("meta") {
                put("trace_id", traceId)
                put("tool_version", toolVersion)
                meta.forEach { (k, v) -> put(k, v) }
            }
        }
    }

    data class Err(
        val kind: String,
        val message: String,
        val nextStep: String,
        val traceId: String,
        val toolVersion: Int,
    ) : Envelope {
        fun toJson(): JsonObject = buildJsonObject {
            put("status", "error")
            putJsonObject("error") {
                put("kind", kind)
                put("message", message)
            }
            put("nextStep", nextStep)
            putJsonObject("meta") {
                put("trace_id", traceId)
                put("tool_version", toolVersion)
            }
        }
    }

    companion object {
        const val KIND_INVALID_INPUT = "invalid_input"
        const val KIND_UNAVAILABLE = "brain_unavailable"
        const val KIND_INTERNAL = "internal_error"
    }
}

/**
 * Monotonic per-process trace ids: "trace-<epochMillis>-<seq>". The sequence is
 * kap `Ref` state (Phase G: the audit's last AtomicLong global — "Traces
 * AtomicLong global — espera kap Ref"), so the ids are created by executing
 * [of] once at startup and [next] advances the sequence atomically.
 */
class Traces private constructor(private val start: Long, private val seq: Ref<Long>) {
    suspend fun next(): String = "trace-$start-${seq.update { it + 1 }.evalGraph()}"

    companion object {
        fun of(): Kap<Traces> = Kap { Traces(Instant.now().toEpochMilli(), Ref.of(0L).evalGraph()) }
    }
}

/**
 * argv-only configuration, like the haskell-rules server: flags and an optional
 * `serve` verb, no config parsing here beyond AB_*.
 *
 * The DEFAULT does not change (compat is a requirement): no arguments means the
 * stdio plugin. The service transport is opt-in:
 *
 *     brain-mcp serve --transport http --port 8767
 *
 * (the verb mirrors `agent-brain serve`; `--transport http` alone is the same).
 */
object Args {
    const val TRANSPORT_STDIO = "stdio"
    const val TRANSPORT_HTTP = "http"
    const val USAGE = "usage: brain-mcp [serve] [--transport stdio|http] [--port N (http, default 8767)] " +
        "[--host H (http, loopback only)] [--ab-base DIR] [--ab-config FILE]"

    data class Parsed(
        val abBase: String?,
        val abConfig: String?,
        val transport: String = TRANSPORT_STDIO,
        val host: String = McpHttp.DEFAULT_HOST,
        val port: Int = McpHttp.DEFAULT_PORT,
    )

    private val FLAGS = listOf("--ab-base", "--ab-config", "--transport", "--port", "--host")

    fun parse(argv: List<String>): Parsed {
        val words = argv.filter { it != "serve" }
        val extra = words.filterIndexed { i, a -> a !in FLAGS && (i == 0 || words[i - 1] !in FLAGS) }
        require(extra.isEmpty()) { "unknown arguments: $extra ($USAGE)" }
        require(words.lastOrNull() !in FLAGS) { "${words.lastOrNull()} needs a value ($USAGE)" }
        fun value(flag: String) = argv.dropWhile { it != flag }.drop(1).firstOrNull()?.also { require(!it.startsWith("--")) { "$flag needs a value" } }
        val transport = value("--transport") ?: TRANSPORT_STDIO
        require(transport == TRANSPORT_STDIO || transport == TRANSPORT_HTTP) {
            "--transport must be $TRANSPORT_STDIO or $TRANSPORT_HTTP, got '$transport'"
        }
        val port = value("--port")?.let { raw ->
            requireNotNull(raw.toIntOrNull()?.takeIf { it in 1..65535 }) { "--port must be 1..65535, got '$raw'" }
        } ?: McpHttp.DEFAULT_PORT
        return Parsed(
            abBase = value("--ab-base"),
            abConfig = value("--ab-config"),
            transport = transport,
            host = value("--host") ?: McpHttp.DEFAULT_HOST,
            port = port,
        )
    }
}
