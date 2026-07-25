package com.aicfo.core.database

/**
 * Compiling placeholder for the :core:database module.
 *
 * Why:  keeps the encrypted-store module compiling under the skeleton (issue 1.1)
 *       until Room + SQLCipher wiring lands (issues 1.6/1.7).
 * What: exposes the module's Gradle path for the smoke test.
 * Result: one type + one test present.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 */
internal object ModulePlaceholder {
    /** The Gradle path of this module (output: constant String). */
    const val PATH: String = ":core:database"
}
