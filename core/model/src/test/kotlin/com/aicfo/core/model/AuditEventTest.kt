package com.aicfo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the audit vocabulary (issue 2.2; §21.6, P-01).
 *
 * Why:  these enums are the entire reason an audit row cannot contain PII — the column stores a
 *       constant's name and nothing else. Two things are therefore worth pinning. First, that the
 *       names really are opaque codes rather than anything resembling user data, because a future
 *       constant like `PIN_1234_REJECTED` would defeat the design silently. Second, that the names
 *       are stable: they are what is written to `audit_log`, so renaming one orphans every existing
 *       row without any migration noticing.
 * What: the constant sets, and that every name is an upper-snake code.
 * Result: the PII-free guarantee is checked, not merely asserted in a comment.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 */
class AuditEventTest {
    /**
     * Input:  the declared events.
     * Output: asserts the exact set. This test is meant to fail when a constant is added — at which
     *         point the author has to confirm the new name is a code, not a description of a person.
     */
    @Test
    fun `the audit vocabulary is the closed set issue 2_2 defines`() {
        assertEquals(
            listOf(
                "APP_UNLOCK_SUCCESS",
                "APP_UNLOCK_FAILURE",
                "APP_LOCKED_TIMEOUT",
                "APP_LOCKOUT_STARTED",
                "PIN_SET",
                "APP_LOCK_ENABLED",
                "APP_LOCK_DISABLED",
            ),
            AuditEvent.entries.map { it.name },
        )
    }

    /** Input: the declared methods. Output: asserts SEC-002's two factors and nothing else. */
    @Test
    fun `the two auth factors are biometric and PIN`() {
        assertEquals(listOf("BIOMETRIC", "PIN"), AuditMethod.entries.map { it.name })
    }

    /**
     * Input:  every constant in both enums.
     * Output: asserts each name is upper-snake ASCII. A name is what lands in the database, so this
     *         is also the check that nothing free-form ever creeps in as a constant.
     */
    @Test
    fun `every stored code is an upper snake identifier`() {
        val names = AuditEvent.entries.map { it.name } + AuditMethod.entries.map { it.name }
        names.forEach { name ->
            assertTrue("'$name' must be an upper-snake code", name.matches(Regex("[A-Z][A-Z_]*[A-Z]")))
        }
    }

    /** Input: a round trip through the stored name. Output: asserts a written row can be read back. */
    @Test
    fun `a stored name round trips to its constant`() {
        AuditEvent.entries.forEach { event ->
            assertEquals(event, AuditEvent.valueOf(event.name))
        }
        AuditMethod.entries.forEach { method ->
            assertEquals(method, AuditMethod.valueOf(method.name))
        }
    }
}
