package dev.lattenero.brain.memory

import dev.lattenero.brain.chat.FakeChatProvider
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The verdict machinery's law battery: the parser is TOTAL (parse, don't
 * validate — any chat string decides or degrades to Distinct, never throws),
 * the schema's vocabulary decides, and the judge never lets a provider
 * failure escape as an exception (the write path must never block on chat).
 */
class VerdictLawsTest {

    /** LAW 1 — totality: ANY string parses into a Verdict; nothing throws. */
    @Test
    fun `law 1 - every string parses to a verdict`() = runBlocking<Unit> {
        checkAll(Arb.string(0..80)) { raw ->
            val verdict = VerdictParser.parse(raw)
            assertThat(verdict).isIn(listOf(Verdict.Same, Verdict.Supersede, Verdict.Distinct))
        }
    }

    /** LAW 2 — the vocabulary decides, case and whitespace do not matter. */
    @Test
    fun `law 2 - the schema vocabulary decides`() = runBlocking<Unit> {
        checkAll(Arb.int(0, 2), Arb.int(0, 6)) { verdictIndex, padding ->
            val wire = listOf("same", "supersede", "distinct")[verdictIndex]
            val padded = " ".repeat(padding) + wire.uppercase() + " ".repeat(padding)
            val raw = buildJsonObject { put("verdict", padded); put("rationale", "because") }.toString()
            val expected = listOf(Verdict.Same, Verdict.Supersede, Verdict.Distinct)[verdictIndex]
            assertThat(VerdictParser.parse(raw)).isEqualTo(expected)
        }
    }

    /** LAW 3 — an unreadable answer degrades honestly to Distinct (the safe verdict). */
    @Test
    fun `law 3 - unreadable answers degrade to distinct`() = runBlocking<Unit> {
        val degradations = listOf(
            "",
            "not json at all",
            "[1, 2, 3]",
            "\"supersede\"",
            "{}",
            buildJsonObject { put("verdict", 42) }.toString(),
            buildJsonObject { put("verdict", null) }.toString(),
            buildJsonObject { put("verdict", "probably same") }.toString(),
            buildJsonObject { put("verdict", "SAME"); put("verdict", 42) }.toString(),
        )
        for (raw in degradations) assertThat(VerdictParser.parse(raw)).isEqualTo(Verdict.Distinct)
    }

    /** LAW 4 — a chat that answers with a well-shaped verdict decides the pair. */
    @Test
    fun `law 4 - a well-shaped answer decides`() = runBlocking<Unit> {
        val chat = FakeChatProvider { _, _ ->
            FakeChatProvider.json("verdict" to JsonPrimitive("supersede"), "rationale" to JsonPrimitive("completed supersedes pending"))
        }
        val judge = VerdictJudge(chat, 5.seconds)
        val attempt = judge.judge("The deploy is pending.", "The deploy completed today.")
        assertThat(attempt).isEqualTo(VerdictAttempt.Decided(Verdict.Supersede, "completed supersedes pending"))
        assertThat(chat.calls).hasSize(1) // ONE call per pair
    }

    /** LAW 5 — a chat outage is a Skipped, never an exception, never a verdict. */
    @Test
    fun `law 5 - a provider failure skips, never throws`() = runBlocking<Unit> {
        val chat = FakeChatProvider { _, _ -> throw IllegalStateException("ollama is down") }
        val judge = VerdictJudge(chat, 5.seconds)
        val attempt = judge.judge("old claim", "new claim")
        assertThat(attempt).isInstanceOf(VerdictAttempt.Skipped::class.java)
    }

    /** LAW 6 — a hanging chat times out into a Skipped within the judge's own budget. */
    @Test
    fun `law 6 - a hanging provider times out into a skip`() = runBlocking<Unit> {
        // A provider that hangs like the real HTTP ones do: the call suspends
        // (the network read), so the judge's own withTimeout is what saves the
        // write path.
        val slow = object : dev.lattenero.brain.chat.ChatProvider {
            override val name = "slow"
            override val model = "slow-chat"
            override suspend fun completeJson(
                system: String,
                user: String,
                schema: kotlinx.serialization.json.JsonObject,
            ): dev.lattenero.brain.chat.ChatResult {
                kotlinx.coroutines.delay(5.seconds)
                return dev.lattenero.brain.chat.ChatResult(FakeChatProvider.json("verdict" to JsonPrimitive("same")))
            }
        }
        val judge = VerdictJudge(slow, 50.milliseconds)
        val attempt = judge.judge("old claim", "new claim")
        assertThat(attempt).isInstanceOf(VerdictAttempt.Skipped::class.java)
    }

    /** LAW 7 — the judge's ONE message carries old first, new second: order is meaning. */
    @Test
    fun `law 7 - the pair's message is old first, new second`() = runBlocking<Unit> {
        val chat = FakeChatProvider { _, _ -> FakeChatProvider.json("verdict" to JsonPrimitive("distinct")) }
        VerdictJudge(chat, 5.seconds).judge("the old claim", "the new claim")
        val (_, user) = chat.calls.single()
        assertThat(user).isEqualTo("OLD: the old claim\nNEW: the new claim\nWhich verdict?")
    }
}
