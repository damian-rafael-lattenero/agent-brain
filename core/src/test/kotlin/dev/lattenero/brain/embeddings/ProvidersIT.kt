package dev.lattenero.brain.embeddings

import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.http.HttpClients
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OllamaProviderIT : EmbeddingProviderContract() {
    private val client = HttpClients.create(TestEnv.base.httpTimeoutS)

    @BeforeAll
    fun up() = TestEnv.needOllama()

    @AfterAll
    fun down() = client.close()

    override fun provider(): EmbeddingProvider = EmbeddingProviders.create(TestEnv.config(provider = "ollama"), client)

    @Test
    fun `the real model places a paraphrase closer than an unrelated sentence`() = runTest {
        val (query, related, unrelated) = provider().embed(
            listOf(
                "how do I configure a Kafka consumer group",
                "setting up consumer groups in Apache Kafka",
                "my grandmother's recipe for lemon pie",
            ),
        )
        assertThat(cosine(query, related)).isGreaterThan(cosine(query, unrelated))
    }
}

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GlmProviderIT : EmbeddingProviderContract() {
    private val client = HttpClients.create(TestEnv.base.httpTimeoutS)

    @BeforeAll
    fun up() = TestEnv.needGlmKey()

    @AfterAll
    fun down() = client.close()

    // GLM embedding-3 accepts 256/512/1024/2048; 1024 keeps the test independent of the 768 default.
    override fun provider(): EmbeddingProvider =
        EmbeddingProviders.create(TestEnv.config(provider = "glm", extra = mapOf("AB_EMBED_DIM" to "1024")), client)
}
