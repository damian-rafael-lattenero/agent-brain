package dev.lattenero.brain.rag

import dev.lattenero.brain.embeddings.BaseProvider
import dev.lattenero.brain.embeddings.FakeProvider
import dev.lattenero.brain.embeddings.ProviderUnavailable
import kap.CircuitBreaker
import kap.CircuitBreakerOpenException
import kap.evalGraph
import kap.kotest.shouldBeClosed
import kap.kotest.shouldBeHalfOpen
import kap.kotest.shouldBeOpen
import kap.kotest.shouldProveParallel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.TimeMark

/** Virtual time throughout: a 50 ms delay costs 0 ms of wall clock, so parallelism is PROVEN, not timed. */
@OptIn(ExperimentalCoroutinesApi::class)
class EmbedPipelineTest {
    private class ScriptedProvider(
        override val dim: Int = 8,
        private val failFirst: Int = 0,
        private val hangFirst: Int = 0,
        private val perCall: Duration = Duration.ZERO,
    ) : BaseProvider() {
        override val name = "scripted"
        override val model = "scripted-model"
        val calls = AtomicInteger()
        private val inner = FakeProvider(dim)

        override suspend fun embedAll(texts: List<String>): List<FloatArray> {
            val n = calls.incrementAndGet()
            if (n <= hangFirst) delay(10.minutes)
            if (n <= failFirst) throw ProviderUnavailable("scripted failure $n")
            if (perCall > Duration.ZERO) delay(perCall)
            return inner.embed(texts)
        }
    }

    /**
     * The breaker's cooldown is measured by ITS injected [TimeSource], not by
     * the test's virtual scheduler — so the F79e tests step this clock across
     * the reset window by hand.
     */
    private class SteppingClock : TimeSource {
        val nowNanos = java.util.concurrent.atomic.AtomicLong()
        override fun markNow(): TimeMark {
            val start = nowNanos.get()
            return object : TimeMark {
                override fun elapsedNow(): Duration = (nowNanos.get() - start).nanoseconds
            }
        }

        fun advance(duration: Duration) {
            nowNanos.addAndGet(duration.inWholeNanoseconds)
        }
    }

    private fun pipeline(
        provider: dev.lattenero.brain.embeddings.EmbeddingProvider,
        batchSize: Int = 1,
        concurrency: Int = 4,
        timeout: Duration = 1.seconds,
        attempts: Int = 3,
        breaker: CircuitBreaker = CircuitBreaker(maxFailures = 100, retryAfter = 1.minutes),
    ) = EmbedPipeline(provider, batchSize, concurrency, timeout, CountingTracer(), breaker, attempts, backoffBase = 10.seconds.div(100))

    @Test
    fun `batches run in parallel up to the configured concurrency`() = runTest {
        val wide = ScriptedProvider(perCall = 50.seconds.div(1000))
        pipeline(wide, concurrency = 4).embedAll(listOf("a", "b", "c", "d")).evalGraph()
        currentTime.shouldProveParallel(taskCount = 4, taskDurationMs = 50)
        assertThat(wide.calls.get()).isEqualTo(4)

        val narrow = ScriptedProvider(perCall = 50.seconds.div(1000))
        val start = currentTime
        pipeline(narrow, concurrency = 2).embedAll(listOf("a", "b", "c", "d")).evalGraph()
        assertThat(currentTime - start).isEqualTo(100)
    }

    @Test
    fun `vectors come back in input order and batching respects the size`() = runTest {
        val provider = ScriptedProvider()
        val texts = (1..7).map { "t$it" }
        val vectors = pipeline(provider, batchSize = 3).embedAll(texts).evalGraph()
        assertThat(vectors).hasSize(7)
        assertThat(provider.calls.get()).isEqualTo(3)
        val direct = FakeProvider(8).embed(texts)
        vectors.zip(direct).forEach { (got, want) -> assertThat(got).containsExactly(*want) }
        assertThat(pipeline(ScriptedProvider()).embedAll(emptyList()).evalGraph()).isEmpty()
    }

