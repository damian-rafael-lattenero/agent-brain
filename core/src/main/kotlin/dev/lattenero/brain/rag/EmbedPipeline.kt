package dev.lattenero.brain.rag

import dev.lattenero.brain.embeddings.EmbeddingProvider
import dev.lattenero.brain.embeddings.ProviderUnavailable
import kap.CircuitBreaker
import kap.Kap
import kap.KapTracer
import kap.Schedule
import kap.TraceEvent
import kap.map
import kap.of
import kap.retry
import kap.timeout
import kap.traced
import kap.traverse
import kap.withCircuitBreaker
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("agent-brain.embed")

/** Per-phase counters fed by KAP's `traced` combinator; the index report prints them. */
class CountingTracer : KapTracer {
    class Phase {
        val count = AtomicLong()
        val failures = AtomicLong()
        val totalMillis = AtomicLong()
    }

    val phases = ConcurrentHashMap<String, Phase>()

    override fun onEvent(event: TraceEvent) {
        val phase = phases.computeIfAbsent(event.name) { Phase() }
        when (event) {
            is TraceEvent.Started -> Unit
            is TraceEvent.Succeeded -> {
                phase.count.incrementAndGet()
                phase.totalMillis.addAndGet(event.duration.inWholeMilliseconds)
            }
            is TraceEvent.Failed -> {
                phase.count.incrementAndGet()
                phase.failures.incrementAndGet()
                phase.totalMillis.addAndGet(event.duration.inWholeMilliseconds)
            }
        }
    }
}

/**
 * Embedding as a KAP graph: texts are batched, batches run with bounded
 * parallelism, and every provider call carries the resilience policy
 * (timeout, retry with jittered exponential backoff, circuit breaker).
 *
 * Two KAP facts shape this code. `retry` rethrows CancellationException by
 * law, and `TimeoutCancellationException` is one, so a plain `timeout(d)`
 * before `retry` would never be retried: the fallback overload converts the
 * timeout into an ordinary failure first. And `Schedule` instances may hold
 * state, so the retry takes a factory and builds a fresh policy per call.
 */
class EmbedPipeline(
    private val provider: EmbeddingProvider,
    private val batchSize: Int,
    private val concurrency: Int,
    private val timeout: Duration,
    private val tracer: KapTracer,
    val breaker: CircuitBreaker = CircuitBreaker(maxFailures = DEFAULT_BREAKER_FAILURES, resetTimeout = DEFAULT_BREAKER_RESET),
    private val attempts: Int = DEFAULT_ATTEMPTS,
    private val backoffBase: Duration = DEFAULT_BACKOFF_BASE,
) {
    init {
        require(batchSize > 0 && concurrency > 0 && attempts > 0)
    }

    val model: String get() = provider.model

    fun embedAll(texts: List<String>): Kap<List<FloatArray>> {
        if (texts.isEmpty()) return Kap.of(emptyList())
        return texts.chunked(batchSize)
            .traverse(concurrency) { batch -> embedBatch(batch) }
            .map { perBatch -> perBatch.flatten() }
    }

    fun embedBatch(batch: List<String>): Kap<List<FloatArray>> =
        Kap { provider.embed(batch) }
            .timeout(timeout, Kap.failed(ProviderUnavailable("${provider.name} embedding timed out after $timeout")))
            .retry({ Schedule.times<Throwable>(attempts - 1) and Schedule.exponential<Throwable>(backoffBase).jittered() }) { attempt, error, delay ->
                log.warn("embed batch retry {} in {} after {}: {}", attempt, delay, error::class.simpleName, error.message)
            }
            .withCircuitBreaker(breaker)
            .traced(PHASE_EMBED, tracer)

    companion object {
        const val PHASE_EMBED = "embed"
        const val DEFAULT_BREAKER_FAILURES = 5
        val DEFAULT_BREAKER_RESET = 30.seconds
        const val DEFAULT_ATTEMPTS = 3
        val DEFAULT_BACKOFF_BASE = 200.milliseconds
    }
}
