package dev.lattenero.brain.bus

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BusEventsTest {
    @Test
    fun `every line of the real bot publisher fixture parses`() {
        val lines = javaClass.classLoader.getResourceAsStream("bus/sample-bot-publisher.jsonl")!!
            .bufferedReader().readLines().filter { it.isNotBlank() }
        assertThat(lines).hasSize(6)
        val events = lines.map { BusEvents.parse(it) }
        events.forEach { parsed ->
            assertThat(parsed).withFailMessage(parsed.toString()).isInstanceOf(BusEvents.Parsed.Ok::class.java)
        }
        val ok = events.map { (it as BusEvents.Parsed.Ok).event }
        assertThat(ok.map { it.kind }).containsExactly(
            "turn.started", "tool.call", "tool.result", "turn.completed", "user.prompt", "turn.failed",
        )
        assertThat(ok.map { it.instant.isAfter(java.time.Instant.EPOCH) }).allMatch { it }
        assertThat(ok[0].payload["prompt"]!!.toString()).contains("ñandú")
    }

    @Test
    fun `the contract rejects unknown kinds, sources, empty ids and bad timestamps`() {
        assertThat(BusEvents.parse("""{"id":"a","ts":"2026-10-28T20:00:00.123Z","kind":"turn.exploded"}"""))
            .isInstanceOf(BusEvents.Parsed.Bad::class.java)
        assertThat(BusEvents.parse("""{"id":"a","ts":"2026-10-28T20:00:00.123Z","kind":"turn.started","source":"mystery"}"""))
            .isInstanceOf(BusEvents.Parsed.Bad::class.java)
        assertThat(BusEvents.parse("""{"id":"","ts":"2026-10-28T20:00:00.123Z","kind":"turn.started"}"""))
            .isInstanceOf(BusEvents.Parsed.Bad::class.java)
        assertThat(BusEvents.parse("""{"id":"a","ts":"yesterday","kind":"turn.started"}"""))
            .isInstanceOf(BusEvents.Parsed.Bad::class.java)
        assertThat(BusEvents.parse("not json at all"))
            .isInstanceOf(BusEvents.Parsed.Bad::class.java)
    }

    @Test
    fun `unknown fields are ignored so producers can grow the payload`() {
        val parsed = BusEvents.parse(
            """{"id":"a","ts":"2026-10-28T20:00:00.123Z","kind":"turn.started","sid":"s",
               "turn":"1","source":"bot","payload":{},"futureField":{"x":1}}""",
        )
        assertThat(parsed).isInstanceOf(BusEvents.Parsed.Ok::class.java)
        assertThat((parsed as BusEvents.Parsed.Ok).event.sid).isEqualTo("s")
    }
}
