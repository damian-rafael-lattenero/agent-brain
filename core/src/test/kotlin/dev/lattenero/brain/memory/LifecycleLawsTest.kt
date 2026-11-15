package dev.lattenero.brain.memory

import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.numericDouble
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The lifecycle law battery (B3): the forgetting classification is a total
 * function, and these are its algebraic commitments — kotest properties over
 * random inputs plus the exact boundary cases the floors promise.
 */
class LifecycleLawsTest {
    private fun halfLife(): Arb<Double> = Arb.numericDouble(0.5, 200.0)
    private fun salience(): Arb<Double> = Arb.numericDouble(0.001, 10.0)
    private fun ageHalfLives(): Arb<Double> = Arb.numericDouble(0.0, 100.0)

    /** LAW 1 — monotone in salience: more salience never classifies lower. */
    @Test
    fun `law 1 - higher salience never lowers the state`() = runBlocking<Unit> {
        checkAll(halfLife(), ageHalfLives(), salience(), Arb.numericDouble(0.001, 10.0)) { halfLifeDays, age, low, delta ->
            val high = low + delta
            val lowState = Lifecycle.classify(Lifecycle.lifeWeight(low, age, halfLifeDays))
            val highState = Lifecycle.classify(Lifecycle.lifeWeight(high, age, halfLifeDays))
            assertThat(highState).isLessThanOrEqualTo(lowState)
        }
    }

    /** LAW 2 — monotone in age: more silence never raises the state. */
    @Test
    fun `law 2 - more silence never raises the state`() = runBlocking<Unit> {
        checkAll(halfLife(), salience(), ageHalfLives(), Arb.numericDouble(0.001, 20.0)) { halfLifeDays, salience, age, delta ->
            val freshState = Lifecycle.classify(Lifecycle.lifeWeight(salience, age, halfLifeDays))
            val olderState = Lifecycle.classify(Lifecycle.lifeWeight(salience, age + delta, halfLifeDays))
            assertThat(olderState).isGreaterThanOrEqualTo(freshState)
        }
    }

    /** LAW 3 — birth: a fresh never-accessed memory of salience >= 1 is ACTIVE. */
    @Test
    fun `law 3 - a fresh row of salience one is active`() = runBlocking<Unit> {
        checkAll(halfLife(), Arb.numericDouble(1.0, 10.0)) { halfLifeDays, salience ->
            assertThat(Lifecycle.classify(Lifecycle.lifeWeight(salience, 0.0, halfLifeDays)))
                .isEqualTo(LifecycleState.ACTIVE)
        }
    }

    /** LAW 4 — the edges belong to the higher state, exact in floating point. */
    @Test
    fun `law 4 - the floor edges classify upward`() {
        // salience 1.0 at exactly one half-life: weight == COOLING_FLOOR -> ACTIVE
        assertThat(Lifecycle.classify(Lifecycle.lifeWeight(1.0, 1.0, 1.0))).isEqualTo(LifecycleState.ACTIVE)
        // salience 1.0 just past one half-life: COOLING
        assertThat(Lifecycle.classify(Lifecycle.lifeWeight(1.0, 1.1, 1.0))).isEqualTo(LifecycleState.COOLING)
        // salience 1.0 at exactly three half-lives: weight == FORGET_FLOOR -> COOLING
        assertThat(Lifecycle.classify(Lifecycle.lifeWeight(1.0, 3.0, 1.0))).isEqualTo(LifecycleState.COOLING)
        // just past three half-lives: ARCHIVED
        assertThat(Lifecycle.classify(Lifecycle.lifeWeight(1.0, 3.1, 1.0))).isEqualTo(LifecycleState.ARCHIVED)
    }

    /** LAW 5 — resurrection: enough reinforcement returns a non-ACTIVE row to ACTIVE. */
    @Test
    fun `law 5 - reinforcement resurrects a forgotten memory`() = runBlocking<Unit> {
        checkAll(halfLife(), salience(), ageHalfLives()) { halfLifeDays, salience, age ->
            val state = Lifecycle.classify(Lifecycle.lifeWeight(salience, age, halfLifeDays))
            // MemoryStore.search reinforces every hit by SEARCH_REINFORCE_STEP per hit;
            // a dedup merge lands a full REINFORCE_STEP — enough salience raised on the
            // same clock brings the row back to ACTIVE. The boost targets TWICE the
            // cooling floor: at extreme age the decay underflows and the intermediate
            // (salience + boost) rounds away the row's own salience — a 1× margin can
            // round back below the floor (measured: 0.4999…), a 2× margin cannot.
            val boost = (Lifecycle.COOLING_FLOOR * 2.0) / Salience.decay(age, halfLifeDays)
            assertThat(Lifecycle.classify(Lifecycle.lifeWeight(salience + boost, age, halfLifeDays)))
                .isEqualTo(LifecycleState.ACTIVE)
            // the law speaks about the non-active rows; an already-ACTIVE row has
            // nothing to resurrect (and the boost assertion above still holds)
            if (state != LifecycleState.ACTIVE) {
                assertThat(state).isIn(LifecycleState.COOLING, LifecycleState.ARCHIVED)
            }
        }
    }

    /** LAW 6 — decay-only movement is one-way: time never raises a fixed-salience row. */
    @Test
    fun `law 6 - time alone only walks the ladder down`() = runBlocking<Unit> {
        checkAll(halfLife(), salience(), ageHalfLives(), Arb.int(1, 50)) { halfLifeDays, salience, age, extraHalfLives ->
            val now = Lifecycle.classify(Lifecycle.lifeWeight(salience, age, halfLifeDays))
            val later = Lifecycle.classify(Lifecycle.lifeWeight(salience, age + extraHalfLives * halfLifeDays, halfLifeDays))
            assertThat(later).isGreaterThanOrEqualTo(now)
        }
    }
}
