package com.aicfo.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The erase's work list (issue 11.4; §34, SEC-003).
 *
 * Why:  this class carries no behaviour worth a test except the two things the erase's correctness
 *       rests on. [SecretInventory.plus] is where a module gets silently dropped — a merge that
 *       lost a field would leave a whole key chain undestroyed and every other test still green.
 *       [SecretInventory.isEmpty] is the guard that turns a lost composition into an error instead
 *       of an erase that reports success having touched nothing.
 * What: the merge keeps every field and every name, and the emptiness check is honest about each.
 * Result: a work list that cannot quietly shrink.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 */
class SecretInventoryTest {
    @Test
    fun `the merge keeps every field from both sides`() {
        val left =
            SecretInventory(
                keystoreAliases = listOf("a1"),
                sharedPrefsNames = listOf("p1"),
                filePaths = listOf("f1"),
                databaseNames = listOf("d1"),
            )
        val right =
            SecretInventory(
                keystoreAliases = listOf("a2"),
                sharedPrefsNames = listOf("p2"),
                filePaths = listOf("f2"),
                databaseNames = listOf("d2"),
            )

        val merged = left + right

        // Asserted field by field rather than as one object, so a merge that dropped exactly one
        // list names which one in the failure.
        assertEquals(listOf("a1", "a2"), merged.keystoreAliases)
        assertEquals(listOf("p1", "p2"), merged.sharedPrefsNames)
        assertEquals(listOf("f1", "f2"), merged.filePaths)
        assertEquals(listOf("d1", "d2"), merged.databaseNames)
    }

    @Test
    fun `a duplicate name is kept, not quietly discarded`() {
        // Deleting a name twice is harmless; silently dropping one is not, and a `distinct()` here
        // would be the kind of helpfulness that hides a real duplication from whoever added it.
        val merged = SecretInventory(filePaths = listOf("f1")) + SecretInventory(filePaths = listOf("f1"))

        assertEquals(listOf("f1", "f1"), merged.filePaths)
    }

    @Test
    fun `merging with nothing changes nothing`() {
        val one = SecretInventory(keystoreAliases = listOf("a1"), filePaths = listOf("f1"))

        assertEquals(one, one + SecretInventory())
        assertEquals(one, SecretInventory() + one)
    }

    @Test
    fun `an inventory with no names at all is empty`() {
        assertTrue(SecretInventory().isEmpty())
    }

    @Test
    fun `an inventory holding anything at all is not empty`() {
        // Each field on its own, because `isEmpty` is an `&&` chain and a dropped clause would make
        // it answer "empty" for an inventory that holds a key.
        assertFalse(SecretInventory(keystoreAliases = listOf("a")).isEmpty())
        assertFalse(SecretInventory(sharedPrefsNames = listOf("p")).isEmpty())
        assertFalse(SecretInventory(filePaths = listOf("f")).isEmpty())
        assertFalse(SecretInventory(databaseNames = listOf("d")).isEmpty())
    }
}
