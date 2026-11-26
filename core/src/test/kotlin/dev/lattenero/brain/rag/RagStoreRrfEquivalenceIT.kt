package dev.lattenero.brain.rag

import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.Migrator
import dev.lattenero.brain.embeddings.FakeProvider
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

/**
 * SQL-vs-pure equivalence on fixtures (plan E2): the fusion inside
 * `RagStore.SEARCH` must score and order exactly like [Rrf.fuse]'s formula.
 * Real Postgres, bag-of-words fake embeddings, generated queries over the
 * fixture vocabulary; every hit reports its own per-leg ranks, so the pin is
 * direct: pure score of the reported ranks == the SQL score.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RagStoreRrfEquivalenceIT {
    private val dim = 768
    private val provider = FakeProvider(dim, FakeProvider.Mode.BOW)
    private lateinit var db: Database
    private lateinit var store: RagStore

    private val docs = mapOf(
        // The manual: every query word of the flip test below repeats, so it plainly
        // leads BOTH legs (the fts leg ranks by term frequency).
        "/corpus/kafka.md" to "Kafka consumer groups\nA consumer group rebalance reassigns partitions. A consumer group rebalance replays partitions from the group offset.",
        // The curated note: every keyword of the flip query below must appear here too —
        // the full-text leg is an AND gate (websearch_to_tsquery ANDs every token), and
        // the vote reaches a note only when BOTH legs recall it. One mention per keyword:
        // the manual must win both legs before the vote.
        "/corpus/fp-kb/notes/consumer-groups.md" to "Notes\nA consumer group rebalance moves partitions between members that share offsets.",
        "/corpus/pgvector.md" to "pgvector HNSW\nThe HNSW index serves cosine distance queries over vector columns in Postgres.",
        "/corpus/lemon.md" to "Lemon pie\nGrandmother's recipe: lemons, sugar, eggs, butter, and patience in the oven.",
        "/corpus/locks.md" to "Advisory locks\nThe migrator serializes runners with pg_advisory_lock 727001 before applying SQL.",
    )

    /** The fixture vocabulary plus tokens no document contains. */
    private val vocabulary = listOf(
        "kafka", "consumer", "group", "rebalance", "partitions", "pgvector", "hnsw", "cosine",
        "vector", "postgres", "lemon", "recipe", "advisory", "locks", "migrator", "727001",
        "members", "offsets",
    )

    /** The pure fusion of a hit's reported ranks, plus the curated vote when its uri is a notes tree. */
    private fun expected(hit: Hit): Double =
        Rrf.score(listOfNotNull(hit.vectorRank, hit.textRank), curated = Curated.isCurated(hit.uri))

    @BeforeAll
    fun up() = runBlocking {
        TestEnv.needPostgres()
        db = Database(TestDatabase.config("rrfeq"))
        Migrator(db, dim).apply()
        store = RagStore(db)
        for ((uri, text) in docs) {
            val id = (store.upsertDocument("test", uri, uri.substringAfterLast('/'), text, Corpus.sha256(text)) as RagStore.Upsert.Changed).docId
            val chunks = Chunker().chunk(uri.substringAfterLast('/'), text)
            val vectors = provider.embed(chunks.map { it.content })
            store.replaceChunks(id, provider.model, chunks.zip(vectors) { c, v -> RagStore.ChunkRow(c.ordinal, c.heading, c.content, v) })
        }
    }

    @AfterAll
    fun down() {
        if (::db.isInitialized) db.close()
    }

    private fun search(query: String, k: Int): List<Hit> = runBlocking {
        store.search(provider.embed(listOf(query)).single(), query, provider.model, k)
    }

    @Test
    fun `every hit scores exactly the pure fusion of the ranks the sql reports`() {
        runBlocking {
            val tokenArb = Arb.int(0, vocabulary.size - 1).map { vocabulary[it] }
            checkAll(Arb.list(tokenArb, 2..4), Arb.int(1, 5)) { tokens, k ->
                val query = tokens.joinToString(" ")
                val hits = search(query, k)
                assertThat(hits).withFailMessage("query '$query'").isNotEmpty
                assertThat(hits.size).isLessThanOrEqualTo(k)
                for (hit in hits) {
                    assertThat(hit.score)
                        .withFailMessage("query '$query' hit ${hit.chunkId} (${hit.uri}): sql ${hit.score} vs pure ${expected(hit)}")
                        .isCloseTo(expected(hit), within(1e-9))
                }
                val ordered = hits.zipWithNext().all { (a, b) -> a.score > b.score || (a.score == b.score && a.chunkId < b.chunkId) }
                assertThat(ordered).withFailMessage("query '$query': ${hits.map { it.chunkId to it.score }}").isTrue
            }
        }
    }

    @Test
    fun `a query both legs rank first scores exactly two over k+1`() {
        // lemon.md has no curated competitor on its query: the plain fusion pins exactly.
        val hits = search("lemon pie recipe", k = 5)
        assertThat(hits).isNotEmpty
        val first = hits.first()
        assertThat(first.uri).isEqualTo("/corpus/lemon.md")
        assertThat(first.vectorRank).isEqualTo(1)
        assertThat(first.textRank).isEqualTo(1)
        assertThat(first.score).isCloseTo(2.0 / (Rrf.K + 1), within(1e-9))
    }

    @Test
    fun `the curated vote flips the manual the query names - the note leads, the manual keeps its own fusion`() {
        // Every token below appears in BOTH docs (the fts leg is an AND gate) and the
        // manual ranks better in both legs — the vote alone flips the order.
        val hits = search("consumer group rebalance partitions", k = 5)
        assertThat(hits).isNotEmpty
        val note = hits.first()
        assertThat(note.uri).withFailMessage(
            "the vote must put the note first: ${hits.map { "${it.uri} vr=${it.vectorRank} tr=${it.textRank} s=${it.score}" }}",
        ).isEqualTo("/corpus/fp-kb/notes/consumer-groups.md")
        assertThat(note.score).isCloseTo(expected(note), within(1e-9))

        // The manual fragment the query names: its own fusion is untouched by the vote,
        // and the vote alone is what flipped the order.
        val manual = hits.first { it.uri == "/corpus/kafka.md" }
        assertThat(manual.score).isCloseTo(Rrf.score(listOfNotNull(manual.vectorRank, manual.textRank)), within(1e-9))
        assertThat(Rrf.score(listOfNotNull(manual.vectorRank, manual.textRank)))
            .withFailMessage("precondition: the manual's plain fusion must lead before the vote")
            .isGreaterThan(Rrf.score(listOfNotNull(note.vectorRank, note.textRank)))
    }

    @Test
    fun `a query only the vector leg recalls scores one over k+rank`() {
        // Tokens no document contains: the full-text leg recalls nothing, the vector leg still orders.
        val hits = search("unicorn rainbow spaceship", k = 5)
        assertThat(hits).isNotEmpty
        for (hit in hits) {
            assertThat(hit.textRank).withFailMessage("hit ${hit.chunkId}").isNull()
            assertThat(hit.score).isCloseTo(expected(hit), within(1e-9))
        }
    }
}
