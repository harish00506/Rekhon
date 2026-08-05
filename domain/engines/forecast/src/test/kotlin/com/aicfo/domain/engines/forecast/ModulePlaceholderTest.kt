package com.aicfo.domain.engines.forecast

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Smoke test proving :domain:engines:forecast compiles and its tests run.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 */
class ModulePlaceholderTest {
    /** Input: none. Output: asserts the placeholder reports this module's path. */
    @Test
    fun exposesModulePath() {
        assertEquals(":domain:engines:forecast", ModulePlaceholder.PATH)
    }
}
