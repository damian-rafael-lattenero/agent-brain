package dev.lattenero.brain.bus

import kap.evalGraph
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the quiet-window drain's value fold (Phase D): the repeated body
 * returns its [DrainProgress] state and the loop terminates on the returned
 * quiet run — the exact (total, quiet) semantics the closure vars used to
 * carry, and the poll stops the moment the window fills. This is the
 * hand-rolled stateful schedule plan.md's D2 calls interim until a kap
 * `Schedule` addition (debounce / only-once-per-key) takes over.
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
    fun `the fold carries the pinned total-and-quiet semantics`() {
        // The old vars over polls [3, 0, 0]: total 3, quiet 2 — the value fold is the same.
        val afterEmpties = DrainProgress().tally(3).tally(0).tally(0)
        assertThat(afterEmpties).isEqualTo(DrainProgress(total = 3, quiet = 2))
        // A non-empty poll adds to the total and resets the quiet run.
        assertThat(afterEmpties.tally(2)).isEqualTo(DrainProgress(total = 5, quiet = 0))
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
}
