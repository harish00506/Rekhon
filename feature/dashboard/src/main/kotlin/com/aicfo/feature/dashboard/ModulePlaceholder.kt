package com.aicfo.feature.dashboard

/**
 * Compiling placeholder for the :feature:dashboard module.
 *
 * Why:  proves the feature convention (library + Compose + Hilt) applies cleanly under
 *       the skeleton (issue 1.1); the real StateFlow-driven screen lands later.
 * What: exposes the module's Gradle path for the smoke test.
 * Result: one type + one test present.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 */
internal object ModulePlaceholder {
    /** The Gradle path of this module (output: constant String). */
    const val PATH: String = ":feature:dashboard"
}
