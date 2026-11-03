package dev.lattenero.brain.memory

import com.pgvector.PGvector
import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.config.Config
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.Migrator
import dev.lattenero.brain.embeddings.FakeProvider
import dev.lattenero.brain.memory.MemoryDedup.SIMILARITY
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Dedup at insert against real Postgres (ADR-008 quality fix): controlled
 * embeddings, the store's own decision. The threshold and the geometry are
 * the measured ones (`MemoryDedup`, `scripts/memory-dedup-evidence.sql`).
 * One throwaway database per test, like the other ITs.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryDedupIT {
    private val model = "test-model"

    private class Fixture(val config: Config, val db: Database) : AutoCloseable {
        val store = MemoryStore(db)
        override fun close() = db.close()
    }

    private fun fixture(tag: String): Fixture {
        val config = TestDatabase.config(tag)
        val db = Database(config)
        runBlocking { Migrator(db, 768).apply() }
        return Fixture(config, db)
    }

    private fun transcript(eventId: String, session: String) = Transcript(eventId, session, "turn-$eventId", "test", "prompt", "reply", emptyList())

    private fun row(kind: String, content: String, vector: FloatArray) = MemoryStore.MemoryRow(kind, content, vector)

    private suspend fun count(db: Database): Long = db.withConnection { conn ->
        conn.createStatement().use { st -> st.executeQuery("SELECT count(*) FROM memories").use { rs -> rs.next(); rs.getLong(1) } }
    }

    private suspend fun salienceOf(db: Database, id: Long): Double = db.withConnection { conn ->
        conn.prepareStatement("SELECT salience FROM memories WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> rs.next(); rs.getDouble(1) }
        }
    }

    private suspend fun contentOf(db: Database, id: Long): String = db.withConnection { conn ->
        conn.prepareStatement("SELECT content FROM memories WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> rs.next(); rs.getString(1) }
        }
    }

    @Test
    fun `a near-duplicate insert merges into the existing row - no new row, salience bumped, report says merged`() = runBlocking<Unit> {
        TestEnv.needPostgres()
        fixture("memdedupmerge").use { f ->
            val first = f.store.insertAllDeduped(
                transcript("e1", "ses-a"),
                model,
                listOf(row("fact", "Todos los subagentes están en estado 'terminado'.", TestVectors.at(1.0, axis = 0))),
            )
            assertThat(first.inserted).isEqualTo(1)
            assertThat(first.merged).isEmpty()
            val id = f.store.recent(1).single().id

            val second = f.store.insertAllDeduped(
                transcript("e2", "ses-b"),
                model,
                listOf(row("summary", "Todos los subagentes han terminado y están en estado 'terminado'.", TestVectors.at(0.9986, axis = 0))),
            )
            assertThat(second.inserted).isZero
            assertThat(second.merged).containsExactly(id)
            assertThat(count(f.db)).isEqualTo(1)
            assertThat(salienceOf(f.db, id)).isCloseTo(1.0 + MemoryStore.REINFORCE_STEP, within(1e-6))
            assertThat(contentOf(f.db, id)).isEqualTo("Todos los subagentes están en estado 'terminado'.")
        }
    }

    @Test
    fun `below the threshold the insert lands as a new row`() = runBlocking<Unit> {
        TestEnv.needPostgres()
        fixture("memdedupbelow").use { f ->
            val first = f.store.insertAllDeduped(transcript("e1", "ses-a"), model, listOf(row("fact", "first", TestVectors.at(1.0, axis = 0))))
            assertThat(first.inserted).isEqualTo(1)
            // cos 0.87 < 0.90: related but distinct, exactly the measured band edge.
            val second = f.store.insertAllDeduped(transcript("e2", "ses-b"), model, listOf(row("fact", "related", TestVectors.at(0.87, axis = 0))))
            assertThat(second.inserted).isEqualTo(1)
            assertThat(second.merged).isEmpty()
            assertThat(count(f.db)).isEqualTo(2)
        }
    }

    @Test
    fun `rows of one turn see each other - a summary restating this turn's fact merges into it`() = runBlocking<Unit> {
        TestEnv.needPostgres()
        fixture("memdedupbatch").use { f ->
            val report = f.store.insertAllDeduped(
                transcript("e1", "ses-a"),
                model,
                listOf(
                    row("fact", "The subagents have completed their tasks.", TestVectors.at(1.0, axis = 0)),
                    row("summary", "The subagents have completed their tasks.", TestVectors.at(0.95, axis = 0)),
                ),
            )
            assertThat(report.inserted).isEqualTo(1)
            assertThat(report.merged).hasSize(1)
            assertThat(count(f.db)).isEqualTo(1)
        }
    }

    @Test
    fun `an exact replay of the same event is absorbed silently - no insert, no salience bump`() = runBlocking<Unit> {
        TestEnv.needPostgres()
        fixture("memdedupreplay").use { f ->
            val same = transcript("e1", "ses-a")
            val rows = listOf(row("fact", "The subagents have completed their tasks.", TestVectors.at(1.0, axis = 0)))
            f.store.insertAllDeduped(same, model, rows)
            val id = f.store.recent(1).single().id

            // A crash between insert and mark re-runs the turn: the same extraction again.
            val replay = f.store.insertAllDeduped(same, model, rows)
            assertThat(replay.inserted).isZero
            assertThat(replay.merged).isEmpty()
            assertThat(count(f.db)).isEqualTo(1)
            assertThat(salienceOf(f.db, id)).isCloseTo(1.0, within(1e-6))
        }
    }

    @Test
    fun `different embed models never compare - cosine across models is meaningless (ADR-004)`() = runBlocking<Unit> {
        TestEnv.needPostgres()
        fixture("memdedupmodel").use { f ->
            f.store.insertAllDeduped(transcript("e1", "ses-a"), model, listOf(row("fact", "one model", TestVectors.at(1.0, axis = 0))))
            val report = f.store.insertAllDeduped(transcript("e2", "ses-b"), "other-model", listOf(row("fact", "other model", TestVectors.at(1.0, axis = 0))))
            assertThat(report.inserted).isEqualTo(1)
            assertThat(report.merged).isEmpty()
            assertThat(count(f.db)).isEqualTo(2)
        }
    }

    @Test
    fun `manual saves dedup with the same rule and return the surviving row`() = runBlocking<Unit> {
        TestEnv.needPostgres()
        fixture("memdedupmanual").use { f ->
            val first = f.store.saveManual("preference", "The user prefers concise responses.", model, TestVectors.at(1.0, axis = 0), "ses-a")
            assertThat(salienceOf(f.db, first)).isCloseTo(1.0, within(1e-6))
            val second = f.store.saveManual("preference", "The user prefers short, direct responses.", model, TestVectors.at(0.95, axis = 0), "ses-b")
            assertThat(second).isEqualTo(first)
            assertThat(count(f.db)).isEqualTo(1)
            assertThat(salienceOf(f.db, first)).isCloseTo(2.0, within(1e-6))
            assertThat(contentOf(f.db, first)).isEqualTo("The user prefers concise responses.")
            assertThat(f.store.byId(first)?.salience).isCloseTo(2.0, within(1e-6))
        }
    }

    @Test
    fun `byId reads a row back`() = runBlocking<Unit> {
        TestEnv.needPostgres()
        fixture("memdedupbyid").use { f ->
            val id = f.store.saveManual("fact", "A row to read back.", model, TestVectors.at(1.0, axis = 1), null)
            val memory = f.store.byId(id)
            assertThat(memory).isNotNull
            assertThat(memory?.kind).isEqualTo("fact")
            assertThat(memory?.content).isEqualTo("A row to read back.")
            assertThat(f.store.byId(-1L)).isNull()
        }
    }

    @Test
    fun `the in-Kotlin cosine agrees with pgvector's cosine distance on the controlled geometry`() = runBlocking<Unit> {
        TestEnv.needPostgres()
        fixture("memdedupequiv").use { f ->
            // Five rows whose pairwise cosines span the threshold decision
            // space, inserted through the RAW path so all five really land.
            val vectors = mapOf(
                "axis0" to TestVectors.at(1.0, axis = 0),
                "near0" to TestVectors.at(0.9447, axis = 0),
                "edge0" to TestVectors.at(0.8789, axis = 0),
                "axis1" to TestVectors.at(1.0, axis = 1),
                "axis2" to TestVectors.at(1.0, axis = 2),
            )
            f.store.insertAll(
                transcript("e1", "ses-a"),
                model,
                vectors.map { (name, vector) -> row("fact", "equivalence $name", vector) },
            )
            val stored: List<Pair<String, FloatArray>> = f.db.withConnection { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT content, embedding::text FROM memories WHERE content LIKE 'equivalence %'").use { rs ->
                        generateSequence {
                            if (rs.next()) rs.getString(1).removePrefix("equivalence ") to PGvector(rs.getString(2)).toArray() else null
                        }.toList()
                    }
                }
            }
            assertThat(stored).hasSize(vectors.size)
            val sqlSimilarity = f.db.withConnection { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery(
                        "SELECT a.content, b.content, 1 - (a.embedding <=> b.embedding) FROM memories a, memories b " +
                            "WHERE a.content LIKE 'equivalence %' AND b.content LIKE 'equivalence %' AND a.id < b.id",
                    ).use { rs ->
                        generateSequence {
                            if (rs.next()) Triple(rs.getString(1).removePrefix("equivalence "), rs.getString(2).removePrefix("equivalence "), rs.getDouble(3)) else null
                        }.toList()
                    }
                }
            }
            assertThat(sqlSimilarity).isNotEmpty
            for ((nameA, nameB, sqlSim) in sqlSimilarity) {
                val kotlinSim = MemoryDedup.cosine(
                    stored.first { it.first == nameA }.second,
                    stored.first { it.first == nameB }.second,
                )
                assertThat(kotlinSim).isCloseTo(sqlSim, within(1e-6))
                assertThat(sqlSim >= SIMILARITY).isEqualTo(kotlinSim >= SIMILARITY)
            }
            // The two bands stay on their measured sides of the threshold.
            assertThat(sqlSimilarity.first { setOf(it.first, it.second) == setOf("axis0", "near0") }.third).isGreaterThanOrEqualTo(SIMILARITY)
            assertThat(sqlSimilarity.first { setOf(it.first, it.second) == setOf("axis0", "edge0") }.third).isLessThan(SIMILARITY)
        }
    }

    @Test
    fun `fake BOW embeddings sit far below the threshold for distinct texts and at 1_0 for identical ones`() = runBlocking<Unit> {
        // The pinned MemoryWorkerIT runs on this embedder: dedup must never
        // merge its distinct items, and its replay path is the exact-text case.
        val embedder = FakeProvider(768, FakeProvider.Mode.BOW)
        val texts = listOf(
            "The user keeps a CV project at /Users/x/Projects/cv.",
            "The CV was reordered this turn.",
            "The user writes prompts mixing Spanish and English.",
            "The CV got reordered.",
        )
        val vectors = embedder.embed(texts)
        for ((i, a) in vectors.withIndex()) {
            for (j in 0 until i) assertThat(MemoryDedup.cosine(a, vectors[j])).isLessThan(SIMILARITY)
        }
        assertThat(MemoryDedup.cosine(vectors[0], vectors[0])).isCloseTo(1.0, within(1e-9))
    }

    @Test
    fun `the backfill merges the planted cluster keeping the oldest row, reinforced by the absorbed count`() = runBlocking<Unit> {
        TestEnv.needPostgres()
        fixture("memdedupbackfill").use { f ->
            // The measured production shape: a Spanish trio, an English pair,
            // one distinct row (ids assigned in insert order, oldest first).
            f.store.insertAll(
                transcript("e1", "ses"),
                model,
                listOf(
                    row("fact", "Todos los subagentes han terminado.", TestVectors.at(1.0, axis = 0)),
                    row("fact", "Todos los subagentes están en estado 'terminado'.", TestVectors.at(0.9994, axis = 0)),
                    row("summary", "Todos los subagentes han terminado y están en estado 'terminado'.", TestVectors.at(0.9986, axis = 0)),
                    row("fact", "The subagents have completed their tasks.", TestVectors.at(1.0, axis = 1)),
                    row("summary", "The subagents have finished their tasks, and the system is in a final state.", TestVectors.at(0.9998, axis = 1)),
                    row("fact", "The current state of everything is finalized.", TestVectors.at(1.0, axis = 2)),
                ),
            )
            val rows = f.store.dedupRows()
            assertThat(rows).hasSize(6)
            val plan = MemoryDedup.plan(rows)
            assertThat(plan.clusters).hasSize(2)
            val spanish = plan.clusters.single { it.reinforcements == 2 }
            val english = plan.clusters.single { it.reinforcements == 1 }

            val applied = f.store.applyDedup(plan)
            assertThat(applied).isEqualTo(3)
            assertThat(count(f.db)).isEqualTo(3)
            // The survivors are the oldest rows; the absorbed ones are gone.
            assertThat(f.store.byId(spanish.keepId)?.content).isEqualTo("Todos los subagentes han terminado.")
            assertThat(f.store.byId(english.keepId)?.content).isEqualTo("The subagents have completed their tasks.")
            (spanish.absorbedIds + english.absorbedIds).forEach { assertThat(f.store.byId(it)).isNull() }
            // Reinforced by the absorbed count: 1 + 2 and 1 + 1.
            assertThat(f.store.byId(spanish.keepId)?.salience).isCloseTo(3.0, within(1e-6))
            assertThat(f.store.byId(english.keepId)?.salience).isCloseTo(2.0, within(1e-6))
            // Idempotent: what survives plans nothing to merge, a second pass removes nothing.
            val again = MemoryDedup.plan(f.store.dedupRows())
            assertThat(again.removed).isZero
            assertThat(f.store.applyDedup(again)).isZero
            assertThat(count(f.db)).isEqualTo(3)
        }
    }

    @Test
    fun `the backfill report shows exactly what would be merged and a dry run writes nothing`() = runBlocking<Unit> {
        TestEnv.needPostgres()
        fixture("memdedupdry").use { f ->
            f.store.insertAll(
                transcript("e1", "ses"),
                model,
                listOf(
                    row("fact", "Todos los subagentes han terminado.", TestVectors.at(1.0, axis = 0)),
                    row("fact", "Todos los subagentes están en estado 'terminado'.", TestVectors.at(0.9994, axis = 0)),
                ),
            )
            val rows = f.store.dedupRows()
            val plan = MemoryDedup.plan(rows)
            val report = MemoryDedup.report(rows, plan, dryRun = true)
            assertThat(report.dryRun).isTrue
            assertThat(report.scanned).isEqualTo(2)
            assertThat(report.removed).isEqualTo(1)
            assertThat(report.clusters.single().keep).isEqualTo("Todos los subagentes han terminado.")
            assertThat(report.clusters.single().absorbedContent)
                .containsExactly("Todos los subagentes están en estado 'terminado'.")
            assertThat(count(f.db)).isEqualTo(2)
        }
    }
}
