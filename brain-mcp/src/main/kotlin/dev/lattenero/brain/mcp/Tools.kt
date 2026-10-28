package dev.lattenero.brain.mcp

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

/** Monotonic per-process trace ids: "trace-<epochMillis>-<seq>". */
object Traces {
    private val start = Instant.now().toEpochMilli()
    private val seq = java.util.concurrent.atomic.AtomicLong()

    fun next(): String = "trace-$start-${seq.incrementAndGet()}"
}

/** argv-only configuration, like the haskell-rules server: no flags, no env parsing beyond AB_*. */
object Args {
    data class Parsed(val abBase: String?, val abConfig: String?)

    fun parse(argv: List<String>): Parsed {
        val known = setOf("--ab-base", "--ab-config")
        val extra = argv.filterIndexed { i, a -> a !in known && (i == 0 || argv[i - 1] !in known) }
        require(extra.isEmpty()) { "unknown arguments: $extra (usage: brain-mcp [--ab-base DIR] [--ab-config FILE])" }
        fun value(flag: String) = argv.dropWhile { it != flag }.drop(1).firstOrNull()?.also { require(!it.startsWith("--")) { "$flag needs a value" } }
        return Parsed(value("--ab-base"), value("--ab-config"))
    }
}
