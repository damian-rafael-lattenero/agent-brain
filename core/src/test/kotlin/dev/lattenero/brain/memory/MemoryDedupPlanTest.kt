package dev.lattenero.brain.memory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The backfill's pure core (plan + report): oldest-first clustering with the
 * measured threshold, idempotent by construction. The production geometry of
 * the cited cluster (`scripts/memory-dedup-evidence.sql`) drives the rows.
 */
class MemoryDedupPlanTest {

    private fun row(
        id: Long,
        vector: FloatArray,
        model: String = "nomic-embed-text",
        created: Instant = Instant.ofEpochSecond(1_700_000_000 + id),
    ) = DedupRow(id = id, kind = "fact", content = "memory $id", embedModel = model, salience = 1.0, createdAt = created, embedding = vector)

    @Test
    fun `the oldest row of a duplicate cluster survives and absorbs the rest`() {
        val rows = listOf(
            row(1, TestVectors.at(1.0, axis = 0)),
            row(2, TestVectors.at(0.9994, axis = 0)), // ~2 degrees off 1
            row(3, TestVectors.at(0.9986, axis = 0)), // ~3 degrees off 1
        )
        val plan = MemoryDedup.plan(rows)
        assertThat(plan.clusters).hasSize(1)
        assertThat(plan.clusters.single().keepId).isEqualTo(1L)
        assertThat(plan.clusters.single().absorbedIds).containsExactly(2L, 3L)
        assertThat(plan.removed).isEqualTo(2)
    }

    @Test
    fun `a younger id with an older createdAt is the survivor - created_at wins, not id`() {
        val rows = listOf(
            row(7, TestVectors.at(1.0, axis = 0), created = Instant.ofEpochSecond(1_700_000_100)),
            row(3, TestVectors.at(0.9994, axis = 0), created = Instant.ofEpochSecond(1_700_000_050)),
        )
        val plan = MemoryDedup.plan(rows)
        assertThat(plan.clusters.single().keepId).isEqualTo(3L)
        assertThat(plan.clusters.single().absorbedIds).containsExactly(7L)
    }

    @Test
    fun `distinct and merely-related rows are never merged`() {
        val rows = listOf(
            row(1, TestVectors.at(1.0, axis = 0)),
            row(2, TestVectors.at(1.0, axis = 1)),
            row(3, TestVectors.at(1.0, axis = 2)),
            row(4, TestVectors.at(0.8789, axis = 0)), // the strongest measured merely-related pair
        )
        assertThat(MemoryDedup.plan(rows).removed).isZero
    }

    @Test
    fun `different embed models never cluster (ADR-004)`() {
        val rows = listOf(
            row(1, TestVectors.at(1.0, axis = 0), model = "nomic-embed-text"),
            row(2, TestVectors.at(1.0, axis = 0), model = "other-model"),
        )
        assertThat(MemoryDedup.plan(rows).removed).isZero
    }

    @Test
    fun `the measured cross-lingual distance keeps the languages apart`() {
        // Spanish 15 vs English 20 measured 0.6524: below the threshold, two rows survive.
        val rows = listOf(
            row(15, TestVectors.at(1.0, axis = 0)),
            row(20, TestVectors.at(1.0, axis = 1)),
        )
        assertThat(MemoryDedup.plan(rows).removed).isZero
    }

    @Test
    fun `applying a plan leaves every surviving pair below the threshold - a second plan is empty`() {
        val rows = listOf(
            row(1, TestVectors.at(1.0, axis = 0)),
            row(2, TestVectors.at(0.9994, axis = 0)),
            row(3, TestVectors.at(0.9986, axis = 0)),
            row(4, TestVectors.at(1.0, axis = 1)),
            row(5, TestVectors.at(0.9998, axis = 1)),
            row(6, TestVectors.at(1.0, axis = 2)),
        )
        val plan = MemoryDedup.plan(rows)
        assertThat(plan.removed).isEqualTo(3)
        val survivors = rows.filter { row -> plan.clusters.none { row.id in it.absorbedIds } }
        assertThat(survivors).hasSize(3)
        assertThat(MemoryDedup.plan(survivors).removed).isZero
    }

    @Test
    fun `the report carries what is merged, with the contents`() {
        val rows = listOf(
            row(15, TestVectors.at(1.0, axis = 0)),
            row(16, TestVectors.at(0.9994, axis = 0)),
        )
        val plan = MemoryDedup.plan(rows)
        val report = MemoryDedup.report(rows, plan, dryRun = true)
        assertThat(report.scanned).isEqualTo(2)
        assertThat(report.dryRun).isTrue
        assertThat(report.removed).isEqualTo(1)
        assertThat(report.clusters.single().keepId).isEqualTo(15L)
        assertThat(report.clusters.single().keep).isEqualTo("memory 15")
        assertThat(report.clusters.single().absorbed).containsExactly(16L)
        assertThat(report.clusters.single().absorbedContent).containsExactly("memory 16")
    }
}
