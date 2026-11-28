package dev.lattenero.brain.memory

import dev.lattenero.brain.db.Database
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

/**
 * WHERE a correction stands in its short life: born [PENDING] at the bus
 * ingest, [PROPOSED] once the extractor landed a non-blank rule sentence,
 * then [ACCEPTED] or [REJECTED] by the owner — and closed forever (the
 * decide guard makes a decided row final). The wire string rides [wire]; the
 * database stores the wire.
 */
enum class CorrectionStatus(val wire: String) {
    PENDING("pending"),
    PROPOSED("proposed"),
    ACCEPTED("accepted"),
    REJECTED("rejected");

    companion object {
        private val byWire: Map<String, CorrectionStatus> = entries.associateBy(CorrectionStatus::wire)

        /** Total on the stored wires; the four above are the only writers. */
        fun parse(wire: String): CorrectionStatus? = byWire[wire]
    }
}

/** One stored correction, newest-first in the CLI's review face. */
@Serializable
data class CorrectionRow(
    val id: String,
    val sid: String,
    val turn: String,
    val kind: String,
    val text: String,
    val candidate: String?,
    val status: String,
    val createdAt: String,
    val extractedAt: String?,
    val decidedAt: String?,
)

/**
 * The corrections table's typed face (plan U8): ingest-or-ignore by bus
 * event id, the extraction stamp with its guarded status flip, the owner's
 * guarded decision, and the read surfaces the CLI reviews with. Every write
 * is idempotent — a replayed bus event or a repeated decide leaves the table
 * identical.
 */
class CorrectionStore(private val db: Database) {

    /** Insert-or-ignore; returns the rows landed (0 for a replayed event id). */
    // The parameters mirror the corrections table's columns one to one — that
    // is the whole point of an insert face, so the shape stays.
    @Suppress("LongParameterList")
    suspend fun insert(
        id: String,
        sid: String,
        turn: String,
        kind: String,
        text: String,
        createdAt: OffsetDateTime,
    ): Int = db.withDriver { brainDb ->
        brainDb.correctionsQueries.insertCorrection(id, sid, turn, kind, text, createdAt).value.toInt()
    }

    /** The worker's queue: pending rows with a blank candidate, oldest first. */
    suspend fun pending(limit: Int): List<CorrectionRow> = db.withDriver { brainDb ->
        brainDb.correctionsQueries.pendingForExtraction(limit.toLong()).executeAsList().map(::row)
    }

    /**
     * The extraction stamp: candidate + extracted_at always, and the status
     * flips pending -> proposed ONLY when [candidate] is non-blank (blank
     * stays pending, so the row is retried). True when the flip happened.
     */
    suspend fun markExtracted(id: String, candidate: String, at: OffsetDateTime): Boolean {
        val trimmed = candidate.trim()
        val updated = db.withDriver { brainDb ->
            brainDb.correctionsQueries.markExtracted(candidate = trimmed, extractedAt = at, id = id).value
        }
        return updated > 0 && trimmed.isNotEmpty()
    }

    /**
     * The owner's decision: accepted | rejected only (any other status
     * updates zero rows — the query's own guard), only from pending |
     * proposed. True when the row flipped; false is the honest "cannot
     * decide this id" (unknown id, already decided, or invalid status).
     */
    suspend fun decide(id: String, status: CorrectionStatus, at: OffsetDateTime): Boolean =
        db.withDriver { brainDb ->
            brainDb.correctionsQueries.decide(status = status.wire, decidedAt = at, id = id).value > 0
        }

    suspend fun listRecent(limit: Int): List<CorrectionRow> = db.withDriver { brainDb ->
        brainDb.correctionsQueries.listRecent(limit.toLong()).executeAsList().map(::row)
    }

    suspend fun countByStatus(): Map<String, Long> = db.withDriver { brainDb ->
        brainDb.correctionsQueries.countByStatus().executeAsList()
            .associate { it.status to it.n }
    }

    suspend fun byId(id: String): CorrectionRow? = db.withDriver { brainDb ->
        brainDb.correctionsQueries.byId(id).executeAsOneOrNull()?.let(::row)
    }

    private fun row(it: dev.lattenero.brain.db.sq.Corrections): CorrectionRow = CorrectionRow(
        id = it.id,
        sid = it.sid,
        turn = it.turn,
        kind = it.kind,
        text = it.text,
        candidate = it.candidate,
        status = it.status,
        createdAt = it.created_at.toString(),
        extractedAt = it.extracted_at?.toString(),
        decidedAt = it.decided_at?.toString(),
    )
}
