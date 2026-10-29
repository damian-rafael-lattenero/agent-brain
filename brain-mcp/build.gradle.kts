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
        // "Compiled with 25, runs on 21+": without this the classes are classfile 69
        // and every spawned launcher under a 21 runtime dies with UnsupportedClassVersionError.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {

    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.logging)
    implementation(libs.mcp.kotlin.sdk.server)
    implementation(libs.bundles.kap)
    ksp(libs.kap.ksp)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj.core)
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.server.core)
}

application {
    applicationName = "brain-mcp"
    mainClass = "dev.lattenero.brain.mcp.MainKt"
    applicationDefaultJvmArgs = listOf("-Xmx128m", "-XX:+UseSerialGC")
}

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
