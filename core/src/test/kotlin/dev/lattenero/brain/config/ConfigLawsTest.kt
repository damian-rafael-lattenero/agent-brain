package dev.lattenero.brain.config

import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.numericDouble
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The fold laws of [Config.load] (plan E6), over generated knobs and values:
 * every knob resolves as `env > file > DEFAULTS`, with the declared type's
 * coercion; an unknown key in config.json fails loudly naming the key; a
 * value that cannot be coerced fails naming the knob — never a silent
 * default. The file IO goes through the injected [Path] (a @TempDir);
 * everything else is the pure fold.
 */
class ConfigLawsTest {
    @TempDir
    lateinit var tmp: Path

    /** Every knob of the fold to the field it lands in: the two must cover DEFAULTS exactly. */
    private val fields: Map<String, (Config) -> Any> = mapOf(
        "pg_url" to { c -> c.pgUrl },
        "pg_user" to { c -> c.pgUser },
        "pg_password" to { c -> c.pgPassword },
        "http_host" to { c -> c.httpHost },
        "http_port" to { c -> c.httpPort },
        "embed_provider" to { c -> c.embedProvider },
        "embed_dim" to { c -> c.embedDim },
        "embed_batch" to { c -> c.embedBatch },
        "ollama_base_url" to { c -> c.ollamaBaseUrl },
        "ollama_model" to { c -> c.ollamaModel },
        "glm_base_url" to { c -> c.glmBaseUrl },
        "glm_model" to { c -> c.glmModel },
        "chat_provider" to { c -> c.chatProvider },
        "chat_model" to { c -> c.chatModel },
        "glm_chat_model" to { c -> c.glmChatModel },
        "chat_timeout_s" to { c -> c.chatTimeoutS },
        "memory_worker" to { c -> c.memoryWorker },
        "memory_poll_ms" to { c -> c.memoryPollMs },
        "memory_half_life_days" to { c -> c.memoryHalfLifeDays },
        "http_timeout_s" to { c -> c.httpTimeoutS },
        "bus_transport" to { c -> c.busTransport },
        "bus_poll_ms" to { c -> c.busPollMs },
        "index_doc_concurrency" to { c -> c.indexDocConcurrency },
        "embed_concurrency" to { c -> c.embedConcurrency },
        "embed_breaker_failures" to { c -> c.embedBreakerFailures },
        "embed_breaker_reset_s" to { c -> c.embedBreakerResetS },
        "kafka_bootstrap" to { c -> c.kafkaBootstrap },
        "kafka_topic" to { c -> c.kafkaTopic },
        "kafka_group" to { c -> c.kafkaGroup },
        "kafka_partitions" to { c -> c.kafkaPartitions },
    )

    private val knobNames: List<String> = Config.DEFAULTS.keys.toList()

    private fun load(env: Map<String, String>, file: String?): Config {
        val base = tmp.resolve("base").also { Files.createDirectories(it) }
        val configFile = base.resolve("config.json")
        Files.deleteIfExists(configFile)
        if (file != null) Files.writeString(configFile, file)
        return Config.load(env = mapOf("AB_BASE" to base.toString()) + env, home = tmp)
    }

    /** A value of the knob's declared type, in the shape the file would carry. */
    private fun typedValue(name: String): Arb<Any> = when (Config.DEFAULTS.getValue(name)) {
        is Boolean -> Arb.boolean().map { it as Any }
        is Int -> Arb.int(-1_000, 100_000).map { it as Any }
        else -> if (name == "bus_transport") {
            // A closed domain: only real transports generate, the fold law stays decidable.
            Arb.of(Config.BUS_TRANSPORTS.toList()).map { it as Any }
        } else {
            Arb.string(0..24).map { it as Any }
        }
    }

    /** One generated (knob, value) pair of the knob's own type. */
    private fun knobValue(): Arb<Pair<String, Any>> = Arb.int(0, knobNames.size - 1)
        .map { knobNames[it] }
        .flatMap { name -> typedValue(name).map { name to it } }

    /** How the environment spells the value: integers may be padded, booleans use a truthy/falsy spelling. */
    private fun envText(value: Any): String = when (value) {
        is Int -> " $value "
        is Boolean -> if (value) "YES" else "no"
        else -> value as String
    }

    /** How config.json spells the value: a typed scalar, so the coercion path is exercised. */
    private fun fileJson(name: String, value: Any): String = buildJsonObject {
        when (value) {
            is Int -> put(name, value)
            is Boolean -> put(name, value)
            else -> put(name, value as String)
        }
    }.toString()

    /** A different value of the same type: the file's opinion when the environment must win. */
    private fun otherValue(value: Any): Any = when (value) {
        is Int -> value + 1
        is Boolean -> !value
        else -> if (value in Config.BUS_TRANSPORTS) {
            Config.BUS_TRANSPORTS.first { it != value }
        } else {
            "$value-file"
        }
    }

    private fun knobOf(config: Config, name: String): Any = fields.getValue(name)(config)

    @Test
    fun `the selectors cover the defaults exactly - a knob without a field would hide a broken law`() {
        assertThat(fields.keys).isEqualTo(Config.DEFAULTS.keys)
    }

