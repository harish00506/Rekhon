package com.aicfo.ml.llm

/**
 * Compiling placeholder for the :ml:llm module.
 *
 * Why:  keeps the on-device LLM module compiling under the skeleton (issue 1.1). The
 *       LLM only verbalises engine numbers (P-03); it never computes figures.
 * What: exposes the module's Gradle path for the smoke test.
 * Result: one type + one test present.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 */
internal object ModulePlaceholder {
    /** The Gradle path of this module (output: constant String). */
    const val PATH: String = ":ml:llm"
}
