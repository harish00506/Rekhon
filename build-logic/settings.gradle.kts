// =============================================================================
// build-logic — isolated composite build holding the convention plugins.
// Why:  §21.6 — shared build config lives in one place so module scripts stay tiny
//       and consistent; keeping it a separate build isolates its classpath.
// What: repositories for the plugin artifacts + the shared version catalog.
// Changelog: 2026-07-19 — Created for issue 1.1.
// =============================================================================

@file:Suppress("UnstableApiUsage")

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
include(":convention")
