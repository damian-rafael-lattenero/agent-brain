package dev.lattenero.brain.embeddings

import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.config.ConfigException
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode

class BaseProviderTest {
    private class Stub(override val dim: Int, private val answer: () -> List<FloatArray>) : BaseProvider() {
        override val name = "stub"
        override val model = "stub-model"
        override suspend fun embedAll(texts: List<String>): List<FloatArray> = answer()
    }

    @Test
    fun `a wrong-length vector is a DimensionMismatch that names the model`() = runTest {
        val stub = Stub(dim = 4) { listOf(FloatArray(3)) }
        assertThatThrownBy { kotlinx.coroutines.runBlocking { stub.embed(listOf("x")) } }
            .isInstanceOf(DimensionMismatch::class.java)
            .hasMessageContaining("stub/stub-model")
            .hasMessageContaining("3-dimensional")
            .hasMessageContaining("expects 4")
    }

    @Test
    fun `a wrong vector count and non-finite values are provider errors`() = runTest {
        val short = Stub(dim = 2) { listOf(FloatArray(2)) }
        assertThatThrownBy { kotlinx.coroutines.runBlocking { short.embed(listOf("a", "b")) } }
            .isInstanceOf(ProviderException::class.java)
            .hasMessageContaining("1 vectors for 2 texts")
        val nan = Stub(dim = 2) { listOf(floatArrayOf(Float.NaN, 0f)) }
        assertThatThrownBy { kotlinx.coroutines.runBlocking { nan.embed(listOf("a")) } }
            .isInstanceOf(ProviderException::class.java)
            .hasMessageContaining("non-finite")
    }

    @Test
    fun `health never throws, it reports the failure with detail`() = runTest {
        val broken = Stub(dim = 2) { throw ProviderUnavailable("nobody home") }
        val health = broken.health()
        assertThat(health.ok).isFalse
        assertThat(health.detail).contains("nobody home")

        val surprising = Stub(dim = 2) { throw IllegalStateException("bug") }
        assertThat(surprising.health().detail).contains("IllegalStateException").contains("bug")
    }

    @Test
    fun `batches split without losing or reordering`() {
        assertThat(BaseProvider.batches(listOf("a", "b", "c", "d", "e"), 2))
            .containsExactly(listOf("a", "b"), listOf("c", "d"), listOf("e"))
        assertThatThrownBy { BaseProvider.batches(listOf("a"), 0) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}

class EmbeddingProvidersTest {
    private val client = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) })

    @Test
    fun `the factory builds the configured provider`() {
        assertThat(EmbeddingProviders.create(TestEnv.config(provider = "fake"), client)).isInstanceOf(FakeProvider::class.java)
        assertThat(EmbeddingProviders.create(TestEnv.config(provider = "ollama"), client)).isInstanceOf(OllamaProvider::class.java)
        val withKey = TestEnv.config(provider = "glm", extra = mapOf("GLM_API_KEY" to "k"))
        assertThat(EmbeddingProviders.create(withKey, client)).isInstanceOf(GlmProvider::class.java)
    }

    @Test
    fun `glm without a key and unknown names fail at construction`() {
        val noKey = TestEnv.config(provider = "glm").copy(glmApiKey = "")
        assertThatThrownBy { EmbeddingProviders.create(noKey, client) }
            .isInstanceOf(ProviderException::class.java)
            .hasMessageContaining("GLM_API_KEY")
        assertThatThrownBy { EmbeddingProviders.create(TestEnv.config(provider = "banana"), client) }
            .isInstanceOf(ConfigException::class.java)
            .hasMessageContaining("banana")
    }
}
