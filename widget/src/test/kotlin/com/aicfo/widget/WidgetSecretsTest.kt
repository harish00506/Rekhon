package com.aicfo.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget's cached figures are on the erase's list (issue 11.4; §34, P-01).
 *
 * Why:  the widget renders without touching the database by caching a snapshot — and that snapshot
 *       is the user's safe-to-spend and net worth in `Long` paise, written by Glance into a
 *       *plaintext* preferences file. It is encrypted by nothing, so crypto-shredding does not touch
 *       it, and no module under `:core` or `:data` knows the file exists. A device run found it
 *       surviving an otherwise complete erase. This test is the thing that keeps it declared.
 * What: the inventory names the Glance state directory, and claims nothing it does not own.
 * Result: an erase that reaches the two most sensitive figures in the app.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 */
class WidgetSecretsTest {
    @Test
    fun `the Glance state directory is declared`() {
        assertEquals(listOf("datastore"), WidgetSecrets.inventory.filePaths)
    }

    @Test
    fun `the widget claims no key and no database, because it has neither`() {
        // The snapshot was never encrypted and the widget never opens the database (issue 5.5).
        // Claiming a key here would make the erase try to destroy one that does not exist and,
        // worse, suggest the cached figures were protected by something.
        assertTrue(WidgetSecrets.inventory.keystoreAliases.isEmpty())
        assertTrue(WidgetSecrets.inventory.sharedPrefsNames.isEmpty())
        assertTrue(WidgetSecrets.inventory.databaseNames.isEmpty())
    }

    @Test
    fun `the inventory is not empty, because an empty one would erase nothing`() {
        assertTrue(WidgetSecrets.inventory.filePaths.isNotEmpty())
    }
}
