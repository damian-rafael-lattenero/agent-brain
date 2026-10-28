package dev.lattenero.brain.embeddings

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

/**
 * The contract every provider must honour. Fake runs it as a unit test;
 * Ollama and GLM run the very same assertions against the real service.
 */
abstract class EmbeddingProviderContract {
    protected abstract fun provider(): EmbeddingProvider

    @Test
    fun `empty input yields empty output without calling anything`() = runTest {
        assertThat(provider().embed(emptyList())).isEmpty()
    }

    @Test
    fun `one vector per text, each of the declared dimension, all finite`() = runTest {
        val provider = provider()
        val texts = listOf("the quick brown fox", "jumps over the lazy dog", "")
        val vectors = provider.embed(texts)
        assertThat(vectors).hasSize(texts.size)
        vectors.forEach { vector ->
            assertThat(vector).hasSize(provider.dim)
            assertThat(vector.all { it.isFinite() }).isTrue
        }
    }

    @Test
    fun `the same text embeds to the same vector`() = runTest {
        val provider = provider()
        val first = provider.embed(listOf("deterministic")).single()
        val second = provider.embed(listOf("deterministic")).single()
        assertThat(cosine(first, second)).isCloseTo(1.0, within(1e-4))
    }

    @Test
    fun `health reports ok with the model and dimension`() = runTest {
        val provider = provider()
        val health = provider.health()
        assertThat(health.ok).withFailMessage(health.detail).isTrue
        assertThat(health.provider).isEqualTo(provider.name)
        assertThat(health.model).isEqualTo(provider.model)
        assertThat(health.dim).isEqualTo(provider.dim)
    }

    companion object {
        fun cosine(a: FloatArray, b: FloatArray): Double {
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in a.indices) {
                dot += a[i].toDouble() * b[i]
                na += a[i].toDouble() * a[i]
                nb += b[i].toDouble() * b[i]
            }
            return dot / (sqrt(na) * sqrt(nb))
        }

        fun within(tolerance: Double): org.assertj.core.data.Offset<Double> = org.assertj.core.data.Offset.offset(tolerance)
    }
}
