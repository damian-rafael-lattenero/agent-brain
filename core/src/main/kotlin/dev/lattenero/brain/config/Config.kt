package dev.lattenero.brain.config

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class ConfigException(message: String) : RuntimeException(message)

/**
 * THE single source of agent-brain paths and knobs (ADR-002).
 *
 * One immutable object built once at startup and passed by constructor to
 * every component; tests build their own, subprocesses receive the same
 * values through `AB_*` environment variables (the only contract black-box
 * tests rely on). Resolution order per knob: `AB_<NAME>` env > config.json >
 * [DEFAULTS]. An unknown key in config.json fails loudly: a typo in a knob
 * name must not degrade into a silently applied default. Secrets
 * (GLM_API_KEY) are environment-only on purpose and never land in the file.
 */
data class Config(
    val base: Path,
    val configPath: Path,
    val pgUrl: String,
    val pgUser: String,
    val pgPassword: String,
    val httpHost: String,
    val httpPort: Int,
    val embedProvider: String,
    val embedDim: Int,
    val embedBatch: Int,
    val ollamaBaseUrl: String,
    val ollamaModel: String,
    val glmBaseUrl: String,
    val glmModel: String,
    val glmApiKey: String,
    val httpTimeoutS: Int,
    val busTransport: String,
    val kafkaBootstrap: String,
    val kafkaTopic: String,
) {
    val stateDir: Path get() = base.resolve("state")
    val logDir: Path get() = base.resolve("logs")
    val busFile: Path get() = base.resolve("bus-events.jsonl")

    /** The effective knobs with secrets redacted, for /health and the CLI. */
    fun snapshot(): JsonObject = buildJsonObject {
        put("base", base.toString())
        put("config_path", configPath.toString())
        put("pg_url", pgUrl)
        put("pg_user", pgUser)
        put("pg_password_set", pgPassword.isNotEmpty())
        put("http", "$httpHost:$httpPort")
        put("embed_provider", embedProvider)
        put("embed_dim", embedDim)
        put("embed_batch", embedBatch)
        putJsonObject("ollama") {
            put("base_url", ollamaBaseUrl)
            put("model", ollamaModel)
        }
        putJsonObject("glm") {
            put("base_url", glmBaseUrl)
            put("model", glmModel)
            put("key_set", glmApiKey.isNotEmpty())
        }
        put("bus_transport", busTransport)
        put("bus_file", busFile.toString())
    }

    companion object {
        const val ENV_PREFIX = "AB_"
        const val MIGRATION_LOCK_ID = 727001L

        val DEFAULTS: Map<String, Any> = linkedMapOf(
            "pg_url" to "jdbc:postgresql://127.0.0.1:5433/agent_brain",
            "pg_user" to "brain",
            "pg_password" to "brain",
            "http_host" to "127.0.0.1",
            "http_port" to 8765,
            "embed_provider" to "ollama",
            "embed_dim" to 768,
            "embed_batch" to 32,
            "ollama_base_url" to "http://127.0.0.1:11434",
            "ollama_model" to "nomic-embed-text",
            "glm_base_url" to "https://api.z.ai/api/paas/v4",
            "glm_model" to "embedding-3",
            "http_timeout_s" to 60,
            "bus_transport" to "file",
            "kafka_bootstrap" to "127.0.0.1:19092",
            "kafka_topic" to "agent-brain.bus",
        )

        fun load(
            env: Map<String, String> = System.getenv(),
            home: Path = Paths.get(System.getProperty("user.home")),
        ): Config {
            val base = env["AB_BASE"]?.let(Paths::get) ?: home.resolve(".zcode").resolve("agent-brain")
            val configPath = env["AB_CONFIG"]?.let(Paths::get) ?: base.resolve("config.json")
            val fileCfg = readFile(configPath)
            val unknown = (fileCfg.keys - DEFAULTS.keys).sorted()
            if (unknown.isNotEmpty()) {
                throw ConfigException(
                    "unknown keys in $configPath: ${unknown.joinToString()} " +
                        "(known: ${DEFAULTS.keys.sorted().joinToString()})",
                )
            }
            fun str(name: String) = resolve(name, env, fileCfg) as String
            fun int(name: String) = resolve(name, env, fileCfg) as Int
            return Config(
                base = base,
                configPath = configPath,
                pgUrl = str("pg_url"),
                pgUser = str("pg_user"),
                pgPassword = str("pg_password"),
                httpHost = str("http_host"),
                httpPort = int("http_port"),
                embedProvider = str("embed_provider"),
                embedDim = int("embed_dim"),
                embedBatch = int("embed_batch"),
                ollamaBaseUrl = str("ollama_base_url"),
                ollamaModel = str("ollama_model"),
                glmBaseUrl = str("glm_base_url"),
                glmModel = str("glm_model"),
                glmApiKey = env["GLM_API_KEY"] ?: env["ZAI_API_KEY"] ?: "",
                httpTimeoutS = int("http_timeout_s"),
                busTransport = str("bus_transport"),
                kafkaBootstrap = str("kafka_bootstrap"),
                kafkaTopic = str("kafka_topic"),
            )
        }

        internal fun resolve(name: String, env: Map<String, String>, fileCfg: Map<String, JsonPrimitive>): Any {
            val default = DEFAULTS.getValue(name)
            env[ENV_PREFIX + name.uppercase()]?.let { return coerceEnv(name, default, it) }
            fileCfg[name]?.let { return coerceJson(name, default, it) }
            return default
        }

        private fun coerceEnv(name: String, default: Any, raw: String): Any = when (default) {
            is Int -> raw.trim().toIntOrNull()
                ?: throw ConfigException("$ENV_PREFIX${name.uppercase()} expects an integer, got '$raw'")
            else -> raw
        }

        private fun coerceJson(name: String, default: Any, value: JsonPrimitive): Any = when (default) {
            is Int -> value.intOrNull ?: throw ConfigException("$name expects an integer in config.json, got $value")
            else -> if (value.isString) value.content else throw ConfigException("$name expects a string in config.json, got $value")
        }

        private fun readFile(path: Path): Map<String, JsonPrimitive> {
            if (!Files.exists(path)) return emptyMap()
            val text = try {
                Files.readString(path)
            } catch (e: IOException) {
                throw ConfigException("config unreadable: $path (${e.message})")
            }
            val element = try {
                Json.parseToJsonElement(text)
            } catch (e: SerializationException) {
                throw ConfigException("config is not valid JSON: $path (${e.message})")
            }
            val obj = element as? JsonObject ?: throw ConfigException("config must be a JSON object: $path")
            return obj.mapValues { (key, value) ->
                value as? JsonPrimitive ?: throw ConfigException("$key must be a scalar value in $path")
            }
        }
    }
}
