package dev.lattenero.brain.embeddings

import kap.CircuitBreaker
import kap.Kap
import kap.Schedule
import kap.retry
import kap.timeoutAsFailure
import kap.withCircuitBreaker
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The provider-call defaults (ONE home; the pipelines and the composition root share them). */
object ProviderResilience {
    const val DEFAULT_BREAKER_FAILURES = 5
    val DEFAULT_BREAKER_RESET: Duration = 30.seconds

    /**
     * The Python F79e lesson (the bot's transport breaker): ONE successful
     * probe in HalfOpen must NOT close the circuit — a provider that
     * answers once and breaks again would flap it open/closed forever.
     * The circuit closes on the SECOND consecutive probe success; a failed
     * probe reopens it. kap 4.x could not express this (it closed with 1);
     * the `successThreshold` unlock (kap 5.0) exists for exactly this.
     */
    const val DEFAULT_BREAKER_SUCCESS_THRESHOLD = 2
    const val DEFAULT_ATTEMPTS = 3
}

/**
 * The ONE retry+breaker chain every provider call wears (was: two copies in
 * EmbedPipeline.embedBatch and MemoryExtractor.extract). The call times out
 * as a FAILURE (so it is retryable — a plain kap `timeout` is a
 * CancellationException that `retry` rethrows by law) and is retried with a
 * FRESH jittered-exponential [Schedule] per call (Schedule instances may
 * hold state; the factory overload exists exactly for that), then guarded
 * by the circuit breaker. The phase trace stays at the call site (each
 * phase names itself), as does the retry log (each pipeline says what it
 * retries).
 */
fun <A> Kap<A>.withProviderResilience(
    timeout: Duration,
    attempts: Int,
    backoffBase: Duration,
    breaker: CircuitBreaker,
    onRetry: suspend (attempt: Int, error: Throwable, nextDelay: Duration) -> Unit,
): Kap<A> {
    val schedule = {
        Schedule.times<Throwable>(attempts - 1) and Schedule.exponential<Throwable>(backoffBase).jittered()
    }
    return timeoutAsFailure(timeout)
        .retry(schedule, onRetry)
        .withCircuitBreaker(breaker)
}
