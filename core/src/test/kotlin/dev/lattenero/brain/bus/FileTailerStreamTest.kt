package dev.lattenero.brain.bus

import kap.evalGraph
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The SPEC-5.0 dogfooding pin: the bus tail IS a [kap.KapStream] —
 * `emitted(poll).every(spaced)` with the named exact backpressure — and the
 * laws survive the move: the cadence is the stream's schedule (provable in
 * virtual time), batches arrive in order, the offset floor holds through a
 * failing poll and through cancellation, and a busy drain suspends the
 * poller instead of running ahead.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FileTailerStreamTest {
    @TempDir
    lateinit var tmp: Path

    private fun append(text: String) {
        Files.write(tmp.resolve("bus.jsonl"), text.toByteArray(Charsets.UTF_8),
            StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun line(id: String) =
        """{"id":"$id","ts":"2026-10-28T20:00:00.123Z","kind":"turn.started","sid":"s","turn":"1","source":"bot","payload":{}}""" + "\n"

    @Test
    fun `the tail stream polls on cadence, delivers in order, and stays resumable after cancellation`() = runTest {
        append(line("a") + line("b"))
        val tailer = FileTailer.open(tmp.resolve("bus.jsonl"), tmp.resolve("state")).evalGraph()
        val sink = mutableListOf<BusEvent>()

        val counts = tailer.stream(100.milliseconds) { events -> sink += events }
            .asFlow()
            .take(3)
            .toList()

        // The first poll is immediate and delivers both lines; the next two
        // cadence polls (virtual time) find nothing new.
        assertThat(counts).isEqualTo(listOf(2, 0, 0))
        assertThat(sink.map { it.id }).containsExactly("a", "b")
        assertThat(tailer.offset).isEqualTo(Files.size(tmp.resolve("bus.jsonl")))

        // take() cancelled the stream upstream: the tailer itself is intact and
        // a later poll continues from the persisted floor.
        append(line("c"))
        val after = mutableListOf<BusEvent>()
        assertThat(tailer.poll { after += it }).isEqualTo(1)
        assertThat(after.single().id).isEqualTo("c")
        assertThat(tailer.offset).isEqualTo(Files.size(tmp.resolve("bus.jsonl")))
    }

    @Test
    fun `the named backpressure is exact - a busy drain suspends the poller, nothing runs ahead`() = runTest {
        append(line("a") + line("b") + line("c"))
        val tailer = FileTailer.open(tmp.resolve("bus.jsonl"), tmp.resolve("state")).evalGraph()
        val gate = CompletableDeferred<Unit>()
        val delivered = mutableListOf<String>()
        var polls = 0

        val collector = launch {
            tailer.stream(10.milliseconds) { events ->
                polls += 1
                delivered += events.map { it.id }
                gate.await() // the drain is busy until the test says otherwise
            }.asFlow().take(2).toList()
        }
        try {
            withTimeout(10.seconds) { while (polls == 0) yield() }
            // Many cadence ticks pass while the drain holds the batch: the
            // exact policy keeps the poller suspended; nothing runs ahead.
            advanceTimeBy(10.seconds)
            runCurrent()
            assertThat(polls).isEqualTo(1)
        } finally {
            gate.complete(Unit)
            collector.join()
        }
        assertThat(delivered).containsExactly("a", "b", "c")
        assertThat(tailer.offset).isEqualTo(Files.size(tmp.resolve("bus.jsonl")))
    }

    @Test
    fun `a failing poll fails the stream cleanly and leaves the offset floor alone`() = runTest {
        append(line("a"))
        val tailer = FileTailer.open(tmp.resolve("bus.jsonl"), tmp.resolve("state")).evalGraph()
        val boom: suspend (List<BusEvent>) -> Unit = { throw IllegalStateException("database down") }

        val outcome = runCatching { tailer.stream(50.milliseconds, handler = boom).asFlow().take(1).toList() }

        // The failing poll fails the stream — the exception a restarted process
        // body sees — and the offset never moved: delivery is at-least-once.
        assertThat(outcome.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(tailer.offset).isZero
        val delivered = mutableListOf<BusEvent>()
        assertThat(tailer.poll { delivered += it }).isEqualTo(1)
        assertThat(delivered.single().id).isEqualTo("a")
    }
}
