package dev.lattenero.brain.http

import dev.lattenero.brain.Brain
import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.embeddings.FakeProvider
import dev.lattenero.brain.rag.Chunker
import dev.lattenero.brain.rag.Corpus
import dev.lattenero.brain.rag.QueryResponse
import dev.lattenero.brain.rag.RagStats
import dev.lattenero.brain.rag.RagStore
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RagQueryTest {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var brain: Brain

    @BeforeAll
    fun up() {
        TestEnv.needPostgres()
        val config = TestDatabase.config("ragquery")
        brain = Brain(config, FakeProvider(config.embedDim, FakeProvider.Mode.BOW))
        kotlinx.coroutines.runBlocking {
            brain.migrator.apply()
            val docs = mapOf(
                "/c/redpanda.md" to "Redpanda\nRedpanda is a Kafka-compatible broker; the bridge produces keyed by session id.",
                "/c/hnsw.md" to "HNSW\nAn HNSW index answers cosine nearest-neighbour queries over pgvector columns.",
            )
            for ((uri, text) in docs) {
                val id = (brain.ragStore.upsertDocument("t", uri, uri.substringAfterLast('/'), text, Corpus.sha256(text)) as RagStore.Upsert.Changed).docId
                val chunks = Chunker().chunk(uri.substringAfterLast('/'), text)
                val vectors = brain.provider.embed(chunks.map { it.content })
                brain.ragStore.replaceChunks(id, brain.provider.model, chunks.zip(vectors) { c, v -> RagStore.ChunkRow(c.ordinal, c.heading, c.content, v) })
            }
        }
    }

    @Test
    fun `a query returns ranked hits with citations`() = testApplication {
        application { brainModule(brain.config, brain.readiness, brain.bus, brain.rag) }
        val response = client.post("/rag/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"query": "kafka compatible broker keyed by session", "k": 2}""")
        }
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val body = json.decodeFromString<QueryResponse>(response.bodyAsText())
        assertThat(body.embedModel).isEqualTo("fake-bow")
        assertThat(body.hits).hasSize(2)
        assertThat(body.hits.first().uri).isEqualTo("/c/redpanda.md")
        assertThat(body.citations.first().title).isEqualTo("redpanda.md")
        assertThat(body.citations.first().heading).isEqualTo("redpanda.md")
        assertThat(body.citations.first().score).isGreaterThan(0.0)
    }

    @Test
    fun `bad requests are 400 with a reason`() = testApplication {
        application { brainModule(brain.config, brain.readiness, brain.bus, brain.rag) }
        val empty = client.post("/rag/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"query": "   "}""")
        }
        assertThat(empty.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(empty.bodyAsText()).contains("must not be empty")
        val tooMany = client.post("/rag/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"query": "x", "k": 500}""")
        }
        assertThat(tooMany.status).isEqualTo(HttpStatusCode.BadRequest)
        val garbage = client.post("/rag/query") {
            contentType(ContentType.Application.Json)
            setBody("""{"nope": 1}""")
        }
        assertThat(garbage.status).isEqualTo(HttpStatusCode.BadRequest)
    }

    @Test
    fun `stats report the indexed corpus`() = testApplication {
        application { brainModule(brain.config, brain.readiness, brain.bus, brain.rag) }
        val stats = json.decodeFromString<RagStats>(client.get("/rag/stats").bodyAsText())
        assertThat(stats.documents).isEqualTo(2)
        assertThat(stats.chunks).isGreaterThanOrEqualTo(2)
        assertThat(stats.bySource).containsEntry("t", 2L)
    }
}
