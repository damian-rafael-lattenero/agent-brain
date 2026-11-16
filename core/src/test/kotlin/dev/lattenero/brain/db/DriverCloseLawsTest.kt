package dev.lattenero.brain.db

import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The ownership law (ADR-007): the Hikari pool is the ONLY connection owner.
 * The SQLDelight driver is a pass-through borrower — the pool must stay
 * functional while only the driver is closed, and Database.close() must end
 * every path (raw and generated alike).
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DriverCloseLawsTest {
    private lateinit var db: Database

    @BeforeAll
    fun up() = runBlocking<Unit> {
        TestEnv.needPostgres()
        db = Database(TestDatabase.config("driver_close"))
        Migrator(db, 4).apply()
    }

    @AfterAll
    fun down() {
        db.close()
    }

    @Test
    fun `database close ends the generated path too`() = runBlocking<Unit> {
        // Healthy before close.
        val before = db.withDriver { it.memoriesQueries.countMemories().executeAsOne() }
        check(before >= 0)

        db.close()

        // The pool is gone: both the raw and the generated path must refuse.
        // The generated path surfaces the closed pool as SQLException from
        // HikariDataSource.getConnection (the driver is a borrower); the raw
        // path throws the same through withConnection.
        assertThatThrownBy {
            runBlocking { db.withDriver { it.memoriesQueries.countMemories().executeAsOne() } }
        }.isInstanceOf(java.sql.SQLException::class.java)
        assertThatThrownBy {
            runBlocking { db.ping() }
        }.isInstanceOf(java.sql.SQLException::class.java)
    }
}
