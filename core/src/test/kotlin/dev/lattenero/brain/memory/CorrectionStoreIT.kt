package dev.lattenero.brain.memory

import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.config.Config
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.Migrator
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.OffsetDateTime

/**
 * The corrections store's laws over a real database (plan U8): the ingest
 * dedupes by id, the extraction stamp flips pending -> proposed ONLY on a
 * non-blank candidate, the decide guard accepts accepted | rejected only and
 * from pending | proposed only, and the read surfaces return the rows they
 * stored. Skips honestly when Postgres is unreachable (TestEnv).
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CorrectionStoreIT {

    private lateinit var config: Config

    private lateinit var db: Database

    @BeforeAll
    fun up() {
        TestEnv.needPostgres()
        config = TestDatabase.config("corrections")
        db = Database(config)
        runBlocking { Migrator(db, config.embedDim).apply() }
    }

    @AfterAll
    fun down() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun `insert dedupes by id - a replayed bus event lands once`() = runBlocking<Unit> {
        val store = CorrectionStore(db)
        val at = OffsetDateTime.parse("2026-11-27T12:00:00Z")
        assertThat(store.insert("dup-1", "s", "1", "steer", "always commit first", at)).isEqualTo(1)
        assertThat(store.insert("dup-1", "s", "1", "steer", "always commit first", at)).isZero

        val stored = store.byId("dup-1")!!
        assertThat(stored.status).isEqualTo("pending")
        assertThat(stored.kind).isEqualTo("steer")
        assertThat(stored.text).isEqualTo("always commit first")
        assertThat(stored.candidate).isNull()
    }

    @Test
    fun `markExtracted flips pending to proposed only with a non-blank candidate`() = runBlocking<Unit> {
        val store = CorrectionStore(db)
        val at = OffsetDateTime.parse("2026-11-27T12:01:00Z")
        store.insert("ex-1", "s", "1", "explicit", "write in English", at)

        // A blank candidate records the attempt but the row STAYS pending.
        assertThat(store.markExtracted("ex-1", "   ", at)).isFalse
        val stillPending = store.byId("ex-1")!!
        assertThat(stillPending.status).isEqualTo("pending")
        assertThat(stillPending.extractedAt).isNotNull

        // A real candidate flips the status and stamps the extraction time.
        assertThat(store.markExtracted("ex-1", "Always write in English.", at)).isTrue
        val proposed = store.byId("ex-1")!!
        assertThat(proposed.status).isEqualTo("proposed")
        assertThat(proposed.candidate).isEqualTo("Always write in English.")
        assertThat(proposed.extractedAt).isNotNull
    }

    @Test
    fun `a proposed row cannot be stamped again - the guard holds decided rows closed`() = runBlocking<Unit> {
        val store = CorrectionStore(db)
        val at = OffsetDateTime.parse("2026-11-27T12:02:00Z")
        store.insert("ex-2", "s", "1", "steer", "never push without my ok", at)
        store.markExtracted("ex-2", "Never push without the owner's explicit ok.", at)

        // The stamp only touches pending rows: a proposed row is closed to it.
        assertThat(store.markExtracted("ex-2", "a different sentence", at)).isFalse
        val after = store.byId("ex-2")!!
        assertThat(after.candidate).isEqualTo("Never push without the owner's explicit ok.")
    }

    @Test
    fun `decide flips proposed to accepted and to rejected`() = runBlocking<Unit> {
        val store = CorrectionStore(db)
        val at = OffsetDateTime.parse("2026-11-27T12:03:00Z")
        store.insert("dec-1", "s", "1", "steer", "no Spanish in code", at)
        store.insert("dec-2", "s", "1", "stop", "stop rewriting the bot", at)
        store.markExtracted("dec-1", "Write every comment in English.", at)

        assertThat(store.decide("dec-1", CorrectionStatus.ACCEPTED, at)).isTrue
        assertThat(store.decide("dec-2", CorrectionStatus.REJECTED, at)).isTrue

        assertThat(store.byId("dec-1")!!.status).isEqualTo("accepted")
        assertThat(store.byId("dec-1")!!.decidedAt).isNotNull
        assertThat(store.byId("dec-2")!!.status).isEqualTo("rejected")
        assertThat(store.byId("dec-2")!!.decidedAt).isNotNull
    }

    @Test
    fun `decide works from pending too and refuses invalid targets`() = runBlocking<Unit> {
        val store = CorrectionStore(db)
        val at = OffsetDateTime.parse("2026-11-27T12:04:00Z")
        store.insert("dec-3", "s", "1", "explicit", "use kap everywhere", at)

        // Accept straight from pending is legal.
        assertThat(store.decide("dec-3", CorrectionStatus.ACCEPTED, at)).isTrue

        // decided -> decided is refused; invalid statuses update zero rows.
        assertThat(store.decide("dec-3", CorrectionStatus.REJECTED, at)).isFalse
        assertThat(store.decide("dec-3", CorrectionStatus.PENDING, at)).isFalse
        assertThat(store.decide("dec-3", CorrectionStatus.PROPOSED, at)).isFalse

        // Unknown ids answer false, never an exception.
        assertThat(store.decide("missing-id", CorrectionStatus.ACCEPTED, at)).isFalse
    }

    @Test
    fun `the read surfaces return what was stored`() = runBlocking<Unit> {
        val store = CorrectionStore(db)
        val older = OffsetDateTime.parse("2026-11-27T12:05:00Z")
        val middle = OffsetDateTime.parse("2026-11-27T12:06:00Z")
        val newest = OffsetDateTime.parse("2026-11-27T12:08:00Z")
        store.insert("read-1", "s1", "7", "steer", "first", older)
        store.insert("read-2", "s2", "8", "stop", "second", newest)
        store.insert("read-3", "s3", "9", "explicit", "third", middle)
        store.markExtracted("read-3", "Use kap everywhere.", middle)
        store.decide("read-3", CorrectionStatus.ACCEPTED, middle)

        val recent = store.listRecent(10)
        assertThat(recent.map { it.id }).contains("read-1", "read-2")
        assertThat(recent.first().id).isEqualTo("read-2")

        val pending = store.pending(10)
        assertThat(pending.map { it.id }).contains("read-1", "read-2")
        assertThat(pending.map { it.id }).doesNotContain("read-3")

        assertThat(store.countByStatus()["pending"]).isGreaterThanOrEqualTo(2)
        assertThat(store.countByStatus()["accepted"]).isGreaterThanOrEqualTo(1)

        assertThat(store.byId("missing-id")).isNull()
    }
}
