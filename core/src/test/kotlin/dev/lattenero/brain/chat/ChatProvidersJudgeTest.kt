package dev.lattenero.brain.chat

import dev.lattenero.brain.TestDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

/**
 * E3's judge-chat selection: absent config → null (the caller falls back to
 * the primary chat — today's behavior, unchanged); a configured judge builds
 * the right shape (OpenAI-compatible for glm/openai, ollama for ollama).
 * No network: construction only.
 */
class ChatProvidersJudgeTest {

    private val client = HttpClient(CIO)

    @Test
    fun `no judge config means null - the caller falls back to the primary chat`() {
        val config = TestDatabase.config("judge_none")
        assertThat(ChatProviders.judge(config.copy(judgeProvider = ""), client)).isNull()
    }

    @Test
    fun `a glm judge builds the OpenAI-compatible provider with its own coordinates`() {
        val config = TestDatabase.config("judge_glm").copy(
            judgeProvider = "glm",
            judgeBaseUrl = "https://opencode.ai/zen/go/v1",
            judgeModel = "deepseek-v4.1-flash",
            judgeApiKey = "test-key",
        )
        val judge = ChatProviders.judge(config, client)
        assertThat(judge).isNotNull
        assertThat(judge!!.name).isEqualTo("openai")
        assertThat(judge.model).isEqualTo("deepseek-v4.1-flash")
    }

    @Test
    fun `an ollama judge defaults its base url to the local server`() {
        val config = TestDatabase.config("judge_ollama").copy(
            judgeProvider = "ollama",
            judgeModel = "qwen2.5:3b",
            judgeBaseUrl = "",
        )
        val judge = ChatProviders.judge(config, client)
        assertThat(judge).isNotNull
        assertThat(judge!!.name).isEqualTo("ollama")
        assertThat(judge.model).isEqualTo("qwen2.5:3b")
    }

    @Test
    fun `an unknown judge provider fails loudly`() {
        val config = TestDatabase.config("judge_bad").copy(judgeProvider = "mystery")
        org.junit.jupiter.api.assertThrows<dev.lattenero.brain.config.ConfigException> {
            ChatProviders.judge(config, client)
        }
    }
}
