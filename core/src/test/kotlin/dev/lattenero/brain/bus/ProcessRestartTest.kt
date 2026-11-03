package dev.lattenero.brain.bus

import kap.ProcessExhaustedException
import kap.RestartPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A2's law: the tailer is a supervised [kap.Process]. A body that dies
 * mid-cycle is restarted, and the restarted body resumes from the persisted
 * offset floor — never from zero, never past a batch the handler did not
 * accept. A body that keeps failing exhausts its finite restart policy
 * instead of hot-looping.
 */
class ProcessRestartTest {
    @TempDir
    lateinit var tmp: Path

    private val busFile get() = tmp.resolve("bus.jsonl")

    private fun append(text: String) {
        Files.write(busFile, text.toByteArray(Charsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun line(id: String, kind: String = "turn.started") =
        """{"id":"$id","ts":"2026-10-28T20:00:00.123Z","kind":"$kind","sid":"s","turn":"1","source":"bot","payload":{}}""" + "\n"

    @Test
    fun `a killed body restarts and resumes from the persisted offset floor`() = runBlocking<Unit> {
        append(line("a") + line("b"))
        val tailer = FileTailer.open(busFile, tmp.resolve("state"))
        val delivered = Collections.synchronizedList(mutableListOf<String>())
        val failNext = AtomicBoolean(false)
        val handler: suspend (List<BusEvent>) -> Unit = { events ->
            delivered += events.map { it.id }
            if (failNext.getAndSet(false)) throw IllegalStateException("killed mid-cycle")
        }

        // The accepted prefix is the floor on disk: the restarted body re-reads
        // from here, not from zero.
        assertThat(tailer.poll(handler)).isEqualTo(2)
        val floor = tailer.offset
        assertThat(Files.readString(tmp.resolve("state").resolve("bus-offset.json"))).contains("\"offset\":$floor")

        append(line("c") + line("d"))
        failNext.set(true)
        val handle = tailer
            .process(10.milliseconds, handler, RestartPolicy.constant(10.milliseconds, maxRestarts = 3))
            .startIn(this)
        try {
            withTimeout(10.seconds) {
                while (handle.restarts.value < 1 || tailer.offset < Files.size(busFile)) delay(5.milliseconds)
            }
        } finally {
            handle.stop()
        }

        assertThat(handle.restarts.value).isEqualTo(1)
        // At-least-once: the failed batch is redelivered, the accepted prefix is not.
        assertThat(delivered).containsExactly("a", "b", "c", "d", "c", "d")
        assertThat(tailer.offset).isEqualTo(Files.size(busFile))

        // A fresh tailer resumes from the same persisted floor: nothing is lost,
        // nothing is read twice.
        val restarted = FileTailer.open(busFile, tmp.resolve("state"))
        assertThat(restarted.offset).isEqualTo(Files.size(busFile))
        assertThat(restarted.poll(handler)).isZero
    }

    @Test
    fun `a body that keeps failing exhausts its finite restart policy`() = runBlocking<Unit> {
        append(line("a"))
        val tailer = FileTailer.open(busFile, tmp.resolve("state"))
        val handle = tailer
            .process(5.milliseconds, { throw IllegalStateException("database down") }, RestartPolicy.constant(5.milliseconds, maxRestarts = 2))
            .startIn(this)

        val failure = runCatching { handle.await() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ProcessExhaustedException::class.java)
        assertThat(handle.restarts.value).isEqualTo(2)
        // The handler never accepted the batch, so the persisted floor stayed at zero.
        assertThat(tailer.offset).isZero
    }

    @Test
    fun `the handle reports a live heartbeat while the body runs`() = runBlocking<Unit> {
        val tailer = FileTailer.open(busFile, tmp.resolve("state"))
        val handle = tailer.process(10.milliseconds, {}).startIn(this)
        try {
            withTimeout(5.seconds) {
                while (!handle.heartbeatAge().isFinite()) delay(1.milliseconds)
            }
            assertThat(handle.heartbeatAge()).isLessThan(5.seconds)
            assertThat(handle.isAlive).isTrue
            assertThat(handle.restarts.value).isZero
        } finally {
            handle.stop()
        }
        assertThat(handle.isAlive).isFalse
    }
}
