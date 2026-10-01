package com.aicfo.widget

import com.aicfo.core.common.SecretInventory

/**
 * What the widget keeps that an erase must destroy (issue 11.4; §34, P-01).
 *
 * Why:  **this one was found by running the erase on a device, not by reading the code.** The widget
 *       renders without touching the database (issue 5.5) by caching a snapshot — and that snapshot
 *       is the user's safe-to-spend and net worth, as `Long` paise, written by Glance into a
 *       *plaintext* preferences file under `filesDir/datastore/`. No key of ours wraps it, so
 *       crypto-shredding does nothing to it, and nothing in `:core:*` or `:data:*` names it. An
 *       erase that skipped it would leave two of the most sensitive figures in the app sitting
 *       readable on disk, under a directory nobody thinks of as ours.
 * What: the whole Glance state directory, deleted recursively.
 * Result: this module's work list for the eraser.
 * Changelog: 2026-10-01 — Created for issue 11.4, after a device run showed the file surviving.
 */
object WidgetSecrets {
    /**
     * Glance's own state directory, relative to `filesDir`.
     *
     * Why the directory and not a file: Glance names a state file per widget id and keeps its
     * `GlanceAppWidgetManager` bookkeeping beside them, so the names are not ours to enumerate — and
     * a user with two widgets placed would have two files this could not have predicted. The
     * directory belongs to Glance alone, so removing all of it removes exactly the right thing.
     */
    const val GLANCE_STATE_DIRECTORY: String = "datastore"

    /**
     * This module's inventory.
     * Result: the [SecretInventory] covering the cached snapshot. No keys — the snapshot was never
     *         encrypted, which is the point.
     * Input:  none. Output: [SecretInventory].
     * Changelog: 2026-10-01 — Created for issue 11.4.
     */
    val inventory: SecretInventory = SecretInventory(filePaths = listOf(GLANCE_STATE_DIRECTORY))
}
