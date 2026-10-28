package dev.lattenero.brain.rag

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ChunkerTest {
    private val chunker = Chunker(targetChars = 400, overlapChars = 60)

    @Test
    fun `empty and blank input give no chunks`() {
        assertThat(chunker.chunk("Doc", "")).isEmpty()
        assertThat(chunker.chunk("Doc", "  \n\n ")).isEmpty()
    }

    @Test
    fun `text without headings lives under the title`() {
        val chunks = chunker.chunk("Readme", "just a paragraph\n\nand another")
        assertThat(chunks).hasSize(1)
        assertThat(chunks[0].heading).isEqualTo("Readme")
        assertThat(chunks[0].content).startsWith("Readme\n").contains("just a paragraph").contains("and another")
        assertThat(chunks[0].ordinal).isZero
    }

    @Test
    fun `headings build a chain and the intro keeps the title`() {
        val text = """
            intro line
            # Guide
            guide intro
            ## Quickstart
            run make up
            ### Details
            fine print
            ## Concepts
            phases and laws
            # Reference
            api table
        """.trimIndent()
        val sections = chunker.sections("KAP", text)
        assertThat(sections.map { it.heading }).containsExactly(
            "KAP", "KAP > Guide", "KAP > Guide > Quickstart", "KAP > Guide > Quickstart > Details",
            "KAP > Guide > Concepts", "KAP > Reference",
        )
        assertThat(sections.map { it.text }).containsExactly(
            "intro line", "guide intro", "run make up", "fine print", "phases and laws", "api table",
        )
    }

    @Test
    fun `a hash inside a fenced code block is not a heading`() {
        val text = "# Real\n```bash\n# not a heading\necho hi\n```\nafter the fence"
        val chunks = Chunker(targetChars = 200, overlapChars = 0).chunk("T", text)
        assertThat(chunks).hasSize(1)
        assertThat(chunks[0].heading).isEqualTo("T > Real")
        assertThat(chunks[0].content).contains("# not a heading").contains("after the fence")
    }

    @Test
    fun `small sections are packed together up to the target`() {
        val text = (1..6).joinToString("\n") { "## S$it\nshort body $it" }
        val chunks = chunker.chunk("T", text)
        assertThat(chunks).hasSize(1)
        assertThat(chunks[0].content).contains("short body 1").contains("short body 6")
        assertThat(chunks[0].heading).isEqualTo("T > S1")
    }

    @Test
    fun `an oversized paragraph is split into overlapping windows and nothing exceeds the bound`() {
        val words = (1..300).joinToString(" ") { "w$it" }
        val chunks = chunker.chunk("T", words)
        assertThat(chunks.size).isGreaterThan(1)
        chunks.forEach { assertThat(it.chars).isLessThanOrEqualTo(400 + 60 + "T\n".length + Chunker.SEPARATOR.length) }
        assertThat(chunks.map { it.ordinal }).isEqualTo(chunks.indices.toList())
        val joined = chunks.joinToString(" ") { it.content }
        assertThat(joined).contains("w1 ").contains("w300")
        // consecutive windows share text
        val tailOfFirst = chunks[0].content.takeLast(20)
        assertThat(chunks[1].content).contains(tailOfFirst.substringAfter(" "))
    }

    @Test
    fun `a new chunk starts with the tail of the previous one, cut at whitespace`() {
        val a = "## A\n" + (1..30).joinToString(" ") { "alpha$it" }
        val b = "## B\n" + (1..30).joinToString(" ") { "beta$it" }
        val chunks = chunker.chunk("T", "$a\n$b")
        assertThat(chunks).hasSize(2)
        val overlap = chunks[1].content.removePrefix(chunks[1].heading + "\n").substringBefore(Chunker.SEPARATOR)
        assertThat(overlap).startsWith("alpha").doesNotStartWith(" ")
        assertThat(chunks[0].content).endsWith(overlap)
    }

    @Test
    fun `deterministic and parameter-checked`() {
        val text = "# A\n" + "x ".repeat(500) + "\n# B\ny"
        assertThat(chunker.chunk("T", text)).isEqualTo(chunker.chunk("T", text))
        assertThatThrownBy { Chunker(targetChars = 10) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { Chunker(targetChars = 400, overlapChars = 300) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
