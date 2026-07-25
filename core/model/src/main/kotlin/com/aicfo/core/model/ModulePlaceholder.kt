package com.aicfo.core.model

/**
 * Compiling placeholder for the :core:model module.
 *
 * Why:  issue 1.1 stands up the §21.2 module graph before any real types exist; this
 *       keeps the module compiling with a test source set until issue 1.2 adds Money.
 * What: exposes the module's Gradle path so a smoke test can assert it builds.
 * Result: the module has one type + one test, satisfying the skeleton's DoD.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 */
internal object ModulePlaceholder {
    /** The Gradle path of this module (output: constant String). */
    const val PATH: String = ":core:model"
}
