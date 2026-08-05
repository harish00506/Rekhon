package com.aicfo.domain.engines.forecast

/**
 * Compiling placeholder for the :domain:engines:forecast module.
 *
 * Why:  proves an engine module can live in :domain:engines:* as pure Kotlin (ARC-002)
 *       and depend downward on :core:* — the shape every real engine will follow.
 * What: exposes the module's Gradle path for the smoke test.
 * Result: one type + one test present; the engine slot is real.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 */
internal object ModulePlaceholder {
    /** The Gradle path of this module (output: constant String). */
    const val PATH: String = ":domain:engines:forecast"
}
