package dev.lattenero.brain.memory

import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.numericDouble
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/**
 * The laws of the salience decay (plan E3) and of the ranking key (the
 * 2026-11-08 relevance-dominance fix), over generated inputs. Decay: monotone
 * decreasing in age, exactly 1 at t=0, exactly 0.5 at the half-life, a longer
 * half-life decays slower, exponentials split over elapsed time. Ranking
 * ([RankKey], the order `MemoryStore.SEARCH` returns hits in): LAW 1 a
 * strictly better fused relevance outranks a worse one for ANY salience and
 * age; LAW 2 among equal-relevance hits the higher reinforcement weight
 * ranks first; LAW 3 monotone in rrf and in salience. The SQL twin
 * (`MemoryStore.SEARCH`) is pinned equivalent on fixtures in
 * `MemorySearchEquivalenceIT`.
 *
 * Generators are [numericDouble] (plain `Arb.double` emits NaN, infinities
 * and out-of-range edge cases) and kept inside 400 half-lives, the last
 * exponent where `2^-x` is still strictly positive.
 */
class SalienceLawsTest {

    /** Half-lives a test store would actually hold (half a day to ~14 years). */
    private fun halfLife(): Arb<Double> = Arb.numericDouble(0.5, 5_000.0)

    /** Saliences the reinforcement system actually produces (steps of 1.0 on a 1.0 default). */
    private fun salience(): Arb<Double> = Arb.numericDouble(0.05, 20.0)

    /** Ages in half-lives: from fresh to effectively forgotten. */
    private fun ageHalfLives(): Arb<Double> = Arb.numericDouble(0.0, 200.0)

    @Test
    fun `one at birth - a fresh memory is not decayed at all`() {
        runBlocking {
            checkAll(halfLife()) { halfLife ->
                assertThat(Salience.decay(0.0, halfLife)).isEqualTo(1.0)
                assertThat(Salience.weight(2.0, 0.0, halfLife)).isEqualTo(2.0)
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
    fun `bounded weight - reinforcement tempered by decay never exceeds the raw salience`() {
        runBlocking {
            checkAll(halfLife(), salience(), ageHalfLives()) { halfLife, salience, age ->
                val weight = Salience.weight(salience, age * halfLife, halfLife)
                assertThat(weight).isGreaterThan(0.0)
                assertThat(weight).isLessThanOrEqualTo(salience)
            }
        }
    }

    /**
     * LAW 1 (relevance dominance): a hit whose fused rrf is strictly better
     * ranks ahead of a worse-rrf hit for ANY salience, ANY age and ANY ids —
     * the exact defect the fix exists for (a salience-3.0 veteran used to
     * beat a genuinely relevant fresh match).
     */
    @Test
    fun `law 1 relevance dominance - strictly better rrf ranks first for any salience and any age`() {
        runBlocking {
            checkAll(
                halfLife(),
                Arb.numericDouble(0.0001, 1.0), // the worse hit's rrf
                Arb.numericDouble(0.0001, 0.5), // the strictly positive rrf gap
                salience(),
                salience(),
                ageHalfLives(),
                ageHalfLives(),
                Arb.long(),
                Arb.long(),
            ) { halfLife, rrfWorse, gap, salienceWorse, salienceBetter, ageWorse, ageBetter, idWorse, idBetter ->
                val better = RankKey(rrfWorse + gap, Salience.weight(salienceBetter, ageBetter * halfLife, halfLife), idBetter)
                val worse = RankKey(rrfWorse, Salience.weight(salienceWorse, ageWorse * halfLife, halfLife), idWorse)
                assertThat(better).withFailMessage("better $better must rank before worse $worse").isLessThan(worse)
            }
        }
    }

    /**
     * LAW 2 (reinforcement still matters): among EQUAL-rrf hits the higher
     * salience-times-decay ranks first — the reinforcement the dedup and
     * access systems accumulate decides exactly where it is safe: relevance
     * ties.
     */
    @Test
    fun `law 2 reinforcement breaks ties - among equal rrf the higher weight ranks first`() {
        runBlocking {
            checkAll(halfLife(), Arb.numericDouble(0.0001, 1.0), Arb.numericDouble(0.0001, 0.5), Arb.long(), Arb.long()) { halfLife, rrf, weightGap, idStronger, idWeaker ->
                val stronger = RankKey(rrf, 10.0, idStronger)
                val weaker = RankKey(rrf, 10.0 - weightGap, idWeaker)
                assertThat(stronger).withFailMessage("stronger $stronger must rank before weaker $weaker").isLessThan(weaker)
            }
        }
    }

    /** LAW 2 composed with the decay: same relevance, same age, salience 3.0 outranks 1.0. */
    @Test
    fun `law 2 composed - a reinforced twin outranks a fresh one at the same relevance and age`() {
        runBlocking {
            checkAll(halfLife(), Arb.numericDouble(0.0001, 1.0), ageHalfLives()) { halfLife, rrf, age ->
                val reinforced = RankKey(rrf, Salience.weight(3.0, age * halfLife, halfLife), 2L)
                val fresh = RankKey(rrf, Salience.weight(1.0, age * halfLife, halfLife), 1L)
                assertThat(reinforced).isLessThan(fresh)
            }
        }
    }

    /**
     * LAW 3 (monotonicity): raising the rrf with weight and id fixed moves a
     * hit strictly forward, and raising the salience with relevance and age
     * fixed moves it strictly forward too — the ranking never punishes being
     * more relevant or more reinforced.
     */
    @Test
    fun `law 3 monotonicity - raising rrf or raising salience never lowers a hit's rank`() {
        runBlocking {
            checkAll(halfLife(), Arb.numericDouble(0.0001, 1.0), Arb.numericDouble(0.0001, 0.5), salience(), ageHalfLives()) { halfLife, rrf, delta, salience, age ->
                val base = RankKey(rrf, Salience.weight(salience, age * halfLife, halfLife), 7L)
                assertThat(RankKey(rrf + delta, base.weight, 7L)).isLessThan(base)
                assertThat(RankKey(rrf, Salience.weight(salience + delta, age * halfLife, halfLife), 7L)).isLessThan(base)
                // And a hit never outranks a strictly better-rrf one by being more salient (LAW 1 restated locally).
                assertThat(base).isGreaterThan(RankKey(rrf + delta, Salience.weight(0.05, 200.0 * halfLife, halfLife), 7L))
            }
        }
    }
}
