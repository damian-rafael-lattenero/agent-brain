plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

allprojects {
    group = "dev.lattenero.brain"
    version = "0.1.0"
}
