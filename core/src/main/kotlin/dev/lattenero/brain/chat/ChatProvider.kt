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
}
