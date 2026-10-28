package dev.lattenero.brain.bus

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FileTailerTest {
    @TempDir
    lateinit var tmp: Path

    private fun tailer() = FileTailer.open(tmp.resolve("bus.jsonl"), tmp.resolve("state"))

    private fun append(text: String) {
        Files.write(tmp.resolve("bus.jsonl"), text.toByteArray(Charsets.UTF_8),
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
    }

    private fun line(id: String, kind: String = "turn.started") =
        """{"id":"$id","ts":"2026-10-28T20:00:00.123Z","kind":"$kind","sid":"s","turn":"1","source":"bot","payload":{}}""" + "\n"

    private fun poll(tailer: FileTailer, sink: MutableList<BusEvent>): Int = runBlocking {
        tailer.poll { sink += it }
    }

    @Test
    fun `a missing or empty file hands over nothing`() {
        val sink = mutableListOf<BusEvent>()
        assertThat(poll(tailer(), sink)).isZero
        assertThat(sink).isEmpty()
    }

    @Test
    fun `complete lines are handed over and the offset advances to the byte`() {
        val tailer = tailer()
        append(line("a") + line("b") + line("c", "turn.completed"))
        val sink = mutableListOf<BusEvent>()
        assertThat(poll(tailer, sink)).isEqualTo(3)
        assertThat(sink.map { it.id }).containsExactly("a", "b", "c")
        assertThat(tailer.offset).isEqualTo(Files.size(tmp.resolve("bus.jsonl")))
        assertThat(poll(tailer, mutableListOf())).isZero
    }

    @Test
    fun `a partial trailing line waits for its newline`() {
        val tailer = tailer()
        append(line("a") + """{"id":"b","ts":"2026-10-28""")
        val sink = mutableListOf<BusEvent>()
        assertThat(poll(tailer, sink)).isEqualTo(1)
        val afterFirst = tailer.offset
        append("""T20:00:00.123Z","kind":"turn.completed","sid":"s","turn":"1","source":"bot","payload":{}}""" + "\n")
        assertThat(poll(tailer, sink)).isEqualTo(1)
        assertThat(sink.map { it.id }).containsExactly("a", "b")
        assertThat(tailer.offset).isGreaterThan(afterFirst)
    }

    @Test
    fun `multibyte lines advance the offset by bytes and stay decodable`() {
        val tailer = tailer()
        val body = """{"id":"ñ1","ts":"2026-10-28T20:00:00.123Z","kind":"turn.started","sid":"s","turn":"1","source":"bot","payload":{"text":"ñandú 🧠 ok"}}""" + "\n"
        append(body)
        val sink = mutableListOf<BusEvent>()
        assertThat(poll(tailer, sink)).isEqualTo(1)
        assertThat(sink.single().payload["text"].toString()).contains("ñandú")
        append(line("a2"))
        assertThat(poll(tailer, mutableListOf())).isEqualTo(1)
        assertThat(tailer.offset).isEqualTo((body.toByteArray(Charsets.UTF_8).size + line("a2").toByteArray(Charsets.UTF_8).size).toLong())
    }

    @Test
    fun `a malformed line is counted and skipped, the good ones still arrive`() {
        val tailer = tailer()
        append(line("good") + "{broken\n" + line("also-good"))
        val sink = mutableListOf<BusEvent>()
        assertThat(poll(tailer, sink)).isEqualTo(2)
        assertThat(sink.map { it.id }).containsExactly("good", "also-good")
        assertThat(tailer.stats.malformed.get()).isEqualTo(1)
        assertThat(tailer.stats.lines.get()).isEqualTo(3)
    }

    @Test
    fun `a truncation restarts the tail from zero`() {
        val tailer = tailer()
        append(line("a") + line("b"))
        poll(tailer, mutableListOf())
        val before = tailer.offset
        assertThat(before).isGreaterThan(0)
        Files.write(tmp.resolve("bus.jsonl"), line("after-rotation").toByteArray(Charsets.UTF_8))
        val sink = mutableListOf<BusEvent>()
        assertThat(poll(tailer, sink)).isEqualTo(1)
        assertThat(sink.single().id).isEqualTo("after-rotation")
        assertThat(tailer.stats.shrinks.get()).isEqualTo(1)
        assertThat(tailer.offset).isEqualTo(Files.size(tmp.resolve("bus.jsonl")))
    }

    @Test
    fun `a restart resumes from the persisted offset`() {
        append(line("a") + line("b"))
        val first = tailer()
        assertThat(poll(first, mutableListOf())).isEqualTo(2)
        append(line("c"))
        val restarted = tailer()
        assertThat(restarted.offset).isEqualTo(first.offset)
        val sink = mutableListOf<BusEvent>()
        assertThat(poll(restarted, sink)).isEqualTo(1)
        assertThat(sink.single().id).isEqualTo("c")
    }

    @Test
    fun `a handler failure leaves the offset alone, so delivery is at-least-once`() {
        val tailer = tailer()
        append(line("a"))
        runBlocking {
            try {
                tailer.poll { throw IllegalStateException("database down") }
            } catch (e: IllegalStateException) {
                // expected: the poll propagates, the offset must not move
            }
        }
        assertThat(tailer.offset).isZero
        val sink = mutableListOf<BusEvent>()
        assertThat(poll(tailer, sink)).isEqualTo(1)
        assertThat(sink.single().id).isEqualTo("a")
    }

    @Test
    fun `a line longer than one read window never stalls the tail`() {
        val tailer = FileTailer.open(tmp.resolve("bus.jsonl"), tmp.resolve("state"), maxReadBytes = 64)
        val big = (1..12).joinToString("") { line("e$it") }
        append(big)
        var total = 0
        var polls = 0
        while (true) {
            val n = poll(tailer, mutableListOf())
            if (n == 0) break
            total += n
            polls += 1
        }
        assertThat(total).isEqualTo(12)
        assertThat(polls).isGreaterThan(1)
        assertThat(tailer.offset).isEqualTo(big.toByteArray(Charsets.UTF_8).size.toLong())
    }
}
