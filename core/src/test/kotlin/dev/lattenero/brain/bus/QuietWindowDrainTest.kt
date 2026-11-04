package dev.lattenero.brain.bus

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the quiet-window drain's CONTRACT at the call site (`ingest --once`,
 * the ITs): poll counts in, the sum of the reports with work out, and the poll
 * stops the moment the window fills.
 *
 * The loop itself is kap 5.1's [kap.KapStream.pollUntilQuiet] stopped by
 * [kap.Schedule.debounce]; its laws (work-only emission, quiet-driven
 * termination, cadence) are pinned by kap's own suite, not restated here. The
 * old value-fold test went with the retired `DrainProgress` interim — the
 * `tally` fold no longer exists anywhere in this repo. The goldens below did
 * NOT change under the migration: same totals, same poll counts.
 */
class QuietWindowDrainTest {

    /** A poll over a scripted list of counts; records how many times it was called. */
    private class ScriptedPolls(counts: List<Int>) {
        private val script = counts.iterator()
        var calls: Int = 0
            private set

        suspend fun poll(): Int {
            calls += 1
            return if (script.hasNext()) script.next() else 0
        }
    }

    @Test
    fun `the drain stops at the first filled quiet window and returns the total handed over`() = runBlocking<Unit> {
        val polls = ScriptedPolls(listOf(3, 4, 0, 2, 0, 0, 0, 99))
        val total = drainUntilQuiet(quietPolls = 3) { polls.poll() }
        assertThat(total).isEqualTo(9)
        assertThat(polls.calls).isEqualTo(7) // the 99 is never polled: the window filled first
    }

    @Test
    fun `a non-empty poll resets the quiet run`() = runBlocking<Unit> {
        val polls = ScriptedPolls(listOf(1, 0, 0, 5, 0, 0, 7, 0, 0, 0))
        val total = drainUntilQuiet(quietPolls = 3) { polls.poll() }
        assertThat(total).isEqualTo(13)
        assertThat(polls.calls).isEqualTo(10)
    }

    @Test
    fun `a quiet window of zero stops after the first poll`() = runBlocking<Unit> {
        val polls = ScriptedPolls(listOf(2, 1, 1))
        val total = drainUntilQuiet(quietPolls = 0) { polls.poll() }
        assertThat(total).isEqualTo(2)
        assertThat(polls.calls).isEqualTo(1)
    }

    @Test
    fun `quiet polls contribute nothing to the total`() = runBlocking<Unit> {
        // The report's total is THAT poll's count: only non-quiet reports are
        // emitted, so the sum never double-counts and empty polls add zero.
        val polls = ScriptedPolls(listOf(0, 0, 3, 0, 0, 0))
        val total = drainUntilQuiet(quietPolls = 3) { polls.poll() }
        assertThat(total).isEqualTo(3)
        assertThat(polls.calls).isEqualTo(6)
    }
}
