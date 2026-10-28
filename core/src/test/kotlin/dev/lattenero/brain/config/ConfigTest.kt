package dev.lattenero.brain.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ConfigTest {
    @TempDir
    lateinit var tmp: Path

    private fun load(env: Map<String, String> = emptyMap(), file: String? = null): Config {
        val base = tmp.resolve("base")
        Files.createDirectories(base)
        if (file != null) Files.writeString(base.resolve("config.json"), file)
        return Config.load(env = mapOf("AB_BASE" to base.toString()) + env, home = tmp)
    }

    @Test
    fun `defaults apply when neither env nor file says otherwise`() {
        val config = load()
        assertThat(config.pgUrl).isEqualTo(Config.DEFAULTS["pg_url"])
        assertThat(config.httpPort).isEqualTo(8765)
        assertThat(config.embedProvider).isEqualTo("ollama")
        assertThat(config.embedDim).isEqualTo(768)
        assertThat(config.glmApiKey).isEmpty()
    }

    @Test
    fun `AB_BASE drives every derived path`() {
        val config = load()
        assertThat(config.base).isEqualTo(tmp.resolve("base"))
        assertThat(config.busFile).isEqualTo(tmp.resolve("base").resolve("bus-events.jsonl"))
        assertThat(config.stateDir).isEqualTo(tmp.resolve("base").resolve("state"))
        assertThat(config.configPath).isEqualTo(tmp.resolve("base").resolve("config.json"))
    }

    @Test
    fun `environment beats file beats default, with integer coercion`() {
        val config = load(
            env = mapOf("AB_HTTP_PORT" to " 9001 ", "AB_EMBED_PROVIDER" to "fake"),
            file = """{"http_port": 9000, "embed_dim": 512, "embed_provider": "glm"}""",
        )
        assertThat(config.httpPort).isEqualTo(9001)
        assertThat(config.embedProvider).isEqualTo("fake")
        assertThat(config.embedDim).isEqualTo(512)
    }

    @Test
    fun `a non-integer in an integer knob fails loudly`() {
        assertThatThrownBy { load(env = mapOf("AB_HTTP_PORT" to "eighty")) }
            .isInstanceOf(ConfigException::class.java)
            .hasMessageContaining("AB_HTTP_PORT")
        assertThatThrownBy { load(file = """{"embed_dim": "big"}""") }
            .isInstanceOf(ConfigException::class.java)
            .hasMessageContaining("embed_dim")
    }

    @Test
    fun `an unknown key in config json is an error, never a silent default`() {
        assertThatThrownBy { load(file = """{"embed_provder": "ollama"}""") }
            .isInstanceOf(ConfigException::class.java)
            .hasMessageContaining("embed_provder")
            .hasMessageContaining("known:")
    }

    @Test
    fun `malformed config files are rejected with the path`() {
        assertThatThrownBy { load(file = "{not json") }
            .isInstanceOf(ConfigException::class.java)
            .hasMessageContaining("config.json")
        assertThatThrownBy { load(file = "[1, 2]") }
            .isInstanceOf(ConfigException::class.java)
            .hasMessageContaining("JSON object")
        assertThatThrownBy { load(file = """{"pg_url": {"nested": true}}""") }
            .isInstanceOf(ConfigException::class.java)
            .hasMessageContaining("scalar")
    }

    @Test
    fun `secrets come from the environment only and never show in the snapshot`() {
        val fromGlm = load(env = mapOf("GLM_API_KEY" to "sk-glm"))
        val fromZai = load(env = mapOf("ZAI_API_KEY" to "sk-zai"))
        assertThat(fromGlm.glmApiKey).isEqualTo("sk-glm")
        assertThat(fromZai.glmApiKey).isEqualTo("sk-zai")

        val snapshot = fromGlm.copy(pgPassword = "hunter2").snapshot().toString()
        assertThat(snapshot).doesNotContain("sk-glm").doesNotContain("hunter2")
        assertThat(snapshot).contains("\"key_set\":true").contains("\"pg_password_set\":true")
    }

    @Test
    fun `every default has a declared type the resolver understands`() {
        Config.DEFAULTS.forEach { (name, default) ->
            assertThat(default).withFailMessage("$name has an unsupported default type").isInstanceOfAny(String::class.java, Int::class.javaObjectType, Boolean::class.javaObjectType)
        }
    }
}
