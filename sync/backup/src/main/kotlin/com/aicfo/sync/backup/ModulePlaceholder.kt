package com.aicfo.sync.backup

/**
 * Compiling placeholder for the :sync:backup module.
 *
 * Why:  keeps the backup module compiling under the skeleton (issue 1.1) until E2EE
 *       backup/restore lands. The platform never sees plaintext or the key.
 * What: exposes the module's Gradle path for the smoke test.
 * Result: one type + one test present.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 */
internal object ModulePlaceholder {
    /** The Gradle path of this module (output: constant String). */
    const val PATH: String = ":sync:backup"
}
