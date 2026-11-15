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
import kotlin.math.abs

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
        "The ollama embedding cache warms repeated prompts.",
    )

    private val vocabulary = listOf(
        "kafka", "consumer", "rebalance", "partitions", "hnsw", "cosine", "pgvector",
        "lemon", "recipe", "oven", "migrator", "727001", "advisory", "ollama", "cache",
    )

    /** Age in days and salience per fixture row: the decay and the reinforcement both vary. */
    private val agesDays = listOf(0, 10, 60, 120, 240)
    private val saliences = listOf(1.0, 2.0, 0.5, 3.0, 1.0)

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
                    // the pristine fixture state EVERY run: salience/created_at to the
                    // table's values and last_accessed synced to the same backdate —
                    // reinforcement and access refreshes from earlier runs must not
                    // leak into this run's pins (last_accessed is NOT NULL; the
                    // usefulness clock starts at the row's own birth)
                    "UPDATE memories SET created_at = now() - (? * interval '1 day'), salience = ?, last_accessed = now() - (? * interval '1 day') WHERE content = ?",
                ).use { ps ->
                    ps.setInt(1, agesDays[index])
                    ps.setDouble(2, saliences[index])
                    ps.setInt(3, agesDays[index])
                    ps.setString(4, content)
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

    // The legs mirror MemoryStore.SEARCH's forgetting exclusion: rows whose
    // life-weight (Lifecycle's last-access clock) is under the floor are not
    // candidates — otherwise the pure recompute would rank a row the SQL
    // never saw.
    private fun vecLeg(conn: java.sql.Connection, vector: FloatArray, model: String, n: Int, halfLifeDays: Int): Map<Long, Int> =
        conn.prepareStatement(
            "SELECT m.id FROM memories m, (SELECT ?::int AS half_life, ?::float8 AS floor) p " +
                "WHERE m.embed_model = ? " +
                "AND (m.salience * power(2.0, -extract(epoch FROM (now() - COALESCE(m.last_accessed, m.created_at))) / (86400.0 * p.half_life))) >= p.floor " +
                "ORDER BY m.embedding <=> ?::vector, m.id LIMIT ?",
        ).use { ps ->
            ps.setInt(1, halfLifeDays)
            ps.setDouble(2, Lifecycle.FORGET_FLOOR)
            ps.setString(3, model)
            ps.setObject(4, PGvector(vector))
            ps.setInt(5, n)
            ps.executeQuery().use { rs -> rankMap(rs) }
        }

    private fun ftsLeg(conn: java.sql.Connection, text: String, model: String, n: Int, halfLifeDays: Int): Map<Long, Int> =
        conn.prepareStatement(
            "SELECT m.id FROM memories m, (SELECT ?::int AS half_life, ?::float8 AS floor) p " +
                "WHERE m.embed_model = ? AND m.content_tsv @@ websearch_to_tsquery('simple', ?) " +
                "AND (m.salience * power(2.0, -extract(epoch FROM (now() - COALESCE(m.last_accessed, m.created_at))) / (86400.0 * p.half_life))) >= p.floor " +
                "ORDER BY ts_rank_cd(m.content_tsv, websearch_to_tsquery('simple', ?)) DESC, m.id LIMIT ?",
        ).use { ps ->
            ps.setInt(1, halfLifeDays)
            ps.setDouble(2, Lifecycle.FORGET_FLOOR)
            ps.setString(3, model)
            ps.setString(4, text)
            ps.setString(5, text)
            ps.setInt(6, n)
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
                val vecRanks = query { conn -> vecLeg(conn, vector, model, candidates, halfLifeDays) }
                val ftsRanks = query { conn -> ftsLeg(conn, queryString, model, candidates, halfLifeDays) }

                fun pure(hit: Memory): RankKey {
                    val (salience, createdAt) = rows.getValue(hit.id)
                    val age = Duration.between(createdAt, pgNow).toMillis() / 1000.0
                    val rrf = Rrf.score(listOfNotNull(vecRanks[hit.id], ftsRanks[hit.id]))
                    return RankKey(rrf, salience * Salience.decay(age, halfLifeDays * 86400.0), hit.id)
                }

                val hits = store.search(vector, queryString, model, k, Lifecycle.Params(halfLifeDays = halfLifeDays))
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
                        // relative bound: the µs between this pgNow and the SQL's own now()
                        // scales with the weight (a heavily reinforced row drifts more in
                        // absolute terms than the 1e-6 absolute tolerance can carry)
                        .isCloseTo(expectedWeight, within(maxOf(1e-6, abs(expectedWeight) * 1e-4)))
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
        // This test owns its state: the other tests' searches reinforce salience
        // (B3's access-reinforces), so the row is re-pristined before pinning.
        val queryString = "727001"
        val row3Id: Long = query { conn ->
            conn.prepareStatement("SELECT id FROM memories WHERE content = ?").use { ps ->
                ps.setString(1, contents[3])
                ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
        }
        query { conn ->
            conn.prepareStatement(
                "UPDATE memories SET salience = ?, created_at = now() - (? * interval '1 day'), last_accessed = now() - (? * interval '1 day') WHERE id = ?",
            ).use { ps ->
                ps.setDouble(1, saliences[3])
                ps.setInt(2, agesDays[3])
                ps.setInt(3, agesDays[3])
                ps.setLong(4, row3Id)
                ps.executeUpdate()
            }
        }
        val vector = provider.embed(listOf(queryString)).single()
        val hits = store.search(vector, queryString, provider.model, k = 5, lifecycle = Lifecycle.Params(halfLifeDays = 30))
        assertThat(hits).isNotEmpty
        val target = hits.single { it.content == contents[3] }
        assertThat(target.salience).isCloseTo(3.0, within(1e-6))
        val candidates = maxOf(5 * MemoryStore.CANDIDATE_FACTOR, MemoryStore.MIN_CANDIDATES)
        val vecRanks = query { conn -> vecLeg(conn, vector, provider.model, candidates, 30) }
        val ftsRanks = query { conn -> ftsLeg(conn, queryString, provider.model, candidates, 30) }
        assertThat(ftsRanks[target.id]).isEqualTo(1)
        val rrf = Rrf.score(listOfNotNull(vecRanks[target.id], ftsRanks[target.id]))
        // The µs between the backfill UPDATE's now() and the search's now() is the only noise.
        assertThat(requireNotNull(target.score)).isCloseTo(rrf, within(1e-6))
        assertThat(requireNotNull(target.weight)).isCloseTo(3.0 * 0.0625, within(1e-6))
    }

    /**
     * B3's forgetting law, executable: the fifth fixture (240 days old,
     * salience 1.0, never accessed) has life-weight 2^-8 ≈ 0.0039 — under the
     * FORGET_FLOOR of 0.125 — so the search NEVER returns it even though the
     * unfiltered full-text leg ranks it first for its unique token, and the
     * bucket counts agree with the pure classification row by row.
     */
    @Test
    fun `the forgotten row never fuses and the buckets agree with the pure classification`() = runBlocking<Unit> {
        val queryString = "ollama"
        val vector = provider.embed(listOf(queryString)).single()
        val row4Id: Long = query { conn ->
            conn.prepareStatement("SELECT id FROM memories WHERE content = ?").use { ps ->
                ps.setString(1, contents[4])
                ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
        }
        // The property test above runs with random half-lives; at a large one the
        // fifth fixture is NOT forgotten and a hit would reinforce it alive. This
        // test owns its state: re-pristine the row before pinning the law at 30d.
        query { conn ->
            conn.prepareStatement(
                "UPDATE memories SET salience = ?, created_at = now() - (? * interval '1 day'), last_accessed = now() - (? * interval '1 day') WHERE id = ?",
            ).use { ps ->
                ps.setDouble(1, saliences[4])
                ps.setInt(2, agesDays[4])
                ps.setInt(3, agesDays[4])
                ps.setLong(4, row4Id)
                ps.executeUpdate()
            }
        }
        // The raw leg WOULD rank the forgotten row: exclusion is the law's work, not absence of data.
        val unfiltered = query { conn ->
            conn.prepareStatement(
                "SELECT m.id FROM memories m WHERE m.embed_model = ? AND m.content_tsv @@ websearch_to_tsquery('simple', ?) ORDER BY m.id",
            ).use { ps ->
                ps.setString(1, provider.model)
                ps.setString(2, queryString)
                ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getLong(1) else null }.toList() }
            }
        }
        assertThat(unfiltered).contains(row4Id)

        val hits = store.search(vector, queryString, provider.model, k = 5, lifecycle = Lifecycle.Params(halfLifeDays = 30))
        assertThat(hits.map { it.content }).doesNotContain(contents[4])

        // The buckets are the pure classification, row by row, over the live rows.
        data class Row(val id: Long, val salience: Double, val lastAccess: Instant?, val createdAt: Instant)
        val rows: List<Row> = query { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT id, salience, last_accessed, created_at FROM memories ORDER BY id").use { rs ->
                    generateSequence {
                        if (!rs.next()) null else Row(
                            rs.getLong(1),
                            rs.getDouble(2),
                            (rs.getObject(3) as Timestamp?)?.toInstant(),
                            (rs.getObject(4) as Timestamp).toInstant(),
                        )
                    }.toList()
                }
            }
        }
        val pgNow: Instant = query { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT now()").use { rs -> rs.next(); (rs.getObject(1) as Timestamp).toInstant() }
            }
        }
        val expected = rows.groupBy { row ->
            val ageDays = Duration.between(row.lastAccess ?: row.createdAt, pgNow).toMillis() / 1000.0 / 86400.0
            Lifecycle.classify(Lifecycle.lifeWeight(row.salience, ageDays, 30.0))
        }.mapValues { it.value.size.toLong() }
        val buckets = store.lifecycleCounts(30)
        assertThat(buckets.active).isEqualTo(expected[LifecycleState.ACTIVE] ?: 0L)
        assertThat(buckets.cooling).isEqualTo(expected[LifecycleState.COOLING] ?: 0L)
        assertThat(buckets.archived).isEqualTo(expected[LifecycleState.ARCHIVED] ?: 0L)
        // and the forgotten row really is forgotten under the fixed half-life
        assertThat(rows.first { it.id == row4Id }.let {
            Lifecycle.classify(Lifecycle.lifeWeight(it.salience, Duration.between(it.lastAccess ?: it.createdAt, pgNow).toMillis() / 1000.0 / 86400.0, 30.0))
        }).isEqualTo(LifecycleState.ARCHIVED)
    }

    /**
     * B3's access-reinforces: every hit's salience grows by exactly
     * SEARCH_REINFORCE_STEP (and last_accessed refreshes) — the returned
     * Memory objects carry the PRE-bump values, so the before/after delta is
     * exact.
     */
    @Test
    fun `search hits reinforce salience by exactly the search step`() = runBlocking<Unit> {
        val queryString = "kafka"
        val vector = provider.embed(listOf(queryString)).single()
        val before: Map<Long, Double> = query { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT id, salience FROM memories").use { rs ->
                    generateSequence { if (rs.next()) rs.getLong(1) to rs.getDouble(2) else null }.toMap()
                }
            }
        }
        val hits = store.search(vector, queryString, provider.model, k = 5, lifecycle = Lifecycle.Params(halfLifeDays = 30))
        assertThat(hits).isNotEmpty
        val after: Map<Long, Double> = query { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT id, salience FROM memories").use { rs ->
                    generateSequence { if (rs.next()) rs.getLong(1) to rs.getDouble(2) else null }.toMap()
                }
            }
        }
        for (hit in hits) {
            assertThat(after.getValue(hit.id)).isCloseTo(before.getValue(hit.id) + MemoryStore.SEARCH_REINFORCE_STEP, within(1e-9))
            // the returned row speaks its pre-bump salience: the bump is the search's own effect
            assertThat(hit.salience).isCloseTo(before.getValue(hit.id), within(1e-9))
        }
        for ((id, was) in before) {
            if (hits.none { it.id == id }) assertThat(after.getValue(id)).isCloseTo(was, within(1e-9))
        }
    }
}
