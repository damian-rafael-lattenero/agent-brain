package dev.lattenero.brain.memory

/**
 * B3's memory lifecycle — the forgetting law as ONE total classification.
 *
 * There is no stored state machine: a memory's lifecycle state is a pure
 * function of what is already on the row. The usefulness clock is the LAST
 * ACCESS (`last_accessed`, else `created_at` at birth — a memory never read
 * ages from its birth), not `created_at`: the ranking tie-break's decay
 * (Salience.weight over created_at, [RankKey]) measures the fact's freshness,
 * while THIS decay measures usefulness — how long since anybody needed it.
 * One decay family, two clocks, two meanings.
 *
 * The law: `lifeWeight = salience * Salience.decay(ageSinceLastAccess,
 * halfLifeDays)`; `>= COOLING_FLOOR` the memory is [LifecycleState.ACTIVE],
 * `>= FORGET_FLOOR` it is [LifecycleState.COOLING], below it is
 * [LifecycleState.ARCHIVED]. The floors are law constants (like [Rrf.K] and
 * `MemoryDedup.SIMILARITY`), exact powers of two so every boundary is exact
 * in floating point: a salience-1.0 memory nobody accesses cools after
 * exactly one half-life and is forgotten after exactly three.
 *
 * FORGETTING is exclusion, not deletion: an archived row leaves both search
 * legs (it must not fuse, it must not consume a candidate slot) but stays on
 * the row — the status buckets count it, and reinforcement resurrects it.
 * The floors' edges belong to the HIGHER state (`>=`), matching the tie-break
 * convention. The laws — monotonicity in salience and age, the birth case,
 * the exact boundaries, resurrection by reinforcement, one-way decay-only
 * movement — are pinned in `LifecycleLawsTest`; `MemoryStore.SEARCH` is the
 * executable SQL twin pinned equivalent in `MemorySearchEquivalenceIT`.
 */
enum class LifecycleState { ACTIVE, COOLING, ARCHIVED }

/** The fleet's bucket counts from one classification pass (the status surface). */
data class LifecycleCounts(val active: Long, val cooling: Long, val archived: Long)

object Lifecycle {
    /** At or above this life-weight a memory is ACTIVE. One half-life of silence cools salience 1.0. */
    const val COOLING_FLOOR: Double = 0.5

    /** At or above this life-weight a memory at least COOLS; below it is forgotten. Three half-lives of silence. */
    const val FORGET_FLOOR: Double = 0.125

    /**
     * The two law knobs traveling together into every retrieval call: the
     * half-life (shared with the ranking decay) and the forgetting floor.
     * `forgetFloor` defaults to the law's own constant.
     */
    data class Params(val halfLifeDays: Int, val forgetFloor: Double = Lifecycle.FORGET_FLOOR)

    /**
     * The usefulness weight: the row's salience tempered by the age of its
     * last access (same unit for [ageLastAccessDays] and [halfLifeDays]).
     * The same decay family the ranking tie-break uses — a different clock.
     */
    fun lifeWeight(salience: Double, ageLastAccessDays: Double, halfLifeDays: Double): Double =
        Salience.weight(salience, ageLastAccessDays, halfLifeDays)

    /**
     * The total classification: one state per life-weight, the edges to the
     * higher state. Total over every double; a negative weight (an
     * impossible row) classifies as ARCHIVED — the honest end of the ladder.
     */
    fun classify(lifeWeight: Double): LifecycleState = when {
        lifeWeight >= COOLING_FLOOR -> LifecycleState.ACTIVE
        lifeWeight >= FORGET_FLOOR -> LifecycleState.COOLING
        else -> LifecycleState.ARCHIVED
    }
}
