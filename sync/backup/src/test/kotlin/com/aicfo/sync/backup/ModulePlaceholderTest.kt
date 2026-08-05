package com.aicfo.sync.backup

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Smoke test proving :sync:backup compiles and its unit-test source set runs.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 */
class ModulePlaceholderTest {
    /** Input: none. Output: asserts the placeholder reports this module's path. */
    @Test
    fun exposesModulePath() {
        assertEquals(":sync:backup", ModulePlaceholder.PATH)
    }
}
