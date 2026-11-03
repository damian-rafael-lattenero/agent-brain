package dev.lattenero.brain

import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The wiring canary for the property-law suites (plan E1): kotest-property
 * runs inside the plain JUnit suite — no kotest engine, a law is an assertion
 * under `checkAll` over generated inputs, and a failure names the seed and
 * the shrunk counterexample for reproduction. The real laws live beside the
 * cores they pin (RRF fusion, salience decay, extraction caps, the Config
 * fold, ingest idempotence).
 */
class PropertyWiringTest {
    @Test
    fun `kotest-property generates inputs and runs laws inside the junit suite`() {
        runBlocking {
            checkAll<Int, Int> { a, b -> assertThat(a + b).isEqualTo(b + a) }
        }
    }
}
