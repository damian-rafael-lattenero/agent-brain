package dev.lattenero.brain.rag

import dev.lattenero.brain.embeddings.EmbeddingProvider
import dev.lattenero.brain.embeddings.ProviderResilience
import dev.lattenero.brain.embeddings.withProviderResilience
import kap.CircuitBreaker
import kap.Kap
import kap.KapTracer
import kap.Ref
import kap.SyncCell
import kap.TraceEvent
import kap.map
import kap.traced
import kap.traverse
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val log = LoggerFactory.getLogger("agent-brain.embed")

/**
 * One phase's counters as one immutable value (the [CountingTracer.Phase] read
 * surface).
 */
data class PhaseCounters(val count: Long = 0, val failures: Long = 0, val totalMillis: Long = 0) {
    /** Folds one finished event in: every finished call counts, a failure is also a failure, time always accrues. */
    fun record(succeeded: Boolean, duration: Duration): PhaseCounters = copy(
        count = count + 1,
        failures = if (succeeded) failures else failures + 1,
        totalMillis = totalMillis + duration.inWholeMilliseconds,
    )
}

/**
 * Per-phase counters fed by KAP's `traced` combinator; the index report prints
 * them.
 *
 * [KapTracer.onEvent] is a non-suspend SPI callback, so the counters cannot be
 * a kap `Ref` (whose operations suspend). kap 5.1's [SyncCell] is exactly the
 * non-suspending counterpart — [Ref.sync] gives a lock-free atomic register
 * with the same laws — so each phase holds ONE cell over [PhaseCounters]: an
 * event is a single atomic read-modify-write (count, failures and millis move
 * together) and [Phase.snapshot] observes a value the cell actually held. The
 * map of phases is a [ConcurrentHashMap] because `computeIfAbsent` is the
 * non-suspending way to create a phase on first sight of its name.
 */
class CountingTracer : KapTracer {
    class Phase {
        private val state: SyncCell<PhaseCounters> = Ref.sync(PhaseCounters())

        /** The counters as one consistent value. */
        fun snapshot(): PhaseCounters = state.get()

        fun record(succeeded: Boolean, duration: Duration) {
            state.update { it.record(succeeded, duration) }
        }
    }

    val phases = ConcurrentHashMap<String, Phase>()

    override fun onEvent(event: TraceEvent) {
        val phase = phases.computeIfAbsent(event.name) { Phase() }
        when (event) {
            is TraceEvent.Started -> Unit
            is TraceEvent.Succeeded -> phase.record(succeeded = true, duration = event.duration)
            is TraceEvent.Failed -> phase.record(succeeded = false, duration = event.duration)
        }
    }
}

/**
 * Embedding as a KAP graph: texts are batched, batches run with bounded
 * parallelism, and every provider call carries the resilience policy
 * (timeout, retry with jittered exponential backoff, circuit breaker).
 *
 * Two KAP facts shape this code. A plain `timeout(d)` is a CancellationException
 * that `retry` rethrows by law, so the pipeline uses `timeoutAsFailure` (KAP
 * 4.1.0) to make a timeout retryable. And `Schedule` instances may hold state,
 * so the retry takes a factory and builds a fresh policy per call.
 */
class EmbedPipeline(
    private val provider: EmbeddingProvider,
    val batchSize: Int,
    private val concurrency: Int,
    private val timeout: Duration,
    private val tracer: KapTracer,
    val breaker: CircuitBreaker = CircuitBreaker(
        maxFailures = ProviderResilience.DEFAULT_BREAKER_FAILURES,
        retryAfter = ProviderResilience.DEFAULT_BREAKER_RESET,
        successThreshold = ProviderResilience.DEFAULT_BREAKER_SUCCESS_THRESHOLD,
    ),
    private val attempts: Int = ProviderResilience.DEFAULT_ATTEMPTS,
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
            .withProviderResilience(timeout, attempts, backoffBase, breaker) { attempt, error, delay ->
                log.warn("embed batch retry {} in {} after {}: {}", attempt, delay, error::class.simpleName, error.message)
            }
            .traced(PHASE_EMBED, tracer)

    companion object {
        const val PHASE_EMBED = "embed"
        val DEFAULT_BACKOFF_BASE = 200.milliseconds
    }
}
