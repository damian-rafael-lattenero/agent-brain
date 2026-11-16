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
        // The pgvector-capable postgres dialect fork (U5, ~/Projects/sqldelight
        // branch damian) resolves from Maven Central since M5 (16/11):
        // io.github.damian-rafael-lattenero:postgresql-dialect — published by
        // the fork's own workflow (five repo secrets, ADR-007). The vendored
        // core/libs/maven copy is gone.
        // Dev loop only: an unpublished fork rebuild lands instantly from
        // mavenLocal when present (publishToMavenLocal
        // -PsignAllPublications=false); CI silently skips it.
        mavenLocal()
    }
}

include("core")
include("brain-mcp")
