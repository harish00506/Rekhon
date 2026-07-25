package com.aicfo.domain.usecase

/**
 * Compiling placeholder for the :domain:usecase module.
 *
 * Why:  keeps the use-case layer compiling under the skeleton (issue 1.1); use cases
 *       depend downward on engines + core and are consumed by features.
 * What: exposes the module's Gradle path for the smoke test.
 * Result: one type + one test present.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 */
internal object ModulePlaceholder {
    /** The Gradle path of this module (output: constant String). */
    const val PATH: String = ":domain:usecase"
}
