package dev.lattenero.brain.kapx

import kap.Kap
import kap.evalGraph
import kap.retry
import kap.settled
import kap.timeoutAsFailure
import kap.traverse
import kap.traverseSettled
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The two KAP 4.0.0 findings this project reported (ADR-007, KAP's
 * SPEC-4.1.0 F-01 and F-02), pinned as FIXED against 4.1.0. If a test here
 * fails after a KAP upgrade, the library regressed.
 */
class KapFindingsTest {
    private fun nested(): Kap<List<Int>> = listOf(1, 2).traverse { n ->
        Kap { if (n == 2) throw IllegalStateException("child $n failed") else n }
    }

    @Test
    fun `fixed in 4-1-0 - a failing child of a nested traverse is contained by traverseSettled`() = runTest {
        val results = listOf("doc").traverseSettled { nested() }.evalGraph()
        assertThat(results.single().isFailure).isTrue
        assertThat(results.single().exceptionOrNull()).hasMessage("child 2 failed")
    }

    @Test
    fun `fixed in 4-1-0 - settled converts a nested parallel failure`() = runTest {
        assertThat(nested().settled().evalGraph().isFailure).isTrue
    }

    @Test
    fun `fixed in 4-1-0 - timeoutAsFailure makes a timeout retryable`() = runTest {
        var calls = 0
        val slowOnce = Kap { if (calls++ == 0) delay(10.minutes); "ok" }
        val value = slowOnce.timeoutAsFailure(1.seconds).retry(maxAttempts = 2).evalGraph()
        assertThat(value).isEqualTo("ok")
        assertThat(calls).isEqualTo(2)
    }
}
