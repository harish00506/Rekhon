package com.aicfo.core.datastore

import com.aicfo.core.common.SecretInventory

/**
 * What `:core:datastore` keeps that an erase must destroy (issue 11.4; §34, P-01).
 *
 * Why:  this file holds no key, and that is exactly why it is easy to forget. It holds the
 *       consent ledger, the lock settings and the profile — the record of what the user agreed to
 *       and who they are. It is also, unlike everything else in the inventory, **not encrypted at
 *       rest**: Proto DataStore writes it in the clear inside app-private storage. Destroying a
 *       Keystore key does nothing for it, so the erase has to actually delete the bytes, and an
 *       inventory entry is the only thing that makes anyone do so.
 * What: the settings file and the temporary file DataStore writes beside it while saving.
 * Result: this module's work list for the eraser.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 *
 * [CfoDataStoreFactory] reads [FILE_NAME] rather than repeating it, so the name has one copy.
 */
object DataStoreSecrets {
    /** The settings file inside app-private storage. Plaintext protobuf — see the class note. */
    const val FILE_NAME: String = "cfo_settings.pb"

    /**
     * The scratch file DataStore writes a new version into before renaming it over [FILE_NAME].
     * Why:    an interrupted write leaves it behind holding a full copy of the settings. Deleting
     *         the live file and not this one would erase the user's consents from the app while
     *         leaving them readable on disk.
     */
    const val TEMP_FILE_NAME: String = "$FILE_NAME.tmp"

    /**
     * This module's inventory.
     * Result: the [SecretInventory] covering the settings file and its scratch sibling. No keys and
     *         no databases — this module has neither.
     * Input:  none. Output: [SecretInventory].
     * Changelog: 2026-10-01 — Created for issue 11.4.
     */
    val inventory: SecretInventory = SecretInventory(filePaths = listOf(FILE_NAME, TEMP_FILE_NAME))
}
