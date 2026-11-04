package dev.lattenero.brain.eval

import dev.lattenero.brain.memory.MemoryExtractor
import dev.lattenero.brain.rag.Hit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EvalAssertionsTest {
    private fun hit(uri: String, content: String) =
        Hit(chunkId = 1, docId = 1, uri = uri, title = "t", heading = "h", ordinal = 0, content = content, score = 1.0, vectorRank = 1, textRank = null)

    @Test
    fun `normalization collapses case and whitespace`() {
        assertThat(EvalAssertions.normalize("  The   Bus\nTailer ")).isEqualTo("the bus tailer")
        assertThat(EvalAssertions.supports("The lock is 727001.", listOf("727001"))).isTrue()
        assertThat(EvalAssertions.supports("The lock is 727001.", listOf("nope", "727001"))).isTrue()
        assertThat(EvalAssertions.supports("nothing here", listOf("727001"))).isFalse()
        assertThat(EvalAssertions.supports("anything", emptyList())).isFalse()
    }

    @Test
    fun `the extraction assertion accepts a parse with the expected content`() {
        val outcome = MemoryExtractor.parseBins(
            """{"facts":["Postgres runs on port 5433"],"decisions":[],"preferences":[],"summary":"ports"}""",
        )
        val check = EvalAssertions.extraction(ExtractionExpect(factsAny = listOf("5433")), outcome)
        assertThat(check.ok).isTrue()
        assertThat(check.facts).isEqualTo(1)
        assertThat(check.failure).isNull()
    }

    @Test
    fun `the extraction assertion fails with the parser's reason when the model did not answer JSON`() {
        val check = EvalAssertions.extraction(ExtractionExpect(), MemoryExtractor.parseBins("not json at all"))
        assertThat(check.ok).isFalse()
        assertThat(check.failure).contains("invalid extraction")
    }

    @Test
    fun `the extraction assertion fails when a group misses its needle or the summary is empty`() {
        val outcome = MemoryExtractor.parseBins(
            """{"facts":["The user likes tea"],"decisions":[],"preferences":[],"summary":""}""",
        )
        val missing = EvalAssertions.extraction(ExtractionExpect(decisionsAny = listOf("kapstream")), outcome)
        assertThat(missing.ok).isFalse()
        assertThat(missing.failure).contains("decisions has none of")

        val noSummary = EvalAssertions.extraction(ExtractionExpect(factsAny = listOf("tea"), summaryRequired = true), outcome)
        assertThat(noSummary.ok).isFalse()
        assertThat(noSummary.failure).contains("summary is empty")

        val noSummaryNeeded = EvalAssertions.extraction(ExtractionExpect(factsAny = listOf("tea"), summaryRequired = false), outcome)
        assertThat(noSummaryNeeded.ok).isTrue()
    }

    @Test
    fun `an empty expectation accepts a quiet turn and rejects invented memories`() {
        val quiet = MemoryExtractor.parseBins("""{"facts":[],"decisions":[],"preferences":[],"summary":"a greeting"}""")
        assertThat(EvalAssertions.extraction(ExtractionExpect(empty = true), quiet).ok).isTrue()

        val invented = MemoryExtractor.parseBins(
            """{"facts":["The user is greeting the agent"],"decisions":[],"preferences":[],"summary":"a greeting"}""",
        )
        val check = EvalAssertions.extraction(ExtractionExpect(empty = true), invented)
        assertThat(check.ok).isFalse()
        assertThat(check.failure).contains("expected no durable items")
    }

    @Test
    fun `grounded passes only with a cited expected document and an answer that states the fact`() {
        val case = GroundedCase(id = "g", question = "which lock?", uri = "/a.md", needles = listOf("727001"))
        val hits = listOf(hit("/a.md", "lock 727001"), hit("/b.md", "something else"))

        val good = EvalAssertions.grounded(case, hits, GroundedAnswer("the lock is 727001", listOf(1)))
        assertThat(good.ok).isTrue()
        assertThat(good.citedExpected).isTrue()
        assertThat(good.citedUris).containsExactly("/a.md")

        val wrongCitation = EvalAssertions.grounded(case, hits, GroundedAnswer("the lock is 727001", listOf(2)))
        assertThat(wrongCitation.ok).isFalse()
        assertThat(wrongCitation.citedExpected).isFalse()
        assertThat(wrongCitation.failure).contains("cited")

        val wrongAnswer = EvalAssertions.grounded(case, hits, GroundedAnswer("the lock is 42", listOf(1)))
        assertThat(wrongAnswer.ok).isFalse()
        assertThat(wrongAnswer.answerSupports).isFalse()
        assertThat(wrongAnswer.failure).contains("does not state")

        val outOfRange = EvalAssertions.grounded(case, hits, GroundedAnswer("the lock is 727001", listOf(9)))
        assertThat(outOfRange.ok).isFalse()
        assertThat(outOfRange.citedUris).isEmpty()

        val unparseable = EvalAssertions.grounded(case, hits, null)
        assertThat(unparseable.ok).isFalse()
        assertThat(unparseable.failure).contains("not JSON")
    }

    @Test
    fun `a grounded answer parses only as the declared JSON object`() {
        assertThat(EvalAssertions.parseGrounded("""{"answer":"x","citations":[1]}"""))
            .isEqualTo(GroundedAnswer("x", listOf(1)))
        assertThat(EvalAssertions.parseGrounded("```json\n{\"answer\":\"x\",\"citations\":[1]}\n```")).isNull()
        assertThat(EvalAssertions.parseGrounded("plain text")).isNull()
    }
}
