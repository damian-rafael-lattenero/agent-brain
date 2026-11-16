package dev.lattenero.brain.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The panel's build-side laws, without a brain: the template must stay a
 * single self-contained page (one placeholder, one importmap), the vendored
 * SDK bundle must be the real thing, and the manifest must point the host at
 * the exact resource the server registers. A drift in any of the three is a
 * broken panel at runtime, where no test could see it.
 */
class PanelTest {
    private val template = File("plugin/panel/panel.template.html")
    private val bundle = File("plugin/assets/ext-apps/app-with-deps.js")
    private val manifest = File("plugin/.zcode-plugin/plugin.json")

    @Test
    fun `the template is one self-contained page with exactly one placeholder`() {
        assertThat(template).exists()
        val html = template.readText()
        assertThat(Regex("@@EXT_APPS_B64@@").findAll(html).count())
            .describedAs("the Gradle expansion replaces ALL placeholders with the bundle")
            .isEqualTo(1)
        assertThat(html).contains("""<script type="importmap">""")
        assertThat(html).contains("data:text/javascript;base64,@@EXT_APPS_B64@@")
        // The three tabs ride the three tools the server routes.
        assertThat(html).contains("memory_status").contains("memory_search").contains("memory_recent")
    }

    @Test
    fun `the vendored SDK bundle is the real app-with-deps bundle`() {
        assertThat(bundle).exists()
        assertThat(bundle.length()).describedAs("the self-contained ESM bundle, ~418 KB").isGreaterThan(300_000)
        val text = bundle.readText()
        assertThat(text).contains("export{").contains("PostMessageTransport").contains("as App")
        assertThat(File("plugin/assets/ext-apps/LICENSE")).exists()
    }

    @Test
    fun `the manifest points the host at the resource the server registers`() {
        assertThat(manifest).exists()
        val root = Json.parseToJsonElement(manifest.readText()).jsonObject
        assertThat(root["version"]!!.jsonPrimitive.content).isEqualTo("0.2.0")
        val surfaces = root["ui"]!!.jsonObject["surfaces"]!!.jsonArray
        assertThat(surfaces).hasSize(1)
        val surface = surfaces[0].jsonObject
        assertThat(surface["id"]!!.jsonPrimitive.content).isEqualTo("brain-panel")
        assertThat(surface["server"]!!.jsonPrimitive.content).isEqualTo("brain")
        assertThat(surface["resourceUri"]!!.jsonPrimitive.content).isEqualTo(Panel.URI)
        assertThat(surface["availability"]!!.jsonPrimitive.content).isEqualTo("session")
        assertThat(root["mcpServers"]!!.jsonObject).containsKey(surface["server"]!!.jsonPrimitive.content)
    }
}
