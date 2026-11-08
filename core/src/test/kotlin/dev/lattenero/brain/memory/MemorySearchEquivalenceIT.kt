package dev.lattenero.brain.memory

import com.pgvector.PGvector
import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.Migrator
import dev.lattenero.brain.embeddings.FakeProvider
import dev.lattenero.brain.rag.Rrf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/**
 * SQL-vs-pure equivalence on fixtures (plan E3, with plan E2's fusion): the
 * `score` inside `MemoryStore.SEARCH` must equal pure `Rrf.score(ranks)` and
 * the `weight` must equal pure `salience * Salience.decay(age, halfLife)`
 * on real Postgres, and the returned order must be pure [RankKey] order —
 * relevance dominant, reinforcement as tie-break. The test recomputes the two
 * legs (the shared inputs, exactly the SQL's candidate queries), takes
 * salience and created_at from the rows, and pins the whole algebra — fusion,
 * reinforcement weight, half-life decay and the lexicographic ordering —
 * against the pure cores. BOW fixtures with distinct ages and saliences;
 * generated queries over the fixture vocabulary.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemorySearchEquivalenceIT {
    private val dim = 768
    private val provider = FakeProvider(dim, FakeProvider.Mode.BOW)
    private lateinit var db: Database
    private lateinit var store: MemoryStore

    private val contents = listOf(
        "The consumer group rebalance reassigns kafka partitions.",
        "The HNSW index serves cosine queries over pgvector columns.",
        "Grandmother's lemon pie recipe needs patience and the oven.",
        "The migrator serializes runners with pg_advisory_lock 727001 before SQL.",
    )

    private val vocabulary = listOf(
        "kafka", "consumer", "rebalance", "partitions", "hnsw", "cosine", "pgvector",
        "lemon", "recipe", "oven", "migrator", "727001", "advisory",
    )

    /** Age in days and salience per fixture row: the decay and the reinforcement both vary. */
    private val agesDays = listOf(0, 10, 60, 120)
    private val saliences = listOf(1.0, 2.0, 0.5, 3.0)

    @BeforeAll
    fun up() = runBlocking {
        TestEnv.needPostgres()
        db = Database(TestDatabase.config("memsearch"))
        Migrator(db, dim).apply()
        store = MemoryStore(db)
        val transcript = Transcript("e1", "ses-a", "turn-e1", "test", "prompt", "reply", emptyList())
        val vectors = provider.embed(contents)
        store.insertAll(transcript, provider.model, contents.zip(vectors) { content, vector -> MemoryStore.MemoryRow("fact", content, vector) })
        db.withConnection { conn ->
            contents.forEachIndexed { index, content ->
                conn.prepareStatement(
                    "UPDATE memories SET created_at = now() - (? * interval '1 day'), salience = ? WHERE content = ?",
                ).use { ps ->
                    ps.setInt(1, agesDays[index])
                    ps.setDouble(2, saliences[index])
                    ps.setString(3, content)
                    ps.executeUpdate()
                }
            }
        }
    }

    @AfterAll
    fun down() {
        if (::db.isInitialized) db.close()
    }

    private suspend fun <T> query(block: suspend (java.sql.Connection) -> T): T = db.withConnection { block(it) }

    private fun vecLeg(conn: java.sql.Connection, vector: FloatArray, model: String, n: Int): Map<Long, Int> =
        conn.prepareStatement(
            "SELECT id FROM memories WHERE embed_model = ? ORDER BY embedding <=> ?::vector, id LIMIT ?",
        ).use { ps ->
            ps.setString(1, model)
            ps.setObject(2, PGvector(vector))
            ps.setInt(3, n)
            ps.executeQuery().use { rs -> rankMap(rs) }
        }

    private fun ftsLeg(conn: java.sql.Connection, text: String, model: String, n: Int): Map<Long, Int> =
        conn.prepareStatement(
            "SELECT id FROM memories WHERE embed_model = ? AND content_tsv @@ websearch_to_tsquery('simple', ?) " +
                "ORDER BY ts_rank_cd(content_tsv, websearch_to_tsquery('simple', ?)) DESC, id LIMIT ?",
        ).use { ps ->
            ps.setString(1, model)
            ps.setString(2, text)
            ps.setString(3, text)
            ps.setInt(4, n)
            ps.executeQuery().use { rs -> rankMap(rs) }
        }

    private fun rankMap(rs: java.sql.ResultSet): MutableMap<Long, Int> {
        val ranks = mutableMapOf<Long, Int>()
        var rank = 1
        while (rs.next()) ranks[rs.getLong(1)] = rank++
        return ranks
    }

    @Test
    fun `every memory hit carries the pure fusion as score and the pure weight, ranked by relevance first`() {
        runBlocking {
            val tokenArb = Arb.int(0, vocabulary.size - 1).map { vocabulary[it] }
            checkAll(Arb.list(tokenArb, 1..3), Arb.int(1, 5), Arb.int(7, 365)) { tokens, k, halfLifeDays ->
                val queryString = tokens.joinToString(" ")
                val vector = provider.embed(listOf(queryString)).single()
                val model = provider.model
                val candidates = maxOf(k * MemoryStore.CANDIDATE_FACTOR, MemoryStore.MIN_CANDIDATES)

                val rows: Map<Long, Pair<Double, Instant>> = query { conn ->
                    conn.createStatement().use { st ->
                        st.executeQuery("SELECT id, salience, created_at FROM memories").use { rs ->
                            generateSequence {
                                if (!rs.next()) null else rs.getLong(1) to (rs.getDouble(2) to (rs.getObject(3) as Timestamp).toInstant())
                            }.toMap()
                        }
                    }
                }
                val pgNow: Instant = query { conn ->
                    conn.createStatement().use { st ->
                        st.executeQuery("SELECT now()").use { rs -> rs.next(); (rs.getObject(1) as Timestamp).toInstant() }
                    }
                }
                val vecRanks = query { conn -> vecLeg(conn, vector, model, candidates) }
                val ftsRanks = query { conn -> ftsLeg(conn, queryString, model, candidates) }

                fun pure(hit: Memory): RankKey {
                    val (salience, createdAt) = rows.getValue(hit.id)
                    val age = Duration.between(createdAt, pgNow).toMillis() / 1000.0
                    val rrf = Rrf.score(listOfNotNull(vecRanks[hit.id], ftsRanks[hit.id]))
                    return RankKey(rrf, salience * Salience.decay(age, halfLifeDays * 86400.0), hit.id)
                }

                val hits = store.search(vector, queryString, model, k, halfLifeDays)
                assertThat(hits).withFailMessage("query '$queryString'").isNotEmpty
                assertThat(hits.size).isLessThanOrEqualTo(k)
                for (hit in hits) {
                    val (salience, createdAt) = rows.getValue(hit.id)
                    val age = Duration.between(createdAt, pgNow).toMillis() / 1000.0
                    val expectedRrf = Rrf.score(listOfNotNull(vecRanks[hit.id], ftsRanks[hit.id]))
                    val expectedWeight = salience * Salience.decay(age, halfLifeDays * 86400.0)
                    assertThat(hit.score)
                        .withFailMessage(
                            "query '$queryString' hit ${hit.id} (sql ${hit.score} vs pure rrf $expectedRrf; " +
                                "vec ${vecRanks[hit.id]}, fts ${ftsRanks[hit.id]}, salience $salience, age $age s)",
                        )
                        .isCloseTo(expectedRrf, within(1e-6))
                    assertThat(hit.weight)
                        .withFailMessage(
                            "query '$queryString' hit ${hit.id} (sql ${hit.weight} vs pure weight $expectedWeight; " +
                                "salience $salience, age $age s, half-life ${halfLifeDays}d)",
                        )
                        .isCloseTo(expectedWeight, within(1e-6))
                }
                val keys = hits.map(::pure)
                val ordered = keys.zipWithNext().all { (a, b) -> a <= b }
                assertThat(ordered).withFailMessage("query '$queryString': ${hits.map { it.id to it.score }} vs keys $keys").isTrue
            }
        }
    }

    @Test
    fun `four half-lives of age leave the unique-token hit at one sixteenth weight and pure fusion score`() = runBlocking<Unit> {
        // The unique-token memory: only it matches "727001" in full text, so the full-text leg
        // ranks it first and it is among the hits. It was backdated 120 days, half-life 30 ->
        // the decay factor is 2^-4 = 0.0625, exact in floating point. The score is now the pure
        // fusion alone; the reinforcement and the decay land in the tie-break weight.
        val queryString = "727001"
        val vector = provider.embed(listOf(queryString)).single()
        val hits = store.search(vector, queryString, provider.model, k = 5, halfLifeDays = 30)
        assertThat(hits).isNotEmpty
        val target = hits.single { it.content == contents[3] }
        assertThat(target.salience).isCloseTo(3.0, within(1e-6))
        val candidates = maxOf(5 * MemoryStore.CANDIDATE_FACTOR, MemoryStore.MIN_CANDIDATES)
        val vecRanks = query { conn -> vecLeg(conn, vector, provider.model, candidates) }
        val ftsRanks = query { conn -> ftsLeg(conn, queryString, provider.model, candidates) }
        assertThat(ftsRanks[target.id]).isEqualTo(1)
        val rrf = Rrf.score(listOfNotNull(vecRanks[target.id], ftsRanks[target.id]))
        // The µs between the backfill UPDATE's now() and the search's now() is the only noise.
        assertThat(requireNotNull(target.score)).isCloseTo(rrf, within(1e-6))
        assertThat(requireNotNull(target.weight)).isCloseTo(3.0 * 0.0625, within(1e-6))
    }
}
