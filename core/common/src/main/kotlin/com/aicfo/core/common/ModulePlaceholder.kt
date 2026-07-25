package com.aicfo.core.common

/**
 * Compiling placeholder for the :core:common module.
 *
 * Why:  keeps this pure-Kotlin module compiling under the skeleton (issue 1.1) until
 *       the injected Clock/DispatcherProvider land (issue 1.3).
 * What: exposes the module's Gradle path for the smoke test.
 * Result: one type + one test present.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 */
internal object ModulePlaceholder {
    /** The Gradle path of this module (output: constant String). */
    const val PATH: String = ":core:common"
}
