package dev.lattenero.brain.db

import com.pgvector.PGvector
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.lattenero.brain.config.Config
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.util.concurrent.Executors

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

    suspend fun <T> withConnection(block: (Connection) -> T): T = withContext(Loom.dispatcher) {
        dataSource.connection.use { conn ->
            // Client-side type registration, no round trip: safe before 0001.
            PGvector.addVectorType(conn)
            block(conn)
        }
    }

    suspend fun ping(): Boolean = withConnection { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT 1").use { rs -> rs.next() && rs.getInt(1) == 1 }
        }
    }

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
