package dev.lattenero.brain.embeddings

import dev.lattenero.brain.config.Config
import dev.lattenero.brain.config.ConfigException
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

open class ProviderException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The provider could not be reached at all (connection refused, timeout). */
class ProviderUnavailable(message: String, cause: Throwable? = null) : ProviderException(message, cause)

class DimensionMismatch(provider: String, model: String, val expected: Int, val got: Int) : ProviderException(
    "$provider/$model returned $got-dimensional vectors, the database expects $expected: " +
        "set embed_dim to $got (fresh database) or pick a model with $expected dims",
)

@Serializable
data class ProviderHealth(val provider: String, val model: String, val dim: Int, val ok: Boolean, val detail: String)

/**
 * ONE interface, three implementations (ollama, glm, fake). The contract
 * every provider honours and the shared test suite enforces: `embed(texts)`
 * returns exactly `texts.size` vectors of exactly [dim] finite floats,
 * `embed(emptyList())` is empty, and a wrong-length vector is raised as
 * [DimensionMismatch] naming the model (Postgres would refuse it anyway,
 * but the error must point at the model, not at the column).
 */
interface EmbeddingProvider : AutoCloseable {
    val name: String
    val model: String
    val dim: Int

    suspend fun embed(texts: List<String>): List<FloatArray>

    /** Embeds one probe and reports; never throws so /ready can show it. */
    suspend fun health(): ProviderHealth

    override fun close() {}
}

abstract class BaseProvider : EmbeddingProvider {
    final override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val vectors = embedAll(texts)
        if (vectors.size != texts.size) {
            throw ProviderException("$name/$model returned ${vectors.size} vectors for ${texts.size} texts")
        }
        for (vector in vectors) {
            if (vector.size != dim) throw DimensionMismatch(name, model, dim, vector.size)
            if (vector.any { !it.isFinite() }) throw ProviderException("$name/$model returned a non-finite value")
        }
        return vectors
    }

    protected abstract suspend fun embedAll(texts: List<String>): List<FloatArray>

    override suspend fun health(): ProviderHealth = try {
        val vectors = embed(listOf(PROBE))
        ProviderHealth(name, model, dim, ok = true, detail = "embedded ${vectors.size} probe(s)")
    } catch (e: CancellationException) {
        throw e
    } catch (e: ProviderException) {
        ProviderHealth(name, model, dim, ok = false, detail = e.message ?: e.toString())
    } catch (e: Exception) {
        ProviderHealth(name, model, dim, ok = false, detail = "${e::class.simpleName}: ${e.message}")
    }

    companion object {
        const val PROBE = "agent-brain readiness probe"

        fun batches(texts: List<String>, size: Int): List<List<String>> {
            require(size > 0) { "batch size must be positive" }
            return texts.chunked(size)
        }
    }
}

object EmbeddingProviders {
    /** The configured provider; the client is shared and owned by the caller. */
    fun create(config: Config, client: HttpClient): EmbeddingProvider = when (config.embedProvider) {
        "ollama" -> OllamaProvider(client, config.ollamaBaseUrl, config.ollamaModel, config.embedDim, config.embedBatch)
        "glm" -> GlmProvider(client, config.glmBaseUrl, config.glmModel, config.embedDim, config.embedBatch, config.glmApiKey)
        "fake" -> FakeProvider(config.embedDim)
        else -> throw ConfigException("embed_provider must be ollama, glm or fake, got '${config.embedProvider}'")
    }
}