    @Test
    fun `transient failures are retried with backoff and exhausted attempts surface the last error`() = runTest {
        val flaky = ScriptedProvider(failFirst = 2)
        assertThat(pipeline(flaky, attempts = 3).embedAll(listOf("x")).evalGraph()).hasSize(1)
        assertThat(flaky.calls.get()).isEqualTo(3)
        assertThat(currentTime).isGreaterThan(0)

        val dead = ScriptedProvider(failFirst = 99)
        assertThatThrownBy { kotlinx.coroutines.runBlocking { pipeline(dead, attempts = 3).embedAll(listOf("x")).evalGraph() } }
            .isInstanceOf(ProviderUnavailable::class.java)
            .hasMessageContaining("scripted failure 3")
        assertThat(dead.calls.get()).isEqualTo(3)
    }

    @Test
    fun `a timeout is converted into an ordinary failure and therefore retried`() = runTest {
        val hanging = ScriptedProvider(hangFirst = 1)
        val vectors = pipeline(hanging, timeout = 1.seconds, attempts = 2).embedAll(listOf("x")).evalGraph()
        assertThat(vectors).hasSize(1)
        assertThat(hanging.calls.get()).isEqualTo(2)
        assertThat(currentTime).isGreaterThanOrEqualTo(1_000)

        val alwaysHanging = ScriptedProvider(hangFirst = 99)
        assertThatThrownBy { kotlinx.coroutines.runBlocking { pipeline(alwaysHanging, timeout = 1.seconds, attempts = 2).embedAll(listOf("x")).evalGraph() } }
            .isInstanceOf(kap.KapTimeoutException::class.java)
            .hasMessageContaining("timed out")
    }

    @Test
    fun `the circuit breaker opens after repeated failures and then fails fast without calling the provider`() = runTest {
        val dead = ScriptedProvider(failFirst = 99)
        val breaker = CircuitBreaker(maxFailures = 2, retryAfter = 1.minutes)
        val pipeline = pipeline(dead, attempts = 1, breaker = breaker)
        repeat(2) {
            assertThatThrownBy { kotlinx.coroutines.runBlocking { pipeline.embedBatch(listOf("x")).evalGraph() } }
                .isInstanceOf(ProviderUnavailable::class.java)
        }
        breaker.shouldBeOpen()
        assertThatThrownBy { kotlinx.coroutines.runBlocking { pipeline.embedBatch(listOf("x")).evalGraph() } }
            .isInstanceOf(CircuitBreakerOpenException::class.java)
        assertThat(dead.calls.get()).isEqualTo(2)
    }

    @Test
    fun `the F79e lesson - the breaker closes on the SECOND consecutive probe success, not the first`() = runTest {
        // Calls 1..5 fail (the default knob opens the circuit); the provider
        // answers from call 6 on. One attempt per batch, so one call each.
        val clock = SteppingClock()
        val recovering = ScriptedProvider(failFirst = EmbedPipeline.DEFAULT_BREAKER_FAILURES)
        val breaker = CircuitBreaker(
            maxFailures = EmbedPipeline.DEFAULT_BREAKER_FAILURES,
            retryAfter = EmbedPipeline.DEFAULT_BREAKER_RESET,
            successThreshold = EmbedPipeline.DEFAULT_BREAKER_SUCCESS_THRESHOLD,
            timeSource = clock,
        )
        val pipeline = pipeline(recovering, attempts = 1, breaker = breaker)
        repeat(EmbedPipeline.DEFAULT_BREAKER_FAILURES) {
            assertThatThrownBy { kotlinx.coroutines.runBlocking { pipeline.embedBatch(listOf("x")).evalGraph() } }
                .isInstanceOf(ProviderUnavailable::class.java)
        }
        breaker.shouldBeOpen()

        // The cooldown passes (the breaker's own clock) and a probe is
        // admitted. The FIRST success leaves the circuit HalfOpen — kap 4.x
        // closed it here; the Python F79e lesson demands two consecutive
        // probe successes.
        clock.advance(EmbedPipeline.DEFAULT_BREAKER_RESET)
        assertThat(pipeline.embedBatch(listOf("x")).evalGraph()).hasSize(1)
        breaker.shouldBeHalfOpen()
        assertThat(recovering.calls.get()).isEqualTo(EmbedPipeline.DEFAULT_BREAKER_FAILURES + 1)

        // The SECOND consecutive success closes the circuit for real.
        assertThat(pipeline.embedBatch(listOf("x")).evalGraph()).hasSize(1)
        breaker.shouldBeClosed()
    }

