package dev.lattenero.brain.http

/**
 * The inventory: every family the brain exposes, each one a projection of a
 * snapshot field the JSON routes already answer. The names are the contract
 * a dashboard reads; the help text names the provenance, so a panel can be
 * judged against the real event behind it (the honest-indicators law).
 *
 * No decorative family exists here: if a value is not already a field of
 * /bus/status, /rag/stats or /memory/status, it is not in this file.
 */
internal fun busFamilies(bus: BusMetrics): List<MetricFamily> {
    val labels = mapOf(LABEL_TRANSPORT to bus.transport)
    return busCounters(bus, labels) + busGauges(bus, labels) + busLiveness(bus, labels)
}

private fun busCounters(bus: BusMetrics, labels: Map<String, String>): List<MetricFamily> = listOf(
    family(NAME_SEEN, HELP_SEEN, TYPE_COUNTER, sample(NAME_SEEN, labels, bus.eventsSeen)),
    family(NAME_INSERTED, HELP_INSERTED, TYPE_COUNTER, sample(NAME_INSERTED, labels, bus.eventsInserted)),
    family(NAME_DUPLICATES, HELP_DUPLICATES, TYPE_COUNTER, sample(NAME_DUPLICATES, labels, bus.duplicates)),
    family(NAME_MALFORMED, HELP_MALFORMED, TYPE_COUNTER, sample(NAME_MALFORMED, labels, bus.malformed)),
    family(NAME_SHRINKS, HELP_SHRINKS, TYPE_COUNTER, sample(NAME_SHRINKS, labels, bus.shrinks)),
)

private fun busGauges(bus: BusMetrics, labels: Map<String, String>): List<MetricFamily> = listOf(
    family(NAME_FILE_PRESENT, HELP_FILE_PRESENT, TYPE_GAUGE, sample(NAME_FILE_PRESENT, labels, flag(bus.filePresent))),
    family(NAME_FILE_BYTES, HELP_FILE_BYTES, TYPE_GAUGE, sample(NAME_FILE_BYTES, labels, bus.fileBytes)),
    family(NAME_OFFSET_BYTES, HELP_OFFSET_BYTES, TYPE_GAUGE, sample(NAME_OFFSET_BYTES, labels, bus.offsetBytes)),
    family(NAME_LAG_BYTES, HELP_LAG_BYTES, TYPE_GAUGE, sample(NAME_LAG_BYTES, labels, bus.lagBytes)),
    family(NAME_DB_EVENTS, HELP_DB_EVENTS, TYPE_GAUGE, sample(NAME_DB_EVENTS, labels, bus.dbEvents)),
)

/** Liveness exists only when the handle does: an unknown restart count is absent, not zero. */
private fun busLiveness(bus: BusMetrics, labels: Map<String, String>): List<MetricFamily> = listOfNotNull(
    bus.restarts?.let { family(NAME_RESTARTS, HELP_RESTARTS, TYPE_COUNTER, sample(NAME_RESTARTS, labels, it)) },
    bus.heartbeatAgeMs?.let {
        family(NAME_HEARTBEAT, HELP_HEARTBEAT, TYPE_GAUGE, sample(NAME_HEARTBEAT, labels, seconds(it)))
    },
    bus.lastEventMillis?.let {
        family(NAME_LAST_EVENT, HELP_LAST_EVENT, TYPE_GAUGE, sample(NAME_LAST_EVENT, labels, seconds(it)))
    },
)

internal fun ragFamilies(rag: RagMetrics): List<MetricFamily> = listOf(
    family(NAME_DOCUMENTS, HELP_DOCUMENTS, TYPE_GAUGE, sample(NAME_DOCUMENTS, EMPTY_LABELS, rag.documents)),
    family(NAME_CHUNKS, HELP_CHUNKS, TYPE_GAUGE, sample(NAME_CHUNKS, EMPTY_LABELS, rag.chunks)),
)

internal fun memoryFamilies(memory: MemoryMetrics): List<MetricFamily> = listOf(
    family(NAME_MEMORIES, HELP_MEMORIES, TYPE_GAUGE, sample(NAME_MEMORIES, EMPTY_LABELS, memory.memories)),
    family(NAME_PENDING, HELP_PENDING, TYPE_GAUGE, sample(NAME_PENDING, EMPTY_LABELS, memory.pendingTurns)),
    family(NAME_PROCESSED, HELP_PROCESSED, TYPE_GAUGE, sample(NAME_PROCESSED, EMPTY_LABELS, memory.processedTurns)),
    family(NAME_QUARANTINED, HELP_QUARANTINED, TYPE_GAUGE, sample(NAME_QUARANTINED, EMPTY_LABELS, memory.quarantined)),
    MetricFamily(NAME_BREAKER, HELP_BREAKER, TYPE_GAUGE, breakerSamples(memory.breakerState)),
)

