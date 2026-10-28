plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.ktor.server)
    implementation(libs.bundles.ktor.client)
    implementation(libs.bundles.db)
    implementation(libs.bundles.logging)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj.core)
    testImplementation(libs.awaitility)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
}

application {
    applicationName = "agent-brain"
    mainClass = "dev.lattenero.brain.MainKt"
    applicationDefaultJvmArgs = listOf("-Xmx256m", "-XX:+UseSerialGC")
}

val serviceEnv = mapOf(
    "AB_PG_URL" to System.getenv("AB_PG_URL"),
    "AB_PG_USER" to System.getenv("AB_PG_USER"),
    "AB_PG_PASSWORD" to System.getenv("AB_PG_PASSWORD"),
    "AB_OLLAMA_BASE_URL" to System.getenv("AB_OLLAMA_BASE_URL"),
    "AB_REQUIRE_SERVICES" to System.getenv("AB_REQUIRE_SERVICES"),
    "GLM_API_KEY" to System.getenv("GLM_API_KEY"),
).filterValues { it != null }.mapValues { it.value!! }

fun Test.commonTestSetup() {
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    environment(serviceEnv)
}

// Unit + integration + e2e: the default `test` task excludes the black-box tiers.
tasks.test {
    useJUnitPlatform { excludeTags("bb-local", "bb-real") }
    commonTestSetup()
}

// Tier 4: real processes, real database, only the Telegram wire virtualized.
tasks.register<Test>("bbLocal") {
    description = "Black-box local tier: spawns the installed distribution against real services"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("bb-local") }
    commonTestSetup()
    dependsOn(tasks.installDist)
    val launcher = layout.buildDirectory.file("install/agent-brain/bin/agent-brain")
    systemProperty("ab.dist", launcher.get().asFile.absolutePath)
    environment("AB_REQUIRE_SERVICES", "1")
    outputs.upToDateWhen { false }
}

// Tier 5: drives and observes a real Telegram account over MTProto.
tasks.register<Test>("e2eReal") {
    description = "Black-box real tier: your Telegram account over MTProto"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("bb-real") }
    commonTestSetup()
    dependsOn(tasks.installDist)
    environment("AB_REQUIRE_SERVICES", "1")
    outputs.upToDateWhen { false }
}
