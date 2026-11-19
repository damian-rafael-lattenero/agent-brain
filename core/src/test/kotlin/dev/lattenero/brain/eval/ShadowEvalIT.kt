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
import java.nio.file.Files
import java.time.OffsetDateTime

/**
 * The shadow judge's laws (plan v2 T1): the cursor starts 24 h back, so the
 * FIRST run grades yesterday's turns; a reply under the length floor is not
 * quality data and never reaches the judge; a rubric score 4+ passes and the
 * verdict rides the case; the cursor advances to the newest judged turn, so
 * a completed run never repeats; a missing prompt grades on the reply alone.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShadowEvalIT {
    private lateinit var db: Database
    private lateinit var brainDb: BrainDb
    private lateinit var cursor: java.nio.file.Path
    private lateinit var shadowEval: ShadowEval

    private val judge = FakeChatProvider { _, _ -> """{"score":4,"reason":"responde lo pedido"}""" }

    @BeforeAll
    fun up() = runBlocking {
        TestEnv.needPostgres()
        db = Database(TestDatabase.config("shadow"))
        Migrator(db, 4).apply()
        brainDb = BrainDb(db.pool.asJdbcDriver())
        cursor = Files.createTempFile("shadow-cursor", ".json")
        Files.writeString(cursor, """{"lastTs":"1970-01-01T00:00Z"}""")
        shadowEval = ShadowEval(db, judge, cursorFile = cursor, embedModel = "nomic-test", embedDim = 4)
    }

    @AfterAll
    fun down() {
        db.close()
    }

    private suspend fun seedTurn(sid: String, turn: String, prompt: String, reply: String, ts: OffsetDateTime) {
        brainDb.busEventsQueries.ingestEvents(
            id = "$sid-$turn-prompt",
            ts = ts,
            kind = "user.prompt",
            sid = sid,
            turn = turn,
            source = "telegram",
            payload = """{"prompt": ${jsonStr(prompt)}}""",
        )
        brainDb.busEventsQueries.ingestEvents(
            id = "$sid-$turn-completed",
            ts = ts.plusSeconds(5),
            kind = "turn.completed",
            sid = sid,
            turn = turn,
            source = "telegram",
            payload = """{"reply": ${jsonStr(reply)}}""",
        )
    }

    private fun jsonStr(s: String): String = kotlinx.serialization.json.JsonPrimitive(s).toString()

    @Test
    fun `the first run grades yesterday's turns, the cursor never repeats`() {
        runBlocking {
            val now = OffsetDateTime.now()
            seedTurn("s1", "t1", "\"como está el cluster\"", "\"El cluster está verde: 3/3 pods, latencia p50 120ms.\"", now.minusHours(2))
            seedTurn("s1", "t2", "\"pasame el diff\"", "\"Aca va el diff de los tres archivos con las lineas marcadas.\"", now.minusHours(1))

            val report = shadowEval.grade(limit = 10, minReplyLen = 40)!!
            assertThat(report.suite).isEqualTo(ShadowEval.SUITE)
            assertThat(report.kind).isEqualTo(EvalKind.SHADOW)
            // The table is shared across tests: this test owns the s1 turns,
            // whichever order JUnit picked for the others.
            val mine = report.cases.filter { it.id.startsWith("s1-") }
            assertThat(mine).hasSize(2)
            // The judge scored 4 → both pass the rubric floor.
            assertThat(mine.map { it.ok }).containsOnly(true)
            assertThat(report.ok).isTrue()
            // The verdict rides the case as evidence.
            assertThat(mine.map { it.verdict }).containsOnly("4")

            // The cursor advanced: a second run has nothing new.
            assertThat(shadowEval.grade(limit = 10, minReplyLen = 40)).isNull()
        }
    }

    @Test
    fun `a trivial reply under the length floor never reaches the judge`() {
        runBlocking {
            val now = OffsetDateTime.now()
            seedTurn("s2", "t1", "\"ok?\"", "\"ok\"", now.minusMinutes(30))
            val before = judge.calls.size
            assertThat(shadowEval.grade(limit = 10)).isNull()
            assertThat(judge.calls.size).isEqualTo(before)
        }
    }

    @Test
    fun `a low rubric score fails the case and the run`() {
        runBlocking {
            val strict = ShadowEval(
                db,
                FakeChatProvider { _, _ -> """{"score":2,"reason":"no responde el pedido"}""" },
                cursorFile = Files.createTempFile("shadow-c2", ".json"),
                embedModel = "nomic-test", embedDim = 4,
            )
            seedTurn("s3", "t1", "\"dame el runbook\"", "\"El runbook es un archivo muy grande con muchas secciones utiles.\"", OffsetDateTime.now().minusMinutes(10))
            val report = strict.grade(limit = 5, minReplyLen = 40)!!
            assertThat(report.passed).isEqualTo(0)
            assertThat(report.ok).isFalse()
            assertThat(report.cases.single().verdict).isEqualTo("2")
        }
    }

    @Test
    fun `verdict parse laws - score range, reason, unparsable`() {
        assertThat(ShadowEval.ShadowVerdict.parse("""{"score":5,"reason":"ok"}""").score).isEqualTo(5)
        assertThatThrownBy { ShadowEval.ShadowVerdict.parse("""{"score":9,"reason":"x"}""") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ShadowEval.ShadowVerdict.parse("""{"reason":"no score"}""") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ShadowEval.ShadowVerdict.parse("rambled") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
