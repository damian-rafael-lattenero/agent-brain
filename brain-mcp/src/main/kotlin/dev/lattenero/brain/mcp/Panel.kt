package dev.lattenero.brain.mcp

/**
 * The MCP App panel as ONE resource: `ui://brain/panel.html` is a
 * self-contained page (the SDK bundle baked into an importmap data-URI by
 * the Gradle task `generatePanelHtml`) that the ZCode host loads raw into a
 * sandboxed webview and offers under the manifest's `ui.surfaces`. The page
 * has no brain of its own: every datum rides the same read-only tools and
 * status envelope the model sees, proxied by the host.
 */
internal object Panel {
    const val URI = "ui://brain/panel.html"
    const val NAME = "Brain panel"
    const val DESCRIPTION = "The brain at a glance: status counters, memory search and recent memories"
    const val MIME_TYPE = "text/html;profile=mcp-app"

    /**
     * The expanded page from the classpath, read once per process: the
     * resource is build output, immutable for the server's lifetime.
     */
    val html: String by lazy {
        val stream = requireNotNull(Panel::class.java.classLoader.getResourceAsStream("panel.html")) {
            "panel.html is missing from the classpath (run :brain-mcp:generatePanelHtml)"
        }
        stream.use { it.readBytes().decodeToString() }
    }
}
