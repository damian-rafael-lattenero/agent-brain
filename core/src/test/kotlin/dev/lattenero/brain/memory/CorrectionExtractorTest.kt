package dev.lattenero.brain.memory

import arrow.core.Either
import dev.lattenero.brain.chat.ChatProvider
import dev.lattenero.brain.chat.ChatResult
import dev.lattenero.brain.chat.FakeChatProvider
import dev.lattenero.brain.rag.CountingTracer
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kap.CircuitBreaker
import kap.evalGraph
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The correction extractor's pins (plan U8): blank input decides BEFORE the
 * model call, the parse is TOTAL (a rambling judge moves a correction between
 * the two bins, never throws), the schema's JSON face is the happy path, the
 * candidate is ONE sentence under the cap.
 */
class CorrectionExtractorTest {

    private fun extractor(chat: ChatProvider): CorrectionExtractor = CorrectionExtractor(
        chat = chat,
        timeout = 1.seconds,
        tracer = CountingTracer(),
        breaker = CircuitBreaker(maxFailures = 100, retryAfter = 1.minutes),
    )

    @Test
    fun `blank text is Invalid without spending a model call`() = runTest {
        val chat = FakeChatProvider { _, _ -> throw AssertionError("the blank gate must not reach the model") }
        for (blank in listOf("", "   ", "\n\t\n")) {
            val outcome = extractor(chat).extract(blank).evalGraph()
            assertThat(outcome.isLeft()).withFailMessage("blank '$blank' must be Invalid").isTrue
            assertThat((outcome as Either.Left).value.reason).contains("blank correction text")
        }
        assertThat(chat.calls).isEmpty()
    }

    @Test
    fun `the schema's JSON face yields the rule sentence`() = runTest {
        val chat = FakeChatProvider { _, _ ->
            """{"rule": "Always write code comments in English."}"""
        }
        val outcome = extractor(chat).extract("no spanish in the code").evalGraph()
        assertThat(chat.calls).hasSize(1)
        assertThat(chat.calls.single().second).contains("no spanish in the code")
        assertThat((outcome as Either.Right).value)
            .isEqualTo("Always write code comments in English.")
    }

    @Test
    fun `a rambling judge still yields one sentence`() = runTest {
        val rambling = "Always commit before pushing. You should also run the tests. And ask before rewriting. " +
            "The reason is that the owner wants checkpoints and small verifiable units of work."
        val outcome = CorrectionExtractor.parse(ChatResult(rambling))
        assertThat((outcome as Either.Right).value).isEqualTo("Always commit before pushing.")
    }

    @Test
    fun `a rambling rule FIELD also yields one sentence`() {
        val outcome = CorrectionExtractor.parse(
            ChatResult("""{"rule": "Commit often. Push together with the commit. Never force."}"""),
        )
        assertThat((outcome as Either.Right).value).isEqualTo("Commit often.")
    }

    @Test
    fun `JSON garbage without a sentence is Invalid with the raw kept as evidence`() {
        for (garbage in listOf("""{"count": 42}""", """[1, 2, 3]""", """{"rule": 7}""", "{ this is not json")) {
            val outcome = CorrectionExtractor.parse(ChatResult(garbage))
            assertThat(outcome).withFailMessage(garbage).isInstanceOf(Either.Left::class.java)
            assertThat((outcome as Either.Left).value.reason).contains("JSON garbage")
            assertThat(outcome.value.raw).isEqualTo(garbage)
        }
    }

    @Test
    fun `a blank answer is Invalid with the reason`() {
        val outcome = CorrectionExtractor.parse(ChatResult("   \n  "))
        assertThat(outcome).isInstanceOf(Either.Left::class.java)
        assertThat((outcome as Either.Left).value.reason).contains("blank answer")
    }

    @Test
    fun `the candidate is capped at the memory item cap`() {
        val long = "x".repeat(CorrectionExtractor.MAX_CANDIDATE_CHARS + 500)
        val outcome = CorrectionExtractor.parse(ChatResult(long))
        assertThat((outcome as Either.Right).value).hasSize(CorrectionExtractor.MAX_CANDIDATE_CHARS)
    }

    /** LAW - totality: ANY judge string lands in exactly one bin, never throws. */
    @Test
    fun `law - the parse is total over any judge string`() = runTest {
        checkAll(Arb.string(0..400)) { raw ->
            val outcome: Either<ExtractionInvalid, String> = CorrectionExtractor.parse(ChatResult(raw))
            assertThat(outcome.isLeft() || (outcome is Either.Right && outcome.value.isNotBlank())).isTrue
        }
    }
}
