package com.aicfo.data.repository

/**
 * Compiling placeholder for the :data:repository module.
 *
 * Why:  keeps the repository layer compiling under the skeleton (issue 1.1).
 *       Repositories are the only DAO/network touchers (ARC-005).
 * What: exposes the module's Gradle path for the smoke test.
 * Result: one type + one test present.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 */
internal object ModulePlaceholder {
    /** The Gradle path of this module (output: constant String). */
    const val PATH: String = ":data:repository"
}
