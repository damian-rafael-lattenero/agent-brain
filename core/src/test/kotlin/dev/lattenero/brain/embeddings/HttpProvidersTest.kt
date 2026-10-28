package dev.lattenero.brain.embeddings

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.ServerSocket

/** Wire-level behaviour of the HTTP providers against a scripted server. */
class HttpProvidersTest {
    private val recorded = mutableListOf<HttpRequestData>()

    private fun client(status: HttpStatusCode, body: String) = HttpClient(
        MockEngine { request ->
            recorded += request
            respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        },
    ) {
        expectSuccess = false
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private fun bodyOf(request: HttpRequestData) = Json.parseToJsonElement((request.body as TextContent).text).jsonObject

    @Test
    fun `ollama posts model and input to api embed and batches by the configured size`() = runTest {
        val client = client(HttpStatusCode.OK, """{"embeddings": [[0.1, 0.2], [0.3, 0.4]]}""")
        val provider = OllamaProvider(client, "http://ollama.test/", "nomic", dim = 2, batchSize = 2)
        val vectors = provider.embed(listOf("a", "b", "c", "d"))
        assertThat(vectors).hasSize(4)
        assertThat(recorded).hasSize(2)
        assertThat(recorded.first().url.toString()).isEqualTo("http://ollama.test/api/embed")
        val body = bodyOf(recorded.first())
        assertThat(body["model"]!!.jsonPrimitive.content).isEqualTo("nomic")
        assertThat(body["input"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("a", "b")
    }

    @Test
    fun `ollama 404 says the model is not pulled, other statuses surface the body`() = runTest {
        val missing = OllamaProvider(client(HttpStatusCode.NotFound, """{"error":"model not found"}"""), "http://o", "m", 2, 8)
        assertThatThrownBy { kotlinx.coroutines.runBlocking { missing.embed(listOf("x")) } }
            .isInstanceOf(ProviderException::class.java)
            .hasMessageContaining("ollama pull m")
        val broken = OllamaProvider(client(HttpStatusCode.InternalServerError, "boom"), "http://o", "m", 2, 8)
        assertThatThrownBy { kotlinx.coroutines.runBlocking { broken.embed(listOf("x")) } }
            .isInstanceOf(ProviderException::class.java)
            .hasMessageContaining("HTTP 500")
            .hasMessageContaining("boom")
    }

    @Test
    fun `a closed port is reported as unavailable, not as a bug`() = runTest {
        val port = ServerSocket(0).use { it.localPort }
        val provider = OllamaProvider(dev.lattenero.brain.http.HttpClients.create(5), "http://127.0.0.1:$port", "m", 2, 8)
        assertThatThrownBy { kotlinx.coroutines.runBlocking { provider.embed(listOf("x")) } }
            .isInstanceOf(ProviderUnavailable::class.java)
            .hasMessageContaining("unreachable")
    }

    @Test
    fun `glm sends the bearer key and requested dimensions, and trusts index over order`() = runTest {
        val body = """{"data": [{"index": 1, "embedding": [1.0, 0.0]}, {"index": 0, "embedding": [0.0, 1.0]}]}"""
        val client = client(HttpStatusCode.OK, body)
        val provider = GlmProvider(client, "https://glm.test/v4", "embedding-3", dim = 2, batchSize = 8, apiKey = "sk-test")
        val vectors = provider.embed(listOf("first", "second"))
        assertThat(vectors[0]).containsExactly(0f, 1f)
        assertThat(vectors[1]).containsExactly(1f, 0f)
        val request = recorded.single()
        assertThat(request.url.toString()).isEqualTo("https://glm.test/v4/embeddings")
        assertThat(request.headers[HttpHeaders.Authorization]).isEqualTo("Bearer sk-test")
        assertThat(bodyOf(request)["dimensions"]!!.jsonPrimitive.content).isEqualTo("2")
    }

    @Test
    fun `glm rejections name the key problem`() = runTest {
        val provider = GlmProvider(client(HttpStatusCode.Unauthorized, """{"error":"bad key"}"""), "https://g", "e", 2, 8, "sk")
        assertThatThrownBy { kotlinx.coroutines.runBlocking { provider.embed(listOf("x")) } }
            .isInstanceOf(ProviderException::class.java)
            .hasMessageContaining("rejected the key")
    }

    @Test
    fun `a provider answering the wrong dimension is refused before anything is stored`() = runTest {
        val provider = OllamaProvider(client(HttpStatusCode.OK, """{"embeddings": [[0.1, 0.2, 0.3]]}"""), "http://o", "m", dim = 2, batchSize = 8)
        assertThatThrownBy { kotlinx.coroutines.runBlocking { provider.embed(listOf("x")) } }
            .isInstanceOf(DimensionMismatch::class.java)
    }
}
