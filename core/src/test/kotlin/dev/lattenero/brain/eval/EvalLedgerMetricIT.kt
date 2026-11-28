package dev.lattenero.brain.eval

import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.db.Database
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** U22: the metric rows the fleet's scripts record — the ledger keeps ONE writer, the brain. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EvalLedgerMetricIT {

    @BeforeAll
    fun up() {
        TestEnv.needPostgres()
    }

    @Test
    fun `a metric run lands in eval_runs and reads back through latest`() {
        val config = TestDatabase.config("evalmetric")
        Database(config).use { db ->
            runBlocking {
                val ledger = EvalLedger(db, config.embedDim)
                val stored = ledger.recordMetric(
                    StoredRun(
                        suite = "first-pass-nu",
                        kind = "metric",
                        axis = "fleet",
                        model = "n/a",
                        provider = "fleet-scripts",
                        passed = 38,
                        total = 40,
                        gate = 0.95,
                        ok = false,
                        detail = buildJsonObject {
                            put("week", "2026-W47")
                            put("oracle_fails", 2)
                        },
                    ),
                )
                assertThat(stored.runId).isNotNull()
                assertThat(stored.startedAt).isNotNull()

                val rows = ledger.latest(suite = "first-pass-nu")
                assertThat(rows).hasSize(1)
                val row = rows.single()
                assertThat(row.suite).isEqualTo("first-pass-nu")
                assertThat(row.kind).isEqualTo("metric")
                assertThat(row.axis).isEqualTo("fleet")
                assertThat(row.passed).isEqualTo(38)
                assertThat(row.total).isEqualTo(40)
                assertThat(row.gate).isEqualTo(0.95)
                assertThat(row.ok).isFalse()
                assertThat(row.detail).isNotNull()
                assertThat(row.detail.toString()).contains("2026-W47")

                // a generated runId, and a second row stacks newest-first
                val again = ledger.recordMetric(
                    StoredRun(
                        suite = "rumbo",
                        kind = "metric",
                        axis = "fleet",
                        model = "n/a",
                        provider = "fleet-scripts",
                        passed = 98,
                        total = 112,
                        gate = 0.0,
                        ok = true,
                    ),
                )
                assertThat(again.runId).isNotEqualTo(stored.runId)
                assertThat(ledger.latest(suite = "rumbo").single().suite).isEqualTo("rumbo")
            }
        }
    }
}
