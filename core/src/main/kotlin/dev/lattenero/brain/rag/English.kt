// lang-ok: detector input — the Spanish stopword list the English boundary needs verbatim.
package dev.lattenero.brain.rag

/**
 * The language of a text, told by its stopwords: ONE rule for the English
 * corpus (the owner's order of 26/11 — the RAG is English at its input, in its
 * store and at its output). A text is FOREIGN when its Spanish stopwords reach
 * a floor and outnumber its English ones; everything else stands as it is:
 * English prose, and the code, config and data that carry too few stopwords of
 * either language to tell.
 *
 * Two floors, because a document and a question are not the same size. Eight
 * Spanish stopwords for a document — measured 26/11 on the live store (2,395
 * documents): every one of its 33 Spanish documents clears it, while the 42
 * code files that carry the bot's own Spanish words stay under it or
 * outnumbered. One for a question: a six-word question of the owner's carries
 * four. The lists are the twin of the fleet's inventory script
 * (rag-language-inventory.nu): a word added here is added there.
 */
object English {

    /** How many stopwords of each language a text carries. */
    data class Count(val spanish: Int, val english: Int) {
        /** Foreign when the Spanish count reaches [floor] and passes the English one. */
        fun foreign(floor: Int): Boolean = spanish >= floor && spanish > english
    }

    const val DOCUMENT_FLOOR = 8
    const val QUESTION_FLOOR = 1

    private val SPANISH_WORDS = setOf(
        "que", "del", "las", "los", "para", "con", "por", "una", "como", "mas", "más", "pero", "sus",
        "cuando", "muy", "sin", "sobre", "también", "hasta", "hay", "donde", "desde", "durante",
        "todos", "contra", "otros", "ese", "eso", "este", "esto", "antes", "algunos", "unos", "otro",
        "otra", "tanto", "esa", "estos", "mucho", "nada", "muchos", "cual", "poco", "algo", "esta",
        "cómo", "qué", "será", "sera", "fueron", "tiene", "tienen", "puede", "pueden", "debe",
        "deben", "cada", "entre", "hacia", "según", "segun", "así", "asi", "entonces", "porque",
        "aunque", "luego", "siempre", "nunca", "aquí", "aqui",
    )

    private val ENGLISH_WORDS = setOf(
        "the", "and", "for", "with", "that", "this", "from", "are", "not", "you", "have", "has", "was",
        "were", "will", "which", "their", "would", "about", "there", "when", "what", "where", "then",
        "them", "these", "those", "can", "into", "only", "also", "other", "than", "your", "should",
        "because", "each", "such", "some", "more", "most", "over", "under", "after", "before",
        "between", "through", "does", "been", "being",
    )

    /** A word is a maximal run of letters: punctuation, digits and symbols all cut. */
    private val NOT_A_LETTER = Regex("[^\\p{L}]+")

    fun count(text: String): Count {
        var spanish = 0
        var english = 0
        for (word in NOT_A_LETTER.split(text.lowercase())) {
            when (word) {
                in SPANISH_WORDS -> spanish += 1
                in ENGLISH_WORDS -> english += 1
            }
        }
        return Count(spanish, english)
    }

    /** A document that is not English: it stays out of the store (the door of the index). */
    fun foreignDocument(text: String): Boolean = count(text).foreign(DOCUMENT_FLOOR)

    /** A question asked in another language: it reaches the store only as its English restatement. */
    fun foreignQuestion(text: String): Boolean = count(text).foreign(QUESTION_FLOOR)
}
