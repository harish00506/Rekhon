package com.aicfo.core.designsystem

/**
 * Compiling placeholder for the :core:designsystem module (JVM-testable).
 *
 * Why:  gives the module a unit-testable type alongside the Compose placeholder.
 * What: exposes the module's Gradle path for the smoke test.
 * Result: one type + one test present.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 */
internal object ModulePlaceholder {
    /** The Gradle path of this module (output: constant String). */
    const val PATH: String = ":core:designsystem"
}
