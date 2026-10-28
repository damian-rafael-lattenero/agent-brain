package dev.lattenero.brain.rag

import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.Migrator
import dev.lattenero.brain.embeddings.FakeProvider
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** Real Postgres, bag-of-words fake embeddings (meaningful order without a model). */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RagStoreIT {
    private val dim = 768
    private val provider = FakeProvider(dim, FakeProvider.Mode.BOW)
    private lateinit var db: Database
    private lateinit var store: RagStore

    private val docs = mapOf(
        "/corpus/kafka.md" to "Kafka consumer groups\nA consumer group rebalance reassigns partitions. Idempotent consumers dedupe by key.",
        "/corpus/pgvector.md" to "pgvector HNSW\nThe HNSW index serves cosine distance queries over vector columns in Postgres.",
        "/corpus/lemon.md" to "Lemon pie\nGrandmother's recipe: lemons, sugar, eggs, butter, and patience in the oven.",
        "/corpus/locks.md" to "Advisory locks\nThe migrator serializes runners with pg_advisory_lock 727001 before applying SQL.",
    )

    @BeforeAll
    fun up() = runBlocking {
        TestEnv.needPostgres()
        db = Database(TestDatabase.config("rag"))
        Migrator(db, dim).apply()
        store = RagStore(db)
        for ((uri, text) in docs) {
            val up = store.upsertDocument("test", uri, uri.substringAfterLast('/'), text, Corpus.sha256(text))
            val id = (up as RagStore.Upsert.Changed).docId
            val chunks = Chunker().chunk(uri.substringAfterLast('/'), text)
            val vectors = provider.embed(chunks.map { it.content })
            store.replaceChunks(id, provider.model, chunks.zip(vectors) { c, v -> RagStore.ChunkRow(c.ordinal, c.heading, c.content, v) })
        }
    }

    @AfterAll
    fun down() {
        if (::db.isInitialized) db.close()
    }

    private fun search(query: String, k: Int = 5, model: String = provider.model): List<Hit> = runBlocking {
        store.search(provider.embed(listOf(query)).single(), query, model, k)
    }

    @Test
    fun `an unchanged document is reported as such and a changed one is updated in place`() = runBlocking<Unit> {
        val text = docs.getValue("/corpus/kafka.md")
        val same = store.upsertDocument("test", "/corpus/kafka.md", "kafka.md", text, Corpus.sha256(text))
        assertThat(same).isInstanceOf(RagStore.Upsert.Unchanged::class.java)
        val changed = store.upsertDocument("test", "/corpus/kafka.md", "kafka.md", "$text\nmore", Corpus.sha256("$text\nmore"))
        assertThat(changed).isInstanceOf(RagStore.Upsert.Changed::class.java)
        assertThat((changed as RagStore.Upsert.Changed).inserted).isFalse
        assertThat(changed.docId).isEqualTo((same as RagStore.Upsert.Unchanged).docId)
        // restore the content so the other tests see the original chunks
        store.upsertDocument("test", "/corpus/kafka.md", "kafka.md", text, Corpus.sha256(text))
    }

    @Test
    fun `hybrid search ranks the relevant document first with both legs agreeing`() {
        val hits = search("kafka consumer group rebalance")
        assertThat(hits).isNotEmpty
        assertThat(hits.first().uri).isEqualTo("/corpus/kafka.md")
        assertThat(hits.first().vectorRank).isEqualTo(1)
        assertThat(hits.first().textRank).isEqualTo(1)
        assertThat(hits.first().heading).isEqualTo("kafka.md")
        assertThat(hits.first().score).isGreaterThan(hits.last().score)
    }

    @Test
    fun `the full-text leg rescues an exact token the vector leg underweights`() {
        val hits = search("727001")
        assertThat(hits.first().uri).isEqualTo("/corpus/locks.md")
        assertThat(hits.first().textRank).isEqualTo(1)
    }

    @Test
    fun `k bounds the result and another embedding model sees nothing`() {
        assertThat(search("recipe", k = 2)).hasSize(2)
        assertThat(search("recipe", model = "some-other-model")).isEmpty()
    }

    @Test
    fun `stats count documents and chunks by source`() = runBlocking<Unit> {
        val stats = store.stats()
        assertThat(stats.documents).isEqualTo(4)
        assertThat(stats.chunks).isGreaterThanOrEqualTo(4)
        assertThat(stats.bySource).containsEntry("test", 4L)
    }

    @Test
    fun `documents missing from a rescan are deleted with their chunks`() = runBlocking<Unit> {
        val text = "Temporary\nthis document will vanish"
        val id = (store.upsertDocument("tmp", "/corpus/tmp.md", "tmp.md", text, Corpus.sha256(text)) as RagStore.Upsert.Changed).docId
        store.replaceChunks(id, provider.model, listOf(RagStore.ChunkRow(0, "tmp.md", text, provider.embed(listOf(text)).single())))
        assertThat(store.deleteDocumentsNotIn("tmp", emptyList())).isEqualTo(1)
        assertThat(store.stats().bySource).doesNotContainKey("tmp")
        assertThat(search("vanish").map { it.uri }).doesNotContain("/corpus/tmp.md")
    }
}
