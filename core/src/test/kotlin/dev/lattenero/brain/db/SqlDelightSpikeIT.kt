package dev.lattenero.brain.db

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.db.sq.BrainDb
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * P0 spike laws (docs/spike-sqldelight.md): the SQLDelight seam rides the
 * SAME Hikari pool as the raw paths (`pool.asJdbcDriver()`), its generated
 * statements execute against the Migrator-owned schema, and the embedding
 * travels as a TEXT literal through `::vector` — no PGvector JDBC type
 * registration anywhere on this path. Dimension 4: tiny vectors, no model.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqlDelightSpikeIT {
    private lateinit var db: Database
    private lateinit var brainDb: BrainDb

    @BeforeAll
    fun up() = runBlocking {
        TestEnv.needPostgres()
        db = Database(TestDatabase.config("sqldelight"))
        Migrator(db, 4).apply()
        brainDb = BrainDb(db.pool.asJdbcDriver())
    }

    @AfterAll
    fun down() {
        db.close()
    }

    @Test
    fun `text-vector bind inserts and returns id`() {
        val id = brainDb.memoriesQueries.insertMemory(
            sessionId = "s1",
            turnId = "t1",
            kind = "fact",
            content = "sql delight law",
            embedModel = "fake",
            embedding = "[1,2,3,4]",
            sourceEventId = "ev-1",
        ).executeAsOne()
        assertThat(id).isPositive()
    }

    @Test
    fun `transaction rolls back on failure and commits on success`() {
        val before = brainDb.memoriesQueries.countMemories().executeAsOne()
        assertThatThrownBy {
            brainDb.transaction {
                brainDb.memoriesQueries.insertMemory("s2", "t2", "fact", "rollback probe", "fake", "[4,3,2,1]", null)
                    .executeAsOne()
                error("rollback-probe")
            }
        }.hasMessageContaining("rollback-probe")
        assertThat(brainDb.memoriesQueries.countMemories().executeAsOne()).isEqualTo(before)

        brainDb.transaction {
            brainDb.memoriesQueries.insertMemory("s2", "t2", "fact", "committed probe", "fake", "[4,3,2,1]", null)
                .executeAsOne()
        }
        assertThat(brainDb.memoriesQueries.countMemories().executeAsOne()).isEqualTo(before + 1)
    }

    @Test
    fun `no aborted transaction leaks to the next pool borrower`() = runBlocking<Unit> {
        // M2 transaction law (ADR-007): a rolled-back generated transaction must
        // leave its pooled connection CLEAN — the next borrower (raw or generated)
        // must not inherit Postgres's "current transaction is aborted" state.
        assertThatThrownBy {
            brainDb.transaction {
                brainDb.memoriesQueries.insertMemory("s3", "t3", "fact", "abort probe", "fake", "[2,2,2,2]", null)
                    .executeAsOne()
                error("abort-probe")
            }
        }.hasMessageContaining("abort-probe")

        // A fresh borrower writes through the RAW path on the same pool.
        db.withConnection { conn ->
            conn.createStatement().use { st ->
                st.execute("INSERT INTO memories (kind, content, embed_model, embedding, salience) VALUES ('fact', 'post-rollback probe', 'fake', '[1,1,1,1]', 1.0)")
            }
        }
        // ...and the generated path still answers on that pool.
        assertThat(brainDb.memoriesQueries.countMemories().executeAsOne()).isPositive()
    }

    @Test
    fun `lateral temporal pair shape executes`() {
        // One transaction, two rows: now() is the transaction timestamp, so
        // created_at ties and the row constructor breaks the tie by id.
        val ids = mutableListOf<Long>()
        brainDb.transaction {
            for (i in 1..2) {
                ids += brainDb.memoriesQueries.insertMemory("tp", "tp-$i", "fact", "temporal $i", "fake", "[1,1,1,1]", null)
                    .executeAsOne()
            }
        }
        val row = brainDb.memoryEdgesQueries.temporalPair(30, 0.125, ids.max()).executeAsList()
            .first { it.cur_id == ids.max() }
        assertThat(row.prev_id).isEqualTo(ids.min())
    }

    @Test
    fun `eval run casts land a row`() {
        val rows = brainDb.evalRunsQueries.insertEvalRun(
            suite = "spike",
            model = "fake",
            provider = "fake",
            passed = 1,
            total = 1,
            costUsd = BigDecimal("0.000001"),
            detail = """{"ok":true}""",
            runId = UUID.randomUUID(),
            matrixId = null,
            kind = "recall",
            axis = "embed",
            gate = 0.9,
            ok = true,
            tokensPrompt = 10L,
            tokensCompletion = 5L,
            costKnown = true,
            p50Ms = null,
            p95Ms = null,
            durationMs = null,
            startedAt = OffsetDateTime.now(),
        ).value
        assertThat(rows).isEqualTo(1)
    }

    @Test
    fun `quarantine buckets ride split_part and case`() = runBlocking<Unit> {
        db.withConnection { conn ->
            conn.createStatement().use { st ->
                st.execute("INSERT INTO memory_quarantine (turn_id, reason, raw) VALUES ('tq', 'gave up: attempt 5', 'raw')")
            }
        }
        val buckets = brainDb.memoriesQueries.quarantineBucketed().executeAsList()
        assertThat(buckets).anySatisfy { bucket ->
            assertThat(bucket.bucket).isEqualTo("gave-up")
            assertThat(bucket.n).isPositive()
        }
    }

    @Test
    fun `array binds carry typed long arrays`() {
        // Two known rows: capture their ids from the RETURNING insert.
        val first = brainDb.memoriesQueries.insertMemory("arr", "arr-1", "fact", "array one", "fake", "[1,2,3,4]", null)
            .executeAsOne()
        val second = brainDb.memoriesQueries.insertMemory("arr", "arr-2", "fact", "array two", "fake", "[4,3,2,1]", null)
            .executeAsOne()
        val ids = arrayOf(first, second)

        val viaUnnest = brainDb.memoryEdgesQueries.hitsByIds(ids).executeAsList()
        val viaAny = brainDb.memoryEdgesQueries.memoriesAny(ids).executeAsList()
        assertThat(viaUnnest).containsExactlyInAnyOrder(first, second)
        assertThat(viaAny).containsExactlyInAnyOrder(first, second)
    }

    @Test
    fun `withDriver rides the same typed facade`() = runBlocking<Unit> {
        val n = db.withDriver { it.memoriesQueries.countMemories().executeAsOne() }
        assertThat(n).isPositive()
    }
}
