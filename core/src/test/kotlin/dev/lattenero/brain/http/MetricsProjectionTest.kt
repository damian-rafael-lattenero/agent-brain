package dev.lattenero.brain.http

import dev.lattenero.brain.bus.BusStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The exposition is a pure function of the snapshots: every law below runs
 * with no stack, no clock and no file — the projection IS the test surface.
 */
class MetricsProjectionTest {

    @Test
    fun `the bus block projects the status counters and gauges`() {
        val text = renderMetrics(MetricsSnapshot(bus = bus()))
        val expected = """
            # HELP brain_bus_events_seen_total Bus events accepted by the ingest since this process started (BusStatus.seen).
            # TYPE brain_bus_events_seen_total counter
            brain_bus_events_seen_total{transport="file"} 416
            # HELP brain_bus_events_inserted_total Bus events inserted since this process started (BusStatus.inserted).
            # TYPE brain_bus_events_inserted_total counter
            brain_bus_events_inserted_total{transport="file"} 416
            # HELP brain_bus_events_duplicates_total Accepted events the database already had, ignored (BusStatus.duplicates).
            # TYPE brain_bus_events_duplicates_total counter
            brain_bus_events_duplicates_total{transport="file"} 7
            # HELP brain_bus_lines_malformed_total Bus lines the parser refused since this process started (BusStatus.malformed).
            # TYPE brain_bus_lines_malformed_total counter
            brain_bus_lines_malformed_total{transport="file"} 2
            # HELP brain_bus_file_shrinks_total Times the bus file shrank under the tailer: the offset was reset (BusStatus.shrinks).
            # TYPE brain_bus_file_shrinks_total counter
            brain_bus_file_shrinks_total{transport="file"} 1
            # HELP brain_bus_file_present 1 when the configured bus file exists at scrape time (BusStatus.fileExists).
            # TYPE brain_bus_file_present gauge
            brain_bus_file_present{transport="file"} 1
            # HELP brain_bus_file_bytes The bus file's size at scrape time (BusStatus.fileBytes).
            # TYPE brain_bus_file_bytes gauge
            brain_bus_file_bytes{transport="file"} 3401951
            # HELP brain_bus_offset_bytes The tailer's committed offset in the bus file (BusStatus.offset).
            # TYPE brain_bus_offset_bytes gauge
            brain_bus_offset_bytes{transport="file"} 3400000
            # HELP brain_bus_lag_bytes Bytes the tailer has not consumed: fileBytes minus offset (BusStatus.lagBytes).
            # TYPE brain_bus_lag_bytes gauge
            brain_bus_lag_bytes{transport="file"} 1951
            # HELP brain_bus_db_events Rows in bus_events, counted at scrape time (BusStatus.dbEvents).
            # TYPE brain_bus_db_events gauge
            brain_bus_db_events{transport="file"} 5953
            # HELP brain_bus_restarts_total Restarts of the running bus process (ProcessHandle.restarts); absent with no loop.
            # TYPE brain_bus_restarts_total counter
            brain_bus_restarts_total{transport="file"} 3
            # HELP brain_bus_heartbeat_age_seconds Age of the bus process's last heartbeat (ProcessHandle.heartbeatAge).
            # TYPE brain_bus_heartbeat_age_seconds gauge
            brain_bus_heartbeat_age_seconds{transport="file"} 0.518
            # HELP brain_bus_last_event_timestamp_seconds The ingest's last accepted event stamp in unix seconds (BusStatus.lastTs).
            # TYPE brain_bus_last_event_timestamp_seconds gauge
            brain_bus_last_event_timestamp_seconds{transport="file"} 1762215295.315
        """.trimIndent()
        assertThat(text).isEqualTo(expected + "\n")
    }

    @Test
    fun `rag and memory blocks project their snapshots`() {
        val text = renderMetrics(
            MetricsSnapshot(
                rag = RagMetrics(documents = 17460, chunks = 60441),
                memory = MemoryMetrics(memories = 44, pendingTurns = 0, processedTurns = 31, quarantined = 2, breakerState = "Closed"),
            ),
        )
        val expected = """
            # HELP brain_rag_documents Indexed documents in the corpus (RagStats.documents).
            # TYPE brain_rag_documents gauge
            brain_rag_documents 17460
            # HELP brain_rag_chunks Indexed chunks across all documents (RagStats.chunks).
            # TYPE brain_rag_chunks gauge
            brain_rag_chunks 60441
            # HELP brain_memory_memories Live memories stored (MemoryStatus.memories).
            # TYPE brain_memory_memories gauge
            brain_memory_memories 44
            # HELP brain_memory_pending_turns Completed turns the extraction has not processed yet (MemoryStatus.pendingTurns).
            # TYPE brain_memory_pending_turns gauge
            brain_memory_pending_turns 0
            # HELP brain_memory_processed_turns Turns the extraction already processed (MemoryStatus.processedTurns).
            # TYPE brain_memory_processed_turns gauge
            brain_memory_processed_turns 31
            # HELP brain_memory_quarantined Extraction attempts quarantined as unusable (MemoryStatus.quarantined).
            # TYPE brain_memory_quarantined gauge
            brain_memory_quarantined 2
            # HELP brain_memory_breaker_state The embedding breaker's state, one-hot over Closed/Open/HalfOpen (MemoryStatus.breaker).
            # TYPE brain_memory_breaker_state gauge
            brain_memory_breaker_state{state="Closed"} 1
            brain_memory_breaker_state{state="Open"} 0
            brain_memory_breaker_state{state="HalfOpen"} 0
        """.trimIndent()
        assertThat(text).isEqualTo(expected + "\n")
    }