/**
 * One-hot over the breaker's three real states, so `{state="Open"}` reads 0
 * while healthy instead of going absent (a stat panel must not confuse
 * "healthy" with "scrape down"); a state outside the machine's set is
 * still exposed as the truth rather than swallowed.
 */
private fun breakerSamples(current: String): List<String> =
    BREAKER_STATES.map { sample(NAME_BREAKER, mapOf(LABEL_STATE to it), flag(it == current)) } +
        if (current in BREAKER_STATES) emptyList() else listOf(sample(NAME_BREAKER, mapOf(LABEL_STATE to current), "1"))

private val EMPTY_LABELS = emptyMap<String, String>()
private val BREAKER_STATES = listOf("Closed", "Open", "HalfOpen")

private const val LABEL_TRANSPORT = "transport"
private const val LABEL_STATE = "state"

// ── bus ─────────────────────────────────────────────────────────────────

private const val NAME_SEEN = "brain_bus_events_seen_total"
private const val HELP_SEEN = "Bus events accepted by the ingest since this process started (BusStatus.seen)."
private const val NAME_INSERTED = "brain_bus_events_inserted_total"
private const val HELP_INSERTED = "Bus events inserted since this process started (BusStatus.inserted)."
private const val NAME_DUPLICATES = "brain_bus_events_duplicates_total"
private const val HELP_DUPLICATES = "Accepted events the database already had, ignored (BusStatus.duplicates)."
private const val NAME_MALFORMED = "brain_bus_lines_malformed_total"
private const val HELP_MALFORMED = "Bus lines the parser refused since this process started (BusStatus.malformed)."
private const val NAME_SHRINKS = "brain_bus_file_shrinks_total"
private const val HELP_SHRINKS = "Times the bus file shrank under the tailer: the offset was reset (BusStatus.shrinks)."
private const val NAME_FILE_PRESENT = "brain_bus_file_present"
private const val HELP_FILE_PRESENT = "1 when the configured bus file exists at scrape time (BusStatus.fileExists)."
private const val NAME_FILE_BYTES = "brain_bus_file_bytes"
private const val HELP_FILE_BYTES = "The bus file's size at scrape time (BusStatus.fileBytes)."
private const val NAME_OFFSET_BYTES = "brain_bus_offset_bytes"
private const val HELP_OFFSET_BYTES = "The tailer's committed offset in the bus file (BusStatus.offset)."
private const val NAME_LAG_BYTES = "brain_bus_lag_bytes"
private const val HELP_LAG_BYTES = "Bytes the tailer has not consumed: fileBytes minus offset (BusStatus.lagBytes)."
private const val NAME_DB_EVENTS = "brain_bus_db_events"
private const val HELP_DB_EVENTS = "Rows in bus_events, counted at scrape time (BusStatus.dbEvents)."
private const val NAME_RESTARTS = "brain_bus_restarts_total"
private const val HELP_RESTARTS = "Restarts of the running bus process (ProcessHandle.restarts); absent with no loop."
private const val NAME_HEARTBEAT = "brain_bus_heartbeat_age_seconds"
private const val HELP_HEARTBEAT = "Age of the bus process's last heartbeat (ProcessHandle.heartbeatAge)."
private const val NAME_LAST_EVENT = "brain_bus_last_event_timestamp_seconds"
private const val HELP_LAST_EVENT = "The ingest's last accepted event stamp in unix seconds (BusStatus.lastTs)."

// ── rag ─────────────────────────────────────────────────────────────────

private const val NAME_DOCUMENTS = "brain_rag_documents"
private const val HELP_DOCUMENTS = "Indexed documents in the corpus (RagStats.documents)."
private const val NAME_CHUNKS = "brain_rag_chunks"
private const val HELP_CHUNKS = "Indexed chunks across all documents (RagStats.chunks)."

// ── memory ──────────────────────────────────────────────────────────────

private const val NAME_MEMORIES = "brain_memory_memories"
private const val HELP_MEMORIES = "Live memories stored (MemoryStatus.memories)."
private const val NAME_PENDING = "brain_memory_pending_turns"
private const val HELP_PENDING = "Completed turns the extraction has not processed yet (MemoryStatus.pendingTurns)."
private const val NAME_PROCESSED = "brain_memory_processed_turns"
private const val HELP_PROCESSED = "Turns the extraction already processed (MemoryStatus.processedTurns)."
private const val NAME_QUARANTINED = "brain_memory_quarantined"
private const val HELP_QUARANTINED = "Extraction attempts quarantined as unusable (MemoryStatus.quarantined)."
private const val NAME_BREAKER = "brain_memory_breaker_state"
private const val HELP_BREAKER =
    "The embedding breaker's state, one-hot over Closed/Open/HalfOpen (MemoryStatus.breaker)."
