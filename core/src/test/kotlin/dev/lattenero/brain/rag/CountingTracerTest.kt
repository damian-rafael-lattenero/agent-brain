package dev.lattenero.brain.rag

import kap.TraceEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The tracer's counters are [kap.SyncCell] registers (kap 5.1, [kap.Ref.sync]):
 * one atomic read-modify-write per event, no suspension, no lock. These laws
 * are what the old AtomicLong triple could not keep together:
 *
 * 1. **Atomicity under contention** — N threads emitting M events leave exactly
 *    N*M counted, no lost update.
 * 2. **One value, one step** — count, failures and millis advance in the same
 *    atomic step, so a [CountingTracer.Phase.snapshot] taken while the writers
 *    run is a state the tracer actually held: `totalMillis == count * DURATION`
 *    always (never a count from one step paired with millis from another) and
 *    `failures <= count`.
 */
class CountingTracerTest {

    private companion object {
        const val PHASE = "embed"
        const val THREADS = 8
        const val PER_THREAD = 1_000
        val DURATION: Duration = 2.milliseconds
    }

    @Test
    fun `concurrent events on one phase lose no update and never tear a snapshot`() {
        val tracer = CountingTracer()
        // Warm the phase through the same non-suspending path the writers use.
        tracer.onEvent(TraceEvent.Succeeded(PHASE, DURATION))
        val phase = tracer.phases.getValue(PHASE)

        val start = CountDownLatch(1)
        val done = AtomicBoolean(false)
        val torn = AtomicReference<PhaseCounters?>(null)
        val watcher = Thread {
            start.await()
            while (!done.get()) {
                val snapshot = phase.snapshot()
                val consistent = snapshot.totalMillis == snapshot.count * DURATION.inWholeMilliseconds &&
                    snapshot.failures <= snapshot.count
                if (!consistent) torn.compareAndSet(null, snapshot)
                Thread.onSpinWait()
            }
        }.apply { isDaemon = true; start() }

        val pool = Executors.newFixedThreadPool(THREADS)
        repeat(THREADS) { thread ->
            pool.execute {
                start.await()
                repeat(PER_THREAD) { i ->
                    // Half succeed, half fail: both branches of the fold, one known duration.
                    if ((thread + i) % 2 == 0) {
                        tracer.onEvent(TraceEvent.Succeeded(PHASE, DURATION))
                    } else {
                        tracer.onEvent(TraceEvent.Failed(PHASE, DURATION, IllegalStateException("scripted $thread/$i")))
                    }
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue()
        done.set(true)
        watcher.join()

        val events = 1L + THREADS * PER_THREAD
        val final = phase.snapshot()
        assertThat(final.count).isEqualTo(events)
        assertThat(final.totalMillis).isEqualTo(events * DURATION.inWholeMilliseconds)
        assertThat(final.failures).isEqualTo(THREADS * PER_THREAD / 2L)
        assertThat(torn.get()).describedAs("a snapshot must be a value the tracer actually held").isNull()
    }
}
