package dev.lattenero.brain.embeddings

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * Z.ai embeddings over the OpenAI-shaped POST {base}/embeddings.
 *
 * Needs GLM_API_KEY (or ZAI_API_KEY) in the environment: the OAuth coding
 * plan used by the ZCode harness does not cover this endpoint, so the key is
 * a separate, explicit credential. A missing key fails at construction.
 */
class GlmProvider(
    private val client: HttpClient,
    baseUrl: String,
    override val model: String,
    override val dim: Int,
    private val batchSize: Int,
    private val apiKey: String,
) : BaseProvider() {
    override val name = "glm"
    private val baseUrl = baseUrl.trimEnd('/')

    init {
        if (apiKey.isBlank()) {
            throw ProviderException("GLM_API_KEY is not set; export it or switch embed_provider to ollama")
        }
    }

    @Serializable
    private data class Request(val model: String, val input: List<String>, val dimensions: Int)

    @Serializable
    private data class Item(val index: Int = 0, val embedding: List<Float>)

    @Serializable
    private data class Response(val data: List<Item> = emptyList())

    override suspend fun embedAll(texts: List<String>): List<FloatArray> =
        batches(texts, batchSize).flatMap { call(it) }

    private suspend fun call(batch: List<String>): List<FloatArray> {
        val url = "$baseUrl/embeddings"
        val response = try {
            client.post(url) {
                contentType(ContentType.Application.Json)
                bearerAuth(apiKey)
                setBody(Request(model, batch, dim))
            }
        } catch (e: HttpRequestTimeoutException) {
            throw ProviderUnavailable("glm timed out at $baseUrl", e)
        } catch (e: IOException) {
            throw ProviderUnavailable("glm unreachable at $baseUrl (${e.message})", e)
        }
        when (response.status) {
            HttpStatusCode.OK -> Unit
            HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> throw ProviderException(
                "glm rejected the key (HTTP ${response.status.value}): ${response.bodyAsText().take(ERROR_TAIL)}",
            )
            else -> throw ProviderException(
                "glm $url -> HTTP ${response.status.value}: ${response.bodyAsText().take(ERROR_TAIL)}",
            )
        }
        val body: Response = response.body()
        // Items may come back out of order; `index` is authoritative.
        return body.data.sortedBy { it.index }.map { it.embedding.toFloatArray() }
    }

    companion object {
        const val ERROR_TAIL = 200
    }
}
