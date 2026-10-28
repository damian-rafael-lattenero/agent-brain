package dev.lattenero.brain.memory

import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.chat.OllamaChatProvider
import dev.lattenero.brain.http.HttpClients
import dev.lattenero.brain.rag.CountingTracer
import kap.evalGraph
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.seconds

/** The real small model under structured outputs: valid shape always, and the obvious facts of a clear turn land. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryExtractionOllamaIT {
    private val client = HttpClients.create(180)

    @BeforeAll
    fun up() = TestEnv.needOllama()

    @AfterAll
    fun down() = client.close()

    @Test
    fun `a clear turn yields a valid extraction that mentions what was said`() = runBlocking<Unit> {
        val chat = OllamaChatProvider(client, TestEnv.base.ollamaBaseUrl, TestEnv.base.chatModel)
        val extractor = MemoryExtractor(chat, 170.seconds, CountingTracer(), attempts = 2)
        val transcript = Transcript(
            "ev", "ses", "1", "bot",
            prompt = "For agent-brain use Kotlin, not Python. I always want Amazon Corretto JDKs installed via brew casks, never openjdk. " +
                "And from now on never push without my explicit ok.",
            reply = "Understood: the core will be Kotlin on Corretto 25, I removed the Python files, and I will only commit, never push, until you say so.",
            tools = listOf("Bash", "Write"),
            title = "agent-brain",
        )
        val extraction = extractor.extract(transcript).evalGraph()
        val all = (extraction.items.map { it.second }).joinToString(" ").lowercase()
        println("extraction (${chat.model}): ${extraction.items}")
        assertThat(extraction.items).isNotEmpty
        assertThat(extraction.summary).isNotBlank
        assertThat(all).containsAnyOf("kotlin", "corretto", "push")
        assertThat(extraction.promptTokens).isNotNull
    }
}
