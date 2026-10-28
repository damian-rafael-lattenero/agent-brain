package dev.lattenero.brain.bus

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The bus contract (bot ADR-019): one JSON object per line. Unknown fields
 * are ignored so producers can grow the payload without a lockstep release;
 * the seven top-level fields are the contract.
 */
@Serializable
data class BusEvent(
    val id: String,
    val ts: String,
    val kind: String,
    val sid: String = "",
    val turn: String = "",
    val source: String = "bot",
    val payload: JsonObject = JsonObject(emptyMap()),
) {
    val instant: Instant get() = Instant.parse(ts)
}

object BusEvents {
    val KINDS = setOf("user.prompt", "turn.started", "tool.call", "tool.result", "turn.completed", "turn.failed")
    val SOURCES = setOf("bot", "hook")

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
        if (event.kind !in KINDS) return Parsed.Bad("unknown kind '${event.kind}'")
        if (event.source !in SOURCES) return Parsed.Bad("unknown source '${event.source}'")
        try {
            Instant.parse(event.ts)
        } catch (e: DateTimeParseException) {
            return Parsed.Bad("bad ts '${event.ts}'")
        }
        return Parsed.Ok(event)
    }
}
