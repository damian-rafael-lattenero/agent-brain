package dev.lattenero.brain.http

import dev.lattenero.brain.Brain
import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.chat.FakeChatProvider
import dev.lattenero.brain.embeddings.FakeProvider
import dev.lattenero.brain.memory.Memory
import dev.lattenero.brain.memory.MemorySearchResponse
import dev.lattenero.brain.memory.MemoryStatus
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryHttpTest {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var brain: Brain

    @BeforeAll
    fun up() {
        TestEnv.needPostgres()
        val config = TestDatabase.config("memhttp")
        brain = Brain(config, FakeProvider(config.embedDim, FakeProvider.Mode.BOW), FakeChatProvider())
        kotlinx.coroutines.runBlocking { brain.migrator.apply() }
    }

    @Test
    fun `save then search then recent then status, with validation`() = testApplication {
        application { brainModule(brain.config, brain.readiness, brain.bus, brain.rag, brain.indexer, brain.memory) }

        val created = client.post("/memory/save") {
            contentType(ContentType.Application.Json)
            setBody("""{"kind": "preference", "content": "The user wants Telegram replies in Spanish, concise."}""")
        }
        assertThat(created.status).isEqualTo(HttpStatusCode.Created)
        val saved = json.decodeFromString<Memory>(created.bodyAsText())
        assertThat(saved.kind).isEqualTo("preference")

        val badKind = client.post("/memory/save") {
            contentType(ContentType.Application.Json)
            setBody("""{"kind": "rumour", "content": "long enough content"}""")
        }
        assertThat(badKind.status).isEqualTo(HttpStatusCode.BadRequest)

        val search = client.post("/memory/search") {
            contentType(ContentType.Application.Json)
            setBody("""{"query": "spanish concise telegram", "k": 3}""")
        }
        assertThat(search.status).isEqualTo(HttpStatusCode.OK)
        val found = json.decodeFromString<MemorySearchResponse>(search.bodyAsText())
        assertThat(found.memories.first().id).isEqualTo(saved.id)
        assertThat(found.memories.first().score).isGreaterThan(0.0)

        val empty = client.post("/memory/search") {
            contentType(ContentType.Application.Json)
            setBody("""{"query": " "}""")
        }
        assertThat(empty.status).isEqualTo(HttpStatusCode.BadRequest)

        val recent = json.decodeFromString(ListSerializer(Memory.serializer()), client.get("/memory/recent?limit=2&kind=preference").bodyAsText())
        assertThat(recent.map { it.id }).contains(saved.id)
        assertThat(client.get("/memory/recent?kind=rumour").status).isEqualTo(HttpStatusCode.BadRequest)

        val status = json.decodeFromString<MemoryStatus>(client.get("/memory/status").bodyAsText())
        assertThat(status.memories).isGreaterThanOrEqualTo(1)
        assertThat(status.chatModel).isEqualTo("fake-chat")
        assertThat(status.breaker).isEqualTo("Closed")
        assertThat(client.get("/memory/quarantine").status).isEqualTo(HttpStatusCode.OK)
    }
}
