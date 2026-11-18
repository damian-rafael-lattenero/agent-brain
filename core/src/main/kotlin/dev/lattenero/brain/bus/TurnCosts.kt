package dev.lattenero.brain.bus

import dev.lattenero.brain.db.Database
import dev.lattenero.brain.eval.Cost
import dev.lattenero.brain.eval.PriceTable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.OffsetDateTime

/** One turn's ledger row — the pure projection of a bus event (priced, with its known flag). */
data class TurnCostRow(
    val eventId: String,
    val sid: String,
    val turn: String,
    val model: String?,
    val tokensIn: Long?,
    val tokensOut: Long?,
    val tokensTotal: Long?,
    val durationMs: Long?,
    val costUsd: BigDecimal,
    val costKnown: Boolean,
    val at: OffsetDateTime,
)

/**
 * THE PURE HALF (audit: per-turn cost ledger): a bus event → the row the
 * ledger stores. `turn.completed` / `turn.failed` carry the bot's REAL token
 * counts (its frames report usage) and the model in force; the price comes
 * from the declared table at projection time and travels WITH its known flag
 * — a model with no declared price yields `costUsd = 0, costKnown = false`,
 * never a zero that pretends to be free (the eval ledger's law).
 */
object TurnCostMap {

    /** The payload's signal for a feedback event: "up"/"down", null for anything else. */
    fun signal(event: BusEvent): String? = when (event.kind) {
        BusKind.TURN_FEEDBACK -> str(event.payload, "signal")?.takeIf { it == "up" || it == "down" }
        else -> null
    }

    /** The row for a sealed turn; null when the kind is not ledger material. */
    fun row(event: BusEvent, prices: PriceTable): TurnCostRow? = when (event.kind) {
        BusKind.TURN_COMPLETED, BusKind.TURN_FAILED -> priced(event, prices)
        else -> null
    }

    private fun priced(event: BusEvent, prices: PriceTable): TurnCostRow {
        val payload = event.payload
        val model = str(payload, "model")?.takeIf { it.isNotBlank() }
        val tokensIn = num(payload, "tokens_in")
        val tokensOut = num(payload, "tokens_out")
        val cost: Cost = if (model != null && (tokensIn != null || tokensOut != null)) {
            prices.costByModel(model, tokensIn ?: 0, tokensOut ?: 0)
        } else {
            Cost(usd = 0.0, known = false)
        }
        return TurnCostRow(
            eventId = event.id,
            sid = event.sid,
            turn = event.turn,
            model = model,
            tokensIn = tokensIn,
            tokensOut = tokensOut,
            tokensTotal = num(payload, "tokens_total") ?: sumOrNull(tokensIn, tokensOut),
            // duration_s is FRACTIONAL on the wire (42.5): parse the double, then scale
            durationMs = dbl(payload, "duration_s")?.let { (it * MILLIS_PER_SECOND).toLong() },
            costUsd = BigDecimal.valueOf(cost.usd),
            costKnown = cost.known,
            at = OffsetDateTime.parse(event.ts),
        )
    }

    private fun sumOrNull(a: Long?, b: Long?): Long? =
        if (a == null && b == null) null else (a ?: 0) + (b ?: 0)

    private fun str(o: JsonObject, key: String): String? =
        (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun num(o: JsonObject, key: String): Long? =
        (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.toLong()

    private fun dbl(o: JsonObject, key: String): Double? =
        (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()

    private const val MILLIS_PER_SECOND = 1000.0
}

/** Today's rollup — the count, the USD and how many rows could not be priced (the HTTP shape). */
@kotlinx.serialization.Serializable
data class TodayCost(val turns: Long, val usd: Double, val unpriced: Long)

/** One ledger row as the HTTP surface answers it (Double USD: kotlinx has no BigDecimal codec). */
@kotlinx.serialization.Serializable
data class TurnCostView(
    val eventId: String,
    val sid: String,
    val turn: String,
    val model: String?,
    val tokensIn: Long?,
    val tokensOut: Long?,
    val tokensTotal: Long?,
    val durationMs: Long?,
    val costUsd: Double,
    val costKnown: Boolean,
    val feedback: String?,
    val at: String,
)

/**
 * THE LEDGER (audit): projects the bus's sealed turns into `turn_costs` and
 * stamps the owner's 👍/👎 from `turn.feedback`. Derived data by design: the
 * bus row is already durable when this runs, so a projection failure is ONE
 * log line — it never blocks the ingest, never rewinds the offset, and the
 * next drain re-projects what is still pending (the insert is idempotent by
 * event id).
 */
class TurnCosts(
    private val db: Database,
    private val prices: PriceTable = PriceTable.load(),
) {

    /** Projects one event; the whole ledger's entry point (bus handler). */
    // any failure is ONE log line: the source event is durable, the projection is derived
    @Suppress("TooGenericExceptionCaught")
    suspend fun project(event: BusEvent) {
        try {
            TurnCostMap.signal(event)?.let { signal ->
                feedback(event, signal)
                return
            }
            val row = TurnCostMap.row(event, prices) ?: return
            db.withDriver { brainDb ->
                brainDb.turnCostsQueries.insertTurnCost(
                    eventId = row.eventId,
                    sid = row.sid,
                    turn = row.turn,
                    model = row.model,
                    tokensIn = row.tokensIn,
                    tokensOut = row.tokensOut,
                    tokensTotal = row.tokensTotal,
                    durationMs = row.durationMs,
                    costUsd = row.costUsd,
                    costKnown = row.costKnown,
                    at = row.at,
                )
            }
        } catch (e: CancellationException) {
            throw e // cancellation is not a projection failure
        } catch (e: Exception) {
            log.warn("turn_costs projection failed for {} ({})", event.id, e.message)
        }
    }

    /** Today's rollup for `/costs/today`. */
    suspend fun today(): TodayCost = db.withDriver { brainDb ->
        val row = brainDb.turnCostsQueries.todayCost().executeAsOne()
        TodayCost(row.turns, row.usd?.toDouble() ?: 0.0, row.unpriced)
    }

    /** The newest rows for `/costs/recent`. */
    suspend fun recent(limit: Int = DEFAULT_LIMIT): List<TurnCostView> = db.withDriver { brainDb ->
        brainDb.turnCostsQueries.recentCosts(limit.coerceIn(1, MAX_LIMIT).toLong())
            .executeAsList()
            .map { r ->
                TurnCostView(
                    eventId = r.event_id,
                    sid = r.sid,
                    turn = r.turn,
                    model = r.model,
                    tokensIn = r.tokens_in,
                    tokensOut = r.tokens_out,
                    tokensTotal = r.tokens_total,
                    durationMs = r.duration_ms,
                    costUsd = r.cost_usd.toDouble(),
                    costKnown = r.cost_known,
                    feedback = r.feedback,
                    at = r.at.toString(),
                )
            }
    }

    /** The owner's tap: stamp the turn's row; a turn the ledger never saw gets a skeleton row. */
    private suspend fun feedback(event: BusEvent, signal: String) {
        db.withDriver { brainDb ->
            val updated = brainDb.turnCostsQueries.setFeedback(signal, event.sid, event.turn).value
            if (updated == 0L) {
                brainDb.turnCostsQueries.insertFeedbackStub(
                    eventId = event.id,
                    sid = event.sid,
                    turn = event.turn,
                    feedback = signal,
                )
            }
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 200

        private val log = LoggerFactory.getLogger("agent-brain.turn-costs")
    }
}
