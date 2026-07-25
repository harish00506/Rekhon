package com.aicfo.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Smoke test proving :core:crypto compiles and its unit-test source set runs.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 */
class ModulePlaceholderTest {
    /** Input: none. Output: asserts the placeholder reports this module's path. */
    @Test
    fun exposesModulePath() {
        assertEquals(":core:crypto", ModulePlaceholder.PATH)
    }
}
