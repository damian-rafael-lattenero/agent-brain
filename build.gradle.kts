import dev.detekt.gradle.extensions.DetektExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.detekt) apply false
}

allprojects {
    group = "dev.lattenero.brain"
    version = "0.1.0"
}

// ── Built with Corretto 25, runs on 21+ (kap's pattern) ─────────────────────
// A single JVM in the build: daemon and toolchain are the same Corretto 25.
// detekt 2.0 runs its analysis in isolated workers on any JDK >= 17, so no
// daemon-version dance (kap's detekt 2.0 upgrade closed the dual-JVM era;
// this repo follows). Modules compile with the 25 toolchain but the BYTECODE
// stays 21 — the plugin dist must load on any 21+ host — and -Xjdk-release
// forbids accidental Java-25-only API usage.
val bytecodeTarget = 21
subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(bytecodeTarget.toString()))
            freeCompilerArgs.add("-Xjdk-release=$bytecodeTarget")
        }
    }
    tasks.withType<JavaCompile>().configureEach { options.release.set(bytecodeTarget) }
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.toVersion(bytecodeTarget)
            targetCompatibility = JavaVersion.toVersion(bytecodeTarget)
        }
    }
}

// ── Static analysis: detekt on every module ────────────────────────────────
// Existing findings live in each module's detekt-baseline.xml (plain flavour)
// and detekt-baseline-main.xml (type-resolved, picked up by naming
// convention); any NEW violation fails the build. detekt 2.0 creates the
// type-resolved per-compilation tasks (detektMain) by default, and its
// isolated workers made the single-JDK build above possible.
val detektModules = listOf("core", "brain-mcp")

subprojects {
    if (name in detektModules) {
        apply(plugin = "dev.detekt")
        configure<DetektExtension> {
            buildUponDefaultConfig = true
            val baselineFile = file("detekt-baseline.xml")
            if (baselineFile.exists()) baseline.set(baselineFile)
            source.setFrom(listOf("src/main/kotlin").filter { file(it).exists() })
            // Tests are out of scope for now (same policy as kap until its Fase 1 decides).
        }
    }
}

// Aggregates both detekt flavours: the plain `detekt` pass (extension
// sources) plus the type-resolved detektMain detekt 2.0 generates per JVM
// compilation. CI gate entry point.
val detektAll = tasks.register("detektAll") {
    group = "verification"
    description = "All detekt checks: plain + type-resolved."
    val detektTaskNames = listOf("detekt", "detektMain")
    detektModules.forEach { moduleName ->
        val sp = subprojects.find { it.name == moduleName } ?: return@forEach
        detektTaskNames.forEach { taskName ->
            if (taskName in sp.tasks.names) dependsOn("${sp.path}:$taskName")
        }
    }
}
