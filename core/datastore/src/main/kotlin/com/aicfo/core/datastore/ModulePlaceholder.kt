package com.aicfo.core.datastore

/**
 * Compiling placeholder for the :core:datastore module.
 *
 * Why:  keeps the settings/consent module compiling under the skeleton (issue 1.1)
 *       until Proto DataStore + the consent ledger land (issue 1.9).
 * What: exposes the module's Gradle path for the smoke test.
 * Result: one type + one test present.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 */
internal object ModulePlaceholder {
    /** The Gradle path of this module (output: constant String). */
    const val PATH: String = ":core:datastore"
}
