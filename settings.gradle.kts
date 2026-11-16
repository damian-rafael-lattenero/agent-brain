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
        // branch damian), VENDORED as a tiny maven-layout repo so CI resolves it
        // with zero auth (GitHub Packages would need a cross-repo PAT). The
        // coordinates never collide with upstream app.cash.sqldelight.
        // Refresh procedure (ADR-007): publishToMavenLocal in the fork
        // (-PsignAllPublications=false), copy jar+pom+module here, bump brainN.
        maven { url = uri("core/libs/maven") }
        // Dev loop only: an unpublished fork rebuild lands instantly from
        // mavenLocal when present; CI silently skips it.
        mavenLocal()
    }
}

include("core")
include("brain-mcp")
