package dev.lattenero.brain.chat

import dev.lattenero.brain.config.Config
import dev.lattenero.brain.config.ConfigException
import dev.lattenero.brain.embeddings.ProviderException
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject

/**
 * ONE interface for "give me a JSON object that matches this schema". Both
 * remote implementations ask the model for constrained JSON (Ollama's
 * structured outputs, Z.ai's json_object response format); the caller still
 * validates, because a schema constrains shape, not truth.
 */
interface ChatProvider : AutoCloseable {
    val name: String
    val model: String

    /** Returns the raw text the model produced (expected to be a JSON object) plus token usage when known. */
    suspend fun completeJson(system: String, user: String, schema: JsonObject): ChatResult

    override fun close() {}
}

data class ChatResult(val text: String, val promptTokens: Int? = null, val completionTokens: Int? = null)

class ChatException(message: String, cause: Throwable? = null) : ProviderException(message, cause)

object ChatProviders {
    fun create(config: Config, client: HttpClient): ChatProvider = when (config.chatProvider) {
        "ollama" -> OllamaChatProvider(client, config.ollamaBaseUrl, config.chatModel)
        "glm" -> GlmChatProvider(client, config.glmBaseUrl, config.glmChatModel, config.glmApiKey)
        "fake" -> FakeChatProvider()
        else -> throw ConfigException("chat_provider must be ollama, glm or fake, got '${config.chatProvider}'")
    }

    /**
     * The JUDGE chat (E3's split-provider): verdicts, rerank and dream are
     * LOW-FREQUENCY, quality-critical calls — they may ride a DIFFERENT,
     * stronger model than the extractor (which stays local and free for the
     * high-volume private path). Absent config → null → the caller falls back
     * to the primary chat (today's behavior, unchanged). The GlmChatProvider
     * class is the OpenAI-compatible implementation: any base URL + key +
     * model that speaks /chat/completions with `response_format json_object`
     * works (OpenCode Zen included).
     */
    fun judge(config: Config, client: HttpClient): ChatProvider? {
        val provider = config.judgeProvider
        if (provider.isBlank()) return null
        return when (provider) {
            "ollama" -> OllamaChatProvider(
                client,
                config.judgeBaseUrl.ifBlank { config.ollamaBaseUrl },
                config.judgeModel,
            )
            "glm", "openai" -> GlmChatProvider(client, config.judgeBaseUrl, config.judgeModel, config.judgeApiKey)
            "fake" -> FakeChatProvider()
            else -> throw ConfigException("judge_provider must be ollama, glm, openai or fake, got '$provider'")
        }
    }
}
