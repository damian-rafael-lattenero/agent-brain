package dev.lattenero.brain.rag

import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.Migrator
import dev.lattenero.brain.embeddings.BaseProvider
import dev.lattenero.brain.embeddings.EmbeddingProvider
import dev.lattenero.brain.embeddings.FakeProvider
import dev.lattenero.brain.embeddings.ProviderUnavailable
import kap.CircuitBreaker
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Real Postgres, real files on disk, fake embeddings: the indexing lifecycle end to end. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IndexerIT {
    private val dim = 768
    private lateinit var db: Database
    private lateinit var store: RagStore
    private lateinit var root: Path

    @BeforeAll
    fun up() = runBlocking {
        TestEnv.needPostgres()
        db = Database(TestDatabase.config("indexer"))
        Migrator(db, dim).apply()
        store = RagStore(db)
        root = Files.createTempDirectory("agent-brain-corpus")
        write("docs/kafka.md", "# Kafka\nConsumer groups rebalance partitions; idempotent consumers dedupe by key.")
        write("docs/pgvector.md", "# pgvector\nHNSW indexes serve cosine queries over vector columns.")
        write("docs/deep/locks.md", "# Locks\nThe migrator takes advisory lock 727001 before applying SQL.")
    }

    @AfterAll
    fun down() {
        if (::db.isInitialized) db.close()
    }

    private fun write(rel: String, text: String) {
        val path = root.resolve(rel)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    private fun indexer(provider: EmbeddingProvider = FakeProvider(dim, FakeProvider.Mode.BOW), attempts: Int = 3): Indexer {
        val tracer = CountingTracer()
        val pipeline = EmbedPipeline(provider, batchSize = 4, concurrency = 2, timeout = 30.seconds, tracer = tracer,
            breaker = CircuitBreaker(100, 1.minutes), attempts = attempts)
        return Indexer(store, pipeline, tracer, docConcurrency = 2).also { it.embedBatchSize = 4 }
    }

    private val glob get() = "$root/docs/**/*.md"

    @Test
    fun `first run indexes everything, second run touches nothing, edits and deletions are incremental`() = runBlocking<Unit> {
        val first = indexer().indexGlobs(listOf(glob))
        assertThat(first.documents).isEqualTo(3)
        assertThat(first.indexed).isEqualTo(3)
        assertThat(first.unchanged).isZero
        assertThat(first.failed).isEmpty()
        assertThat(first.chunks).isGreaterThanOrEqualTo(3)
        assertThat(first.embedCalls).isGreaterThanOrEqualTo(3)
        assertThat(first.phases.keys).contains(Indexer.PHASE_UPSERT, EmbedPipeline.PHASE_EMBED, Indexer.PHASE_STORE)
        assertThat(first.embedModel).isEqualTo("fake-bow")

        val second = indexer().indexGlobs(listOf(glob))
        assertThat(second.indexed).isZero
        assertThat(second.unchanged).isEqualTo(3)
        assertThat(second.embedCalls).isZero
        assertThat(second.phases).doesNotContainKey(EmbedPipeline.PHASE_EMBED)

        write("docs/kafka.md", "# Kafka\nConsumer groups rebalance partitions; idempotent consumers dedupe by key.\nNew paragraph about Redpanda.")
        val third = indexer().indexGlobs(listOf(glob))
        assertThat(third.indexed).isEqualTo(1)
        assertThat(third.unchanged).isEqualTo(2)

        Files.delete(root.resolve("docs/pgvector.md"))
        val fourth = indexer().indexGlobs(listOf(glob))
        assertThat(fourth.documents).isEqualTo(2)
        assertThat(fourth.pruned).isEqualTo(1)
        assertThat(store.stats().documents).isEqualTo(2)
        assertThat(store.stats().chunks).isGreaterThanOrEqualTo(2)
    }

    @Test
    fun `one document failing to embed is reported and does not sink the others`() = runBlocking<Unit> {
        val poison = object : BaseProvider() {
            override val name = "poison"
            override val model = "poison-model"
            override val dim = this@IndexerIT.dim
            private val inner = FakeProvider(dim)
            override suspend fun embedAll(texts: List<String>): List<FloatArray> {
                if (texts.any { "POISON" in it }) throw ProviderUnavailable("refusing the poisoned batch")
                return inner.embed(texts)
            }
        }
        val own = Files.createTempDirectory("agent-brain-poison")
        Files.writeString(own.resolve("ok.md"), "# Fine\nthis one embeds")
        Files.writeString(own.resolve("bad.md"), "# Bad\nthis one carries POISON in its text")
        val report = indexer(poison, attempts = 1).indexGlobs(listOf("$own/*.md"))
        assertThat(report.indexed).isEqualTo(1)
        assertThat(report.failed).hasSize(1)
        assertThat(report.failed.single().uri).endsWith("bad.md")
        assertThat(report.failed.single().reason).contains("ProviderUnavailable").contains("poisoned")
        assertThat(report.phases.getValue(EmbedPipeline.PHASE_EMBED).failures).isEqualTo(1)
    }

    @Test
    fun `the queries find what was indexed, with citations pointing at the file`() = runBlocking<Unit> {
        indexer().indexGlobs(listOf(glob))
        val provider = FakeProvider(dim, FakeProvider.Mode.BOW)
        val service = RagService(provider, store)
        val response = service.query(QueryRequest("advisory lock before applying SQL", k = 3))
        assertThat(response.citations.first().uri).endsWith("docs/deep/locks.md")
        assertThat(response.citations.first().title).isEqualTo("deep/locks.md")
        assertThat(response.citations.first().heading).isEqualTo("deep/locks.md > Locks")
    }
}
