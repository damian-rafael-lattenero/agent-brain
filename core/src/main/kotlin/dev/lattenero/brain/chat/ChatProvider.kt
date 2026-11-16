package dev.lattenero.brain.chat

import dev.lattenero.brain.config.Config
import dev.lattenero.brain.config.ConfigException
import dev.lattenero.brain.embeddings.ProviderException
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject
import java.util.UUID

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

/**
 * [truncated] is the provider's own "I stopped at the output cap" signal
 * (`done_reason: length` on Ollama, `finish_reason: length` on OpenAI-shaped
 * APIs). A cut generation is NOT a bad model answer — the 16/11 finding: 13
 * quarantined turns in two days labelled "not JSON" when the JSON was simply
 * never finished because `num_predict` was 512. The extractor reads this flag
 * and quarantines the turn as TRUNCATED instead of posing it as invalid.
 */
data class ChatResult(
    val text: String,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val truncated: Boolean = false,
)

class ChatException(message: String, cause: Throwable? = null) : ProviderException(message, cause)

object ChatProviders {

    /** OpenCode Zen's routing header: a stable per-process prefix + short uuid. */
    private const val SESSION_CHARS = 8

    /** One OpenAI-compatible remote on OpenCode Zen, tagged by leg (judge / chat). */
    private fun zen(
        client: HttpClient,
        baseUrl: String,
        model: String,
        apiKey: String,
        leg: String,
    ): ChatProvider = GlmChatProvider(
        client,
        baseUrl,
        model,
        apiKey,
        extraHeaders = mapOf("x-opencode-session" to leg + UUID.randomUUID().toString().take(SESSION_CHARS)),
    )

    fun create(config: Config, client: HttpClient): ChatProvider = when (config.chatProvider) {
        "ollama" -> OllamaChatProvider(client, config.ollamaBaseUrl, config.chatModel)
        "glm" -> GlmChatProvider(client, config.glmBaseUrl, config.glmChatModel, config.glmApiKey)
        // The extractor on a remote OpenAI-compatible model (16/11: the 1.5b
        // degenerates into repetition loops on giant transcripts — deepseek
        // does not). The key resolves from OPENAI_API_KEY with JUDGE_API_KEY
        // as the deliberate fallback (same relay, same secret).
        "openai" -> zen(client, config.chatBaseUrl, config.chatModel, config.chatApiKey, leg = "brain-chat-")
        "fake" -> FakeChatProvider()
        else -> throw ConfigException("chat_provider must be ollama, glm, openai or fake, got '${config.chatProvider}'")
    }

    /**
     * The JUDGE chat (E3's split-provider): verdicts, rerank and dream are
     * LOW-FREQUENCY, quality-critical calls — they may ride a DIFFERENT,
     * stronger model than the extractor. Absent config → null → the caller
     * falls back to the primary chat (today's behavior, unchanged). The
     * GlmChatProvider class is the OpenAI-compatible implementation: any base
     * URL + key + model that speaks /chat/completions with `response_format
     * json_object` works (OpenCode Zen included).
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
            // OpenCode Zen REJECTS requests without `x-opencode-session` (measured:
            // 400 MissingSessionID); a stable per-process id satisfies the router.
            "openai" -> zen(client, config.judgeBaseUrl, config.judgeModel, config.judgeApiKey, leg = "brain-judge-")
            "glm" -> GlmChatProvider(client, config.judgeBaseUrl, config.judgeModel, config.judgeApiKey)
            "fake" -> FakeChatProvider()
            else -> throw ConfigException("judge_provider must be ollama, glm, openai or fake, got '$provider'")
        }
    }
}
