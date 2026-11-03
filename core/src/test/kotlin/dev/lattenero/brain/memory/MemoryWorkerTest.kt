package dev.lattenero.brain.memory

import dev.lattenero.brain.embeddings.ProviderUnavailable
import kap.CircuitBreakerOpenException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The extraction failure taxonomy (plan C1): every failure of one attempt lands in exactly one bin. */
class MemoryWorkerTest {
    @Test
    fun `invalid output is its own bin and carries the reason and the raw text`() {
        val failure = MemoryWorker.classify(ExtractionInvalid("not JSON", "I refuse to answer"), attemptsSoFar = 0, maxAttempts = 5)
        assertThat(failure).isEqualTo(ExtractionFailure.Invalid(reason = "not JSON", raw = "I refuse to answer"))
    }

    @Test
    fun `a provider failure under the cap defers with the attempt counted`() {
        val failure = MemoryWorker.classify(ProviderUnavailable("llm offline"), attemptsSoFar = 1, maxAttempts = 5)
        assertThat(failure).isEqualTo(ExtractionFailure.ProviderDown(attempts = 2, reason = "ProviderUnavailable: llm offline"))
    }

    @Test
    fun `the same failure at the cap gives up instead of deferring`() {
        val failure = MemoryWorker.classify(ProviderUnavailable("llm offline"), attemptsSoFar = 4, maxAttempts = 5)
        assertThat(failure).isEqualTo(ExtractionFailure.GaveUp(attempts = 5, reason = "ProviderUnavailable: llm offline"))
    }

    @Test
    fun `an open circuit breaker is a provider failure like any other - the C2 interim has no special case`() {
        val failure = MemoryWorker.classify(CircuitBreakerOpenException(), attemptsSoFar = 0, maxAttempts = 5)
        assertThat(failure).isEqualTo(ExtractionFailure.ProviderDown(attempts = 1, reason = "CircuitBreakerOpenException: Circuit breaker is open"))
    }
}
