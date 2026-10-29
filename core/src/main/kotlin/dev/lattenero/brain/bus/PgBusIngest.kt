package dev.lattenero.brain.bus

import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.inTransaction
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicLong

class IngestStats {
    val seen = AtomicLong()
    val inserted = AtomicLong()
    val duplicates = AtomicLong()

    @Volatile
    var lastTs: String? = null

    @Volatile
    var lastKind: String? = null
}

/** The ONE handler behind every transport (file now, Kafka in P4): a batch insert that ignores ids it already has. */
class PgBusIngest(private val db: Database) {
    val stats = IngestStats()

    suspend fun handle(events: List<BusEvent>) {
        if (events.isEmpty()) return
        val inserted = db.inTransaction { conn ->
            conn.prepareStatement(INSERT).use { ps ->
                for (event in events) {
                    ps.setString(1, event.id)
                    ps.setString(2, event.ts)
                    ps.setString(3, event.kind)
                    ps.setString(4, event.sid)
                    ps.setString(5, event.turn)
                    ps.setString(6, event.source)
                    ps.setString(7, event.payload.toString())
                    ps.addBatch()
                }
                ps.executeBatch()
            }.count { it > 0 }
        }
        stats.seen.addAndGet(events.size.toLong())
        stats.inserted.addAndGet(inserted.toLong())
        stats.duplicates.addAndGet((events.size - inserted).toLong())
        events.last().let {
            stats.lastTs = it.ts
            stats.lastKind = it.kind
        }
    }

    suspend fun count(): Long = db.withConnection { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM bus_events").use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    companion object {
        const val INSERT = """
            INSERT INTO bus_events (id, ts, kind, sid, turn, source, payload)
            VALUES (?, ?::timestamptz, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (id) DO NOTHING
        """
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
    val polls: Long,
    val linesRead: Long,
    val malformed: Long,
    val shrinks: Long,
    val seen: Long,
    val inserted: Long,
    val duplicates: Long,
    val dbEvents: Long,
    val lastTs: String?,
    val lastKind: String?,
    val kafkaCommits: Long? = null,
    val kafkaOffsets: Map<String, Long>? = null,
)