    @Test
    fun `nothing wired exposes nothing`() {
        assertThat(renderMetrics(MetricsSnapshot())).isEmpty()
    }

    @Test
    fun `an unwired leg is absent while the wired ones still answer`() {
        val text = renderMetrics(MetricsSnapshot(rag = RagMetrics(1, 2)))
        assertThat(text).contains("brain_rag_documents 1")
        assertThat(text).doesNotContain("brain_bus_")
        assertThat(text).doesNotContain("brain_memory_")
    }

    @Test
    fun `unknown liveness is omitted, never a zeroed gauge`() {
        val text = renderMetrics(MetricsSnapshot(bus = bus(restarts = null, heartbeatAgeMs = null)))
        assertThat(text).doesNotContain("brain_bus_restarts_total")
        assertThat(text).doesNotContain("brain_bus_heartbeat_age_seconds")
        // the rest of the bus block is untouched
        assertThat(text).contains("brain_bus_lag_bytes{transport=\"file\"} 1951")
    }

    @Test
    fun `milliseconds render as plain seconds with three decimals`() {
        assertThat(renderMetrics(MetricsSnapshot(bus = bus(heartbeatAgeMs = 5))))
            .contains("brain_bus_heartbeat_age_seconds{transport=\"file\"} 0.005")
        assertThat(renderMetrics(MetricsSnapshot(bus = bus(heartbeatAgeMs = 60_000))))
            .contains("brain_bus_heartbeat_age_seconds{transport=\"file\"} 60.000")
        assertThat(renderMetrics(MetricsSnapshot(bus = bus(lastEventMillis = 1_762_215_295_315))))
            .contains("brain_bus_last_event_timestamp_seconds{transport=\"file\"} 1762215295.315")
    }

    @Test
    fun `a bus status maps every field, and an unparseable stamp is unknown`() {
        val status = BusStatus(
            transport = "file",
            file = "/tmp/bus.jsonl",
            fileExists = false,
            fileBytes = 100,
            offset = 90,
            lagBytes = 10,
            linesRead = 4,
            malformed = 1,
            shrinks = 2,
            seen = 3,
            inserted = 2,
            duplicates = 1,
            dbEvents = 9,
            lastTs = "not-a-timestamp",
            lastKind = "turn.completed",
            restarts = null,
            heartbeatAgeMs = 250,
        )
        val mapped = BusMetrics.of(status)
        assertThat(mapped).isEqualTo(
            BusMetrics(
                transport = "file",
                filePresent = false,
                fileBytes = 100,
                offsetBytes = 90,
                lagBytes = 10,
                eventsSeen = 3,
                eventsInserted = 2,
                duplicates = 1,
                malformed = 1,
                shrinks = 2,
                dbEvents = 9,
                restarts = null,
                heartbeatAgeMs = 250,
                lastEventMillis = null,
            ),
        )
        assertThat(renderMetrics(MetricsSnapshot(bus = mapped))).doesNotContain("brain_bus_last_event_timestamp_seconds")
    }

    @Test
    fun `the breaker is one-hot and an unknown state is still the truth`() {
        val open = renderMetrics(MetricsSnapshot(memory = memory(breaker = "Open")))
        assertThat(open).contains("brain_memory_breaker_state{state=\"Open\"} 1")
        assertThat(open).contains("brain_memory_breaker_state{state=\"Closed\"} 0")

        val exotic = renderMetrics(MetricsSnapshot(memory = memory(breaker = "Melted")))
        assertThat(exotic).contains("brain_memory_breaker_state{state=\"Melted\"} 1")
        assertThat(exotic).contains("brain_memory_breaker_state{state=\"Closed\"} 0")
    }

    @Test
    fun `label values escape the exposition grammar`() {
        val text = renderMetrics(MetricsSnapshot(bus = bus(transport = "ka\"fka\\x")))
        assertThat(text).contains("brain_bus_lag_bytes{transport=\"ka\\\"fka\\\\x\"} 1951")
    }

    @Test
    fun `every family declares HELP and TYPE before its samples`() {
        val text = renderMetrics(
            MetricsSnapshot(
                bus = bus(),
                rag = RagMetrics(1, 2),
                memory = memory(breaker = "HalfOpen"),
            ),
        )
        val lines = text.trimEnd('\n').lines()
        val declared = mutableSetOf<String>()
        lines.forEach { line ->
            when {
                line.startsWith("# HELP ") -> {
                    val name = line.removePrefix("# HELP ").substringBefore(' ')
                    assertThat(declared).doesNotContain(name)
                    declared += name
                }

                line.startsWith("# TYPE ") -> assertThat(line.removePrefix("# TYPE ").substringBefore(' ')).isIn(declared)

                else -> {
                    val name = line.substringBefore('{').substringBefore(' ')
                    assertThat(declared).contains(name)
                }
            }
        }
        assertThat(text).doesNotContain("NaN").doesNotContain("Infinity")
    }

    private fun bus(
        transport: String = "file",
        restarts: Int? = 3,
        heartbeatAgeMs: Long? = 518,
        lastEventMillis: Long? = 1_762_215_295_315,
    ) = BusMetrics(
        transport = transport,
        filePresent = true,
        fileBytes = 3_401_951,
        offsetBytes = 3_400_000,
        lagBytes = 1_951,
        eventsSeen = 416,
        eventsInserted = 416,
        duplicates = 7,
        malformed = 2,
        shrinks = 1,
        dbEvents = 5_953,
        restarts = restarts,
        heartbeatAgeMs = heartbeatAgeMs,
        lastEventMillis = lastEventMillis,
    )

    private fun memory(breaker: String) =
        MemoryMetrics(memories = 44, pendingTurns = 0, processedTurns = 31, quarantined = 2, breakerState = breaker)
}
