package dev.lattenero.brain.db

import dev.lattenero.brain.config.Config
import java.sql.Connection
import java.sql.SQLException

class MigrationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class Migration(val name: String, val sql: String)

data class MigrationStatus(val applied: List<String>, val pending: List<String>, val embedDim: Int?)

interface MigrationSource {
    fun list(): List<Migration>
}

/**
 * Migrations ship inside the jar under `migrations/` and are enumerated by
 * `migrations/index.txt` (a jar cannot list a directory reliably). A unit
 * test keeps the index and the directory in sync, so forgetting to register
 * a file is a red build, not a missing table in production.
 */
object ClasspathMigrations : MigrationSource {
    const val DIR = "migrations"
    const val INDEX = "$DIR/index.txt"

    override fun list(): List<Migration> {
        val loader = ClasspathMigrations::class.java.classLoader
        val index = loader.getResourceAsStream(INDEX)?.bufferedReader()?.use { it.readLines() }
            ?: throw MigrationException("missing $INDEX on the classpath")
        val migrations = index.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { name ->
            val sql = loader.getResourceAsStream("$DIR/$name")?.bufferedReader()?.use { it.readText() }
                ?: throw MigrationException("migration listed in index.txt but missing on the classpath: $name")
            Migration(name, sql)
        }
        Migrations.validate(migrations)
        return migrations
    }
}

object Migrations {
    val NAME_RE = Regex("^(\\d{4})_[a-z0-9_]+\\.sql$")
    val PLACEHOLDER_RE = Regex("__([A-Z_]+)__")
    const val EMBED_DIM_PLACEHOLDER = "__EMBED_DIM__"

    /** Names conform, numbers strictly increase, nothing is duplicated. */
    fun validate(migrations: List<Migration>) {
        var last = 0
        for (migration in migrations) {
            val match = NAME_RE.matchEntire(migration.name)
                ?: throw MigrationException("migration file name must be NNNN_snake_case.sql: ${migration.name}")
            val number = match.groupValues[1].toInt()
            if (number <= last) {
                throw MigrationException("migration numbers must strictly increase: ${migration.name} after $last")
            }
            last = number
        }
    }

    /**
     * The ONLY templating: pgvector columns need a fixed dimension and the
     * dimension is a property of the embedding model, not of the schema.
     */
    fun render(sql: String, embedDim: Int): String {
        if (embedDim <= 0) throw MigrationException("embed_dim must be positive, got $embedDim")
        val rendered = sql.replace(EMBED_DIM_PLACEHOLDER, embedDim.toString())
        val leftovers = PLACEHOLDER_RE.findAll(rendered).map { it.groupValues[1] }.toSortedSet()
        if (leftovers.isNotEmpty()) {
            throw MigrationException("unknown placeholders in migration: ${leftovers.joinToString()}")
        }
        return rendered
    }
}

/**
 * Plain SQL files applied in order, once each, recorded in schema_migrations.
 * brain_meta remembers the embedding dimension the database was created
 * with so a provider that disagrees is refused: a silent mismatch would be
 * a corrupt index, not an error. Changing the embedding model means a fresh
 * database (make nuke && make up), by design (ADR-004).
 */
class Migrator(
    private val db: Database,
    private val embedDim: Int,
    private val source: MigrationSource = ClasspathMigrations,
) {
    suspend fun status(): MigrationStatus = db.withConnection { conn ->
        ensureLedger(conn)
        val applied = appliedNames(conn)
        val pending = source.list().map { it.name }.filter { it !in applied }
        MigrationStatus(applied, pending, storedEmbedDim(conn))
    }

    /** Returns the names applied in this run; serialized across processes by an advisory lock. */
    suspend fun apply(): List<String> {
        val done = applyLocked()
        if (done.isNotEmpty()) db.evictConnections()
        return done
    }

    private suspend fun applyLocked(): List<String> = db.withConnection { conn ->
        val migrations = source.list()
        conn.createStatement().use { it.execute("SELECT pg_advisory_lock(${Config.MIGRATION_LOCK_ID})") }
        try {
            ensureLedger(conn)
            val applied = appliedNames(conn).toSet()
            val done = mutableListOf<String>()
            conn.autoCommit = false
            try {
                for (migration in migrations) {
                    if (migration.name in applied) continue
                    conn.createStatement().use { it.execute(Migrations.render(migration.sql, embedDim)) }
                    conn.prepareStatement("INSERT INTO schema_migrations (name) VALUES (?)").use {
                        it.setString(1, migration.name)
                        it.executeUpdate()
                    }
                    conn.commit()
                    done += migration.name
                }
            } catch (e: SQLException) {
                conn.rollback()
                throw MigrationException("migration failed: ${e.message}", e)
            } finally {
                conn.autoCommit = true
            }
            val stored = storedEmbedDim(conn)
            if (stored != null && stored != embedDim) {
                throw MigrationException(
                    "database was created with embed_dim=$stored but the configured provider needs $embedDim; " +
                        "a different embedding model needs a fresh database (make nuke && make up)",
                )
            }
            done
        } finally {
            conn.createStatement().use { it.execute("SELECT pg_advisory_unlock(${Config.MIGRATION_LOCK_ID})") }
        }
    }

    private fun ensureLedger(conn: Connection) {
        conn.createStatement().use { it.execute(LEDGER_DDL) }
    }

    private fun appliedNames(conn: Connection): List<String> = conn.createStatement().use { st ->
        st.executeQuery("SELECT name FROM schema_migrations ORDER BY name").use { rs ->
            generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
        }
    }

    private fun storedEmbedDim(conn: Connection): Int? = conn.createStatement().use { st ->
        val exists = st.executeQuery("SELECT to_regclass('public.brain_meta') IS NOT NULL").use { rs ->
            rs.next() && rs.getBoolean(1)
        }
        if (!exists) return@use null
        st.executeQuery("SELECT value FROM brain_meta WHERE key = 'embed_dim'").use { rs ->
            if (rs.next()) rs.getString(1).toInt() else null
        }
    }

    companion object {
        const val LEDGER_DDL = """
            CREATE TABLE IF NOT EXISTS schema_migrations (
              name       text PRIMARY KEY,
              applied_at timestamptz NOT NULL DEFAULT now()
            )
        """
    }
}
