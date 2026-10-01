package com.aicfo.core.crypto

import com.aicfo.core.common.SecretInventory

/**
 * What `:core:crypto` keeps that an erase must destroy (issue 11.4; SEC-003, §34).
 *
 * Why:  two independent key chains live here and neither is the database's. The PIN key tags the
 *       credential that unlocks the app; the receipt key encrypts photographs of the user's
 *       shopping. An erase that destroyed only the database key would leave both of those intact
 *       and readable, which is precisely the kind of omission [SecretInventory] exists to prevent
 *       — the module that owns a secret declares it, next to the code that creates it.
 * What: the PIN chain, the receipt chain, and the two files they protect.
 * Result: this module's work list for the eraser, as plain strings.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 *
 * The constants here are the **only** copies of these names in the codebase: [KeystoreMacFactory]
 * and [ReceiptImageStoreFactory] read them rather than repeating them, so a renamed alias moves
 * its own shred with it instead of leaving an undestroyed key behind.
 */
object CryptoSecrets {
    /** Keyset name inside the PIN preferences file; changing it orphans the stored credential. */
    const val PIN_KEYSET_NAME: String = "cfo_pin_keyset"

    /** The preferences file holding the *encrypted* PIN keyset. Never holds a usable key. */
    const val PIN_KEYSET_PREF_FILE: String = "cfo_pin_keyset_prefs"

    /** The Keystore alias of the master key that encrypts the PIN keyset. Never exported. */
    const val PIN_MASTER_KEY_ALIAS: String = "cfo_pin_master_key"

    /** Where the salt-and-tag credential lives, inside app-private storage. */
    const val PIN_CREDENTIAL_FILE: String = "cfo-pin.bin"

    /** Keyset name inside the receipt preferences file; changing it orphans every receipt. */
    const val RECEIPT_KEYSET_NAME: String = "cfo_receipt_keyset"

    /** The preferences file holding the *encrypted* receipt keyset. Never holds a usable key. */
    const val RECEIPT_KEYSET_PREF_FILE: String = "cfo_receipt_keyset_prefs"

    /** The Keystore alias of the master key that encrypts the receipt keyset. Never exported. */
    const val RECEIPT_MASTER_KEY_ALIAS: String = "cfo_receipt_master_key"

    /** The app-private directory the receipt ciphertext blobs live in. */
    const val RECEIPT_DIRECTORY: String = "receipts"

    /**
     * This module's inventory.
     * Result: the [SecretInventory] covering both key chains, the PIN credential and the whole
     *         receipt directory — named as a directory, which the eraser deletes recursively,
     *         because the blobs inside it have per-receipt names this module cannot enumerate
     *         without a database it is about to destroy.
     * Input:  none. Output: [SecretInventory].
     * Changelog: 2026-10-01 — Created for issue 11.4.
     */
    val inventory: SecretInventory =
        SecretInventory(
            keystoreAliases = listOf(PIN_MASTER_KEY_ALIAS, RECEIPT_MASTER_KEY_ALIAS),
            sharedPrefsNames = listOf(PIN_KEYSET_PREF_FILE, RECEIPT_KEYSET_PREF_FILE),
            filePaths = listOf(PIN_CREDENTIAL_FILE, RECEIPT_DIRECTORY),
        )
}
