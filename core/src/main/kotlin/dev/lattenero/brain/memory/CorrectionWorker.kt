package dev.lattenero.brain.memory

import arrow.core.Either
import kap.CircuitBreakerOpenException
import kap.Kap
import kap.Meter
import kap.Process
import kap.RestartPolicy
import kap.Schedule
import kap.Tags
import kap.attempt
import kap.evalGraph
import kap.process
import kap.repeat
import kap.traverseSettled
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("agent-brain.memory")

/**
 * Why one correction's extraction did not propose (plan U8) — the bins as
 * values. Both degraded bins LEAVE THE ROW PENDING: the bus must never break,
 * and a correction is the owner's words, never dropped.
 */
sealed interface CorrectionOutcome {
    /** The extractor yielded a rule sentence; the row flipped to proposed. */
    data class Proposed(val id: String, val candidate: String) : CorrectionOutcome

    /** The judge answered but not with a usable rule: the row keeps its evidence stamp and STAYS pending. */
    data class Degraded(val id: String, val reason: String) : CorrectionOutcome

    /** The provider (chat) could not answer: the row is untouched and STAYS pending; the next cycle retries. */
    data class Deferred(val id: String, val reason: String) : CorrectionOutcome
}

/** One pass's facts as one value: what the cycle scanned and where every row landed. */
@Serializable
data class CorrectionReport(
    val scanned: Int,
    val proposed: Int,
    val degraded: Int,
    val deferred: Int,
)

/** The worker's knobs as ONE value: the bounded batch per pass. */
data class CorrectionParams(
    val batch: Int = DEFAULT_BATCH,
) {
    companion object {
        /** A small batch per pass: one chat call per row, never a wall of calls. */
        const val DEFAULT_BATCH = 8
    }
}

/**
 * Polls the corrections table for pending rows with a blank candidate, turns
 * the owner's words into a rule sentence (ONE chat call each, bounded by
 * [CorrectionParams.batch]), and stamps the extraction. Idempotent by row id:
 * the insert-or-ignore ingest means a replayed bus event adds nothing, and
 * the markExtracted guard means a stamped row is never re-fronted.
 *
 * The degraded law (silence is a lie): an extraction failure — a judge answer
 * with no usable rule, or a provider outage — LEAVES THE ROW PENDING and logs
 * exactly one line; the next pass retries it. A Degraded row keeps its
 * evidence stamp (candidate = "", extracted_at set); a Deferred row is
 * untouched until the provider heals. The pass runs ONE row at a time (the
 * 16/11 measured lesson the memory worker carries: the chat runner is a
 * single process, concurrency only queues behind itself into timeouts).
 */
