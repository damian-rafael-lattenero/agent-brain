package dev.lattenero.brain.rag

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The laws of the listwise rerank stage: the model may speak, but it may
 * only RE-ORDER — [Rerank.apply]'s output is always a permutation of its
 * input, and a verdict that names nothing (or lies) leaves the fused order
 * standing. The eval's graded columns (B1) decide whether the stage helps;
 * these laws pin that it can never hurt by losing a hit.
 */
class RerankLawsTest {

    @Test
    fun `a valid verdict reorders without losing or duplicating a hit`() {
        val hits = listOf("a", "b", "c", "d")
        assertThat(Rerank.apply(listOf(3, 1, 4, 2), hits)).containsExactly("c", "a", "d", "b")
    }

    @Test
    fun `a partial verdict keeps the unnamed hits in their fused order after the prefix`() {
        val hits = listOf("a", "b", "c", "d")
        assertThat(Rerank.apply(listOf(4), hits)).containsExactly("d", "a", "b", "c")
    }

    @Test
    fun `out of range, zero, negative and repeated indices are ignored`() {
        val hits = listOf("a", "b", "c")
        assertThat(Rerank.apply(listOf(0, 9, -1, 2, 2, 3), hits)).containsExactly("b", "c", "a")
    }

    @Test
    fun `a null or empty verdict is the identity — the fused order stands`() {
        val hits = listOf("a", "b", "c")
        assertThat(Rerank.apply(null, hits)).isSameAs(hits)
        assertThat(Rerank.apply(emptyList(), hits)).isSameAs(hits)
    }

    @Test
    fun `the verdict's first valid name is the output's first element`() {
        val hits = listOf("a", "b", "c", "d", "e")
        assertThat(Rerank.apply(listOf(99, 5, 1), hits)).containsExactly("e", "a", "b", "c", "d")
    }

    @Test
    fun `the parse reads the order array and nothing else`() {
        assertThat(Rerank.parseVerdict("""{"order": [3, 1, 2]}""")).containsExactly(3, 1, 2)
        assertThat(Rerank.parseVerdict("""{"order": ["x", 1]}""")).containsExactly(1)
        assertThat(Rerank.parseVerdict("""{"answer": "no order here"}""")).isNull()
        assertThat(Rerank.parseVerdict("not json at all")).isNull()
        assertThat(Rerank.parseVerdict("{}")).isNull()
    }

    @Test
    fun `the user prompt numbers the snippets and caps their length`() {
        val hit = Hit(1, 1, "u", "t", "h", 0, "x".repeat(1000), 0.0, null, null)
        val prompt = Rerank.userPrompt("why?", listOf(hit))
        assertThat(prompt).contains("QUESTION: why?")
        assertThat(prompt).contains("1. ")
        val snippet = prompt.lineSequence().first { it.startsWith("1. ") }
        assertThat(snippet.length).isLessThanOrEqualTo("1. ".length + Rerank.SNIPPET_CHARS)
    }
}
