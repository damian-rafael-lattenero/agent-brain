package dev.lattenero.brain.kapx

import kap.Kap
import kap.evalGraph
import kap.settled
import kap.traverse
import kap.traverseSettled
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Behaviour of KAP 4.0.0 this codebase works around. If a test here starts
 * failing after a KAP upgrade, the library fixed it: drop `isolated()`.
 */
class KapFindingsTest {
    private fun nested(): Kap<List<Int>> = listOf(1, 2).traverse { n ->
        Kap { if (n == 2) throw IllegalStateException("child $n failed") else n }
    }

    @Test
    fun `finding - a failing child of a nested traverse escapes traverseSettled`() = runTest {
        assertThatThrownBy { kotlinx.coroutines.runBlocking { listOf("doc").traverseSettled { nested() }.evalGraph() } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("child 2 failed")
    }

    @Test
    fun `finding - the same failure escapes settled`() = runTest {
        assertThatThrownBy { kotlinx.coroutines.runBlocking { nested().settled().evalGraph() } }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `workaround - isolated gives the failure a boundary so settled semantics hold`() = runTest {
        val results = listOf("doc").traverseSettled { nested().isolated() }.evalGraph()
        assertThat(results).hasSize(1)
        assertThat(results.single().isFailure).isTrue
        assertThat(results.single().exceptionOrNull()).hasMessage("child 2 failed")

        val settled = nested().isolated().settled().evalGraph()
        assertThat(settled.isFailure).isTrue

        val ok = listOf(1, 2).traverse { n -> Kap { n * 10 } }.isolated().evalGraph()
        assertThat(ok).containsExactly(10, 20)
    }
}
