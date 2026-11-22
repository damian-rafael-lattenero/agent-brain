package dev.lattenero.brain.rag

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The expansion laws: the second leg is added, never substituted, and never invented. */
class QueryExpansionTest {
    @Test
    fun `a valid restatement is the second leg`() {
        assertThat(QueryExpansion.parse("""{"query":"chat timeout ownership client"}""", "por que chat_timeout_s no funcionaba"))
            .isEqualTo("chat timeout ownership client")
    }

    @Test
    fun `an English query restating itself adds no leg`() {
        assertThat(QueryExpansion.parse("""{"query":"Where is the lock?"}""", "where is the lock")).isNull()
        assertThat(QueryExpansion.parse("""{"query":"  WHERE   is the lock "}""", "where is the lock")).isNull()
    }

    @Test
    fun `blank, missing or unparsable answers never invent a leg`() {
        assertThat(QueryExpansion.parse("""{"query":""}""", "alguna pregunta")).isNull()
        assertThat(QueryExpansion.parse("""{}""", "alguna pregunta")).isNull()
        assertThat(QueryExpansion.parse("not json at all", "alguna pregunta")).isNull()
        assertThat(QueryExpansion.parse("""{"query":"   "}""", "alguna pregunta")).isNull()
    }

    @Test
    fun `the restatement keeps identifiers verbatim`() {
        val restated = QueryExpansion.parse("""{"query":"kap traverseSettled Either error accumulation"}""", "kap traverseSettled acumulacion de errores")
        assertThat(restated).contains("traverseSettled").contains("Either")
    }

    /** The synonym laws: triggers fire case-insensitively, non-triggers add nothing, several triggers combine. */
    @Test
    fun `synonyms map a paraphrase to the law's canonical vocabulary`() {
        assertThat(QueryExpansion.synonyms("how do I dispatch on data without if-else chains"))
            .contains("mode table").contains("predicate table")
        assertThat(QueryExpansion.synonyms("How should Validation Errors ACCUMULATE across a list"))
            .contains("validated").contains("accumulation")
    }

    @Test
    fun `synonyms never fire without a trigger word`() {
        assertThat(QueryExpansion.synonyms("denotational ladder design method")).isNull()
        assertThat(QueryExpansion.synonyms("how to bake sourdough bread at home")).isNull()
    }

    @Test
    fun `synonyms combine when several triggers fire`() {
        val combined = QueryExpansion.synonyms("dispatch the nullable accumulate path")
        assertThat(combined).contains("mode table").contains("validated").contains("option maybe")
    }
}
