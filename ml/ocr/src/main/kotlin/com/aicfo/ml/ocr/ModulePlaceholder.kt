package com.aicfo.ml.ocr

/**
 * Compiling placeholder for the :ml:ocr module.
 *
 * Why:  keeps the OCR module compiling under the skeleton (issue 1.1) until on-device
 *       receipt scanning lands. Everything stays on-device (P-01).
 * What: exposes the module's Gradle path for the smoke test.
 * Result: one type + one test present.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 */
internal object ModulePlaceholder {
    /** The Gradle path of this module (output: constant String). */
    const val PATH: String = ":ml:ocr"
}