    @Test
    fun `a failed probe reopens the breaker - one lucky answer never closes the circuit`() = runTest {
        // Calls 0..4 fail (opens the circuit), call 5 answers once, call 6 fails again.
        val clock = SteppingClock()
        val plan = listOf(false, false, false, false, false, true, false)
        val calls = AtomicInteger()
        val scripted = object : BaseProvider() {
            override val name = "scripted-reopen"
            override val model = "scripted-reopen-model"
            override val dim = 8
            private val inner = FakeProvider(8)
            override suspend fun embedAll(texts: List<String>): List<FloatArray> {
                val n = calls.getAndIncrement()
                return if (n < plan.size && plan[n]) inner.embed(texts) else throw ProviderUnavailable("scripted failure $n")
            }
        }
        val breaker = CircuitBreaker(
            maxFailures = EmbedPipeline.DEFAULT_BREAKER_FAILURES,
            retryAfter = EmbedPipeline.DEFAULT_BREAKER_RESET,
            successThreshold = EmbedPipeline.DEFAULT_BREAKER_SUCCESS_THRESHOLD,
            timeSource = clock,
        )
        val failing = pipeline(scripted, attempts = 1, breaker = breaker)
        repeat(EmbedPipeline.DEFAULT_BREAKER_FAILURES) {
            assertThatThrownBy { kotlinx.coroutines.runBlocking { failing.embedBatch(listOf("x")).evalGraph() } }
                .isInstanceOf(ProviderUnavailable::class.java)
        }
        breaker.shouldBeOpen()

        // One lucky probe answer must NOT close the circuit (F79e): the next
        // failing probe reopens it instead.
        clock.advance(EmbedPipeline.DEFAULT_BREAKER_RESET)
        assertThat(failing.embedBatch(listOf("x")).evalGraph()).hasSize(1)
        assertThatThrownBy { kotlinx.coroutines.runBlocking { failing.embedBatch(listOf("x")).evalGraph() } }
            .isInstanceOf(ProviderUnavailable::class.java)
        breaker.shouldBeOpen()
    }

    @Test
    fun `the tracer counts successes, failures and time per phase`() = runTest {
        val tracer = CountingTracer()
        val flaky = ScriptedProvider(failFirst = 1, perCall = 20.seconds.div(1000))
        val pipeline = EmbedPipeline(flaky, 1, 1, 1.seconds, tracer, CircuitBreaker(maxFailures = 100, retryAfter = 1.minutes), attempts = 2)
        pipeline.embedAll(listOf("a", "b")).evalGraph()
        val phase = tracer.phases.getValue(EmbedPipeline.PHASE_EMBED)
        // traced wraps the retry, so the retried first call counts as ONE success; durations are wall clock.
        assertThat(phase.snapshot().count).isEqualTo(2)
        assertThat(phase.snapshot().failures).isZero
        assertThat(phase.snapshot().totalMillis).isGreaterThanOrEqualTo(0)

        val dead = ScriptedProvider(failFirst = 99)
        val failing = EmbedPipeline(dead, 1, 1, 1.seconds, tracer, CircuitBreaker(maxFailures = 100, retryAfter = 1.minutes), attempts = 1)
        assertThatThrownBy { kotlinx.coroutines.runBlocking { failing.embedAll(listOf("z")).evalGraph() } }
        assertThat(phase.snapshot().count).isEqualTo(3)
        assertThat(phase.snapshot().failures).isEqualTo(1)
    }
}
