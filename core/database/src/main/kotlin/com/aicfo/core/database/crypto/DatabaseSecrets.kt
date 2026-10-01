package com.aicfo.core.database.crypto

import com.aicfo.core.common.SecretInventory
import com.aicfo.core.database.CfoDatabase

/**
 * What `:core:database` keeps that an erase must destroy (issue 11.4; SEC-003, §34).
 *
 * Why:  this module owns the key that matters most — the one wrapping the SQLCipher passphrase.
 *       Destroy it and `cfo.db` is a file of random bytes for everyone, forever, which is what
 *       makes an erase cryptographic rather than merely thorough (ADR-0060). The module declares
 *       the names because it creates them; the eraser in `:data:repository` consumes this and
 *       knows none of them.
 * What: the master-key alias, the preferences file holding the wrapped keyset, the wrapped
 *       passphrase and its pending slot, and the database itself.
 * Result: the eraser's work list for this module, as plain strings.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 *
 * The constants here are the **only** copies of these names in the codebase:
 * [KeystoreAeadFactory] and [CfoDatabaseFactory] read them rather than repeating them, so a
 * renamed alias moves its own shred with it instead of leaving an undestroyed key behind.
 */
object DatabaseSecrets {
    /** Keyset name inside the preferences file; changing it orphans the existing key. */
    const val KEYSET_NAME: String = "cfo_db_keyset"

    /** The preferences file holding the *encrypted* keyset. Never holds a usable key. */
    const val KEYSET_PREF_FILE: String = "cfo_db_keyset_prefs"

    /** The Keystore alias of the master key that encrypts the keyset. Never exported. */
    const val MASTER_KEY_ALIAS: String = "cfo_db_master_key"

    /** Where the wrapped passphrase lives, inside app-private storage. */
    const val PASSPHRASE_FILE: String = "cfo-db-passphrase.bin"

    /** The staging slot a rotation writes before it promotes (issue 11.1). */
    const val PASSPHRASE_PENDING_FILE: String = "$PASSPHRASE_FILE.pending"

    /**
     * This module's inventory.
     * Why:    one value for the eraser to add in, rather than five constants it has to remember to
     *         read — forgetting a field of a data class is harder than forgetting a constant.
     * Result: the [SecretInventory] covering the database and its key chain. The `-wal` and `-shm`
     *         siblings are not listed: they are derived from the database name by whoever deletes
     *         it, because SQLite's own naming is not this module's choice to restate.
     * Input:  none. Output: [SecretInventory].
     * Changelog: 2026-10-01 — Created for issue 11.4.
     */
    val inventory: SecretInventory =
        SecretInventory(
            keystoreAliases = listOf(MASTER_KEY_ALIAS),
            sharedPrefsNames = listOf(KEYSET_PREF_FILE),
            filePaths = listOf(PASSPHRASE_FILE, PASSPHRASE_PENDING_FILE),
            databaseNames = listOf(CfoDatabase.FILE_NAME),
        )
}
