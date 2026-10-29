plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

allprojects {
    group = "dev.lattenero.brain"
    version = "0.1.0"
}

// ── Built with Corretto 25, runs on 21+ (kap's pattern) ─────────────────────
// Modules compile with the 25 toolchain but the BYTECODE stays 21: the stdio
// tests spawn the installDist launcher under the runner's JAVA_HOME (21), and
// the plugin dist must load on any 21+ host. -Xjdk-release also forbids
// accidental Java-25-only API usage.
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
