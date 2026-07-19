package com.aicfo.widget

/**
 * Compiling placeholder for the :widget module.
 *
 * Why:  keeps the home-screen-widget module compiling under the skeleton (issue 1.1).
 *       The widget renders cached engine output and works fully offline (P-04).
 * What: exposes the module's Gradle path for the smoke test.
 * Result: one type + one test present.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 */
internal object ModulePlaceholder {
    /** The Gradle path of this module (output: constant String). */
    const val PATH: String = ":widget"
}
