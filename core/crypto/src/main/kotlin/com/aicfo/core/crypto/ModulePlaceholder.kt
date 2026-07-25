package com.aicfo.core.crypto

/**
 * Compiling placeholder for the :core:crypto module.
 *
 * Why:  keeps the crypto module compiling under the skeleton (issue 1.1) until Tink /
 *       Keystore key management lands (issues 1.6/11.1). No hand-rolled crypto (SEC-003).
 * What: exposes the module's Gradle path for the smoke test.
 * Result: one type + one test present.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 */
internal object ModulePlaceholder {
    /** The Gradle path of this module (output: constant String). */
    const val PATH: String = ":core:crypto"
}
