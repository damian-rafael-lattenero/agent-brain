package dev.lattenero.brain.chat

import dev.lattenero.brain.TestDatabase
import dev.lattenero.brain.config.ConfigException
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The PRIMARY chat factory: one interface, four shapes. The openai branch is
 * the 16/11 extractor swap (the local 1.5b degenerates into repetition loops
 * on giant transcripts; the remote model does not). No network: construction only.
 */
class ChatProvidersCreateTest {

    private val client = HttpClient(CIO)

    @Test
    fun `an openai chat builds the OpenAI-compatible provider on the configured endpoint`() {
        val config = TestDatabase.config("chat_openai").copy(
            chatProvider = "openai",
            chatBaseUrl = "https://opencode.ai/zen/go/v1",
            chatModel = "deepseek-v4.1-flash",
            chatApiKey = "test-key",
        )
        val chat = ChatProviders.create(config, client)
        assertThat(chat.name).isEqualTo("openai")
        assertThat(chat.model).isEqualTo("deepseek-v4.1-flash")
    }

    @Test
    fun `an ollama chat keeps the local shape`() {
        val config = TestDatabase.config("chat_ollama").copy(chatProvider = "ollama", chatModel = "qwen2.5:1.5b")
        val chat = ChatProviders.create(config, client)
        assertThat(chat.name).isEqualTo("ollama")
        assertThat(chat.model).isEqualTo("qwen2.5:1.5b")
    }

    @Test
    fun `an unknown chat provider fails loudly`() {
        val config = TestDatabase.config("chat_bad").copy(chatProvider = "mystery")
        assertThrows<ConfigException> { ChatProviders.create(config, client) }
    }
}
