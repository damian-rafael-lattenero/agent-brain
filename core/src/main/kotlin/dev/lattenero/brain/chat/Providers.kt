package dev.lattenero.brain.chat

import dev.lattenero.brain.embeddings.glmErrors
import dev.lattenero.brain.embeddings.ollamaErrors
import dev.lattenero.brain.embeddings.postForProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.setBody
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** POST {base}/api/chat with `format` = the JSON schema (Ollama structured outputs), temperature 0, no streaming. */
class OllamaChatProvider(private val client: HttpClient, baseUrl: String, override val model: String) : ChatProvider {
    override val name = "ollama"
    private val baseUrl = baseUrl.trimEnd('/')

    @Serializable
    private data class Message(val role: String, val content: String)

    @Serializable
    private data class Response(val message: Message? = null, val prompt_eval_count: Int? = null, val eval_count: Int? = null, val error: String? = null)

    override suspend fun completeJson(system: String, user: String, schema: JsonObject): ChatResult {
        val url = "$baseUrl/api/chat"
        val body: JsonElement = buildJsonObject {
            put("model", model)
            put("stream", false)
            put("format", schema)
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            }
            put("options", buildJsonObject { put("temperature", 0) })
        }
        val response = postForProvider(client, url, "ollama chat", ollamaErrors(model, ::ChatException)) {
            setBody(body)
        }
        val parsed: Response = response.body()
        val content = parsed.message?.content ?: throw ChatException("ollama chat answered without a message: ${parsed.error ?: "?"}")
        return ChatResult(content, parsed.prompt_eval_count, parsed.eval_count)
    }
}

/** POST {base}/chat/completions with response_format json_object (OpenAI-shaped), temperature 0. */
class GlmChatProvider(private val client: HttpClient, baseUrl: String, override val model: String, private val apiKey: String) : ChatProvider {
    override val name = "glm"
    private val baseUrl = baseUrl.trimEnd('/')

    init {
        if (apiKey.isBlank()) throw ChatException("GLM_API_KEY is not set; export it or switch chat_provider to ollama")
    }

    @Serializable
    private data class Message(val role: String, val content: String)

    @Serializable
    private data class Choice(val message: Message)

    @Serializable
    private data class Usage(val prompt_tokens: Int? = null, val completion_tokens: Int? = null)

    @Serializable
    private data class Response(val choices: List<Choice> = emptyList(), val usage: Usage? = null)

    override suspend fun completeJson(system: String, user: String, schema: JsonObject): ChatResult {
        val url = "$baseUrl/chat/completions"
        val body: JsonElement = buildJsonObject {
            put("model", model)
            put("temperature", 0)
            put("response_format", buildJsonObject { put("type", "json_object") })
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", system + "\nThe JSON must match this schema: " + schema) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            }
        }
        val response = postForProvider(client, url, "glm chat", glmErrors(::ChatException)) {
            headers.append("Authorization", "Bearer $apiKey")
            setBody(body)
        }
        val parsed: Response = response.body()
        val content = parsed.choices.firstOrNull()?.message?.content ?: throw ChatException("glm answered without choices")
        return ChatResult(content, parsed.usage?.prompt_tokens, parsed.usage?.completion_tokens)
    }
}

/** Scripted answers for tests; records every call. */
class FakeChatProvider(private val responder: (system: String, user: String) -> String = { _, _ -> "{}" }) : ChatProvider {
    override val name = "fake"
    override val model = "fake-chat"
    val calls = ArrayList<Pair<String, String>>()

    override suspend fun completeJson(system: String, user: String, schema: JsonObject): ChatResult {
        calls += system to user
        return ChatResult(responder(system, user), promptTokens = user.length / 4, completionTokens = 20)
    }

    companion object {
        fun json(vararg pairs: Pair<String, JsonElement>): String = JsonObject(pairs.toMap()).toString()
        fun strings(vararg items: String): JsonElement = kotlinx.serialization.json.JsonArray(items.map { JsonPrimitive(it) })
    }
}