class CorrectionWorker(
    private val store: CorrectionStore,
    private val extractor: CorrectionExtractor,
    private val params: CorrectionParams = CorrectionParams(),
    private val meter: Meter = Meter.Noop,
) {
    private val proposedTags = Tags.of("outcome" to "proposed")
    private val degradedTags = Tags.of("outcome" to "degraded")
    private val deferredTags = Tags.of("outcome" to "deferred")

    suspend fun processOnce(): CorrectionReport {
        val pending = store.pending(params.batch)
        val settled: List<Result<CorrectionOutcome>> =
            pending.traverseSettled(EXTRACTION_CONCURRENCY) { row -> Kap { processRow(row) } }.evalGraph()
        val report = settled.fold(CorrectionReport(pending.size, 0, 0, 0)) { acc, result ->
            acc.completed(result.getOrThrow())
        }
        record(report)
        return report
    }

    /** One cycle's facts as counters: only what a DB row or a log line never shows. */
    private fun record(report: CorrectionReport) {
        meter.increment("brain.corrections.cycles")
        repeat(report.proposed) { meter.increment("brain.corrections.rows", proposedTags) }
        repeat(report.degraded) { meter.increment("brain.corrections.rows", degradedTags) }
        repeat(report.deferred) { meter.increment("brain.corrections.rows", deferredTags) }
    }

    /**
     * One row, one settled outcome — the bins are the signature, and every
     * failure of the attempt is classified AT this edge, never thrown across
     * the cycle.
     */
    private suspend fun processRow(row: CorrectionRow): CorrectionOutcome {
        val settled = extractor.extract(row.text).attempt().evalGraph()
        return settled.fold(
            { error -> deferred(row, error) },
            { extracted ->
                extracted.fold(
                    { invalid -> degraded(row, invalid) },
                    { candidate ->
                        store.markExtracted(row.id, candidate, OffsetDateTime.now())
                        CorrectionOutcome.Proposed(row.id, candidate)
                    },
                )
            },
        )
    }

    /** The provider failed: the row is untouched (stays pending), one honest line. */
    private fun deferred(row: CorrectionRow, error: Throwable): CorrectionOutcome {
        // An open breaker is the provider's self-healing state (16/11 law):
        // it reads as any other outage here — the row stays pending for free.
        if (error is CircuitBreakerOpenException) {
            log.warn("correction extraction deferred for {} (breaker open, stays pending)", row.id)
        } else {
            log.warn(
                "correction extraction deferred for {} (stays pending): {}: {}",
                row.id, error::class.simpleName, error.message,
            )
        }
        return CorrectionOutcome.Deferred(row.id, "${error::class.simpleName}: ${error.message}")
    }

    /** The judge answered without a usable rule: the evidence stamp lands, the row stays pending. */
    private suspend fun degraded(row: CorrectionRow, invalid: ExtractionInvalid): CorrectionOutcome {
        store.markExtracted(row.id, "", OffsetDateTime.now())
        log.warn("correction extraction degraded for {} (stays pending): {}", row.id, invalid.reason)
        return CorrectionOutcome.Degraded(row.id, invalid.reason)
    }

    /**
     * The supervised worker: one cycle per cadence, and a [RestartPolicy] —
     * the MemoryWorker's own pattern — owns failures, so a crash anywhere in
     * the cycle restarts the loop instead of silently ending it.
     */
    fun process(interval: Duration, restart: RestartPolicy = defaultRestart(interval)): Process<CorrectionReport> {
        require(interval > Duration.ZERO) { "poll interval must be strictly positive, was $interval" }
        return Kap { processOnce() }
            .repeat(Schedule.spaced(interval))
            .process(
                name = PROCESS_NAME,
                restart = restart,
                heartbeatEvery = HEARTBEAT_EVERY,
                onRestart = { attempt, error, nextDelay ->
                    log.warn(
                        "correction worker failed (restart {} in {}): {}: {}",
                        attempt, nextDelay, error::class.simpleName, error.message,
                    )
                },
            )
    }

    /** Runs the process for the caller's lifetime (serve); cancellation stops it. */
    suspend fun run(interval: Duration) {
        coroutineScope { process(interval).startIn(this).await() }
    }

    companion object {
        const val PROCESS_NAME = "correction-worker"

        /** One row at a time: the chat runner is ONE process (the 16/11 measured lesson). */
        const val EXTRACTION_CONCURRENCY = 1

        /** The heartbeat answers "is the process there", never "did the cycle finish". */
        val HEARTBEAT_EVERY = 10.seconds

        /** Positive base (the cadence), exponential backoff, finite cap: the convergence law. */
        fun defaultRestart(interval: Duration): RestartPolicy =
            RestartPolicy.exponential(base = interval, max = RESTART_MAX_DELAY, maxRestarts = RESTART_MAX)

        val RESTART_MAX_DELAY = 60.seconds
        const val RESTART_MAX = 8
    }
}

/** The outcomes, counted: proposed rows alone; the degraded bins by name. */
private fun CorrectionReport.completed(outcome: CorrectionOutcome): CorrectionReport = when (outcome) {
    is CorrectionOutcome.Proposed -> copy(proposed = proposed + 1)
    is CorrectionOutcome.Degraded -> copy(degraded = degraded + 1)
    is CorrectionOutcome.Deferred -> copy(deferred = deferred + 1)
}
