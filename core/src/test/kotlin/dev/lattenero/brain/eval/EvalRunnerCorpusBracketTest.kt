package dev.lattenero.brain.eval

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The corpus bracket's laws (plan E7): the fixture corpus is materialized on
 * acquire — every file the shipped index names — and the temp tree is removed
 * on release, on success, on failure AND on cancellation. The eval run never
 * leaks corpus files into the system temp directory.
 */
class EvalRunnerCorpusBracketTest {
    private fun indexNames(): List<String> =
        javaClass.classLoader.getResourceAsStream("eval/corpus/index.txt")!!.bufferedReader().use { reader ->
            reader.readLines().filter { it.endsWith(".md") }
        }

    @Test
    fun `the corpus resource materializes the indexed files and removes the tree on release`() {
        var dir: Path? = null
        runBlocking {
            EvalRunner.evalCorpus().use { corpus ->
                dir = corpus
                assertThat(Files.isDirectory(corpus)).isTrue
                val names = indexNames()
                assertThat(names).isNotEmpty
                for (name in names) assertThat(Files.exists(corpus.resolve(name))).withFailMessage(name).isTrue
            }
        }
        assertThat(Files.exists(dir!!)).isFalse
    }

    @Test
    fun `the tree is removed when the block fails and when it is cancelled`() {
        var failed: Path? = null
        assertThatThrownBy {
            runBlocking {
                EvalRunner.evalCorpus().use { corpus ->
                    failed = corpus
                    error("boom")
                }
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(Files.exists(failed!!)).isFalse

        var cancelled: Path? = null
        runBlocking {
            val job = launch {
                EvalRunner.evalCorpus().use { corpus ->
                    cancelled = corpus
                    awaitCancellation()
                }
            }
            while (cancelled == null) yield()
            job.cancelAndJoin()
        }
        assertThat(Files.exists(cancelled!!)).isFalse
    }
}
