rootProject.name = "agent-brain"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        // KAP is on Maven Central since 4.2.0 (2026-10-29); no mavenLocal friction (plan G5).
        mavenCentral()
    }
}

include("core")
include("brain-mcp")
