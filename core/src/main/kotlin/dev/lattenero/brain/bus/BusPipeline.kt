package dev.lattenero.brain.bus

import dev.lattenero.brain.config.Config
import dev.lattenero.brain.db.Database
import kap.Kap
import kap.KapTypeSafe
import kap.Process
import kap.ProcessHandle
import kap.evalGraph
import kap.repeatUntil
import kotlinx.coroutines.coroutineScope
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tailer-or-consumer + ingest + status, wired from the injected config. The
 * transport is a knob (`bus_transport: file | kafka`); the handler behind it
 * is the same object either way (ADR-003).
 */
class BusPipeline(private val config: Config, db: Database) {
    val tailer: FileTailer = FileTailer.open(config.busFile, config.stateDir)
    val ingest: PgBusIngest = PgBusIngest(db)
    val isKafka: Boolean get() = config.busTransport == TRANSPORT_KAFKA
    val kafka: KafkaBusConsumer? = if (isKafka) KafkaBusConsumer(config, ingest) else null

    /**
     * The ONE long-lived loop for the configured transport, as a named
     * [Process]: start it in the process scope, never launch it by hand.
     */
    fun process(): Process<Int> =
        if (isKafka) {
            KafkaTopics.ensure(config)
            kafka!!.process()
        } else {
            tailer.process(config.busPollMs.milliseconds, handler = { ingest.handle(it) })
        }

    /** Runs the loop for the caller's lifetime (CLI `ingest`); cancellation stops it. */
    suspend fun run() {
        coroutineScope { process().startIn(this).await() }
    }

    /** Drains everything currently available on the transport; returns the events handed over. */
    suspend fun drain(): Int {
        if (isKafka) {
            KafkaTopics.ensure(config)
            return kafka!!.drain()
        }
        var total = 0
        Kap {
            tailer.poll { ingest.handle(it) }.also { total += it }
        }.repeatUntil { tailer.offset >= fileBytes() }.evalGraph()
        return total
    }

    private fun fileBytes(): Long = if (Files.exists(config.busFile)) Files.size(config.busFile) else 0

    /** File size and database count are independent probes; the typed builder runs them together. */
    @KapTypeSafe
    data class Probe(val fileBytes: Long, val dbEvents: Long)

    /**
     * The status envelope. Business counters stay plain (Phase G's `Ref` will
     * own them); liveness comes from the process [handle] — restarts and
     * heartbeat age — never from a counter that fakes it. Without a handle
     * (the `bus status` CLI talks to a database, not a running loop) both
     * liveness fields are null: unknown, not invented.
     */
    suspend fun status(handle: ProcessHandle<*>? = null): BusStatus {
        val probe = kap(::Probe)
            .with { fileBytes from fileBytes() }
            .with { dbEvents from ingest.count() }
            .evalGraph()
        val bytes = probe.fileBytes
        return BusStatus(
            transport = config.busTransport,
            file = config.busFile.toString(),
            fileExists = Files.exists(config.busFile),
            fileBytes = bytes,
            offset = tailer.offset,
            lagBytes = (bytes - tailer.offset).coerceAtLeast(0),
            linesRead = if (isKafka) kafka!!.stats.records.get() else tailer.stats.lines.get(),
            malformed = if (isKafka) kafka!!.stats.malformed.get() else tailer.stats.malformed.get(),
            shrinks = tailer.stats.shrinks.get(),
            seen = ingest.stats.seen.get(),
            inserted = ingest.stats.inserted.get(),
            duplicates = ingest.stats.duplicates.get(),
            dbEvents = probe.dbEvents,
            lastTs = ingest.stats.lastTs,
            lastKind = ingest.stats.lastKind,
            restarts = handle?.restarts?.value,
            heartbeatAgeMs = handle?.heartbeatAge()?.takeIf { it.isFinite() }?.inWholeMilliseconds,
            kafkaCommits = kafka?.stats?.commits?.get(),
            kafkaOffsets = kafka?.stats?.lastOffsets?.mapKeys { it.key.toString() },
        )
    }

    companion object {
        const val TRANSPORT_FILE = "file"
        const val TRANSPORT_KAFKA = "kafka"
    }
}
