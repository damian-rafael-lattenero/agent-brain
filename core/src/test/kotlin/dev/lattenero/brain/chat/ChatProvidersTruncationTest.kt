package dev.lattenero.brain.chat

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The 16/11 truncation finding, pinned at the wire: the Ollama response's
 * `done_reason` decides [ChatResult.truncated], and the request carries a
 * `num_predict` big enough that schema-shaped extractions fit (~5-6 KB of
 * output). The previous 512-message cap cut 1.7-3.0 KB raws mid-word and the
 * loss surfaced as "not JSON" quarantine.
 */
class ChatProvidersTruncationTest {

    private fun client(
        body: String,
        capture: MutableList<String> = mutableListOf(),
    ): HttpClient = HttpClient(
        MockEngine { request ->
            capture += ((request.body as? TextContent)?.text ?: "")
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    ) {
        expectSuccess = false
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private val schema: JsonObject = Json.parseToJsonElement("""{"type":"object","properties":{}}""").jsonObject

    @Test
    fun `done_reason length marks the result truncated and the request carries roomy num_predict`() = runTest {
        val requests = mutableListOf<String>()
        val provider = OllamaChatProvider(
            client(
                """{"message":{"role":"assistant","content":"{\"facts\":[\"The Kotlin"},"done_reason":"length","prompt_eval_count":900,"eval_count":1536}""",
                requests,
            ),
            "http://fake-ollama",
            "qwen2.5:1.5b",
        )
        val result = provider.completeJson("system", "user", schema)
        assertThat(result.truncated).isTrue()
        assertThat(result.text).isEqualTo("{\"facts\":[\"The Kotlin")

        val sent = Json.parseToJsonElement(requests.single()).jsonObject
        val options = sent["options"]!!.jsonObject
        assertThat(options["num_predict"]!!.jsonPrimitive.content.toInt())
            .describedAs("a cut extraction cannot reach the quarantine as bad JSON if the cap leaves room")
            .isGreaterThanOrEqualTo(1_536)
    }

    @Test
    fun `done_reason stop is a clean generation`() = runTest {
        val provider = OllamaChatProvider(
            client(
                """{"message":{"role":"assistant","content":"{\"facts\":[],\"decisions\":[],\"preferences\":[],\"summary\":\"ok\"}"},"done_reason":"stop"}""",
            ),
            "http://fake-ollama",
            "qwen2.5:1.5b",
        )
        assertThat(provider.completeJson("system", "user", schema).truncated).isFalse()
    }

    @Test
    fun `a glm finish_reason of length marks the result truncated`() = runTest {
        val provider = GlmChatProvider(
            client(
                """{"choices":[{"message":{"role":"assistant","content":"{\"facts\":[\"cut"},"finish_reason":"length"}],"usage":{"prompt_tokens":10,"completion_tokens":99}}""",
            ),
            "http://fake-glm",
            "glm-4.5-flash",
            "test-key",
        )
        assertThat(provider.completeJson("system", "user", schema).truncated).isTrue()
    }
}
