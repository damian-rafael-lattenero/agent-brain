rootProject.name = "agent-brain"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        // KAP 4.1.0 is consumed from mavenLocal until the Central release is tagged.
        mavenLocal { content { includeGroup("io.github.damian-rafael-lattenero") } }
        mavenCentral()
    }
}

include("core")
