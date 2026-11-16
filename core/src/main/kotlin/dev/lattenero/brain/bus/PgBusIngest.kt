package dev.lattenero.brain.bus

import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.inTransaction
import java.time.OffsetDateTime
import kap.Kap
import kap.Ref
import kap.evalGraph
import kotlinx.serialization.Serializable

/** The handler's accepted counters as one immutable value (the [IngestStats] read surface). */
data class IngestCounters(
    val seen: Long = 0,
    val inserted: Long = 0,
    val duplicates: Long = 0,
    val lastTs: String? = null,
    val lastKind: String? = null,
)

/**
 * Business counters (what the handler accepted), not liveness: liveness is the
 * process handle's restarts and heartbeat age (see [BusStatus]). The state is
 * one kap [Ref] over [IngestCounters] (Phase G: "true business counters stay
 * plain until kap Ref") — one atomic update per accepted batch.
 */
class IngestStats private constructor(private val state: Ref<IngestCounters>) {
    suspend fun accept(events: Int, insertedRows: Int, ts: String, kind: String) {
        state.update {
            it.copy(
                seen = it.seen + events,
                inserted = it.inserted + insertedRows,
                duplicates = it.duplicates + (events - insertedRows),
                lastTs = ts,
                lastKind = kind,
            )
        }.evalGraph()
    }

    /** The counters as a value; the /bus/status surface reads this. */
    suspend fun snapshot(): IngestCounters = state.get().evalGraph()

    companion object {
        fun of(): Kap<IngestStats> = Kap { IngestStats(Ref.of(IngestCounters()).evalGraph()) }
    }
}

/** The ONE handler behind every transport (file now, Kafka in P4): a batch insert that ignores ids it already has. */
class PgBusIngest private constructor(private val db: Database, val stats: IngestStats) {

    suspend fun handle(events: List<BusEvent>) {
        if (events.isEmpty()) return
        val inserted = db.withDriver { brainDb ->
            var landed = 0
            brainDb.transaction {
                for (event in events) {
                    landed += brainDb.busEventsQueries.ingestEvents(
                        event.id,
                        OffsetDateTime.parse(event.ts),
                        event.kind.wire,
                        event.sid,
                        event.turn,
                        event.source.wire,
                        event.payload.toString(),
                    ).value.toInt()
                }
            }
            landed
        }
        events.last().let { stats.accept(events.size, inserted, it.ts, it.kind.wire) }
    }

    suspend fun count(): Long = db.withDriver { it.busEventsQueries.countBusEvents().executeAsOne() }

    companion object {
        const val INSERT = """
            INSERT INTO bus_events (id, ts, kind, sid, turn, source, payload)
            VALUES (?, ?::timestamptz, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (id) DO NOTHING
        """

        /** Construction is a [Kap]: the stats are kap `Ref` state (see [IngestStats]). */
        fun of(db: Database): Kap<PgBusIngest> = Kap { PgBusIngest(db, IngestStats.of().evalGraph()) }
    }
}

@Serializable
data class BusStatus(
    val transport: String,
    val file: String,
    val fileExists: Boolean,
    val fileBytes: Long,
    val offset: Long,
    val lagBytes: Long,
    val linesRead: Long,
    val malformed: Long,
    val shrinks: Long,
    val seen: Long,
    val inserted: Long,
    val duplicates: Long,
    val dbEvents: Long,
    val lastTs: String?,
    val lastKind: String?,
    val restarts: Int? = null,
    val heartbeatAgeMs: Long? = null,
    val kafkaCommits: Long? = null,
    val kafkaOffsets: Map<String, Long>? = null,
)
