package dev.lattenero.brain.rag

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The English-corpus law's one rule, on the shapes measured in the live store on 26/11. */
class EnglishTest {

    @Test
    fun `a Spanish document is foreign and an English one is not`() {
        val spanish = "El owner quiere que todo el código se escriba de una sola forma y sin errores, " +
            "desde el inicio hasta el final, para que nadie tenga que adivinar nada."
        val counted = English.count(spanish)
        assertThat(counted.spanish).isGreaterThanOrEqualTo(English.DOCUMENT_FLOOR)
        assertThat(counted.english).isZero()
        assertThat(English.foreignDocument(spanish)).isTrue()

        val english = "The owner wants every piece of code written one way and without errors, " +
            "from the start to the end, so that nobody has to guess anything."
        assertThat(English.foreignDocument(english)).isFalse()
        assertThat(English.foreignQuestion(english)).isFalse()
    }

    @Test
    fun `a note that quotes Spanish passes while its English outweighs the quote`() {
        val mixed = "# The critical challenge protocol\n\n" +
            "The owner's prompt, kept verbatim: \"No me des soluciones para nada: preguntame de a una " +
            "hasta que podamos nombrar la medición que mataría o confirmaría la idea, porque si no " +
            "hay objeciones reales entonces la idea sigue como está.\"\n\n" +
            "The protocol separates the challenger from the generator, because the model that " +
            "produced the idea shares its blind spots; the critique must come from a different model."
        val counted = English.count(mixed)
        assertThat(counted.spanish).isGreaterThanOrEqualTo(English.DOCUMENT_FLOOR)
        assertThat(counted.english).isGreaterThan(counted.spanish)
        assertThat(English.foreignDocument(mixed)).isFalse()
    }

    @Test
    fun `code that carries the bot's own Spanish words is not a Spanish document`() {
        val kotlin = """
            // The bot answers the owner in Spanish: the words are data, the code is not.
            val ready = "listo para continuar"
            fun ask(): String = "¿en qué te ayudo?"
        """.trimIndent()
        assertThat(English.count(kotlin).spanish).isLessThan(English.DOCUMENT_FLOOR)
        assertThat(English.foreignDocument(kotlin)).isFalse()
    }

    @Test
    fun `a question is told by a single Spanish stopword`() {
        assertThat(English.foreignQuestion("qué es una mónada y para qué sirve")).isTrue()
        assertThat(English.foreignQuestion("por que chat_timeout_s no funcionaba")).isTrue()
        assertThat(English.foreignQuestion("what does Monad denote and which laws must it keep")).isFalse()
        assertThat(English.foreignQuestion("kap Validated error accumulation traverse")).isFalse()
    }

    @Test
    fun `a bare word, an identifier or nothing is not a language`() {
        assertThat(English.foreignQuestion("mónada")).isFalse()
        assertThat(English.foreignQuestion("traverseSettled")).isFalse()
        assertThat(English.foreignQuestion("")).isFalse()
        assertThat(English.foreignDocument("")).isFalse()
        assertThat(English.count("")).isEqualTo(English.Count(spanish = 0, english = 0))
    }
}
