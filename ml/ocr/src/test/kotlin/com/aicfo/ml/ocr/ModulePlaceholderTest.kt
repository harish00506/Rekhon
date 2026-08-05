package com.aicfo.ml.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Smoke test proving :ml:ocr compiles and its unit-test source set runs.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 */
class ModulePlaceholderTest {
    /** Input: none. Output: asserts the placeholder reports this module's path. */
    @Test
    fun exposesModulePath() {
        assertEquals(":ml:ocr", ModulePlaceholder.PATH)
    }
}
