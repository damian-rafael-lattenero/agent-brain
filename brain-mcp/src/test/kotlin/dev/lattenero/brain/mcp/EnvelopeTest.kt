package dev.lattenero.brain.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kap.evalGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The catch-all law of the envelope — the hottest MCP path: every typed
 * failure answers an [Envelope.Err], but a cancelled tool call must surface
 * the [CancellationException]. Wrapping a cancellation into an internal-error
 * envelope would swallow it and keep a dead call running.
 */
class EnvelopeTest {
    private val spec = ToolSpec(
        name = "rag_query",
        description = "test tool",
        properties = buildJsonObject { put("query", buildJsonObject { put("type", "string") }) },
        required = listOf("query"),
        version = 1,
        nextStep = "test next step",
    )

    private fun request() = CallToolRequest(
        CallToolRequestParams(name = "rag_query", arguments = buildJsonObject { put("query", "consumer groups") }),
    )

    private suspend fun envelopeJson(body: suspend (JsonObject?) -> String): JsonObject {
        val traces = Traces.of().evalGraph()
        val result = envelope(traces, spec, request(), body)
        val text = (result.content.first() as TextContent).text
        return Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun `a cancelled tool call surfaces the cancellation, never an internal-error envelope`() {
        assertThatThrownBy {
            runBlocking {
                envelopeJson { throw CancellationException("the caller hung up") }
            }
        }.isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `an unexpected failure still answers the internal-error envelope`() {
        runBlocking {
            val json = envelopeJson { throw IllegalStateException("boom") }
            assertThat(json["status"]!!.jsonPrimitive.content).isEqualTo("error")
            assertThat(json["error"]!!.jsonObject["kind"]!!.jsonPrimitive.content).isEqualTo(Envelope.KIND_INTERNAL)
        }
    }

    @Test
    fun `invalid arguments still answer the invalid-input envelope`() {
        runBlocking {
            val json = envelopeJson { throw IllegalArgumentException("k must be positive") }
            assertThat(json["status"]!!.jsonPrimitive.content).isEqualTo("error")
            assertThat(json["error"]!!.jsonObject["kind"]!!.jsonPrimitive.content).isEqualTo(Envelope.KIND_INVALID_INPUT)
        }
    }
}
