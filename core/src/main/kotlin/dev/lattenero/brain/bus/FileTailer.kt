package dev.lattenero.brain.bus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kap.Backpressure
import kap.Kap
import kap.KapStream
import kap.Process
import kap.Ref
import kap.RestartPolicy
import kap.Schedule
import kap.evalGraph
import kap.process
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("agent-brain.tailer")

/** The byte offset survives restarts; written atomically (temp file + move). */
class OffsetStore(private val path: Path) {
    @Serializable
    private data class State(val offset: Long)

    fun load(): Long {
        if (!Files.exists(path)) return 0
        return try {
            Json.decodeFromString<State>(Files.readString(path)).offset.coerceAtLeast(0)
        } catch (e: Exception) {
            log.warn("offset file unreadable, starting from 0: {} ({})", path, e.message)
            0
        }
    }

    fun save(offset: Long) {
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(tmp, Json.encodeToString(State.serializer(), State(offset)))
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

/** The tailer's delivered counters as one immutable value (the [TailStats] read surface). */
data class TailCounters(val lines: Long = 0, val malformed: Long = 0, val shrinks: Long = 0, val bytes: Long = 0)

/**
 * Business counters (what the tailer delivered), not liveness: liveness is the
 * process handle's restarts and heartbeat age (see [BusStatus]). The state is
 * one kap [Ref] over [TailCounters] (Phase G: "true business counters stay
 * plain until kap Ref"). The ref ops are suspend, so a poll applies its counts
 * at the suspend edge of [FileTailer.poll] — never inside the blocking read.
 */
class TailStats private constructor(private val state: Ref<TailCounters>) {
    /** One poll's delivery, atomically: lines seen (good and bad), malformed skipped, bytes consumed. */
    suspend fun add(lines: Int, malformed: Int, bytes: Long) {
        state.update {
            it.copy(lines = it.lines + lines, malformed = it.malformed + malformed, bytes = it.bytes + bytes)
        }.evalGraph()
    }

    /** The bus file shrank: truncated or rotated, the tail restarts from zero. */
    suspend fun shrunk() {
        state.update { it.copy(shrinks = it.shrinks + 1) }.evalGraph()
    }

    /** The counters as a value; the /bus/status surface reads this. */
    suspend fun snapshot(): TailCounters = state.get().evalGraph()

    companion object {
        fun of(): Kap<TailStats> = Kap { TailStats(Ref.of(TailCounters()).evalGraph()) }
    }
}

/**
 * Tails the bus file by byte offset (the diff-ask inbox pattern): reads what
 * is available past the offset, hands every COMPLETE line to the handler and
 * persists the offset only after the handler returned. A partial trailing
 * line waits for its newline; a file shorter than the offset means it was
 * truncated or rotated and the tail restarts from zero. Delivery is
 * therefore at-least-once; the database deduplicates by id (ADR-003).
 */
class FileTailer private constructor(
    private val file: Path,
    private val offsets: OffsetStore,
    private val maxReadBytes: Int,
    private val maxLineBytes: Int,
    val stats: TailStats,
) {
    /**
     * The live byte cursor. Single-writer: only the coroutine inside [poll]
     * advances it (sequential by stream/process construction); a second
     * poll() caller would interleave writes and corrupt the at-least-once
     * floor the offset file guarantees (ADR-003).
     */
    @Volatile
    var offset: Long = offsets.load()
        private set

    /** One poll. Returns the number of events handed to the handler. */
    suspend fun poll(handler: suspend (List<BusEvent>) -> Unit): Int {
        val read = withContext(Dispatchers.IO) { readChunk() } ?: return 0
        if (read.shrank) stats.shrunk()
        val (events, consumedBytes, badLines) = read
        if (consumedBytes == 0L) return 0
        if (events.isNotEmpty()) handler(events)
        offset += consumedBytes
        offsets.save(offset)
        stats.add(events.size + badLines, badLines, consumedBytes)
        return events.size
    }

    /**
     * The tail as a [KapStream] — the SPEC-5.0 dogfooding surface ("the event
     * tailer as a stream"): the poll loop IS the stream. [KapStream.emitted]
     * runs one [poll] per cadence tick and emits the batch size; the schedule
     * owns the cadence (first poll immediately, then every [interval]) and a
     * failing poll fails the stream cleanly, which is what [process] restarts.
     *
     * The laws live in [poll] and are byte-identical: the offset floor
     * (persisted only after the handler returned), the partial-line hold, the
     * shrink restart-from-zero, the per-line dedupe in the ingest below. The
     * [Backpressure] is a NAMED value and exact by law ([Backpressure.Suspend],
     * the default): nothing may be dropped and nothing may run ahead, because
     * every polled batch is delivered before the next poll begins — a drop
     * policy here would break at-least-once (ADR-003), and read-ahead would
     * decouple the cadence from the offset floor.
     */
    fun stream(
        interval: Duration,
        backpressure: Backpressure = BACKPRESSURE,
        handler: suspend (List<BusEvent>) -> Unit,
    ): KapStream<Int> =
        KapStream
            .emitted(Kap { poll(handler) })
            .every(Schedule.spaced(interval))
            .withBackpressure(backpressure)

    /**
     * The supervised long-lived loop: the [stream] drains into this body — one
     * poll per cadence comes from the stream's schedule — and a [RestartPolicy]
     * — not a `recover` around the body — owns failures, so a crash between
     * polls (a failing poll fails the stream) restarts the process too. The
     * offset file is the floor: a failed poll never advances it, so the
     * restarted body re-reads from the last persisted byte (at-least-once,
     * ADR-003). The body never completes; only cancellation (stop) ends it.
     */
    fun process(
        interval: Duration,
        handler: suspend (List<BusEvent>) -> Unit,
        restart: RestartPolicy = defaultRestart(interval),
    ): Process<Unit> {
        require(interval > Duration.ZERO) { "poll interval must be strictly positive, was $interval" }
        return Kap {
            stream(interval, handler = handler).asFlow().collect()
        }.process(
            name = PROCESS_NAME,
            restart = restart,
            heartbeatEvery = HEARTBEAT_EVERY,
            onRestart = { attempt, error, nextDelay ->
                log.warn(
                    "tailer failed (restart {} in {}): {}: {}",
                    attempt, nextDelay, error::class.simpleName, error.message,
                )
            },
        )
    }

    /** Runs the process for the caller's lifetime (CLI `ingest`); cancellation stops it. */
    suspend fun run(interval: Duration, handler: suspend (List<BusEvent>) -> Unit) {
        coroutineScope { process(interval, handler).startIn(this).await() }
    }

    private data class Chunk(
        val events: List<BusEvent>,
        val consumedBytes: Long,
        val badLines: Int,
        val shrank: Boolean = false,
    )

    private fun readChunk(): Chunk? {
        if (!Files.exists(file)) return null
        val size = Files.size(file)
        var shrank = false
        if (size < offset) {
            log.warn("bus file shrank ({} < {}): truncated or rotated, restarting from 0", size, offset)
            offset = 0
            offsets.save(0)
            shrank = true
        }
        if (size == offset) return if (shrank) Chunk(emptyList(), 0, 0, shrank = true) else null
        val bytes = readUntilNewline(size) ?: return Chunk(emptyList(), 0, 0, shrank)
        val lastNewline = bytes.lastIndexOf(NEWLINE)
        val complete = String(bytes, 0, lastNewline + 1, Charsets.UTF_8)
        val events = ArrayList<BusEvent>()
        var bad = 0
        complete.lineSequence().filter { it.isNotEmpty() }.forEach { line ->
            if (line.toByteArray(Charsets.UTF_8).size > maxLineBytes) {
                bad += 1
                log.warn("oversized bus line skipped at offset ~{} ({} bytes)", offset, line.length)
                return@forEach
            }
            when (val parsed = BusEvents.parse(line)) {
                is BusEvents.Parsed.Ok -> events += parsed.event
                is BusEvents.Parsed.Bad -> {
                    bad += 1
                    log.warn("malformed bus line skipped at offset ~{}: {}", offset, parsed.reason)
                }
            }
        }
        return Chunk(events, (lastNewline + 1).toLong(), bad, shrank)
    }

    /**
     * Reads from the offset until the window holds at least one newline. The
     * window starts at [maxReadBytes] and doubles while the file still has
     * bytes beyond it: a line longer than one read must never stall the tail
     * (it did, once). Null when only a partial line exists so far.
     */
    private fun readUntilNewline(size: Long): ByteArray? = growWindow(minOf(size - offset, maxReadBytes.toLong()).toInt(), size)

    /** Bounded byte algorithm (blocking IO, no suspension): the window doubles until a newline fits. */
    private tailrec fun growWindow(want: Int, size: Long): ByteArray? {
        val bytes = readBytes(want)
        if (bytes.lastIndexOf(NEWLINE) >= 0) return bytes
        val remaining = size - offset - bytes.size
        if (remaining <= 0) return null
        if (want >= maxLineBytes) {
            // Poison line: skip to the newline that ends it, however far away.
            return readBytes(minOf(size - offset, (maxLineBytes * 2).toLong()).toInt()).takeIf { it.lastIndexOf(NEWLINE) >= 0 }
        }
        return growWindow(minOf(size - offset, want * 2L).toInt(), size)
    }

    private fun readBytes(count: Int): ByteArray {
        val buffer = ByteBuffer.allocate(count)
        FileChannel.open(file, StandardOpenOption.READ).use { channel ->
            channel.position(offset)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) break
            }
        }
        buffer.flip()
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    companion object {
        const val PROCESS_NAME = "bus-tailer"
        const val NEWLINE: Byte = 0x0A
        const val DEFAULT_MAX_READ_BYTES = 4 * 1024 * 1024
        const val DEFAULT_MAX_LINE_BYTES = 16 * 1024 * 1024

        /**
         * The tail's named backpressure: exact. Nothing is dropped and nothing
         * runs ahead — the poller suspends until the drain took the batch.
         * Every other [Backpressure] policy drops or conflates, which the bus
         * may not (at-least-once, ADR-003).
         */
        val BACKPRESSURE: Backpressure = Backpressure.Suspend

        /** The heartbeat answers "is the process there", never "did the cycle finish". */
        val HEARTBEAT_EVERY = 5.seconds

        /** Positive base (the cadence), exponential backoff, finite cap: the convergence law. */
        fun defaultRestart(interval: Duration): RestartPolicy =
            RestartPolicy.exponential(base = interval, max = RESTART_MAX_DELAY, maxRestarts = RESTART_MAX)

        val RESTART_MAX_DELAY = 30.seconds
        const val RESTART_MAX = 8

        /**
         * The tailer is stateful (its [TailStats] is kap `Ref` state, which only
         * a suspend context can create), so construction is a [Kap]: run it
         * where the component is born (the composition root, a test's
         * `runBlocking`).
         */
        fun open(file: Path, stateDir: Path, maxReadBytes: Int = DEFAULT_MAX_READ_BYTES): Kap<FileTailer> =
            of(file, OffsetStore(stateDir.resolve("bus-offset.json")), maxReadBytes)

        /** The bridge and the tests build a tailer over their own offset store. */
        fun of(
            file: Path,
            offsets: OffsetStore,
            maxReadBytes: Int = DEFAULT_MAX_READ_BYTES,
            maxLineBytes: Int = DEFAULT_MAX_LINE_BYTES,
        ): Kap<FileTailer> = Kap { FileTailer(file, offsets, maxReadBytes, maxLineBytes, TailStats.of().evalGraph()) }
    }
}
