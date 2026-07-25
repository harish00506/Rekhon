// =============================================================================
// Root build script — declares the pinned plugins (apply false) so every module
// resolves the same versions from the catalog, and applies repo-wide quality gates.
//
// Why:  Issue 1.1 / §21.6 — one place fixes the plugin versions; convention plugins
//       in build-logic then apply them to each module. Nothing is applied at the root
//       except tasks that must run across the whole build.
// What: `plugins { … apply false }` for the §21.3 stack.
// Result: Subprojects and the build-logic composite share one version matrix.
// Changelog:
//   2026-07-19 — Created for issue 1.1 (Gradle multi-module skeleton).
// =============================================================================

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover) apply false
}
