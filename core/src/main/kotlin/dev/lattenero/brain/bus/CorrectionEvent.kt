package dev.lattenero.brain.bus

import kotlinx.serialization.json.JsonPrimitive

/**
 * WHAT the owner's correction means (plan U8), as a closed taxonomy: steer
 * (adjust the course), stop (end this line of work), cancel (undo the
 * request) and explicit (the owner stated a rule outright). The wire string
 * rides [wire]; the bus payload's kind is read TYPO-TOLERANT — an unknown or
 * missing kind degrades to [EXPLICIT] (the honest default: the words are
 * there, the classification is not), never a failed decode.
 */
enum class CorrectionKind(val wire: String) {
    STEER("steer"),
    STOP("stop"),
    CANCEL("cancel"),
    EXPLICIT("explicit");

    companion object {
        private val byWire: Map<String, CorrectionKind> = entries.associateBy(CorrectionKind::wire)

        /** Total: any string (or the absence of one) is a [CorrectionKind]. */
        fun parse(wire: String?): CorrectionKind = wire?.trim()?.lowercase()?.let(byWire::get) ?: EXPLICIT
    }
}

/**
 * One [BusKind.CORRECTION] event's payload, parsed ONCE into values at the
 * bus boundary (parse, don't validate): the taxonomy [kind] and the owner's
 * words [text] — empty when the payload omits them, verbatim otherwise.
 */
data class CorrectionEvent(val kind: CorrectionKind, val text: String) {
    companion object {
        /** The payload's string field, or null when it is absent or not a string. */
        private fun field(payload: kotlinx.serialization.json.JsonObject, key: String): String? =
            (payload[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

        fun of(event: BusEvent): CorrectionEvent = CorrectionEvent(
            kind = CorrectionKind.parse(field(event.payload, "kind")),
            text = field(event.payload, "text").orEmpty(),
        )
    }
}
