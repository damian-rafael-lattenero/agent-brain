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

/** Tailer + ingest + status, wired from the injected config. */
class BusPipeline(private val config: Config, db: Database) {
    val tailer: FileTailer = FileTailer.open(config.busFile, config.stateDir)
    val ingest: PgBusIngest = PgBusIngest(db)
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch { tailer.run(config.busPollMs.milliseconds) { ingest.handle(it) } }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Drains everything currently in the file; returns the events handed over. */
    suspend fun drain(): Int {
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
            polls = tailer.stats.polls.get(),
            linesRead = tailer.stats.lines.get(),
            malformed = tailer.stats.malformed.get(),
            shrinks = tailer.stats.shrinks.get(),
            seen = ingest.stats.seen.get(),
            inserted = ingest.stats.inserted.get(),
            duplicates = ingest.stats.duplicates.get(),
            dbEvents = probe.dbEvents,
            lastTs = ingest.stats.lastTs,
            lastKind = ingest.stats.lastKind,
        )
    }
}
