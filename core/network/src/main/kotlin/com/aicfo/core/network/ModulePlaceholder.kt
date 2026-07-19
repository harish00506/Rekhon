package com.aicfo.core.network

/**
 * Compiling placeholder for the :core:network module.
 *
 * Why:  keeps the network edge compiling under the skeleton (issue 1.1). All core
 *       features must work offline (P-04); network arrives behind consent later.
 * What: exposes the module's Gradle path for the smoke test.
 * Result: one type + one test present.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 */
internal object ModulePlaceholder {
    /** The Gradle path of this module (output: constant String). */
    const val PATH: String = ":core:network"
}
