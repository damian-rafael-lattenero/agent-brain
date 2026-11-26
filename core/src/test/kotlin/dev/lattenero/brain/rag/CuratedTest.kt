package dev.lattenero.brain.rag

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/** The curated-source rule ([Curated]) and its SQL twin literal (`RagStore.SEARCH`). */
class CuratedTest {

    @Test
    fun `a notes tree is curated - manuals, source and docs are not`() {
        assertThat(Curated.isCurated("/Users/x/haskell-kb/notes/concepts/monad.md")).isTrue
        assertThat(Curated.isCurated("/Users/x/theory-kb/notes/category-theory/functor.md")).isTrue
        assertThat(Curated.isCurated("/Users/x/fp-kb/notes/minimal-code-law.md")).isTrue
        assertThat(Curated.isCurated("/Users/x/nushell-kb/notes/functions.md")).isTrue
        assertThat(Curated.isCurated("/Users/x/kotlin-kb/notes/idioms-and-pitfalls.md")).isTrue

        assertThat(Curated.isCurated("/Users/x/haskell-kb/official/lyah/source_md/chapters.md")).isFalse
        assertThat(Curated.isCurated("/Users/x/theory-kb/official/milewski-ctfp/english/part1/ch01.md")).isFalse
        assertThat(Curated.isCurated("/Users/x/kap/LAWS.md")).isFalse
        assertThat(Curated.isCurated("/Users/x/agent-brain/docs/book/00-route-sheet.md")).isFalse
        assertThat(Curated.isCurated("/Users/x/zcode-bot-kotlin/docs/runbook.md")).isFalse
    }

    @Test
    fun `the sql twin carries the same literal - the vote and the like pattern`() {
        assertThat(Rrf.CURATED_VOTE).isCloseTo(1.0 / (Rrf.K + 1), within(0.0))
        assertThat(Curated.LIKE).isEqualTo("%-kb/notes/%")
        assertThat(RagStore.SEARCH).contains(Curated.LIKE)
        assertThat(RagStore.SEARCH).contains(Rrf.CURATED_VOTE.toString())
    }
}
