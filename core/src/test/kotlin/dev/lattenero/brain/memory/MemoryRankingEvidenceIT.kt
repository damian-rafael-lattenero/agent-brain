package dev.lattenero.brain.memory

import com.pgvector.PGvector
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.embeddings.EmbeddingProviders
import dev.lattenero.brain.http.HttpClients
import dev.lattenero.brain.rag.Rrf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/**
 * The ranking-fix evidence, measured against the LIVE production memories
 * (the configured `pg_url`, by default 127.0.0.1:5433/agent_brain). The
 * session is forced READ-ONLY (`default_transaction_read_only = on` plus a
 * statement timeout): this test only ever SELECTs and embeds three queries —
 * it never writes, never reinforces, never bumps `last_accessed`. For each
 * query it recomputes the OLD algebra (score = rrf * salience * decay) and
 * the NEW one ([RankKey]: rrf dominant, weight as tie-break) over the same
 * captured candidate rows, and prints both top-5 orderings — the table in
 * `docs/memory-ranking-fix.md` is transcribed from this output.
 *
 * Skips when the live services are unreachable; with AB_REQUIRE_SERVICES=1
 * an unreachable service fails instead (TestEnv.gate). Not part of any gate
 * tier on purpose: it reads the owner's real database.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryRankingEvidenceIT {

    private val queries = listOf(
        "functional programming pure functions denotational design",
        "busy waiting while true reactive loop schedule",
        "the user prefers concise summaries",
    )

    @Test
    fun `the relevant match wins under the new ranking on the live memories`() = runBlocking<Unit> {
        // OPT-IN ONLY (never in CI, even with AB_REQUIRE_SERVICES=1): this test
        // reads the OWNER'S real production memories — there is no CI fixture
        // for them. Run it on the Mac with AB_RANKING_EVIDENCE=1.
        assumeTrue(
            System.getenv("AB_RANKING_EVIDENCE") == "1",
            "live-production evidence is opt-in: AB_RANKING_EVIDENCE=1",
        )
        TestEnv.gate(TestEnv.postgresAvailable(), "Postgres at ${TestEnv.base.pgUrl}")
        TestEnv.needOllama()

        val config = TestEnv.base
        val halfLifeSeconds = config.memoryHalfLifeDays * 86400.0
        val provider = EmbeddingProviders.create(config, HttpClients.create(config.httpTimeoutS))
        val k = 5
        val candidates = maxOf(k * MemoryStore.CANDIDATE_FACTOR, MemoryStore.MIN_CANDIDATES)

        DriverManager.getConnection(
            withParams(TestEnv.base.pgUrl, "connectTimeout=3&loginTimeout=3"),
            TestEnv.base.pgUser,
            TestEnv.base.pgPassword,
        ).use { conn ->
            // The blast radius of a bug here must be zero: no write can even parse.
            conn.createStatement().use { st ->
                st.execute("SET SESSION default_transaction_read_only = on")
                st.execute("SET SESSION statement_timeout = '15s'")
            }
            val embedModel = conn.createStatement().use { st ->
                st.executeQuery("SELECT embed_model FROM memories GROUP BY embed_model ORDER BY count(*) DESC LIMIT 1").use { rs ->
                    check(rs.next()) { "the live memories table is empty" }
                    rs.getString(1)
                }
            }
            check(embedModel == provider.model) {
                "the live memories are embedded with '$embedModel' but the configured provider is '${provider.model}'"
            }

            for (queryString in queries) {
                val vector = provider.embed(listOf(queryString)).single()
                val vecRanks = leg(conn, "SELECT id FROM memories WHERE embed_model = ? ORDER BY embedding <=> ?::vector, id LIMIT ?", embedModel, vector, candidates)
                val ftsRanks = ftsLeg(conn, queryString, embedModel, candidates)
                val pgNow: Instant = conn.createStatement().use { st ->
                    st.executeQuery("SELECT now()").use { rs -> rs.next(); (rs.getObject(1) as Timestamp).toInstant() }
                }

                data class Row(val id: Long, val kind: String, val content: String, val salience: Double, val createdAt: Instant, val rrf: Double) {
                    val ageDays = Duration.between(createdAt, pgNow).toMillis() / 86_400_000.0
                    val weight = Salience.weight(salience, Duration.between(createdAt, pgNow).toMillis() / 1000.0, halfLifeSeconds)
                    val oldScore = rrf * salience * Salience.decay(Duration.between(createdAt, pgNow).toMillis() / 1000.0, halfLifeSeconds)
                }

                val rows: Map<Long, Row> = conn.prepareStatement(
                    "SELECT id, kind, content, salience, created_at FROM memories WHERE id = ANY(?)",
                ).use { ps ->
                    ps.setArray(1, conn.createArrayOf("bigint", (vecRanks.keys + ftsRanks.keys).toTypedArray()))
                    ps.executeQuery().use { rs ->
                        generateSequence {
                            if (!rs.next()) {
                                null
                            } else {
                                val id = rs.getLong(1)
                                id to Row(
                                    id = id,
                                    kind = rs.getString(2),
                                    content = rs.getString(3),
                                    salience = rs.getDouble(4),
                                    createdAt = (rs.getObject(5) as Timestamp).toInstant(),
                                    rrf = Rrf.score(listOfNotNull(vecRanks[id], ftsRanks[id])),
                                )
                            }
                        }.toMap()
                    }
                }

                val oldOrder = rows.values.sortedWith(compareByDescending<Row> { it.oldScore }.thenBy { it.id }).take(k)
                val newOrder = rows.values.map { RankKey(it.rrf, it.weight, it.id) }.sorted().take(k).mapNotNull { key -> rows[key.id] }

                println()
                println("### QUERY: \"$queryString\"")
                println()
                println("OLD (score = rrf * salience * decay, ORDER BY score DESC):")
                oldOrder.forEachIndexed { index, row ->
                    println(
                        "%d. id=%d %-10s old_score=%.4f rrf=%.4f salience=%.1f age=%.2fd weight=%.4f | %s"
                            .format(index + 1, row.id, row.kind, row.oldScore, row.rrf, row.salience, row.ageDays, row.weight, snippet(row.content)),
                    )
                }
                println()
                println("NEW (RankKey: rrf DESC, weight DESC, id):")
                newOrder.forEachIndexed { index, row ->
                    println(
                        "%d. id=%d %-10s rrf=%.4f weight=%.4f age=%.2fd salience=%.1f (old_score would be %.4f, old rank %s) | %s"
                            .format(
                                index + 1, row.id, row.kind, row.rrf, row.weight, row.ageDays, row.salience, row.oldScore,
                                (oldOrder.indexOfFirst { it.id == row.id } + 1).takeIf { it > 0 } ?: "-",
                                snippet(row.content),
                            ),
                    )
                }
                println()
            }
        }
    }

    private fun leg(conn: java.sql.Connection, sql: String, model: String, vector: FloatArray, n: Int): Map<Long, Int> =
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, model)
            ps.setObject(2, PGvector(vector))
            ps.setInt(3, n)
            ranks(ps.executeQuery())
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
            ranks(ps.executeQuery())
        }

    private fun ranks(rs: java.sql.ResultSet): MutableMap<Long, Int> {
        val out = mutableMapOf<Long, Int>()
        var rank = 1
        while (rs.next()) out[rs.getLong(1)] = rank++
        return out
    }

    private fun snippet(content: String): String =
        content.take(70).replace(Regex("\\s+"), " ") + if (content.length > 70) "..." else ""

    private fun withParams(jdbcUrl: String, params: String): String =
        if ("?" in jdbcUrl) "$jdbcUrl&$params" else "$jdbcUrl?$params"
}
