package dev.lattenero.brain.memory

import dev.lattenero.brain.chat.ChatProvider
import dev.lattenero.brain.chat.ChatResult
import dev.lattenero.brain.chat.FakeChatProvider
import dev.lattenero.brain.embeddings.ProviderUnavailable
import dev.lattenero.brain.rag.CountingTracer
import kap.CircuitBreaker
import kap.evalGraph
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class MemoryExtractorTest {
    private val transcript = Transcript("ev1", "ses_1", "17", "bot", "use Kotlin, never push without my ok", "understood", listOf("Bash"), "CV")

    private fun extractor(chat: ChatProvider, attempts: Int = 3) =
        MemoryExtractor(chat, 1.seconds, CountingTracer(), CircuitBreaker(100, 1.minutes), attempts, backoffBase = 10.seconds.div(100))

    @Test
    fun `a valid answer becomes typed items with the summary last`() = runTest {
        val chat = FakeChatProvider { _, _ ->
            FakeChatProvider.json(
                "facts" to FakeChatProvider.strings("The core is Kotlin on Corretto 25."),
                "decisions" to FakeChatProvider.strings("Commit only, never push without an explicit ok."),
                "preferences" to FakeChatProvider.strings("Prefers Amazon Corretto JDKs via brew casks."),
                "summary" to JsonPrimitive("Language and git policy were decided."),
            )
        }
        val extraction = extractor(chat).extract(transcript).evalGraph()
        assertThat(extraction.items).containsExactly(
            "fact" to "The core is Kotlin on Corretto 25.",
            "decision" to "Commit only, never push without an explicit ok.",
            "preference" to "Prefers Amazon Corretto JDKs via brew casks.",
            "summary" to "Language and git policy were decided.",
        )
        assertThat(chat.calls).hasSize(1)
        assertThat(chat.calls.single().second).contains("PROMPT:").contains("use Kotlin").contains("TOOLS: Bash").contains("TITLE: CV")
        assertThat(chat.calls.single().first).contains("durable memory")
    }

    @Test
    fun `invalid shapes are ExtractionInvalid with a reason and the raw text, never a crash`() {
        val cases = mapOf(
            "not json at all" to "not JSON",
            "[1, 2]" to "not a JSON object",
            """{"facts": "a string"}""" to "facts is not an array",
            """{"facts": [1, 2]}""" to "non-string",
            """{"facts": [], "summary": {"x": 1}}""" to "summary is not a string",
        )
        cases.forEach { (raw, reason) ->
            assertThatThrownBy { MemoryExtractor.parse(ChatResult(raw)) }
                .isInstanceOf(ExtractionInvalid::class.java)
                .hasMessageContaining(reason)
                .extracting("raw").isEqualTo(raw)
        }
    }

    @Test
    fun `caps and hygiene - short items dropped, long ones clipped, duplicates removed, ten per kind, empty lists fine`() {
        val many = (1..15).map { "Fact number $it that is long enough to keep." }
        val raw = FakeChatProvider.json(
            "facts" to FakeChatProvider.strings(*(many + listOf("short", "  ", many[0], "x".repeat(1000))).toTypedArray()),
            "decisions" to FakeChatProvider.strings(),
            "summary" to JsonPrimitive(" ".repeat(3)),
        )
        val extraction = MemoryExtractor.parse(ChatResult(raw))
        assertThat(extraction.facts).hasSize(MemoryExtractor.MAX_ITEMS_PER_KIND)
        assertThat(extraction.facts).doesNotContain("short")
        assertThat(extraction.facts.map { it.length }.max()).isLessThanOrEqualTo(MemoryExtractor.MAX_ITEM_CHARS)
        assertThat(extraction.decisions).isEmpty()
        assertThat(extraction.preferences).isEmpty()
        assertThat(extraction.summary).isEmpty()
        assertThat(extraction.items.map { it.first }).doesNotContain("summary")
    }

    @Test
    fun `transport failures are retried, invalid output is not`() = runTest {
        val calls = AtomicInteger()
        val flaky = object : ChatProvider {
            override val name = "flaky"
            override val model = "flaky-model"
            override suspend fun completeJson(system: String, user: String, schema: JsonObject): ChatResult {
                if (calls.incrementAndGet() < 3) throw ProviderUnavailable("down")
                return ChatResult("""{"facts": [], "decisions": [], "preferences": [], "summary": "fine now"}""")
            }
        }
        val extraction = extractor(flaky, attempts = 3).extract(transcript).evalGraph()
        assertThat(extraction.summary).isEqualTo("fine now")
        assertThat(calls.get()).isEqualTo(3)

        val garbage = FakeChatProvider { _, _ -> "nope" }
        assertThatThrownBy { kotlinx.coroutines.runBlocking { extractor(garbage).extract(transcript).evalGraph() } }
            .isInstanceOf(ExtractionInvalid::class.java)
        assertThat(garbage.calls).hasSize(1)
    }

    @Test
    fun `the transcript renders both producers and caps long texts`() {
        val hook = Transcript("e", "s", "t", "hook", "p".repeat(10_000), "r", emptyList())
        val rendered = hook.render(100)
        assertThat(rendered).startsWith("PROMPT:\n").contains("REPLY:\nr").contains("TOOLS: none")
        assertThat(rendered.length).isLessThan(300)
        assertThat(Transcript("e", "s", "t", "bot", " ", "", emptyList()).isEmpty).isTrue
    }
}
