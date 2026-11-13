package dev.lattenero.brain.embeddings

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.setBody
import kotlinx.serialization.Serializable

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
        val response = postForProvider(client, url, "ollama", ollamaErrors(model, ::ProviderException)) {
            setBody(Request(model, batch))
        }
        val body: Response = response.body()
        return body.embeddings.map { it.toFloatArray() }
    }
}
