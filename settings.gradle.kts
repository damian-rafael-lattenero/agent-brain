rootProject.name = "agent-brain"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        // kap 5.0.0 hit Maven Central on 2026-11-03 (release v5.0.0; the four
        // modules answer 200 on repo1) — the Phase G dogfooding fallback is
        // retired with it, and G5's Central-only resolution is back for good.
        mavenCentral()
    }
}

include("core")
include("brain-mcp")
