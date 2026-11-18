package dev.lattenero.brain.db

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.pgvector.PGvector
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.lattenero.brain.config.Config
import dev.lattenero.brain.db.sq.BrainDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.util.concurrent.Executors
import javax.sql.DataSource

/**
 * JDBC is blocking; running it on virtual threads makes the blocking cheap
 * (the carrier is released on socket I/O, JDK 21+; pinning on monitors is
 * gone since JDK 24). One dispatcher for the whole process.
 */
object Loom {
    val dispatcher: CoroutineDispatcher = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
}

/** One pool per process, built from the injected [Config]. */
class Database(config: Config) : AutoCloseable {
    private val dataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.pgUrl
            username = config.pgUser
            password = config.pgPassword
            poolName = "agent-brain"
            minimumIdle = POOL_MIN
            maximumPoolSize = POOL_MAX
            connectionTimeout = CONNECT_TIMEOUT_MS
            // The pool must come up even when Postgres is down: readiness reports it.
            initializationFailTimeout = -1
        },
    )

    // The block is suspend so callers can use kap operators (inTransaction) inside.
    suspend fun <T> withConnection(block: suspend (Connection) -> T): T = withContext(Loom.dispatcher) {
        dataSource.connection.use { conn ->
            // Client-side type registration, no round trip: safe before 0001.
            PGvector.addVectorType(conn)
            block(conn)
        }
    }

    /**
     * The generated SQLDelight path: blocking JDBC like [withConnection], on the
     * same Loom dispatcher, with NO connection checkout of its own — each
     * generated statement borrows a pooled connection through the driver. The
     * pool is the ONLY owner of connections here (ADR-007).
     */
    suspend fun <T> withDriver(block: suspend (BrainDb) -> T): T = withContext(Loom.dispatcher) {
        block(brainDb)
    }

    /**
     * The pool itself, exposed for the SQLDelight seam (docs/spike-sqldelight.md):
     * `pool.asJdbcDriver()` makes the generated queries ride the SAME Hikari
     * connections as the raw paths — one pool, one database object.
     */
    val pool: DataSource get() = dataSource

    /**
     * The typed facade over the SAME pool. The driver is a pass-through
     * borrower: it never owns or closes connections (the pool does, in
     * [close]) — pinned by DriverCloseLawsTest.
     */
    private val brainDb: BrainDb by lazy { BrainDb(dataSource.asJdbcDriver()) }

    /** Readiness: one typed round trip (audit wave 4 — the query lives in Health.sq). */
    suspend fun ping(): Boolean = withDriver { brainDb -> brainDb.healthQueries.ping().executeAsOne() == 1 }

    /**
     * pgjdbc keeps a per-connection type cache and PGvector registers as a
     * binary type, which looks the OID up eagerly: a connection that
     * registered `vector` before migration 0001 created the extension has
     * cached "unknown type" and can never write a vector. After a migration
     * runs, every pooled connection is retired so the next one starts clean.
     */
    fun evictConnections() {
        dataSource.hikariPoolMXBean?.softEvictConnections()
    }

    override fun close() = dataSource.close()

    companion object {
        const val POOL_MIN = 1
        const val POOL_MAX = 8
        const val CONNECT_TIMEOUT_MS = 5_000L
    }
}
