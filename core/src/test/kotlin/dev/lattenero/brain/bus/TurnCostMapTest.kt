package dev.lattenero.brain.bus

import dev.lattenero.brain.eval.PriceEntry
import dev.lattenero.brain.eval.PriceTable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * THE LEDGER'S PURE HALF (audit: per-turn cost ledger): a bus event → the row
 * the brain stores. The laws pinned here are the honest ones — real tokens
 * priced with the declared table, an unpriced model that says `known = false`
 * instead of a zero that lies, and a feedback signal parsed as a value.
 */
class TurnCostMapTest {

    private val prices = PriceTable(
        entries = listOf(
            PriceEntry(provider = "zai", model = "glm-test", promptPer1m = 2.0, completionPer1m = 4.0),
        ),
    )

    private fun event(kind: BusKind, payload: JsonObject, sid: String = "ses_1", turn: String = "42"): BusEvent =
        BusEvent(
            id = "evt_1",
            ts = "2026-11-18T12:00:00Z",
            kind = kind,
            sid = sid,
            turn = turn,
            payload = payload,
        )

    private fun completedPayload(model: String?, tokensIn: Long?, tokensOut: Long?, total: Long? = null): JsonObject =
        buildJsonObject {
            put("duration_s", 12.5)
            put("reply_chars", 300)
            model?.let { put("model", it) }
            tokensIn?.let { put("tokens_in", it) }
            tokensOut?.let { put("tokens_out", it) }
            total?.let { put("tokens_total", it) }
            put("stop_reason", "end_turn")
        }

    @Test
    fun `a priced turn carries its usd and the known flag`() {
        val row = TurnCostMap.row(
            event(BusKind.TURN_COMPLETED, completedPayload("glm-test", 1_000, 2_000)),
            prices,
        )!!
        assertThat(row.model).isEqualTo("glm-test")
        assertThat(row.tokensIn).isEqualTo(1_000)
        assertThat(row.tokensOut).isEqualTo(2_000)
        assertThat(row.tokensTotal).isEqualTo(3_000) // no explicit total: the sum
        assertThat(row.durationMs).isEqualTo(12_500)
        // 1000 * 2/1M + 2000 * 4/1M = 0.002 + 0.008 = 0.010
        assertThat(row.costUsd.toDouble()).isCloseTo(0.010, org.assertj.core.data.Offset.offset(1e-9))
        assertThat(row.costKnown).isTrue()
        assertThat(row.sid).isEqualTo("ses_1")
        assertThat(row.turn).isEqualTo("42")
    }

    @Test
    fun `an unpriced model says unknown - never a zero that lies`() {
        val row = TurnCostMap.row(
            event(BusKind.TURN_COMPLETED, completedPayload("model-without-price", 1_000, 500)),
            prices,
        )!!
        assertThat(row.costKnown).isFalse()
        assertThat(row.costUsd.toDouble()).isZero()
        assertThat(row.tokensTotal).isEqualTo(1_500)
    }

    @Test
    fun `a turn without usage stays unpriced with null counters`() {
        val row = TurnCostMap.row(event(BusKind.TURN_FAILED, completedPayload(null, null, null)), prices)!!
        assertThat(row.model).isNull()
        assertThat(row.tokensIn).isNull()
        assertThat(row.tokensOut).isNull()
        assertThat(row.tokensTotal).isNull()
        assertThat(row.costKnown).isFalse()
    }

    @Test
    fun `an explicit total wins over the sum`() {
        val row = TurnCostMap.row(
            event(BusKind.TURN_COMPLETED, completedPayload("glm-test", 100, 200, total = 999)),
            prices,
        )!!
        assertThat(row.tokensTotal).isEqualTo(999)
    }

    @Test
    fun `feedback signals parse as values, anything else is ignored`() {
        fun feedback(signal: String) = event(BusKind.TURN_FEEDBACK, buildJsonObject { put("signal", signal) })
        assertThat(TurnCostMap.signal(feedback("up"))).isEqualTo("up")
        assertThat(TurnCostMap.signal(feedback("down"))).isEqualTo("down")
        assertThat(TurnCostMap.signal(feedback("meh"))).isNull()
        assertThat(TurnCostMap.signal(event(BusKind.TURN_COMPLETED, completedPayload("glm-test", 1, 1)))).isNull()
    }

    @Test
    fun `non-ledger kinds project no row`() {
        assertThat(TurnCostMap.row(event(BusKind.TOOL_CALL, JsonObject(emptyMap())), prices)).isNull()
        assertThat(TurnCostMap.row(event(BusKind.USER_PROMPT, JsonObject(emptyMap())), prices)).isNull()
    }
}
