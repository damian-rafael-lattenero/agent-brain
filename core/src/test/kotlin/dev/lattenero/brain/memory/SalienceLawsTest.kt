package dev.lattenero.brain.memory

import io.kotest.property.Arb
import io.kotest.property.arbitrary.numericDouble
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/**
 * The laws of the salience decay (plan E3), over generated inputs: monotone
 * decreasing in age, exactly 1 at t=0, exactly 0.5 at the half-life, a longer
 * half-life decays slower, exponentials split over elapsed time, and the
 * search score composes. The SQL twin (`MemoryStore.SEARCH`) is pinned
 * equivalent on fixtures in `MemorySearchEquivalenceIT`.
 *
 * Generators are [numericDouble] (plain `Arb.double` emits NaN, infinities
 * and out-of-range edge cases) and kept inside 400 half-lives, the last
 * exponent where `2^-x` is still strictly positive.
 */
class SalienceLawsTest {

    /** Half-lives a test store would actually hold (half a day to ~14 years). */
    private fun halfLife(): Arb<Double> = Arb.numericDouble(0.5, 5_000.0)

    @Test
    fun `one at birth - a fresh memory is not decayed at all`() {
        runBlocking {
            checkAll(halfLife()) { halfLife ->
                assertThat(Salience.decay(0.0, halfLife)).isEqualTo(1.0)
                assertThat(Salience.score(0.03, 2.0, 0.0, halfLife)).isEqualTo(0.03 * 2.0)
            }
        }
    }

    @Test
    fun `exactly half at the half-life - that is what half-life means`() {
        runBlocking {
            checkAll(halfLife()) { halfLife ->
                assertThat(Salience.decay(halfLife, halfLife)).isEqualTo(0.5)
            }
        }
    }

    @Test
    fun `monotone decreasing in age - older is strictly less`() {
        runBlocking {
            checkAll(halfLife(), Arb.numericDouble(0.0, 100.0), Arb.numericDouble(0.001, 100.0)) { halfLife, t, delta ->
                val age = t * halfLife
                assertThat(Salience.decay(age, halfLife)).isGreaterThan(Salience.decay(age + delta * halfLife, halfLife))
            }
        }
    }

    @Test
    fun `longer half-life, slower decay - raising the knob keeps old memories hotter`() {
        runBlocking {
            checkAll(Arb.numericDouble(0.5, 200.0), Arb.numericDouble(0.5, 200.0), Arb.numericDouble(0.001, 100.0)) { age, halfLife, delta ->
                // Hotter = LESS decayed: the factor grows with the half-life, bounded by 1 (no decay at all).
                assertThat(Salience.decay(age, halfLife)).isLessThan(Salience.decay(age, halfLife + delta))
            }
        }
    }

    @Test
    fun `exponentials split - aging a then b is aging a+b, in one factor per elapsed stretch`() {
        runBlocking {
            checkAll(halfLife(), Arb.numericDouble(0.0, 60.0), Arb.numericDouble(0.0, 60.0)) { halfLife, a, b ->
                val whole = Salience.decay((a + b) * halfLife, halfLife)
                val split = Salience.decay(a * halfLife, halfLife) * Salience.decay(b * halfLife, halfLife)
                assertThat(whole).isCloseTo(split, within(1e-12))
            }
        }
    }

    @Test
    fun `bounded - a decay factor is in zero-excluded one-included for any age`() {
        runBlocking {
            checkAll(halfLife(), Arb.numericDouble(0.0, 200.0)) { halfLife, age ->
                val decay = Salience.decay(age, halfLife)
                assertThat(decay).isGreaterThan(0.0)
                assertThat(decay).isLessThanOrEqualTo(1.0)
            }
        }
    }

    @Test
    fun `the score composes - monotone in salience and in the fused rrf`() {
        runBlocking {
            checkAll(halfLife(), Arb.numericDouble(0.0, 100.0), Arb.numericDouble(0.001, 1.0), Arb.numericDouble(0.001, 5.0)) { halfLife, age, rrf, salience ->
                val score = Salience.score(rrf, salience, age * halfLife, halfLife)
                assertThat(score).isGreaterThan(0.0)
                assertThat(score).isLessThanOrEqualTo(rrf * salience) // decay only lowers
                assertThat(Salience.score(rrf, salience * 2.0, age * halfLife, halfLife)).isGreaterThan(score)
                assertThat(Salience.score(rrf * 2.0, salience, age * halfLife, halfLife)).isGreaterThan(score)
            }
        }
    }
}
