package dev.lattenero.brain.memory

import com.pgvector.PGvector
import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.bus.BusPipeline
import dev.lattenero.brain.chat.FakeChatProvider
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.Migrator
import dev.lattenero.brain.embeddings.FakeProvider
import dev.lattenero.brain.embeddings.OllamaProvider
import dev.lattenero.brain.http.HttpClients
import dev.lattenero.brain.rag.CountingTracer
import dev.lattenero.brain.rag.EmbedPipeline
import kap.CircuitBreaker
import kap.evalGraph
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.time.Duration
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * B4's memory graph against real Postgres. The throwaway databases are
 * created fresh per run — `TestDatabase.fresh` names each with a random
 * suffix, so parallel IT runs never collide. The related band is exercised
 * with REAL nomic-embed-text vectors where the law says "the cosine pgvector
 * computed"; the band boundaries that need exact control use vectors with an
 * exact pairwise cosine (the `TestVectors.at` construction). The forgetting
 * exclusion is pinned row by row: an ARCHIVED row is never an edge endpoint
 * and never a traversal result.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryEdgesIT {
    private val dim = 768
    private val halfLifeDays = 30

    private class Fixture(val db: Database, val config: dev.lattenero.brain.config.Config, val halfLifeDays: Int) : AutoCloseable {
        val store = MemoryStore(db)
        val graph = MemoryGraphStore(db)
        val builder = EdgeBuilder(graph, halfLifeDays)
        val backfill = EdgeBackfill(db)
        override fun close() = db.close()
    }

    private fun fixture(tag: String): Fixture {
        val config = TestDatabase.config(tag)
        val db = Database(config)
        runBlocking { Migrator(db, dim).apply() }
        return Fixture(db, config, halfLifeDays)
    }

    private fun ollama(): OllamaProvider {
        TestEnv.needOllama()
        TestEnv.needOllamaModel(OLLAMA_MODEL)
        return OllamaProvider(HttpClients.create(30), TestEnv.base.ollamaBaseUrl, OLLAMA_MODEL, dim, 8)
    }

    private fun service(provider: OllamaProvider, f: Fixture): MemoryService = MemoryService(
        provider,
        f.store,
        MemoryExtractor(FakeChatProvider(), 10.seconds, CountingTracer()),
        halfLifeDays,
        f.graph,
        f.builder,
    )

    /** A row inserted raw (no dedup), returning its id. */
    private suspend fun insertRow(
        f: Fixture,
        content: String,
        vector: FloatArray,
        model: String,
        sessionId: String? = null,
        turnId: String? = null,
    ): Long = f.db.withConnection { conn ->
        conn.prepareStatement(
            "INSERT INTO memories (session_id, turn_id, kind, content, embed_model, embedding) " +
                "VALUES (?, ?, 'fact', ?, ?, ?) RETURNING id",
        ).use { ps ->
            ps.setString(1, sessionId)
            ps.setString(2, turnId)
            ps.setString(3, content)
            ps.setString(4, model)
            ps.setObject(5, PGvector(vector))
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    /** Forgets a row the B3 way: three-plus half-lives of silence on the usefulness clock. */
    private suspend fun forget(f: Fixture, id: Long) = f.db.withConnection { conn ->
        conn.prepareStatement(
            "UPDATE memories SET created_at = now() - interval '400 days'," +
                " last_accessed = now() - interval '400 days' WHERE id = ?",
        ).use { ps ->
            ps.setLong(1, id)
            ps.executeUpdate()
        }
    }

    private suspend fun backdate(f: Fixture, id: Long, minutes: Long) = f.db.withConnection { conn ->
        conn.prepareStatement(
            "UPDATE memories SET created_at = now() - (? * interval '1 minute')," +
                " last_accessed = now() - (? * interval '1 minute') WHERE id = ?",
        ).use { ps ->
            ps.setLong(1, minutes)
            ps.setLong(2, minutes)
            ps.setLong(3, id)
            ps.executeUpdate()
        }
    }

    private suspend fun edgesTouching(f: Fixture, id: Long): List<String> = f.db.withConnection { conn ->
        conn.prepareStatement(
            "SELECT kind FROM memory_edges WHERE src_id = ? OR dst_id = ? ORDER BY kind",
        ).use { ps ->
            ps.setLong(1, id)
            ps.setLong(2, id)
            ps.executeQuery().use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }
    }

    private suspend fun edgeCount(f: Fixture): Long = f.db.withConnection { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM memory_edges").use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    /** A unit vector whose cosine with the axis is exactly [similarity] (float32-rounded). */
    private fun vectorAt(similarity: Double, axis: Int = 0): FloatArray {
        val angle = acos(similarity)
        val v = FloatArray(dim)
        v[axis] = cos(angle).toFloat()
        v[(axis + 1) % dim] = sin(angle).toFloat()
        return v
    }

    /**
     * The related band with REAL embeddings: two memories that are related but
     * not duplicates (measured in [0.70, MemoryDedup.SIMILARITY) with the live
     * ollama model) hang off each other through the builder, and the search
     * traversal carries the neighbor BOTH ways — one edge row serves both
     * endpoints.
     */
    @Test
    fun `related but not duplicate memories walk the band, both ways, with real embeddings`() = runBlocking<Unit> {
        val provider = ollama()
        // Measured with the live model: pair (0,1) ~0.868 and pair (0,2) ~0.772 —
        // both inside the band, none at merge grade (the pool is the fixture).
        val pool = listOf(
            "The memory worker extracts facts and summaries from completed turns.",
            "Memory extraction reads completed turns and writes facts and summaries.",
            "The memory worker polls the bus, extracts memories and stores them.",
            "Dedup merges near-identical memories into their oldest stored row.",
            "Kafka partitions are reassigned when the consumer group rebalances.",
            "Grandmother's lemon pie recipe needs patience and a hot oven.",
        )
        val vectors = provider.embed(pool)
        val inBand = pool.indices.flatMap { i ->
            pool.indices.filter { it > i }.mapNotNull { j ->
                val sim = MemoryDedup.cosine(vectors[i], vectors[j])
                if (sim >= MemoryEdges.RELATED_FLOOR && sim < MemoryDedup.SIMILARITY) Triple(i, j, sim) else null
            }
        }
        assumeTrue(inBand.isNotEmpty(), "no measured in-band pair: $inBand")
        val (ai, bi, similarity) = inBand.first()

        fixture("memgraph_real").use { f ->
            val a = insertRow(f, pool[ai], vectors[ai], provider.model, sessionId = "ses-graph")
            val b = insertRow(f, pool[bi], vectors[bi], provider.model, sessionId = "ses-graph")
            val outcome = f.builder.forMemory(a)
            assertThat(outcome).isInstanceOf(EdgeOutcome.Built::class.java)
            assertThat(edgesTouching(f, a)).isNotEmpty

            val searchA = service(provider, f).search(MemorySearchRequest(pool[ai], k = 1))
            assertThat(searchA.memories.map { it.id }).containsExactly(a)
            val relatedB = searchA.related.filter { it.id == b }
            assertThat(relatedB).hasSize(1)
            assertThat(relatedB.single().viaId).isEqualTo(a)
            assertThat(relatedB.single().edgeKind).isEqualTo(EdgeKind.RELATED.wire)
            assertThat(relatedB.single().weight).isGreaterThanOrEqualTo(MemoryEdges.RELATED_FLOOR)
            assertThat(relatedB.single().weight).isLessThan(MemoryDedup.SIMILARITY)
            // Symmetry: from B's search, the same edge row carries A back.
            val searchB = service(provider, f).search(MemorySearchRequest(pool[bi], k = 1))
            assertThat(searchB.memories.map { it.id }).containsExactly(b)
            assertThat(searchB.related.map { it.id }).contains(a)
        }
    }

    /**
     * The B3 exclusion over the graph: a forgotten row earns NO edge (as
     * related neighbor, as temporal predecessor) and never surfaces through a
     * traversal even when an edge to it already exists.
     */
    @Test
    fun `a forgotten row never earns edges and never surfaces in the traversal`() = runBlocking<Unit> {
        val provider = ollama()
        // The same measured fixture pool: (0,1) ~0.868 in band, (0,2) ~0.772 in band.
        val pool = listOf(
            "The memory worker extracts facts and summaries from completed turns.",
            "Memory extraction reads completed turns and writes facts and summaries.",
            "The memory worker polls the bus, extracts memories and stores them.",
            "Kafka partitions are reassigned when the consumer group rebalances.",
        )
        val vectors = provider.embed(pool)
        fixture("memgraph_lifecycle").use { f ->
            val a = insertRow(f, pool[0], vectors[0], provider.model, sessionId = "ses-forget")
            val b = insertRow(f, pool[1], vectors[1], provider.model, sessionId = "ses-forget")
            val forgotten = insertRow(f, pool[2], vectors[2], provider.model, sessionId = "ses-forget")
            val simWithA = MemoryDedup.cosine(vectors[0], vectors[2])
            val simWithB = MemoryDedup.cosine(vectors[1], vectors[2])
            val bandWithA = simWithA >= MemoryEdges.RELATED_FLOOR && simWithA < MemoryDedup.SIMILARITY
            assumeTrue(
                bandWithA || simWithB >= MemoryEdges.RELATED_FLOOR && simWithB < MemoryDedup.SIMILARITY,
                "measured cosines not in band: a=$simWithA b=$simWithB",
            )
            forget(f, forgotten)

            // The builder skips the forgotten row as an edge endpoint of ANY kind —
            // the alive rows still earn their own edges between them.
            val outcome = f.builder.forMemory(b)
            assertThat(outcome).isInstanceOf(EdgeOutcome.Built::class.java)
            assertThat(edgesTouching(f, forgotten)).isEmpty()
            assertThat(edgesTouching(f, b)).isNotEmpty

            // Even a pre-existing edge to the forgotten row is invisible to the traversal.
            f.db.withConnection { conn ->
                conn.prepareStatement(
                    "INSERT INTO memory_edges (src_id, dst_id, kind, weight) VALUES (?, ?, 'related', 0.8)",
                ).use { ps ->
                    ps.setLong(1, a)
                    ps.setLong(2, forgotten)
                    ps.executeUpdate()
                }
            }
            val search = service(provider, f).search(MemorySearchRequest(pool[0], k = 1))
            assertThat(search.memories.map { it.id }).containsExactly(a)
            assertThat(search.related.map { it.id }).doesNotContain(forgotten)
            if (bandWithA) assertThat(search.related.map { it.id }).contains(b)
        }
    }

    /**
     * The insert path: one turn's memories clique (cooccur) and chain within
     * their session (temporal), and the honest degrade — an edge builder over
     * a dead database reports Failed as a VALUE while the memory write stands.
     */
    @Test
    fun `insert-time edges clique and chain, and a dead graph degrades honestly`() = runBlocking<Unit> {
        fixture("memgraph_worker").use { f ->
            val embedder = FakeProvider(dim, FakeProvider.Mode.BOW)
            val tracer = CountingTracer()
            Files.createDirectories(f.config.busFile.parent)
            Files.write(f.config.busFile, javaClass.classLoader.getResourceAsStream("bus/sample-bot-publisher.jsonl")!!.readAllBytes())
            BusPipeline.of(f.config, f.db).evalGraph().drain()
            val chat = FakeChatProvider { _, _ ->
                FakeChatProvider.json(
                    "facts" to FakeChatProvider.strings("The user keeps a CV project at /Users/x/Projects/cv."),
                    "decisions" to FakeChatProvider.strings("The CV was reordered this turn."),
                    "preferences" to FakeChatProvider.strings("The user writes prompts mixing Spanish and English."),
                    "summary" to JsonPrimitive("The CV got reordered."),
                )
            }
            val breaker = CircuitBreaker(maxFailures = 100, retryAfter = 1.minutes)
            val extractor = MemoryExtractor(chat, 10.seconds, tracer, breaker)
            val worker = MemoryWorker(
                f.store,
                extractor,
                EmbedPipeline(embedder, 8, 2, 10.seconds, tracer, breaker),
                edges = f.builder,
            )
            val report = worker.processOnce()
            // The memory write stood, and the turn's four memories clique and chain.
            assertThat(report.processed).isEqualTo(1)
            assertThat(report.memories).isEqualTo(4)
            val counts = f.graph.countByKind()
            assertThat(counts["cooccur"]).isEqualTo(6) // C(4,2): all pairs of the same turn
            assertThat(counts["temporal"]).isEqualTo(3) // the session chain: each row to its predecessor

            // The honest degrade: a builder whose database is gone reports Failed as a
            // value — nothing throws, nothing is written, the caller logs and moves on.
            val deadDb = Database(TestDatabase.config("memgraph_dead"))
            deadDb.close()
            val degraded = EdgeBuilder(MemoryGraphStore(deadDb), halfLifeDays).forTurn("ses-x", "turn-x", listOf(1L, 2L))
            assertThat(degraded).isInstanceOf(EdgeOutcome.Failed::class.java)
            assertThat((degraded as EdgeOutcome.Failed).reason).isNotBlank()
        }
    }

    /**
     * The backfill over existing rows: the band (with exact-cosine vectors),
     * the clique and the chain land once; a second run lands nothing — the
     * idempotence law. The forgotten row earns no edge despite an in-band
     * cosine with R1.
     */
    @Test
    fun `backfill lands band, cliques and chains once and is idempotent`() = runBlocking<Unit> {
        fixture("memgraph_backfill").use { f ->
            val marker = "backfill"
            // R1..R4: exact pairwise cosines against R1 — only pairs inside
            // [RELATED_FLOOR, SIMILARITY) may become related edges: R1-R2 (0.80) and
            // R3-R4 (~0.82, the band crossing mid-air); R1-R3 (0.95) is MERGE-grade
            // (dedup's boundary, never an edge), R1-R4 (0.60) is noise below the floor.
            val r1 = insertRow(f, "r1-$marker", vectorAt(1.0), BAND_MODEL)
            insertRow(f, "r2-$marker", vectorAt(0.80), BAND_MODEL)
            insertRow(f, "r3-$marker", vectorAt(0.95), BAND_MODEL)
            insertRow(f, "r4-$marker", vectorAt(0.60), BAND_MODEL)
            val forgotten = insertRow(f, "forgotten-$marker", vectorAt(0.85), BAND_MODEL)
            forget(f, forgotten)
            // One clique (same session and turn) whose rows are 1 day apart —
            // cooccur yes, temporal no.
            insertRow(f, "clique1-$marker", vectorAt(0.0, axis = 2), "clique-model", "s-bf", "t-bf")
            val c2 = insertRow(f, "clique2-$marker", vectorAt(0.0, axis = 3), "clique-model", "s-bf", "t-bf")
            backdate(f, c2, minutes = 24 * 60)
            // One chain: 20 min ago, 5 min ago, now — only the last pair is inside the window.
            val t1 = insertRow(f, "chain1-$marker", vectorAt(0.0, axis = 4), "chain-model", "s-t")
            val t2 = insertRow(f, "chain2-$marker", vectorAt(0.0, axis = 5), "chain-model", "s-t")
            insertRow(f, "chain3-$marker", vectorAt(0.0, axis = 6), "chain-model", "s-t")
            backdate(f, t1, minutes = 20)
            backdate(f, t2, minutes = 5)

            val first = f.backfill.backfill(halfLifeDays)
            assertThat(first.scanned).isEqualTo(10)
            assertThat(first.related).isEqualTo(4) // R1<->R2 and R3<->R4, one row per direction
            assertThat(first.cooccur).isEqualTo(1)
            assertThat(first.temporal).isEqualTo(1)
            assertThat(edgesTouching(f, forgotten)).isEmpty()
            assertThat(edgesTouching(f, r1)).contains("related")

            val second = f.backfill.backfill(halfLifeDays)
            assertThat(second.related).isZero()
            assertThat(second.cooccur).isZero()
            assertThat(second.temporal).isZero()
            assertThat(edgeCount(f)).isEqualTo(first.total.toLong())
            // The window's boundary belongs to the chain (the pure law's twin in SQL):
            // the chained pair really sits inside the window.
            val chainedGap = f.db.withConnection { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery(
                        "SELECT extract(epoch FROM (b.created_at - a.created_at)) FROM memories a, memories b " +
                            "WHERE a.content = 'chain2-$marker' AND b.content = 'chain3-$marker'",
                    ).use { rs -> rs.next(); rs.getDouble(1) }
                }
            }
            assertThat(chainedGap).isLessThanOrEqualTo(MemoryEdges.TEMPORAL_WINDOW_SECONDS)
            assertThat(Duration.ofSeconds(chainedGap.toLong())).isGreaterThan(Duration.ZERO)
        }
    }

    companion object {
        const val OLLAMA_MODEL = "nomic-embed-text"
        const val BAND_MODEL = "band-model"
    }
}
