package dev.lattenero.brain.bb

import dev.lattenero.brain.TestEnv
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tier 5: the author's Telegram account drives the bot over MTProto and
 * reads the chat back; the brain is observed through the bot. The Python
 * driver (tools/e2e-real/driver.py, Telethon) does the talking; this test
 * runs it and asserts its report. Needs AB_TG_API_ID, AB_TG_API_HASH,
 * AB_TG_SESSION (tools/e2e-real/login.py, one-time) and AB_TG_BOT.
 */
@Tag("bb-real")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealTelegramBB {
    private val json = Json { ignoreUnknownKeys = true }
    private val repoRoot = File(System.getProperty("ab.repoRoot") ?: File(System.getProperty("user.dir")).parent)
    private val python = System.getenv("AB_E2E_PYTHON")?.takeIf { it.isNotBlank() } ?: "python3"

    @BeforeAll
    fun gate() {
        val missing = listOf("AB_TG_API_ID", "AB_TG_API_HASH", "AB_TG_SESSION", "AB_TG_BOT").filter { System.getenv(it).isNullOrBlank() }
        if (missing.isNotEmpty()) {
            val why = "tier 5 needs ${missing.joinToString()} (one-time: tools/e2e-real/login.py)"
            if (TestEnv.required) fail<Unit>(why) else assumeTrue(false, "$why; skipping")
        }
        assertThat(File(repoRoot, "tools/e2e-real/driver.py")).exists()
    }

    private fun drive(scenario: String) {
        val process = ProcessBuilder(python, "tools/e2e-real/driver.py", scenario).directory(repoRoot)
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val out = process.inputStream.bufferedReader().readText()
        assertThat(process.waitFor(20, TimeUnit.MINUTES)).withFailMessage("driver hung on %s", scenario).isTrue
        println(out)
        val report = json.parseToJsonElement(out).jsonObject
        assertThat(report["ok"]!!.jsonPrimitive.content).withFailMessage("scenario %s failed: %s", scenario, report["error"]).isEqualTo("true")
        assertThat(process.exitValue()).isZero
    }

    @Test
    fun `selfcheck - the session logs in and sees the bot`() = drive("selfcheck")

    @Test
    fun `p1 - a real phone turn lands on the bus`() = drive("p1")

    @Test
    fun `p3 - a stated preference is recalled by the brain on a later turn`() = drive("p3")
}
