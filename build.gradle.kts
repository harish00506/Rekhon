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

/**
 * `unitTests` — every module's unit tests, in one command (issue 2.6).
 *
 * Why:  the workflow, CLAUDE.md and every issue template told a developer to run
 *       `./gradlew testDebugUnitTest`, and that command **silently skips whole modules**. It is an
 *       Android variant task, so it does not exist on the pure-Kotlin ones — `:core:model` (Money),
 *       `:core:common` (Clock, Result) and every `:domain:engines:*` use `test` instead. Locally
 *       that leaves stale results on disk and reports a green that covered less than it looked like.
 *
 *       **And `:lint` was reachable from nothing at all.** Its fourteen tests cover the five custom
 *       detectors that make MNY-001, TIM-001, ARC-006, the PII-logging ban and the hardcoded-string
 *       ban fail the build. Neither `testDebugUnitTest` nor `koverVerify` reaches it, and CI ran
 *       only those two — so the enforcement layer's own tests had never run in CI. A detector could
 *       have broken and every gate would have stayed green while the rules quietly stopped being
 *       enforced. That is the same shape as audit G-01's vacuous coverage gate.
 *       **And the screenshot baselines were verified by nothing.** Paparazzi only compares against
 *       its recorded PNGs when `verifyPaparazziDebug` is in the task graph; a plain
 *       `testDebugUnitTest` renders and asserts nothing. That task lived only in `/pre-merge`, which
 *       is a checklist a human runs, so a UI change could — and did — ship against a stale baseline:
 *       issue 5.4 recorded the dashboard's empty state, the Settings screen added a button to it,
 *       and every gate stayed green for a commit while the recorded image no longer showed the app.
 *       Screenshot tests are the DoD's evidence for dark mode and 200% font (§4.2), so a baseline
 *       nothing compares against is the same vacuous gate in a different costume.
 * What: depends on all three unit-test task names across every subproject, so a module cannot be
 *       missed by having the "wrong" kind of build script.
 * Result: one command that genuinely means "run the unit tests".
 * Changelog: 2026-08-02 — Created for issue 2.6, after `testDebugUnitTest` reported a stale failure.
 *            2026-08-29 — Issue 6.5: added `verifyPaparazziDebug`, after a stale dashboard baseline
 *            survived a merge. Paparazzi reuses the same test task, so this enables verification
 *            rather than running the suite twice.
 *
 * Deliberately matched by **name** rather than by listing modules: a module added later is picked up
 * without anyone remembering to edit this, which is precisely the failure being fixed.
 */
tasks.register("unitTests") {
    group = "verification"
    description =
        "Runs every module's unit tests — Android variants, pure-Kotlin modules, :lint, " +
            "and the Paparazzi baselines."
    dependsOn(
        subprojects.mapNotNull { module ->
            module.tasks.matching {
                it.name == "testDebugUnitTest" || it.name == "test" || it.name == "verifyPaparazziDebug"
            }
        },
    )
}
