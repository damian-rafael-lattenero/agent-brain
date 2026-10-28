package dev.lattenero.brain.db

import com.pgvector.PGvector
import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** Every test owns its database state: no ordering, no shared leftovers. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigratorPgTest {
    private lateinit var db: Database

    @BeforeAll
    fun up() {
        TestEnv.needPostgres()
        db = Database(TestDatabase.config("migrator"))
        runBlocking { Migrator(db, 768).apply() }
    }

    @AfterAll
    fun down() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun `a fresh database reports everything pending and no dimension`() = runBlocking<Unit> {
        Database(TestDatabase.config("fresh")).use { fresh ->
            val status = Migrator(fresh, 768).status()
            assertThat(status.applied).isEmpty()
            assertThat(status.pending).contains("0001_init.sql")
            assertThat(status.embedDim).isNull()
        }
    }

    @Test
    fun `apply is idempotent and records the dimension`() = runBlocking<Unit> {
        val migrator = Migrator(db, 768)
        assertThat(migrator.apply()).isEmpty()
        val status = migrator.status()
        assertThat(status.pending).isEmpty()
        assertThat(status.applied).isEqualTo(ClasspathMigrations.list().map { it.name })
        assertThat(status.embedDim).isEqualTo(768)
    }

    @Test
    fun `the schema is complete with the vector extension`() = runBlocking<Unit> {
        val tables = db.withConnection { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'").use { rs ->
                    generateSequence { if (rs.next()) rs.getString(1) else null }.toSet()
                }
            }
        }
        assertThat(tables).contains(
            "schema_migrations", "brain_meta", "bus_events", "documents", "chunks",
            "memories", "memory_quarantine", "eval_runs",
        )
        val extension = db.withConnection { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT extversion FROM pg_extension WHERE extname = 'vector'").use { rs -> rs.next() }
            }
        }
        assertThat(extension).isTrue
    }

    @Test
    fun `a provider with another dimension is refused, never silently indexed`() = runBlocking<Unit> {
        Database(TestDatabase.config("dim")).use { own ->
            assertThat(Migrator(own, 768).apply()).contains("0001_init.sql")
            assertThatThrownBy { runBlocking { Migrator(own, 1024).apply() } }
                .isInstanceOf(MigrationException::class.java)
                .hasMessageContaining("embed_dim=768")
                .hasMessageContaining("needs 1024")
        }
    }

    @Test
    fun `two runners racing on a fresh database apply exactly once`() = runBlocking<Unit> {
        Database(TestDatabase.config("race")).use { own ->
            val results = coroutineScope {
                (1..4).map { async { Migrator(own, 768).apply() } }.map { it.await() }
            }
            assertThat(results.flatten()).containsExactly("0001_init.sql")
            assertThat(Migrator(own, 768).status().pending).isEmpty()
        }
    }

    @Test
    fun `a connection that saw the database before the extension can still write vectors after migrating`() = runBlocking<Unit> {
        Database(TestDatabase.config("typecache")).use { own ->
            // First use registers the vector type on a connection where the extension does not exist yet.
            assertThat(own.ping()).isTrue
            assertThat(Migrator(own, 768).apply()).contains("0001_init.sql")
            val written = own.withConnection { conn ->
                conn.createStatement().use { it.execute("CREATE TEMP TABLE after_migration (v vector(3))") }
                conn.prepareStatement("INSERT INTO after_migration VALUES (?)").use { ps ->
                    ps.setObject(1, PGvector(floatArrayOf(1f, 2f, 3f)))
                    ps.executeUpdate()
                }
            }
            assertThat(written).isEqualTo(1)
        }
    }

    @Test
    fun `vectors round-trip and cosine distance orders as expected`() = runBlocking<Unit> {
        val nearest = db.withConnection { conn ->
            conn.createStatement().use { it.execute("CREATE TEMP TABLE probe (label text, v vector(3))") }
            conn.prepareStatement("INSERT INTO probe VALUES (?, ?)").use { ps ->
                listOf("x" to floatArrayOf(1f, 0f, 0f), "y" to floatArrayOf(0f, 1f, 0f), "xy" to floatArrayOf(1f, 1f, 0f))
                    .forEach { (label, vector) ->
                        ps.setString(1, label)
                        ps.setObject(2, PGvector(vector))
                        ps.executeUpdate()
                    }
            }
            conn.prepareStatement("SELECT label, v FROM probe ORDER BY v <=> ? LIMIT 2").use { ps ->
                ps.setObject(1, PGvector(floatArrayOf(1f, 0.1f, 0f)))
                ps.executeQuery().use { rs ->
                    generateSequence { if (rs.next()) rs.getString(1) to (rs.getObject(2) as PGvector).toArray().size else null }.toList()
                }
            }
        }
        assertThat(nearest.map { it.first }).containsExactly("x", "xy")
        assertThat(nearest.map { it.second }).containsOnly(3)
    }
}
