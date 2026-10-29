package dev.lattenero.brain.db

import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The transaction-bracket law (plan B1): commit on success, rollback on
 * failure AND on cancellation, autocommit restored in every case — the next
 * pool borrower never inherits a half-open transaction. Each test asserts
 * against the count it observed before it, so the law holds in any method
 * order the class is run in.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InTransactionTest {
    private lateinit var db: Database

    @BeforeAll
    fun up() {
        TestEnv.needPostgres()
        db = Database(TestDatabase.config("txlaw"))
        runBlocking {
            db.withConnection { conn ->
                conn.createStatement().use { it.execute("CREATE TABLE tx_law (id int PRIMARY KEY)") }
            }
        }
    }

    @AfterAll
    fun down() {
        if (::db.isInitialized) db.close()
    }

    private suspend fun count(): Long = db.withConnection { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM tx_law").use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    @Test
    fun `commits on success`() = runBlocking<Unit> {
        val before = count()
        db.inTransaction { conn ->
            conn.createStatement().use { it.execute("INSERT INTO tx_law VALUES (1)") }
        }
        assertThat(count()).isEqualTo(before + 1)
    }

    @Test
    fun `rolls back on a failing statement`() = runBlocking<Unit> {
        val before = count()
        val thrown = runCatching {
            db.inTransaction { conn ->
                conn.createStatement().use { it.execute("INSERT INTO tx_law VALUES (2)") }
                // The injected failing statement: the same primary key, inside the same transaction.
                conn.createStatement().use { it.execute("INSERT INTO tx_law VALUES (2)") }
            }
        }
        assertThat(thrown.isFailure).isTrue()
        assertThat(count()).isEqualTo(before)
    }

    @Test
    fun `rolls back and restores autocommit on cancellation`() = runBlocking<Unit> {
        val before = count()
        val (thrown, autocommitAfter) = db.withConnection { conn ->
            val outcome = runCatching {
                conn.inTransaction {
                    it.createStatement().use { st -> st.execute("INSERT INTO tx_law VALUES (3)") }
                    throw CancellationException("cancelled mid-transaction")
                }
            }
            outcome.isFailure to conn.autoCommit
        }
        assertThat(thrown).isTrue()
        assertThat(autocommitAfter).isTrue()
        assertThat(count()).isEqualTo(before)
    }

    @Test
    fun `autocommit is restored on success and failure alike`() = runBlocking<Unit> {
        val (afterSuccess, afterFailure) = db.withConnection { conn ->
            conn.inTransaction { it.createStatement().use { st -> st.execute("INSERT INTO tx_law VALUES (4)") } }
            val ok = conn.autoCommit
            runCatching { conn.inTransaction { error("boom") } }
            ok to conn.autoCommit
        }
        assertThat(afterSuccess).isTrue()
        assertThat(afterFailure).isTrue()
    }
}
