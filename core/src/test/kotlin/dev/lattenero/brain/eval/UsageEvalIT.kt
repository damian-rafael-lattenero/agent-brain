package dev.lattenero.brain.eval

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.chat.FakeChatProvider
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.Migrator
import dev.lattenero.brain.db.sq.BrainDb
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.OffsetDateTime

/**
 * The application-specific eval's laws (the judge-graded production log): a
 * served chunk that grades relevant makes the case ok and feeds the graded
 * retrieval columns with the PRODUCTION latency; an unparsable verdict is an
 * honest case failure, never a crash; evidence lands on the row (graded,
 * verdict verbatim); a graded row never grades twice; a zero-sample run
 * returns null instead of a lying ledger row. Block-bodied tests throughout —
 * an expression-bodied test returns the assert and JUnit skips it silently
 * (the house gate [TestMethodsReturnUnitGate] says so loudly).
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UsageEvalIT {
    private lateinit var db: Database
    private lateinit var brainDb: BrainDb
    private lateinit var usageEval: UsageEval

    private val judge = FakeChatProvider { _, _ -> """{"relevant":[1,3]}""" }

    @BeforeAll
    fun up() = runBlocking {
        TestEnv.needPostgres()
        db = Database(TestDatabase.config("usage"))
        Migrator(db, 4).apply()
        brainDb = BrainDb(db.pool.asJdbcDriver())
        seedChunks()
        usageEval = UsageEval(db, judge, embedModel = "nomic-test", embedDim = 4)
    }

    @AfterAll
    fun down() {
        db.close()
    }

    private suspend fun seedChunks() {
        val doc = brainDb.ragQueries.upsertDocument(
            source = "usage-test", uri = "docs/usage-test.md", title = "usage test",
            content = "the body", sha = "sha-usage",
        ).executeAsOne()
        for (ordinal in 1..3) {
            brainDb.ragQueries.insertChunk(
                docId = doc, ordinal = ordinal, heading = "h$ordinal",
                content = "chunk $ordinal content about usage eval", embedModel = "nomic-test",
                embedding = "[1,0,0,0]",
            )
        }
    }

    private suspend fun logQuery(query: String, ids: List<Long>, latency: Int) {
        brainDb.ragQueryLogQueries.insertRagQueryLog(
            query = query,
            chunkIds = ids.joinToString(",", "[", "]"),
            topUris = ids.joinToString(",", "[", "]") { "\"docs/usage-test.md\"" },
            latencyMs = latency,
            at = OffsetDateTime.now(),
        )
    }

    @Test
    fun `a graded run scores the production latencies and rides the ledger`() {
        runBlocking {
            logQuery("what grades the usage eval?", listOf(1, 2), latency = 25)
            logQuery("second real query", listOf(2, 3), latency = 45)

            val report = usageEval.grade(sampleSize = 10)!!

            assertThat(report.suite).isEqualTo(UsageEval.SUITE)
            assertThat(report.kind).isEqualTo(EvalKind.USAGE)
            assertThat(report.total).isEqualTo(2)
            // The judge's indices are POSITIONS in the served list, not chunk
            // ids: {"relevant":[1,3]} clamps to [1] on a 2-chunk row → rank 1
            // for both rows.
            assertThat(report.cases.map { it.rank }).containsExactly(1, 1)
            assertThat(report.cases.map { it.relevantCount }).containsExactly(1, 1)
            assertThat(report.passed).isEqualTo(2)
            assertThat(report.ok).isTrue()
            // The latency columns are the PRODUCTION latencies (25/45), not the judge's.
            assertThat(report.metrics.p50Ms).isEqualTo(25L)
            assertThat(report.metrics.p95Ms).isEqualTo(45L)
            // The graded retrieval columns ride for free: MRR = 1.0.
            assertThat(report.metrics.retrieval).isNotNull()
            assertThat(report.metrics.retrieval!!.mrr).isEqualTo(1.0)

            val runs = EvalLedger(db, 4).latest(suite = UsageEval.SUITE)
            assertThat(runs).hasSize(1)
            assertThat(runs.single().ok).isTrue()
        }
    }

    @Test
    fun `a graded row never grades twice and an empty log answers null`() {
        runBlocking {
            assertThat(usageEval.ungradedCount()).isEqualTo(0)
            assertThat(usageEval.grade(sampleSize = 10)).isNull()
        }
    }

    @Test
    fun `chunks pruned since the query fail the case honestly`() {
        runBlocking {
            logQuery("query whose chunks vanished", listOf(9999), latency = 12)
            val report = usageEval.grade(sampleSize = 1)!!
            val case = report.cases.single()
            assertThat(case.ok).isFalse()
            assertThat(case.failure).contains("re-materialization")
            assertThat(report.ok).isFalse()
        }
    }

    @Test
    fun `an unparsable verdict is a case failure, not a crash`() {
        runBlocking {
            val rambler = UsageEval(
                db, FakeChatProvider { _, _ -> "the judge rambled instead of JSON" },
                embedModel = "nomic-test", embedDim = 4,
            )
            logQuery("the unparsable verdict query", listOf(1, 2), latency = 9)
            val report = rambler.grade(sampleSize = 1)!!
            val case = report.cases.single()
            assertThat(case.ok).isFalse()
            assertThat(case.failure).contains("unparsable verdict")
            // Evidence still lands: the row is graded, so a broken judge cannot spin.
            assertThat(usageEval.ungradedCount()).isEqualTo(0)
        }
    }

    @Test
    fun `verdict parse laws - range, dedup, unparsable`() {
        assertThat(UsageEval.UsageVerdict.parse("""{"relevant":[1,3]}""", 5).ranks).containsExactly(1, 3)
        // Out-of-range and duplicates are noise, dropped; the rest sorted.
        assertThat(UsageEval.UsageVerdict.parse("""{"relevant":[0,2,2,9]}""", 5).ranks).containsExactly(2)
        assertThat(UsageEval.UsageVerdict.parse("""{"relevant":[]}""", 5).ranks).isEmpty()
        assertThatThrownBy { UsageEval.UsageVerdict.parse("not json at all", 5) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { UsageEval.UsageVerdict.parse("""{"other":[]}""", 5) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
