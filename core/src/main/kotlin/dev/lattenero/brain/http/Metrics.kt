package dev.lattenero.brain.http

import dev.lattenero.brain.bus.BusPipeline
import dev.lattenero.brain.bus.BusStatus
import dev.lattenero.brain.memory.MemoryService
import dev.lattenero.brain.memory.MemoryStatus
import dev.lattenero.brain.rag.RagService
import dev.lattenero.brain.rag.RagStats
import kap.ProcessHandle
import java.time.Instant

/**
 * E4's Prometheus exposition (U5d): a PURE projection of the snapshots the
 * server already serves as JSON — `/bus/status`, `/rag/stats`,
 * `/memory/status`. Nothing here reads a database, a clock, a file or a
 * process: [renderMetrics] is a function of [MetricsSnapshot] and nothing
 * else, so the whole exposition is testable without a stack.
 *
 * The honest-indicators law applies at this edge twice:
 *
 * - **absent is absent**: a component that is not wired, a liveness handle
 *   that is unknown (`restarts`/`heartbeatAgeMs` are null when no loop is
 *   running), or a last-event stamp that does not parse omits its series —
 *   the exposition never invents a zero;
 * - **the provenance is the help text**: every family names the snapshot
 *   field it projects, so a panel that reads it can be judged against the
 *   real event behind it.
 */
data class MetricsSnapshot(
    val bus: BusMetrics? = null,
    val rag: RagMetrics? = null,
    val memory: MemoryMetrics? = null,
)

/**
 * The bus leg, projected from one [BusStatus]: the business counters are
 * process-lifetime and monotone within one run (counters); file size,
 * offset, lag and the database count are point-in-time magnitudes
 * (gauges). [lastEventMillis] is the ingest's own `lastTs` parsed —
 * null when it does not parse.
 */
data class BusMetrics(
    val transport: String,
    val filePresent: Boolean,
    val fileBytes: Long,
    val offsetBytes: Long,
    val lagBytes: Long,
    val eventsSeen: Long,
    val eventsInserted: Long,
    val duplicates: Long,
    val malformed: Long,
    val shrinks: Long,
    val dbEvents: Long,
    val restarts: Int?,
    val heartbeatAgeMs: Long?,
    val lastEventMillis: Long?,
) {
    companion object {
        /** One [BusStatus] in, one exposition value-set out — the only mapping. */
        fun of(status: BusStatus): BusMetrics = BusMetrics(
            transport = status.transport,
            filePresent = status.fileExists,
            fileBytes = status.fileBytes,
            offsetBytes = status.offset,
            lagBytes = status.lagBytes,
            eventsSeen = status.seen,
            eventsInserted = status.inserted,
            duplicates = status.duplicates,
            malformed = status.malformed,
            shrinks = status.shrinks,
            dbEvents = status.dbEvents,
            restarts = status.restarts,
            heartbeatAgeMs = status.heartbeatAgeMs,
            lastEventMillis = status.lastTs?.let(::parseMillis),
        )

        /** A stamp the parser does not understand is UNKNOWN, never epoch 0. */
        private fun parseMillis(ts: String): Long? = runCatching { Instant.parse(ts).toEpochMilli() }.getOrNull()
    }
}

/** The RAG corpus size, projected from one [RagStats]. */
data class RagMetrics(val documents: Long, val chunks: Long) {
    companion object {
        fun of(stats: RagStats): RagMetrics = RagMetrics(documents = stats.documents, chunks = stats.chunks)
    }
}

/** The memory leg, projected from one [MemoryStatus]; [breakerState] is the machine's own name. */
data class MemoryMetrics(
    val memories: Long,
    val pendingTurns: Long,
    val processedTurns: Long,
    val quarantined: Long,
    val breakerState: String,
) {
    companion object {
        fun of(status: MemoryStatus): MemoryMetrics = MemoryMetrics(
            memories = status.memories,
            pendingTurns = status.pendingTurns,
            processedTurns = status.processedTurns,
            quarantined = status.quarantined,
            breakerState = status.breaker,
        )
    }
}

/**
 * Gathers the three existing snapshots — no new query, no new counter:
 * `/bus/status`, `/rag/stats` and `/memory/status` are exactly what the JSON
 * routes already answer. Sequential on purpose: a scrape is an interval,
 * not a hot path, and the three reads have no ordering hazard.
 */
suspend fun metricsSnapshot(
    bus: BusPipeline?,
    rag: RagService?,
    memory: MemoryService?,
    busProcess: ProcessHandle<*>?,
): MetricsSnapshot = MetricsSnapshot(
    bus = bus?.let { BusMetrics.of(it.status(busProcess)) },
    rag = rag?.let { RagMetrics.of(it.stats()) },
    memory = memory?.let { MemoryMetrics.of(it.status()) },
)

/** The scraped body content type (Prometheus' text exposition, v0.0.4). */
const val METRICS_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8"

/** The exposition: every wired snapshot's families, in a fixed order, one trailing newline each. */
fun renderMetrics(snapshot: MetricsSnapshot): String = renderExposition(
    listOfNotNull(
        snapshot.bus?.let(::busFamilies),
        snapshot.rag?.let(::ragFamilies),
        snapshot.memory?.let(::memoryFamilies),
    ).flatten(),
)
