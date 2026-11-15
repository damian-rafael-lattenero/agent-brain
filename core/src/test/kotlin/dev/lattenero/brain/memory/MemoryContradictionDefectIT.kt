package dev.lattenero.brain.memory

import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.http.HttpClients
import dev.lattenero.brain.db.Migrator
import dev.lattenero.brain.embeddings.EmbeddingProvider
import dev.lattenero.brain.embeddings.OllamaProvider
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * THE LATENT DEFECT, pinned with a measured number (the reconcile unit's
 * test-first proof): today's dedup ([MemoryDedup.SIMILARITY] = 0.90) fuses
 * statements that CONTRADICT each other when their embeddings land in or
 * near the merge band — and the survivor keeps the stale claim.
 *
 * Measured with the real local embedder (nomic-embed-text, 2026-11-15):
 *
 * - the NAIVE contradiction the defect was first suspected with — "The deploy
 *   is pending." vs "The deploy completed today." — measures ~0.81: BELOW the
 *   band, in the merely-related band. Naive antonyms do not fuse; the defect
 *   is band-dependent, not universal;
 * - realistic STATUS-FLIP pairs share nearly all their tokens and DO fuse:
 *   "The orders database failover is in progress." vs "The orders database
 *   failover is complete." measures ~0.95; "The rollout of the new search
 *   index is in progress." vs "... is finished." ~0.94; the tense flip "The
 *   migration will run on Saturday." vs "The migration ran on Saturday."
 *   ~0.94 — all at or above 0.90.
 *
 * The failure mode, executed against the REAL dedup path
 * ([MemoryStore.insertAllDeduped]) with real embeddings: the first claim
 * lands, the contradicting claim is ABSORBED into it (merged, not inserted),
 * and the store keeps the stale claim as the only retrievable truth. A later
 * reconcile pass (the Verdict machinery) is the fix this test motivates.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryContradictionDefectIT {
    private lateinit var config: dev.lattenero.brain.config.Config
    private lateinit var db: Database
    private lateinit var store: MemoryStore
    private lateinit var embedder: EmbeddingProvider
    private var client: HttpClient? = null

    @BeforeAll
    fun up() = runBlocking {
        TestEnv.needPostgres()
        TestEnv.needOllama()
        TestEnv.needOllamaModel(TestEnv.base.ollamaModel)
        config = TestEnv.config(pgUrl = TestDatabase.fresh("contradiction"), provider = "ollama")
        val httpClient = HttpClients.create(config.httpTimeoutS).also { client = it }
        db = Database(config)
        Migrator(db, config.embedDim).apply()
        store = MemoryStore(db)
        embedder = OllamaProvider(httpClient, config.ollamaBaseUrl, config.ollamaModel, config.embedDim, config.embedBatch)
    }

    @AfterAll
    fun down() {
        if (::db.isInitialized) db.close()
        client?.close()
    }

    /** The real dedup path: two turns, one contradicting statement each. */
    private suspend fun insert(memory: String): MemoryStore.InsertReport {
        val vector = embedder.embed(listOf(memory)).single()
        val transcript = Transcript("e-$memory", "ses-defect", "turn-${memory.hashCode()}", "test", memory, "", emptyList())
        return store.insertAllDeduped(
            transcript,
            embedder.model,
            listOf(MemoryStore.MemoryRow(MemoryKind.FACT.wire, memory, vector)),
        )
    }

    private suspend fun contents(): List<String> = db.withConnection { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT content FROM memories ORDER BY id").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        }
    }

    @Test
    fun `the naive contradiction measures below the merge band - the defect is band-dependent`() = runBlocking<Unit> {
        val naive = listOf("The deploy is pending.", "The deploy completed today.")
        val vectors = embedder.embed(naive)
        val cosine = MemoryDedup.cosine(vectors[0], vectors[1])
        // Pinned measurement (nomic-embed-text, 2026-11-15): ~0.8095. The pair
        // is genuinely contradicting yet sits in the merely-related band, so
        // dedup cannot see it either — below the band, contradiction is free.
        assertThat(cosine)
            .withFailMessage("naive pair measured $cosine; the pinned model behavior changed — re-measure")
            .isLessThan(MemoryDedup.SIMILARITY)
    }

    @Test
    fun `a realistic status-flip contradiction lands in the merge band`() = runBlocking<Unit> {
        val pairs = listOf(
            "The orders database failover is in progress." to "The orders database failover is complete.",
            "The rollout of the new search index is in progress." to "The rollout of the new search index is finished.",
            "The migration will run on Saturday." to "The migration ran on Saturday.",
        )
        val vectors = embedder.embed(pairs.flatMap { listOf(it.first, it.second) })
        for ((index, pair) in pairs.withIndex()) {
            val cosine = MemoryDedup.cosine(vectors[2 * index], vectors[2 * index + 1])
            assertThat(cosine)
                .withFailMessage("status-flip pair '$pair' measured $cosine — the pinned failure mode moved")
                .isGreaterThanOrEqualTo(MemoryDedup.SIMILARITY)
        }
    }

    @Test
    fun `dedup fuses the contradiction - the stale claim survives, the update never lands`() = runBlocking<Unit> {
        val stale = "The orders database failover is in progress."
        val fresh = "The orders database failover is complete."
        val vectors = embedder.embed(listOf(stale, fresh))
        val measured = MemoryDedup.cosine(vectors[0], vectors[1])
        assertThat(measured)
            .withFailMessage("pinned pair measured $measured — the pinned failure mode moved")
            .isGreaterThanOrEqualTo(MemoryDedup.SIMILARITY)

        val first = insert(stale)
        assertThat(first.inserted).isEqualTo(1)
        assertThat(first.merged).isEmpty()

        // The contradicting update arrives — and the REAL dedup path absorbs it
        // into the stale row instead of landing it.
        val second = insert(fresh)
        assertThat(second.inserted)
            .withFailMessage("the contradicting claim was inserted; cosine $measured is in the merge band")
            .isZero()
        assertThat(second.merged).hasSize(1)

        // The store now holds ONE claim — the STALE one. The update is gone.
        assertThat(contents()).containsExactly(stale)

        // And retrieval surfaces the false claim as the only answer.
        val queryVector = embedder.embed(listOf("did the orders database failover complete?")).single()
        val hits = store.search(queryVector, "failover complete", embedder.model, k = 5, lifecycle = Lifecycle.Params(30))
        assertThat(hits.map { it.content }).containsExactly(stale)
    }
}
