package dev.lattenero.brain.rag

/**
 * The lighthouse's source rule (plan v2.0 U1): the `*-kb/notes/` trees are the
 * curated layer — one hand-written note per concept — and the manuals they
 * cite are supporting cast. Measured on the live store (26/11): on concept
 * questions the mirrors (Milewski, LYAH, the Typeclassopedia, wiwinwlh) and
 * repo-index noise outranked the notes in 16 of 45 English cases, so the
 * fusion gives a curated chunk one extra first-place vote ([Rrf.CURATED_VOTE]).
 * The SQL twin repeats the literal in `Rag.sq` / `RagStore.SEARCH`.
 */
object Curated {
    /** The path mark every curated notes tree carries (`haskell-kb/notes/`, `fp-kb/notes/`, …). */
    const val PATH_MARK = "-kb/notes/"

    /** The SQL LIKE pattern with the same meaning as [PATH_MARK]. */
    const val LIKE = "%-kb/notes/%"

    fun isCurated(uri: String): Boolean = PATH_MARK in uri
}
