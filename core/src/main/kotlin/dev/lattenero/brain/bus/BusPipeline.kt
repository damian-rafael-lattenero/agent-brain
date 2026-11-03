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
 * is the same object either way (ADR-003). The pipeline and its parts hold kap
 * `Ref` state (the business counters), so construction is a [Kap] — run
 * [of] where the component is born (the composition root's resource chain).
 */
class BusPipeline private constructor(
    private val config: Config,
    val tailer: FileTailer,
    val ingest: PgBusIngest,
    val kafka: KafkaBusConsumer?,
) {
    val isKafka: Boolean get() = config.busTransport == TRANSPORT_KAFKA

    /**
     * The ONE long-lived loop for the configured transport, as a named
     * [Process]: start it in the process scope, never launch it by hand. The
     * tailer's process drains its [FileTailer.stream] (the body never
     * completes); the Kafka consumer keeps its own cycle value.
     */
    fun process(): Process<*> =
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

    /**
     * Drains everything currently available on the transport; returns the
     * events handed over. The repeated body is stateless — the tailer polls
     * one batch and hands it to the ingest — and the loop terminates on the
     * same live condition as before (the tail caught up with the file's
     * current size, re-read each round). The drain's total is not a closure
     * counter: the ingest folds every accepted batch into its stats `Ref`, so
     * the caller folds the total from the counter's before/after values
     * (Phase D: state as values, never side effects on captured vars).
     * Single-shot: no other handle may run on this ingest while the drain's
     * two reads bracket the loop.
     */
    suspend fun drain(): Int {
        if (isKafka) {
            KafkaTopics.ensure(config)
            return kafka!!.drain()
        }
        val before = ingest.stats.snapshot().seen
        Kap { tailer.poll { ingest.handle(it) } }
            .repeatUntil { tailer.offset >= fileBytes() }
            .evalGraph()
        return (ingest.stats.snapshot().seen - before).toInt()
    }

    private fun fileBytes(): Long = if (Files.exists(config.busFile)) Files.size(config.busFile) else 0

    /** File size and database count are independent probes; the typed builder runs them together. */
    @KapTypeSafe
    data class Probe(val fileBytes: Long, val dbEvents: Long)

    /**
     * The status envelope. The business counters come from the components'
     * kap `Ref` snapshots (one immutable value each); liveness comes from the
     * process [handle] — restarts and heartbeat age — never from a counter
     * that fakes it. Without a handle (the `bus status` CLI talks to a
     * database, not a running loop) both liveness fields are null: unknown,
     * not invented.
     */
    suspend fun status(handle: ProcessHandle<*>? = null): BusStatus {
        val probe = kap(::Probe)
            .with { fileBytes from fileBytes() }
            .with { dbEvents from ingest.count() }
            .evalGraph()
        val bytes = probe.fileBytes
        val tail = tailer.stats.snapshot()
        val accepted = ingest.stats.snapshot()
        val consumed = kafka?.stats?.snapshot()
        return BusStatus(
            transport = config.busTransport,
            file = config.busFile.toString(),
            fileExists = Files.exists(config.busFile),
            fileBytes = bytes,
            offset = tailer.offset,
            lagBytes = (bytes - tailer.offset).coerceAtLeast(0),
            linesRead = consumed?.records ?: tail.lines,
            malformed = consumed?.malformed ?: tail.malformed,
            shrinks = tail.shrinks,
            seen = accepted.seen,
            inserted = accepted.inserted,
            duplicates = accepted.duplicates,
            dbEvents = probe.dbEvents,
            lastTs = accepted.lastTs,
            lastKind = accepted.lastKind,
            restarts = handle?.restarts?.value,
            heartbeatAgeMs = handle?.heartbeatAge()?.takeIf { it.isFinite() }?.inWholeMilliseconds,
            kafkaCommits = consumed?.commits,
            kafkaOffsets = consumed?.lastOffsets?.mapKeys { it.key.toString() },
        )
    }

    companion object {
        const val TRANSPORT_FILE = "file"
        const val TRANSPORT_KAFKA = "kafka"

        /** Builds the transport's parts (each a [Kap] itself: they hold `Ref` state). */
        fun of(config: Config, db: Database): Kap<BusPipeline> = Kap {
            val tailer = FileTailer.open(config.busFile, config.stateDir).evalGraph()
            val ingest = PgBusIngest.of(db).evalGraph()
            val kafka = if (config.busTransport == TRANSPORT_KAFKA) {
                KafkaBusConsumer.of(config, ingest).evalGraph()
            } else {
                null
            }
            BusPipeline(config, tailer, ingest, kafka)
        }
    }
}
