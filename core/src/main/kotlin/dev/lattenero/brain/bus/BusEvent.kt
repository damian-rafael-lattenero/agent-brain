package dev.lattenero.brain.bus

import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.format.DateTimeParseException

/** The event taxonomy on the wire (bot ADR-019); the wire string rides [SerialName] and [wire]. */
@Serializable
enum class BusKind(val wire: String) {
    @SerialName("user.prompt") USER_PROMPT("user.prompt"),
    @SerialName("turn.started") TURN_STARTED("turn.started"),
    @SerialName("tool.call") TOOL_CALL("tool.call"),
    @SerialName("tool.result") TOOL_RESULT("tool.result"),
    @SerialName("turn.completed") TURN_COMPLETED("turn.completed"),
    @SerialName("turn.failed") TURN_FAILED("turn.failed"),
}

/** Who produced the event. */
@Serializable
enum class BusSource(val wire: String) {
    @SerialName("bot") BOT("bot"),
    @SerialName("hook") HOOK("hook"),
}

/**
 * The bus contract (bot ADR-019): one JSON object per line. Unknown fields
 * are ignored so producers can grow the payload without a lockstep release;
 * the seven top-level fields are the contract, and the taxonomy is a TYPE:
 * an unknown kind or source fails the decode (the parse edge below turns it
 * into a value, never a thrown), so no downstream code can ever see an
 * off-taxonomy event.
 */
@Serializable
data class BusEvent(
    val id: String,
    val ts: String,
    val kind: BusKind,
    val sid: String = "",
    val turn: String = "",
    val source: BusSource = BusSource.BOT,
    val payload: JsonObject = JsonObject(emptyMap()),
) {
    val instant: Instant get() = Instant.parse(ts)
}

object BusEvents {
    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Parsed {
        data class Ok(val event: BusEvent) : Parsed
        data class Bad(val reason: String) : Parsed
    }

    /** A malformed line is reported, never thrown: the bus is append-only and one bad line must not stop the tail. */
    fun parse(line: String): Parsed {
        val event = try {
            json.decodeFromString<BusEvent>(line)
        } catch (e: SerializationException) {
            return Parsed.Bad("not a bus event: ${e.message?.lineSequence()?.firstOrNull()}")
        } catch (e: IllegalArgumentException) {
            return Parsed.Bad("not a bus event: ${e.message}")
        }
        if (event.id.isBlank()) return Parsed.Bad("empty id")
        try {
            Instant.parse(event.ts)
        } catch (e: DateTimeParseException) {
            return Parsed.Bad("bad ts '${event.ts}'")
        }
        return Parsed.Ok(event)
    }
}
