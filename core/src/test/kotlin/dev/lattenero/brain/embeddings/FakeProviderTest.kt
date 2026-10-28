package dev.lattenero.brain.embeddings

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.abs

class FakeProviderTest {
    @Nested
    inner class HashContract : EmbeddingProviderContract() {
        override fun provider(): EmbeddingProvider = FakeProvider(dim = 64)
    }

    @Nested
    inner class BowContract : EmbeddingProviderContract() {
        override fun provider(): EmbeddingProvider = FakeProvider(dim = 64, mode = FakeProvider.Mode.BOW)
    }

    @Test
    fun `hash mode keeps different texts near-orthogonal`() = runTest {
        val provider = FakeProvider(dim = 256)
        val (a, b) = provider.embed(listOf("alpha", "beta"))
        assertThat(abs(EmbeddingProviderContract.cosine(a, b))).isLessThan(0.25)
    }

    @Test
    fun `bow mode ranks texts that share words closer`() = runTest {
        val provider = FakeProvider(dim = 256, mode = FakeProvider.Mode.BOW)
        val (query, related, unrelated) = provider.embed(
            listOf("kafka consumer rebalance", "the kafka consumer group rebalance protocol", "quarterly tax forms"),
        )
        val near = EmbeddingProviderContract.cosine(query, related)
        val far = EmbeddingProviderContract.cosine(query, unrelated)
        assertThat(near).isGreaterThan(far)
        assertThat(near).isGreaterThan(0.5)
    }

    @Test
    fun `vectors are unit length and calls are counted`() = runTest {
        val provider = FakeProvider(dim = 32)
        val vector = provider.embed(listOf("x")).single()
        val norm = Math.sqrt(vector.sumOf { it.toDouble() * it })
        assertThat(norm).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5))
        provider.embed(listOf("y", "z"))
        assertThat(provider.calls).isEqualTo(2)
    }
}
