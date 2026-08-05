package com.aicfo.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Smoke test proving :domain:usecase compiles and its test source set runs.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 */
class ModulePlaceholderTest {
    /** Input: none. Output: asserts the placeholder reports this module's path. */
    @Test
    fun exposesModulePath() {
        assertEquals(":domain:usecase", ModulePlaceholder.PATH)
    }
}
