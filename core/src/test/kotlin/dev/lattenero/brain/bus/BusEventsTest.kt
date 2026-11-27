// lang-ok: quoted record — "ñandú" is the non-ASCII round-trip fixture.
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
            BusKind.TURN_STARTED, BusKind.TOOL_CALL, BusKind.TOOL_RESULT,
            BusKind.TURN_COMPLETED, BusKind.USER_PROMPT, BusKind.TURN_FAILED,
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

    @Test
    fun `a correction event decodes with its payload kind and text`() {
        val parsed = BusEvents.parse(
            """{"id":"c1","ts":"2026-11-27T12:00:00.000Z","kind":"correction","sid":"bot-task-9",
               "turn":"42","source":"bot","payload":{"kind":"steer","text":"no, in English"}}""",
        )
        assertThat(parsed).isInstanceOf(BusEvents.Parsed.Ok::class.java)
        val event = (parsed as BusEvents.Parsed.Ok).event
        assertThat(event.kind).isEqualTo(BusKind.CORRECTION)
        assertThat(event.sid).isEqualTo("bot-task-9")
        assertThat(event.turn).isEqualTo("42")
        val correction = CorrectionEvent.of(event)
        assertThat(correction.kind).isEqualTo(CorrectionKind.STEER)
        assertThat(correction.text).isEqualTo("no, in English")
    }

    @Test
    fun `the correction payload is typo-tolerant - unknown kinds degrade to explicit`() {
        listOf(null, "", "  ", "steered", "CANCEL-ALL").forEach { payloadKind ->
            val payload = if (payloadKind == null) "{}" else """{"kind":"$payloadKind","text":"always commit first"}"""
            val parsed = BusEvents.parse(
                """{"id":"c2","ts":"2026-11-27T12:00:00.000Z","kind":"correction","payload":$payload}""",
            )
            val event = (parsed as BusEvents.Parsed.Ok).event
            assertThat(CorrectionEvent.of(event).kind)
                .withFailMessage("payload kind '$payloadKind' must degrade to explicit")
                .isEqualTo(CorrectionKind.EXPLICIT)
        }
    }

    @Test
    fun `the correction text defaults to empty when the payload omits it`() {
        val parsed = BusEvents.parse(
            """{"id":"c3","ts":"2026-11-27T12:00:00.000Z","kind":"correction","payload":{"kind":"stop"}}""",
        )
        val event = (parsed as BusEvents.Parsed.Ok).event
        assertThat(CorrectionEvent.of(event).text).isEmpty()
    }
}