    @Test
    fun `neither env nor file means the default, for every knob`() {
        runBlocking {
            checkAll(200, Arb.int(0, knobNames.size - 1)) { index ->
                val name = knobNames[index]
                val config = load(emptyMap(), file = null)
                assertThat(knobOf(config, name)).withFailMessage(name).isEqualTo(Config.DEFAULTS.getValue(name))
            }
        }
    }

    @Test
    fun `the fold is env over file over default, with the declared type's coercion`() {
        runBlocking {
            checkAll(200, knobValue()) { (name, value) ->
                val envKey = Config.ENV_PREFIX + name.uppercase()

                val fileOnly = load(emptyMap(), file = fileJson(name, value))
                assertThat(knobOf(fileOnly, name)).withFailMessage("file only: $name").isEqualTo(value)

                val envOnly = load(mapOf(envKey to envText(value)), file = null)
                assertThat(knobOf(envOnly, name)).withFailMessage("env only: $name").isEqualTo(value)

                val both = load(mapOf(envKey to envText(value)), file = fileJson(name, otherValue(value)))
                assertThat(knobOf(both, name)).withFailMessage("env over file: $name").isEqualTo(value)
            }
        }
    }

    @Test
    fun `an unknown key in config json fails loudly, naming the key`() {
        runBlocking {
            checkAll(Arb.string(0..20)) { tail ->
                val unknown = "zz_$tail"
                assertThatThrownBy { load(emptyMap(), file = buildJsonObject { put(unknown, "x") }.toString()) }
                    .isInstanceOf(ConfigException::class.java)
                    .hasMessageContaining(unknown)
                    .hasMessageContaining("known:")
            }
        }
    }

    @Test
    fun `an integer knob whose environment value is not an integer fails naming the knob`() {
        runBlocking {
            checkAll(Arb.int(0, knobNames.size - 1), Arb.string(0..10)) { index, tail ->
                val name = knobNames[index]
                if (Config.DEFAULTS.getValue(name) is Int) {
                    val envKey = Config.ENV_PREFIX + name.uppercase()
                    assertThatThrownBy { load(mapOf(envKey to "not-int-$tail"), file = null) }
                        .isInstanceOf(ConfigException::class.java)
                        .hasMessageContaining(envKey)
                }
            }
        }
    }

    @Test
    fun `a knob whose json value has the wrong type fails naming the knob`() {
        runBlocking {
            checkAll(Arb.string(0..10)) { tail ->
                // An integer knob given a JSON string, a boolean knob given a JSON number, a string knob given an object:
                // three coercion failures, each with its own message shape.
                assertThatThrownBy { load(emptyMap(), file = """{"http_port": "not-$tail"}""") }
                    .isInstanceOf(ConfigException::class.java).hasMessageContaining("http_port")
                assertThatThrownBy { load(emptyMap(), file = """{"memory_worker": 7}""") }
                    .isInstanceOf(ConfigException::class.java).hasMessageContaining("memory_worker")
                assertThatThrownBy { load(emptyMap(), file = """{"pg_url": {"nested": "$tail"}}""") }
                    .isInstanceOf(ConfigException::class.java).hasMessageContaining("pg_url")
            }
        }
    }

    @Test
    fun `the secret knobs are environment only and never resolved from the file`() {
        runBlocking {
            checkAll(Arb.string(0..10)) { tail ->
                // GLM_API_KEY is not a DEFAULTS knob: it must be an unknown key in config.json, never a silent value.
                assertThatThrownBy { load(emptyMap(), file = """{"glm_api_key": "$tail"}""") }
                    .isInstanceOf(ConfigException::class.java).hasMessageContaining("glm_api_key")
                val fromEnv = load(mapOf("GLM_API_KEY" to "sk-$tail"), file = null)
                assertThat(fromEnv.glmApiKey).isEqualTo("sk-$tail")
            }
        }
    }

    @Test
    fun `doubles are not silently accepted for integer knobs`() {
        runBlocking {
            checkAll(Arb.numericDouble(0.5, 100.0)) { number ->
                assertThatThrownBy { load(emptyMap(), file = """{"embed_dim": $number}""") }
                    .isInstanceOf(ConfigException::class.java)
                    .hasMessageContaining("embed_dim")
            }
        }
    }

    @Test
    fun `an unknown bus_transport fails loudly naming the key and the valid values`() {
        runBlocking {
            checkAll(Arb.string(0..10, Codepoint.alphanumeric())) { tail ->
                // The kaf_ prefix can never collide with a valid transport ("file", "kafka");
                // alphanumerics only, so the JSON stays well-formed and the knob-level law is exercised.
                val typo = "kaf_$tail"
                assertThatThrownBy { load(emptyMap(), file = """{"bus_transport": "$typo"}""") }
                    .isInstanceOf(ConfigException::class.java)
                    .hasMessageContaining("bus_transport")
                    .hasMessageContaining(Config.BUS_TRANSPORT_FILE)
                    .hasMessageContaining(Config.BUS_TRANSPORT_KAFKA)
                    .hasMessageContaining(typo)
                assertThatThrownBy { load(mapOf("AB_BUS_TRANSPORT" to typo), file = null) }
                    .isInstanceOf(ConfigException::class.java)
                    .hasMessageContaining("bus_transport")
            }
        }
    }
}
