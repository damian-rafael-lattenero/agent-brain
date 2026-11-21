package dev.lattenero.brain.eval

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.TestEnv
import dev.lattenero.brain.chat.ChatException
import dev.lattenero.brain.chat.ChatProvider
import dev.lattenero.brain.chat.ChatResult
import dev.lattenero.brain.chat.FakeChatProvider
import dev.lattenero.brain.db.Database
import dev.lattenero.brain.db.Migrator
import dev.lattenero.brain.db.sq.BrainDb
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.OffsetDateTime

/**
 * The corpus doctor's laws against a real Postgres: only graded FAILURES are
 * diagnosed (successes and ungraded rows stay out), a zero-hit query and a
 * chat failure degrade to UNCLASSIFIED with their evidence kept, identical
 * queries dedup into one case while failedTotal keeps the raw count, and an
 * empty window answers null instead of a lying report. Block-bodied tests
 * throughout — an expression-bodied test returns the assert and JUnit skips
 * it silently (the house gate [TestMethodsReturnUnitGate] says so loudly).
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CorpusDoctorIT {
    private lateinit var db: Database
    private lateinit var brainDb: BrainDb
    private lateinit var doctor: CorpusDoctor

    private val diagnosingJudge = FakeChatProvider { _, user ->
        if (user.contains("buried wording")) {
            """{"cause":"phrasing_mismatch","target_uri":"docs/doctor-test.md","proposal":"add the answer to the section heading"}"""
        } else {
            """{"cause":"corpus_gap","target_uri":"","proposal":"create a note covering the question"}"""
        }
    }

    @BeforeAll
    fun up() = runBlocking {
        TestEnv.needPostgres()
        db = Database(TestDatabase.config("doctor"))
        Migrator(db, 4).apply()
        brainDb = BrainDb(db.pool.asJdbcDriver())
        seedChunks()
        doctor = CorpusDoctor(db, diagnosingJudge)
    }

    @AfterAll
    fun down() {
        db.close()
    }

    /**
     * The doctor reads EVERY graded failure in its window — earlier tests'
     * rows would leak into later reports. Each test starts from an empty log
     * (order independence; the seeded chunks stay).
     */
    @BeforeEach
    fun cleanLog() {
        val deleted: Int = runBlocking {
            db.withConnection { conn ->
                conn.createStatement().use { it.executeUpdate("DELETE FROM rag_query_log") }
            }
        }
        assertThat(deleted).isGreaterThanOrEqualTo(0)
    }

    private suspend fun seedChunks() {
        val doc = brainDb.ragQueries.upsertDocument(
            source = "doctor-test", uri = "docs/doctor-test.md", title = "doctor test",
            content = "the body", sha = "sha-doctor",
        ).executeAsOne()
        for (ordinal in 1..3) {
            brainDb.ragQueries.insertChunk(
                docId = doc, ordinal = ordinal, heading = "h$ordinal",
                content = "chunk $ordinal content about the corpus", embedModel = "nomic-test",
                embedding = "[1,0,0,0]",
            )
        }
    }

    /** One production row: inserted ungraded, then graded by the usage judge with [relevantCount]. */
    private suspend fun seedGraded(
        query: String,
        ids: List<Long>,
        relevantCount: Int,
        at: OffsetDateTime = OffsetDateTime.now(),
    ) {
        seedUngraded(query, ids, at)
        val row = brainDb.ragQueryLogQueries.ungradedSample(100).executeAsList().first { it.query == query }
        brainDb.ragQueryLogQueries.markGraded(
            id = row.id,
            relevantCount = relevantCount,
            firstRelevantRank = null,
            verdicts = null,
            judgedAt = at,
        )
    }

    /** One production row that stays exactly as the HTTP boundary wrote it: ungraded. */
    private suspend fun seedUngraded(
        query: String,
        ids: List<Long>,
        at: OffsetDateTime = OffsetDateTime.now(),
    ) {
        brainDb.ragQueryLogQueries.insertRagQueryLog(
            query = query,
            chunkIds = ids.joinToString(",", "[", "]"),
            topUris = ids.joinToString(",", "[", "]") { "\"docs/doctor-test.md\"" },
            latencyMs = 20,
            at = at,
        )
    }

    @Test
    fun `only graded failures are diagnosed - successes and ungraded stay out`() {
        runBlocking {
            seedGraded("the corpus gap question", ids = listOf(1, 2), relevantCount = 0)
            seedGraded("the buried wording question", ids = listOf(2, 3), relevantCount = 0)
            seedGraded("the answered question", ids = listOf(1, 2), relevantCount = 2)
            seedUngraded("the ungraded question", ids = listOf(1))

            val report = doctor.diagnose(days = 7, limit = 30)!!

            assertThat(report.gradedTotal).isEqualTo(3)
            assertThat(report.failedTotal).isEqualTo(2)
            assertThat(report.cases.map { it.query }).containsExactlyInAnyOrder(
                "the corpus gap question",
                "the buried wording question",
            )
            assertThat(report.causes["corpus_gap"]).isEqualTo(1)
            assertThat(report.causes["phrasing_mismatch"]).isEqualTo(1)
            assertThat(report.failureRate).isEqualTo(2.0 / 3.0)
            // The judge saw the served chunks, and the case carries the query's history.
            assertThat(report.cases.single { it.cause == DoctorCause.PHRASING_MISMATCH }.targetUri)
                .isEqualTo("docs/doctor-test.md")
            assertThat(report.cases.single { it.cause == DoctorCause.CORPUS_GAP }.servedUris)
                .containsExactly("docs/doctor-test.md", "docs/doctor-test.md")
            // The doctor proposed, and the log's write path stayed untouched:
            // diagnosing is a read — the ungraded row keeps waiting for the
            // usage judge exactly as it was.
            assertThat(brainDb.ragQueryLogQueries.countUngraded().executeAsOne()).isEqualTo(1L)
        }
    }

    @Test
    fun `a zero-hit query degrades honestly`() {
        runBlocking {
            seedGraded("the zero-hit question", ids = emptyList(), relevantCount = 0)
            val report = doctor.diagnose(days = 7, limit = 30)!!
            val case = report.cases.single { it.query == "the zero-hit question" }
            assertThat(case.cause).isEqualTo(DoctorCause.UNCLASSIFIED)
            assertThat(case.evidence).contains("zero chunks")
            assertThat(case.proposal).isNull()
        }
    }

    @Test
    fun `a chat failure is unclassified with evidence, never a crash`() {
        runBlocking {
            val brokenDoctor = CorpusDoctor(db, object : ChatProvider {
                override val name = "fake-broken"
                override val model = "fake-broken-chat"
                override suspend fun completeJson(system: String, user: String, schema: JsonObject): ChatResult =
                    throw ChatException("connection reset")
            })
            seedGraded("the broken judge question", ids = listOf(1), relevantCount = 0)
            val report = brokenDoctor.diagnose(days = 7, limit = 30)!!
            val case = report.cases.single { it.query == "the broken judge question" }
            assertThat(case.cause).isEqualTo(DoctorCause.UNCLASSIFIED)
            assertThat(case.evidence).contains("judge call failed")
        }
    }

    @Test
    fun `identical queries dedup into one case while failedTotal keeps the raw count`() {
        runBlocking {
            seedGraded("the repeated gap", ids = listOf(1), relevantCount = 0)
            seedGraded("the repeated gap", ids = listOf(2), relevantCount = 0)
            val report = doctor.diagnose(days = 7, limit = 30)!!
            assertThat(report.failedTotal).isEqualTo(2)
            assertThat(report.cases.filter { it.query == "the repeated gap" }).hasSize(1)
        }
    }

    @Test
    fun `failures outside the window are invisible and an empty window answers null`() {
        runBlocking {
            seedGraded(
                "the ten-day-old failure",
                ids = listOf(1),
                relevantCount = 0,
                at = OffsetDateTime.now().minusDays(10),
            )
            assertThat(doctor.diagnose(days = 7, limit = 30)).isNull()
            assertThat(doctor.diagnose(days = 14, limit = 30)).isNotNull()
        }
    }

    @Test
    fun `the cap truncates honestly`() {
        runBlocking {
            for (n in 1..3) seedGraded("cap question $n", ids = listOf(1), relevantCount = 0)
            val report = doctor.diagnose(days = 7, limit = 2)!!
            assertThat(report.cases).hasSize(2)
            assertThat(report.truncated).isTrue()
            assertThat(report.failedTotal).isEqualTo(2)
        }
    }

    @Test
    fun `the markdown report carries the window stats and the proposals`() {
        runBlocking {
            seedGraded("the gap behind the markdown", ids = listOf(1), relevantCount = 0)
            seedGraded("the answered markdown question", ids = listOf(2), relevantCount = 1)
            val report = doctor.diagnose(days = 14, limit = 30)!!
            val markdown = report.markdown()
            assertThat(markdown).contains("Corpus doctor")
            assertThat(markdown).contains("Graded")
            assertThat(markdown).contains("corpus_gap")
            assertThat(markdown).contains("create a note covering the question")
        }
    }
}
