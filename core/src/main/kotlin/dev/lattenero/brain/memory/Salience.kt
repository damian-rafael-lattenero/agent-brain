package dev.lattenero.brain.memory

import kotlin.math.pow

/**
 * The salience decay — the pure core of what `MemoryStore.SEARCH` computes in
 * SQL as `exp(-ln(2) * extract(epoch FROM (now() - created_at)) / (86400 *
 * half_life_days))`: a memory's weight halves every `halfLife` of age. Here
 * it is one total function of numbers (`age` and `halfLife` in the SAME
 * unit — the SQL renders the knob's days and the row's epoch seconds into
 * seconds); the laws (monotone in age, 1 at birth, exactly 0.5 at one
 * half-life, longer half-life decays slower, exponentials split over
 * elapsed time) are pinned in `SalienceLawsTest`, and the SQL twin is pinned
 * equivalent on fixtures in `MemorySearchEquivalenceIT`. The `pow(2, -t/h)`
 * rendering (not `exp(-ln2 * t/h)`) makes the two exact laws exact in
 * floating point.
 */
object Salience {
    private const val TWO = 2.0

    /**
     * The decay factor of a memory aged [age] under half-life [halfLife]
     * (same unit): `2^(-age/halfLife)`.
     */
    fun decay(age: Double, halfLife: Double): Double {
        require(age >= 0.0) { "age must not be negative, was $age" }
        require(halfLife > 0.0) { "half-life must be strictly positive, was $halfLife" }
        return TWO.pow(-age / halfLife)
    }

    /**
     * The tie-break weight: the row's reinforcement ([salience]) tempered by
     * its freshness (the [decay]). NOT the search score and never multiplied
     * into relevance — see [RankKey] for where it may act.
     */
    fun weight(salience: Double, age: Double, halfLife: Double): Double =
        salience * decay(age, halfLife)
}

/**
 * One memory hit's ranking key, best first: fused relevance ([rrf]) is the
 * DOMINANT key, the reinforcement weight ([Salience.weight]) breaks exact
 * relevance ties, and [id] is the deterministic last word (the SQL's
 * tie-break). The natural order IS the ranking order: an ascending sort
 * ranks best first, and a strictly better `rrf` wins for ANY weight and id —
 * a veteran reinforced to salience 3.0 can no longer outrank a genuinely
 * relevant fresh match (the measured 2026-11-08 defect). The laws —
 * relevance dominance, reinforcement breaking ties, monotonicity in rrf and
 * salience — are pinned in `SalienceLawsTest`; `MemoryStore.SEARCH` is the
 * executable SQL twin pinned equivalent in `MemorySearchEquivalenceIT`.
 */
data class RankKey(val rrf: Double, val weight: Double, val id: Long) : Comparable<RankKey> {
    override fun compareTo(other: RankKey): Int =
        compareByDescending<RankKey> { it.rrf } // better relevance ranks first
            .thenByDescending { it.weight } // only among equal relevance
            .thenBy { it.id } // deterministic
            .compare(this, other)
}
