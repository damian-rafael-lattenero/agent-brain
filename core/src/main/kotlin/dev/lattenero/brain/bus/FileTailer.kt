package dev.lattenero.brain.bus

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

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

class TailStats {
    val polls = AtomicLong()
    val lines = AtomicLong()
    val malformed = AtomicLong()
    val shrinks = AtomicLong()
    val bytes = AtomicLong()
}

/**
 * Tails the bus file by byte offset (the diff-ask inbox pattern): reads what
 * is available past the offset, hands every COMPLETE line to the handler and
 * persists the offset only after the handler returned. A partial trailing
 * line waits for its newline; a file shorter than the offset means it was
 * truncated or rotated and the tail restarts from zero. Delivery is
 * therefore at-least-once; the database deduplicates by id (ADR-003).
 */
class FileTailer(
    private val file: Path,
    private val offsets: OffsetStore,
    private val maxReadBytes: Int = DEFAULT_MAX_READ_BYTES,
    private val maxLineBytes: Int = DEFAULT_MAX_LINE_BYTES,
) {
    @Volatile
    var offset: Long = offsets.load()
        private set

    val stats = TailStats()

    /** One poll. Returns the number of events handed to the handler. */
    suspend fun poll(handler: suspend (List<BusEvent>) -> Unit): Int {
        stats.polls.incrementAndGet()
        val read = withContext(Dispatchers.IO) { readChunk() } ?: return 0
        val (events, consumedBytes, badLines) = read
        if (consumedBytes == 0L) return 0
        if (events.isNotEmpty()) handler(events)
        offset += consumedBytes
        offsets.save(offset)
        stats.lines.addAndGet(events.size.toLong() + badLines)
        stats.malformed.addAndGet(badLines.toLong())
        stats.bytes.addAndGet(consumedBytes)
        return events.size
    }

    /** Polls forever at the given cadence; cancellation stops it cleanly. */
    suspend fun run(interval: Duration, handler: suspend (List<BusEvent>) -> Unit) {
        while (true) {
            try {
                poll(handler)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("poll failed, will retry: {}: {}", e::class.simpleName, e.message)
            }
            delay(interval)
        }
    }

    private data class Chunk(val events: List<BusEvent>, val consumedBytes: Long, val badLines: Int)

    private fun readChunk(): Chunk? {
        if (!Files.exists(file)) return null
        val size = Files.size(file)
        if (size < offset) {
            log.warn("bus file shrank ({} < {}): truncated or rotated, restarting from 0", size, offset)
            stats.shrinks.incrementAndGet()
            offset = 0
            offsets.save(0)
        }
        if (size == offset) return null
        val bytes = readUntilNewline(size) ?: return Chunk(emptyList(), 0, 0)
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
        return Chunk(events, (lastNewline + 1).toLong(), bad)
    }

    /**
     * Reads from the offset until the window holds at least one newline. The
     * window starts at [maxReadBytes] and doubles while the file still has
     * bytes beyond it: a line longer than one read must never stall the tail
     * (it did, once). Null when only a partial line exists so far.
     */
    private fun readUntilNewline(size: Long): ByteArray? {
        var want = minOf(size - offset, maxReadBytes.toLong()).toInt()
        while (true) {
            val bytes = readBytes(want)
            if (bytes.lastIndexOf(NEWLINE) >= 0) return bytes
            val remaining = size - offset - bytes.size
            if (remaining <= 0) return null
            if (want >= maxLineBytes) {
                // Poison line: skip to the newline that ends it, however far away.
                return readBytes(minOf(size - offset, (maxLineBytes * 2).toLong()).toInt()).takeIf { it.lastIndexOf(NEWLINE) >= 0 }
            }
            want = minOf(size - offset, want * 2L).toInt()
        }
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
        const val NEWLINE: Byte = 0x0A
        const val DEFAULT_MAX_READ_BYTES = 4 * 1024 * 1024
        const val DEFAULT_MAX_LINE_BYTES = 16 * 1024 * 1024

        fun open(file: Path, stateDir: Path, maxReadBytes: Int = DEFAULT_MAX_READ_BYTES): FileTailer =
            FileTailer(file, OffsetStore(stateDir.resolve("bus-offset.json")), maxReadBytes)
    }
}
