rootProject.name = "agent-brain"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        // TEMPORARY (Phase G dogfooding, 2026-11-03): kap 5.0.0 resolves from
        // mavenLocal because the Central release of 5.0.0 is the owner's call
        // and has not happened yet. This re-introduces the machine friction G5
        // removed (4.2.0 is still on Central, hence the fallback order below)
        // and MUST be dropped — leaving only mavenCentral() — as soon as
        // 5.0.0 is published there.
        mavenLocal()
        mavenCentral()
    }
}

include("core")
include("brain-mcp")
