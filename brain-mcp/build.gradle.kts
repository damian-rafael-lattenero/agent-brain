import java.util.Base64

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    application
}

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(25)
        vendor = JvmVendorSpec.AMAZON
    }
    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {

    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.logging)
    implementation(libs.mcp.kotlin.sdk.server)
    implementation(libs.bundles.kap)
    // The tool handlers talk to the brain over the SAME ktor client setup as
    // the core module (plan C4); the client types must resolve here.
    implementation(libs.bundles.ktor.client)
    // The HTTP transport: the MCP SDK's streamable-HTTP routes are ktor server
    // extensions, so the engine lives here too. It serves ONLY /mcp and
    // /health, on loopback (see McpHttp).
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    ksp(libs.kap.ksp)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj.core)
}

application {
    applicationName = "brain-mcp"
    mainClass = "dev.lattenero.brain.mcp.MainKt"
    applicationDefaultJvmArgs = listOf("-Xmx128m", "-XX:+UseSerialGC")
}

// The MCP App panel (ui://brain/panel.html): ONE self-contained HTML
// resource, because the host loads it raw into a sandboxed webview where
// sibling fetches are impossible. The official MCP Apps SDK bundle
// (plugin/assets/ext-apps, MIT) rides an importmap data-URI baked here —
// 418 KB JS -> ~558 KB base64, well under the host's 4 MiB HTML limit.
// A typed task (not a doLast lambda): the configuration cache refuses
// script-object captures.
abstract class GeneratePanelHtmlTask : DefaultTask() {
    @get:InputFile
    abstract val template: RegularFileProperty

    @get:InputFile
    abstract val bundle: RegularFileProperty

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun generate() {
        val sdk = Base64.getEncoder().encodeToString(bundle.get().asFile.readBytes())
        val html = template.get().asFile.readText().replace("@@EXT_APPS_B64@@", sdk)
        check("@@EXT_APPS_B64@@" !in html) { "placeholder survived the expansion (bundle contains it?)" }
        val out = output.get().asFile
        out.parentFile.mkdirs()
        out.writeText(html)
    }
}

val generatedPanelDir = layout.buildDirectory.dir("generated/panel")
tasks.register<GeneratePanelHtmlTask>("generatePanelHtml") {
    description = "Expands the panel template with the base64 SDK bundle (importmap data-URI)"
    group = "distribution"
    template.set(layout.projectDirectory.file("plugin/panel/panel.template.html"))
    bundle.set(layout.projectDirectory.file("plugin/assets/ext-apps/app-with-deps.js"))
    output.set(generatedPanelDir.map { it.file("panel.html") })
}
sourceSets.main {
    resources.srcDir(generatedPanelDir)
}
tasks.named("processResources") { dependsOn("generatePanelHtml") }

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    dependsOn(tasks.installDist)
    systemProperty("brainmcpt.dist", layout.buildDirectory.file("install/brain-mcp/bin/brain-mcp").get().asFile.absolutePath)
    environment("AB_REQUIRE_SERVICES", "")
    // The stdio e2e spawns the installed binary; give it the same AB_ knobs as the fake brain.
    outputs.upToDateWhen { false }
}

// The ZCode plugin layout: the installed distribution (bin/ + lib/) next to the manifest.
// `make plugin` builds it; the marketplace file points at this directory.
val pluginDir = layout.buildDirectory.dir("plugin/brain-mcp")
tasks.register<Sync>("plugin") {
    description = "Assembles the ZCode plugin directory (manifest + installed distribution)"
    group = "distribution"
    dependsOn(tasks.installDist, "pluginMarketplace")
    from(layout.projectDirectory.dir("plugin/.zcode-plugin")) { into(".zcode-plugin") }
    from(layout.buildDirectory.dir("install/brain-mcp"))
    into(pluginDir)
}
tasks.register<Sync>("pluginMarketplace") {
    description = "Copies the local marketplace entry next to the plugin directory"
    group = "distribution"
    from(layout.projectDirectory.file("plugin/marketplace.json"))
    into(layout.buildDirectory.dir("plugin"))
}
