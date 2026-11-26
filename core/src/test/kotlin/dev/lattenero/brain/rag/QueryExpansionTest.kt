package dev.lattenero.brain.rag

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The expansion laws: for an English question the second leg is added, never substituted and
 * never invented; a question in another language is searched only as its English restatement.
 */
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

    /** The English-input law: which text the store is searched with, and what is added to it. */
    @Test
    fun `an English question is searched as asked and the restatement adds a leg`() {
        val question = "how do I dispatch on data without if-else chains"
        val legs = QueryExpansion.legs(question, "dispatch data branches")
        assertThat(legs.primary).isEqualTo(question)
        assertThat(legs.extras).containsExactly("dispatch data branches", "mode table predicate table dispatch data priority")
    }

    @Test
    fun `a question in Spanish is searched only as its English restatement`() {
        val legs = QueryExpansion.legs("qué es una mónada y para qué sirve", "monad definition purpose")
        assertThat(legs.primary).isEqualTo("monad definition purpose")
        assertThat(legs.extras).isEmpty()
    }

    @Test
    fun `with no restatement a foreign question stands as asked`() {
        val question = "qué es una mónada y para qué sirve"
        assertThat(QueryExpansion.legs(question, null)).isEqualTo(QueryExpansion.Legs(question, emptyList()))
    }
}
