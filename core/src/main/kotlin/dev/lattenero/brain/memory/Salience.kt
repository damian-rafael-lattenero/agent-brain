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
 * equivalent on fixtures in `MemorySearchEquivalenceIT` — fusion ([Rrf])
 * times salience times this decay. The `pow(2, -t/h)` rendering (not
 * `exp(-ln2 * t/h)`) makes the two exact laws exact in floating point.
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

    /** The search score: fused RRF, times the row's salience, decayed by age. */
    fun score(rrf: Double, salience: Double, age: Double, halfLife: Double): Double =
        rrf * salience * decay(age, halfLife)
}
