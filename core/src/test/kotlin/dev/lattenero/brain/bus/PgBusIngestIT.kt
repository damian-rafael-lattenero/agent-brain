package dev.lattenero.brain.bus

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
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PgBusIngestIT {
    private lateinit var config: Config
    private lateinit var db: Database

    @BeforeAll
    fun up() {
        TestEnv.needPostgres()
        config = TestDatabase.config("bus")
        db = Database(config)
        runBlocking { Migrator(db, config.embedDim).apply() }
    }

    @AfterAll
    fun down() {
        if (::db.isInitialized) db.close()
    }

    private fun fixtureLines(): List<String> =
        javaClass.classLoader.getResourceAsStream("bus/sample-bot-publisher.jsonl")!!.bufferedReader().readLines().filter { it.isNotBlank() }

    private fun fixtureEvents(): List<BusEvent> =
        fixtureLines().map { (BusEvents.parse(it) as BusEvents.Parsed.Ok).event }

    @Test
    fun `a batch is inserted once and a repeat of it is counted as duplicates`() = runBlocking<Unit> {
        val ingest = PgBusIngest(db)
        val events = fixtureEvents().map { it.copy(id = "batch-" + it.id) }
        ingest.handle(events)
        assertThat(ingest.stats.inserted.get()).isEqualTo(events.size.toLong())
        ingest.handle(events)
        assertThat(ingest.stats.seen.get()).isEqualTo(events.size * 2L)
        assertThat(ingest.stats.inserted.get()).isEqualTo(events.size.toLong())
        assertThat(ingest.stats.duplicates.get()).isEqualTo(events.size.toLong())
        assertThat(ingest.stats.lastKind).isEqualTo("turn.failed")
    }

    @Test
    fun `payloads land as jsonb and timestamps as timestamptz`() = runBlocking<Unit> {
        val ingest = PgBusIngest(db)
        val started = fixtureEvents().first { it.kind == "turn.started" }.copy(id = "jsonb-probe")
        ingest.handle(listOf(started))
        val (title, prompt, year) = db.withConnection { conn ->
            conn.prepareStatement("SELECT payload->>'title', payload->>'prompt', extract(year from ts)::int FROM bus_events WHERE id = ?").use { ps ->
                ps.setString(1, "jsonb-probe")
                ps.executeQuery().use { rs -> rs.next(); Triple(rs.getString(1), rs.getString(2), rs.getInt(3)) }
            }
        }
        assertThat(title).isEqualTo("CV")
        assertThat(prompt).contains("ñandú")
        assertThat(year).isEqualTo(2026)
    }

    @Test
    fun `golden - replaying the whole bus twice leaves the database identical`() = runBlocking<Unit> {
        val own = TestDatabase.config("replay")
        Database(own).use { odb ->
            Migrator(odb, own.embedDim).apply()
            val busFile: Path = own.busFile
            Files.createDirectories(busFile.parent)
            // The real fixture, plus one duplicate id and one malformed line in the middle.
            val lines = fixtureLines()
            val withNoise = lines.take(3) + listOf(lines[1], "{this is not json") + lines.drop(3)
            Files.writeString(busFile, withNoise.joinToString("\n") + "\n")

            val first = BusPipeline(own, odb)
            val handedFirst = first.drain()
            val snapshotFirst = snapshotOf(odb)
            val statusFirst = first.status()
            assertThat(handedFirst).isEqualTo(7)
            assertThat(statusFirst.inserted).isEqualTo(6)
            assertThat(statusFirst.duplicates).isEqualTo(1)
            assertThat(statusFirst.malformed).isEqualTo(1)
            assertThat(statusFirst.dbEvents).isEqualTo(6)
            assertThat(statusFirst.lagBytes).isZero

            // Forget the offset (as a lost state dir would) and replay from byte 0.
            Files.deleteIfExists(own.stateDir.resolve("bus-offset.json"))
            val second = BusPipeline(own, odb)
            assertThat(second.tailer.offset).isZero
            val handedSecond = second.drain()
            val statusSecond = second.status()
            assertThat(handedSecond).isEqualTo(7)
            assertThat(statusSecond.inserted).isZero
            assertThat(statusSecond.duplicates).isEqualTo(7)
            assertThat(statusSecond.dbEvents).isEqualTo(6)
            assertThat(snapshotOf(odb)).isEqualTo(snapshotFirst)
        }
    }

    private fun snapshotOf(database: Database): Pair<List<String>, String> = runBlocking {
        database.withConnection { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT id, ts, kind, sid, turn, source, payload::text FROM bus_events ORDER BY id").use { rs ->
                    val ids = ArrayList<String>()
                    val digest = MessageDigest.getInstance("SHA-256")
                    while (rs.next()) {
                        ids += rs.getString(1)
                        for (i in 1..7) digest.update((rs.getString(i) ?: "").toByteArray())
                    }
                    ids to digest.digest().joinToString("") { "%02x".format(it) }
                }
            }
        }
    }
}
