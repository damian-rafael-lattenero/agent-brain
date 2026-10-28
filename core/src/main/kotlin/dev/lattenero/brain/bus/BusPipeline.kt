package dev.lattenero.brain.bus

import dev.lattenero.brain.config.Config
import dev.lattenero.brain.db.Database
import kap.KapTypeSafe
import kap.evalGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = if (isKafka) {
            KafkaTopics.ensure(config)
            scope.launch { kafka!!.run() }
        } else {
            scope.launch { tailer.run(config.busPollMs.milliseconds) { ingest.handle(it) } }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        kafka?.close()
    }

    /** Drains everything currently available on the transport; returns the events handed over. */
    suspend fun drain(): Int {
        if (isKafka) {
            KafkaTopics.ensure(config)
            return kafka!!.drain()
        }
        var total = 0
        while (true) {
            val n = tailer.poll { ingest.handle(it) }
            if (n == 0 && tailer.offset >= fileBytes()) break
            total += n
        }
        return total
    }

    private fun fileBytes(): Long = if (Files.exists(config.busFile)) Files.size(config.busFile) else 0

    /** File size and database count are independent probes; the typed builder runs them together. */
    @KapTypeSafe
    data class Probe(val fileBytes: Long, val dbEvents: Long)

    suspend fun status(): BusStatus {
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
            polls = if (isKafka) kafka!!.stats.polls.get() else tailer.stats.polls.get(),
            linesRead = if (isKafka) kafka!!.stats.records.get() else tailer.stats.lines.get(),
            malformed = if (isKafka) kafka!!.stats.malformed.get() else tailer.stats.malformed.get(),
            shrinks = tailer.stats.shrinks.get(),
            seen = ingest.stats.seen.get(),
            inserted = ingest.stats.inserted.get(),
            duplicates = ingest.stats.duplicates.get(),
            dbEvents = probe.dbEvents,
            lastTs = ingest.stats.lastTs,
            lastKind = ingest.stats.lastKind,
            kafkaCommits = kafka?.stats?.commits?.get(),
            kafkaOffsets = kafka?.stats?.lastOffsets?.mapKeys { it.key.toString() },
        )
    }

    companion object {
        const val TRANSPORT_FILE = "file"
        const val TRANSPORT_KAFKA = "kafka"
    }
}
