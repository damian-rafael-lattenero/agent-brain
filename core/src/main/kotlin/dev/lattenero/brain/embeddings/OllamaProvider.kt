package dev.lattenero.brain.embeddings

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import java.io.IOException

/** Local embeddings over POST {base}/api/embed (the batch endpoint). Zero cost, no key; the default provider. */
class OllamaProvider(
    private val client: HttpClient,
    baseUrl: String,
    override val model: String,
    override val dim: Int,
    private val batchSize: Int,
) : BaseProvider() {
    override val name = "ollama"
    private val baseUrl = baseUrl.trimEnd('/')

    @Serializable
    private data class Request(val model: String, val input: List<String>)

    @Serializable
    private data class Response(val embeddings: List<List<Float>> = emptyList())

    override suspend fun embedAll(texts: List<String>): List<FloatArray> =
        batches(texts, batchSize).flatMap { call(it) }

    private suspend fun call(batch: List<String>): List<FloatArray> {
        val url = "$baseUrl/api/embed"
        val response = try {
            client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(Request(model, batch))
            }
        } catch (e: HttpRequestTimeoutException) {
            throw ProviderUnavailable("ollama timed out at $baseUrl", e)
        } catch (e: IOException) {
            throw ProviderUnavailable("ollama unreachable at $baseUrl (${e.message})", e)
        }
        when (response.status) {
            HttpStatusCode.OK -> Unit
            HttpStatusCode.NotFound -> throw ProviderException(
                "ollama model '$model' is not pulled (ollama pull $model): ${response.bodyAsText().take(ERROR_TAIL)}",
            )
            else -> throw ProviderException(
                "ollama $url -> HTTP ${response.status.value}: ${response.bodyAsText().take(ERROR_TAIL)}",
            )
        }
        val body: Response = response.body()
        return body.embeddings.map { it.toFloatArray() }
    }

    companion object {
        const val ERROR_TAIL = 200
    }
}
