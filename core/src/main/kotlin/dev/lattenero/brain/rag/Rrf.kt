package dev.lattenero.brain.rag

/**
 * Reciprocal Rank Fusion with k = [K] (Cormack et al. 2009) — the pure core
 * of the hybrid retrieval that [RagStore] and `MemoryStore` run in SQL. Each
 * retrieval leg (vector, full-text) orders candidates best-first; an id's
 * rank in a leg is its 1-based position there; the fused score of an id is
 * the sum over the legs that recall it of `1 / (K + rank)`.
 *
 * The formula is small, total and side-effect-free, and it lives in TWO
 * places by nature: here (executable, law-pinned by `RrfLawsTest`) and in
 * the SQL strings (`RagStore.SEARCH`, `MemoryStore.SEARCH`), which Kotlin
 * cannot reach. The two are pinned equivalent on fixtures against real
 * Postgres (`RagStoreRrfEquivalenceIT`, and the memory search equivalence in
 * the salience laws). Postgres aggregates in unspecified order, so the
 * equivalence pins to a tolerance, never to bit equality.
 */
object Rrf {
    /** The fusion constant: rank 1 contributes 1/61, rank r fades as 1/(60+r). */
    const val K = 60

    /** One leg's contribution at 1-based [rank]. */
    fun contribution(rank: Int): Double = 1.0 / (K + rank)

    /** The fused score of an id recalled by legs at exactly these 1-based [ranks]. */
    fun score(ranks: List<Int>): Double = ranks.sumOf { contribution(it) }

    /**
     * The fusion stage as a value: [legs] of ids in rank order (best first,
     * distinct within a leg — what `row_number() OVER (ORDER BY ...) LIMIT n`
     * produces) -> every recalled id with its fused [Fused.score] and the
     * ranks each leg gave it, ordered by score DESC then id ASC (the SQL's
     * tie-break). An id absent from a leg contributes nothing from it; an id
     * absent from ALL legs never appears — no recall, no score.
     */
    fun fuse(legs: List<List<Long>>): List<Fused> {
        val ranksById = LinkedHashMap<Long, MutableList<Int>>()
        legs.forEachIndexed { leg, ids ->
            require(ids.distinct().size == ids.size) { "a leg recalls each id at most once, got $ids" }
            ids.forEachIndexed { index, id -> ranksById.getOrPut(id) { mutableListOf() } += index + 1 }
        }
        return ranksById.map { (id, ranks) -> Fused(id, score(ranks), ranks) }
            .sortedWith(compareByDescending<Fused> { it.score }.thenBy { it.id })
    }

    /** One fused id: [score] over the legs, and the 1-based [ranks] they gave it. */
    data class Fused(val id: Long, val score: Double, val ranks: List<Int>)
}
