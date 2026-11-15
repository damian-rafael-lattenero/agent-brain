package dev.lattenero.brain.memory

import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.chat.FakeChatProvider
import dev.lattenero.brain.chat.OllamaChatProvider
import dev.lattenero.brain.config.Config
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.Migrator
import dev.lattenero.brain.embeddings.FakeProvider
import dev.lattenero.brain.embeddings.OllamaProvider
import dev.lattenero.brain.http.HttpClients
import dev.lattenero.brain.http.MemoryMetrics
import dev.lattenero.brain.http.MetricsSnapshot
import dev.lattenero.brain.http.renderMetrics
import dev.lattenero.brain.rag.CountingTracer
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Timestamp
import kotlin.time.Duration.Companion.seconds

/**
 * THE END-TO-END PROOF on live services: two GENUINELY CONTRADICTING
 * memories with REAL nomic-embed-text embeddings land through the real dedup
 * path, the REAL ollama chat (the extractor's own client) judges the pair,
 * and the Supersede verdict invalidates the old row — retrieval then answers
 * with the new claim ONLY.
 *
 * The pair is chosen IN the reconcile band (measured cosine ~0.89 with
 * nomic-embed-text, below [MemoryDedup.SIMILARITY] 0.90 — dedup does NOT
 * merge it; the stronger flip at ~0.95 WOULD have been deduped and
 * unreachable by the reconcile pass):
 *
 * - OLD: "The orders database failover is still pending."
 * - NEW: "The orders database failover completed successfully."
 *
 * Every test owns a per-run unique fresh database (`TestDatabase.fresh` — a
 * random suffix on every call), so parallel IT runs against the same
 * Postgres never collide.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryReconcileIT {
    private val halfLifeDays = 30
    private val oldClaim = "The orders database failover is still pending."
    private val newClaim = "The orders database failover completed successfully."

    private class Fixture(val config: Config, val db: Database, val store: MemoryStore, val client: HttpClient) : AutoCloseable {
        override fun close() {
            db.close()
        }
    }

    private fun fixture(tag: String, real: Boolean): Fixture {
        val config = TestEnv.config(pgUrl = TestDatabase.fresh(tag), provider = if (real) "ollama" else "fake")
        val db = Database(config)
        runBlocking { Migrator(db, config.embedDim).apply() }
        val client = HttpClients.create(config.httpTimeoutS)
        return Fixture(config, db, MemoryStore(db), client)
    }

    private fun ollamaProvider(f: Fixture) = OllamaProvider(f.client, f.config.ollamaBaseUrl, f.config.ollamaModel, f.config.embedDim, f.config.embedBatch)

    /** The real dedup path: one claim, its embedding, its own event id. */
    private suspend fun insert(
        f: Fixture,
        provider: dev.lattenero.brain.embeddings.EmbeddingProvider,
        claim: String,
        eventId: String,
    ): MemoryStore.InsertReport {
        val vector = provider.embed(listOf(claim)).single()
        val transcript = Transcript(eventId, "ses-reconcile", "turn-$eventId", "test", claim, "", emptyList())
        return f.store.insertAllDeduped(
            transcript,
            provider.model,
            listOf(MemoryStore.MemoryRow(MemoryKind.FACT.wire, claim, vector)),
        )
    }

    private suspend fun invalidatedAt(f: Fixture, id: Long): Timestamp? = f.db.withConnection { conn ->
        conn.prepareStatement("SELECT invalid_at FROM memories WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> rs.next(); rs.getTimestamp(1) }
        }
    }

    @Test
    fun `a real contradiction is reconciled - the old claim invalidated, retrieval answers only the new claim`() = runBlocking<Unit> {
        TestEnv.needPostgres()
        TestEnv.needOllama()
        TestEnv.needOllamaModel(TestEnv.base.ollamaModel)
        TestEnv.needOllamaModel(TestEnv.base.chatModel)
        fixture("reconcile_real", real = true).use { f ->
            val provider = ollamaProvider(f)

            // The two contradicting claims through the REAL dedup path: the pair
            // sits in the band, so BOTH land (dedup cannot fuse them below 0.90).
            val first = insert(f, provider, oldClaim, "e-old")
            assertThat(first.inserted).isEqualTo(1)
            val oldId = first.insertedIds.single()
            val second = insert(f, provider, newClaim, "e-new")
            assertThat(second.inserted).isEqualTo(1)
            val newId = second.insertedIds.single()

            // The measured cosine of the pinned pair: IN the band, not merged.
            val measured = MemoryDedup.cosine(f.store.embeddingOf(oldId)!!, f.store.embeddingOf(newId)!!)
            assertThat(measured)
                .withFailMessage("pinned pair measured $measured — re-measure the band members")
                .isGreaterThanOrEqualTo(MemoryReconcile.BAND_LOW)
                .isLessThan(MemoryDedup.SIMILARITY)

            // Before the verdict, retrieval returns BOTH claims (nothing is hidden yet).
            val query = provider.embed(listOf("orders database failover")).single()
            val before = f.store.search(query, "orders database failover", provider.model, k = 10, Lifecycle.Params(halfLifeDays))
            assertThat(before.map { it.content }).contains(oldClaim, newClaim)

            // The REAL chat (the extractor's own client and model) judges the pair.
            val chat = OllamaChatProvider(f.client, f.config.ollamaBaseUrl, f.config.chatModel)
            val judge = VerdictJudge(chat, 60.seconds)
            val report = MemoryReconciler(f.store, judge).reconcileInserted(listOf(newId), provider.model, halfLifeDays)

            assertThat(report.judged).isEqualTo(1)
            assertThat(report.invalidated).isEqualTo(1)
            assertThat(report.pairs.single().verdict).isEqualTo(Verdict.Supersede)

            // Exactly the OLD row is stamped.
            assertThat(invalidatedAt(f, oldId)).isNotNull()
            assertThat(invalidatedAt(f, newId)).isNull()

            // Retrieval answers with the NEW claim ONLY - the stale claim left both legs.
            val after = f.store.search(query, "orders database failover", provider.model, k = 10, Lifecycle.Params(halfLifeDays))
            assertThat(after.map { it.content }).doesNotContain(oldClaim)
            assertThat(after.map { it.content }).contains(newClaim)
        }
    }

    @Test
    fun `the status bucket and the gauge count the superseded row`() = runBlocking<Unit> {
        TestEnv.needPostgres()
        fixture("reconcile_status", real = false).use { f ->
            val provider = FakeProvider(f.config.embedDim, FakeProvider.Mode.HASH)
            val extractor = MemoryExtractor(FakeChatProvider { _, _ -> "{}" }, 10.seconds, CountingTracer())
            val service = MemoryService(provider, f.store, extractor, halfLifeDays)

            val id = insert(f, provider, oldClaim, "e-superseded").insertedIds.single()
            val statusBefore = service.status()
            assertThat(statusBefore.superseded).isZero()
            assertThat(statusBefore.memories).isEqualTo(1)

            assertThat(f.store.invalidate(id)).isTrue()

            val status = service.status()
            assertThat(status.superseded).isEqualTo(1)
            assertThat(status.memories).isEqualTo(1) // the row stays: invalidation is NOT deletion

            val text = renderMetrics(MetricsSnapshot(memory = MemoryMetrics.of(status)))
            assertThat(text).contains("brain_memory_superseded 1")
        }
    }
}
