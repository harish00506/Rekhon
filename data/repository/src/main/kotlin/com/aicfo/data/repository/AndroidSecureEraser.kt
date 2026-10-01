package com.aicfo.data.repository

import android.content.Context
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.SecretInventory
import com.aicfo.core.common.flatMap
import com.aicfo.core.crypto.CryptoSecrets
import com.aicfo.core.database.crypto.DatabaseSecrets
import com.aicfo.core.datastore.DataStoreSecrets
import java.io.File
import java.security.KeyStore

/**
 * The platform half of the erase (issue 11.4; §23, §34, SEC-003, P-01).
 *
 * Why:  [DefaultEraseRepository] decides the order; this decides nothing. It walks a
 *       [SecretInventory] the owning modules declared, deletes each name, and — the part that
 *       matters — **checks afterwards that the key is really gone**. `KeyStore.deleteEntry` can
 *       return without throwing on a device that kept the key, and an erase that reported success
 *       while the master key survived would be the single worst bug this feature can have: the
 *       user is told their finances are unrecoverable and they are not.
 * What: destroy every alias and verify; then delete the keysets, files, databases and caches.
 * Result: `Ok(Unit)` only when no listed alias remains in the Keystore.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 *
 * Input:  [context] — any context; the application context is used; [inventory] — every secret to
 *         destroy, composed from the owning modules by [androidEraseInventory]; [keyStore] — the
 *         Keystore seam, so the verify-after-delete logic is unit-testable off a device.
 * Output: the eraser.
 *
 * **What this cannot reach, and says so.** A backup the user exported (issue 8.1) is sealed with
 * *their* Argon2id passphrase, not with any key this app holds, and it sits wherever the system
 * file picker put it. Destroying the Keystore does nothing to it. The erase screen tells the user
 * to delete their own backup files; pretending otherwise would be the one lie this feature must
 * not tell (ADR-0060).
 */
internal class AndroidSecureEraser(
    context: Context,
    private val inventory: SecretInventory,
    private val keyStore: KeyStoreGateway,
) : SecureEraser {
    private val application = context.applicationContext

    override suspend fun destroyKeys(): Result<Unit, AppError> =
        // An empty inventory means the composition lost a module, not that there is nothing to do.
        // Reporting `Ok` here would be a successful erase that touched not one key.
        if (inventory.isEmpty()) {
            Err(AppError.Crypto("erase.inventory_empty"))
        } else {
            deleteEachAlias().flatMap { verifiedGone() }
        }

    /**
     * Asks the Keystore to delete every listed alias.
     * Why:    separated from the verification because they answer different questions, and the
     *         second one is the promise made to the user. A refusal stops the sweep: deleting a
     *         user's files while a live key remains is the worst of both outcomes.
     * Result: `Ok(Unit)` when every call was accepted, or the first `Err`.
     * Input:  none. Output: `Result<Unit, AppError>`.
     * Changelog: 2026-10-01 — Created for issue 11.4.
     */
    private fun deleteEachAlias(): Result<Unit, AppError> =
        inventory.keystoreAliases.fold(Ok(Unit) as Result<Unit, AppError>) { acc, alias ->
            acc.flatMap { keyStore.delete(alias) }
        }

    /**
     * Checks the Keystore holds none of ours, then drops the wrapped keysets.
     * Why:    `deleteEntry` returning without throwing is not evidence the key is gone, and an erase
     *         that reported success while the master key survived would tell the user their finances
     *         are unrecoverable when they are not. The keysets go too: worthless without the master
     *         key, but a keyset left on disk is a thing a future reader has to reason about, and the
     *         point of an erase is that there is nothing left to reason about.
     * Result: `Ok(Unit)`, or `Err(Crypto("erase.key_survived"))`.
     * Input:  none. Output: `Result<Unit, AppError>`.
     * Changelog: 2026-10-01 — Created for issue 11.4.
     */
    private fun verifiedGone(): Result<Unit, AppError> =
        if (inventory.keystoreAliases.any { keyStore.contains(it) }) {
            Err(AppError.Crypto("erase.key_survived"))
        } else {
            inventory.sharedPrefsNames.forEach { name -> application.deleteSharedPreferences(name) }
            Ok(Unit)
        }

    override suspend fun deleteDataFiles(): Result<Unit, AppError> {
        val failures = mutableListOf<String>()
        inventory.filePaths.forEach { path ->
            if (!File(application.filesDir, path).deleteRecursivelyIfPresent()) failures += path
        }
        inventory.databaseNames.forEach { name ->
            // SQLite's sidecars hold recently written pages, so a database deleted without its
            // `-wal` leaves the most recent transactions on disk. They are ciphertext under a key
            // that no longer exists, but leaving them would make the erase look half-finished.
            DATABASE_SIBLING_SUFFIXES.forEach { suffix ->
                val file = application.getDatabasePath(name + suffix)
                if (!file.deleteRecursivelyIfPresent()) failures += name + suffix
            }
        }
        // Caches last, and whole: decoded receipt bitmaps, OCR scratch files and HTTP bodies all
        // land here, and none of it is listed by name because none of it is named by this app.
        listOfNotNull(application.cacheDir, application.codeCacheDir, application.externalCacheDir)
            .forEach { dir -> dir.listFiles()?.forEach { if (!it.deleteRecursivelyIfPresent()) failures += it.name } }

        return if (failures.isEmpty()) Ok(Unit) else Err(AppError.Storage("erase.undeleted:${failures.size}"))
    }

    private companion object {
        /**
         * The database file and the three names SQLite derives from it.
         *
         * Why:  the empty string is the database itself, so one loop covers all four and no caller
         *       can delete the main file and forget the sidecars. `-journal` is listed even though
         *       this app runs in WAL mode: a database restored from a backup taken on a device in
         *       rollback-journal mode can leave one behind.
         */
        val DATABASE_SIBLING_SUFFIXES = listOf("", "-wal", "-shm", "-journal")
    }
}

/**
 * Deletes this file or directory, treating "it was not there" as success.
 * Why:    an erase runs over an inventory of everything the app *can* hold, on an installation that
 *         may never have held half of it — a user who never set a PIN has no `cfo-pin.bin`, and
 *         reporting that as a failed deletion would turn a complete erase into an error.
 * Result: `true` when nothing remains at this path, `false` when something does.
 * Input:  the receiver. Output: [Boolean].
 * Changelog: 2026-10-01 — Created for issue 11.4.
 *
 * The `!exists()` arm is deliberately explicit rather than left to the standard library, which as
 * it happens agrees — `deleteRecursively` answers `true` for a path that was never there, so a
 * mutation removing this arm changes no behaviour and no test can kill it. It stays because the
 * guarantee the erase depends on should be visible here instead of inferred from `kotlin.io`.
 */
private fun File.deleteRecursivelyIfPresent(): Boolean = !exists() || (deleteRecursively() && !exists())

/**
 * The Keystore operations an erase needs (issue 11.4).
 *
 * Why:    `java.security.KeyStore` needs a real `AndroidKeyStore` provider, so the one piece of
 *         logic worth testing — delete everything, then check nothing survived — would otherwise
 *         only ever run on a device. Behind this interface is a class with no branches; in front of
 *         it is the assertion that the user's data is unrecoverable.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 */
internal interface KeyStoreGateway {
    /**
     * Deletes [alias], or does nothing if it is absent.
     * Result: `Ok(Unit)`, or `Err(Crypto)` when the Keystore itself refused. Input: [alias].
     * Output: `Result<Unit, AppError>`.
     */
    fun delete(alias: String): Result<Unit, AppError>

    /**
     * Whether [alias] is still there.
     * Result: `true` when the key survived — which the caller treats as a failed erase. A Keystore
     *         that cannot be read answers `true`, because "I could not check" must never be
     *         reported to a user as "your data is gone".
     * Input:  [alias]. Output: [Boolean].
     */
    fun contains(alias: String): Boolean
}

/**
 * [KeyStoreGateway] over the real `AndroidKeyStore` (issue 11.4; SEC-003).
 * Why:    the platform's own key store is the only thing that can delete a platform key; this is
 *         the thinnest possible wrapper so that nothing but the platform decides what happens.
 * Result: deletions that actually reach hardware. Input: none. Output: the gateway.
 * Changelog: 2026-10-01 — Created for issue 11.4.
 */
internal class AndroidKeyStoreGateway : KeyStoreGateway {
    override fun delete(alias: String): Result<Unit, AppError> =
        try {
            loaded().deleteEntry(alias)
            Ok(Unit)
        } catch (e: java.security.GeneralSecurityException) {
            Err(AppError.Crypto("erase.keystore_delete:${e.javaClass.simpleName}"))
        }

    override fun contains(alias: String): Boolean =
        try {
            loaded().containsAlias(alias)
        } catch (_: java.security.GeneralSecurityException) {
            // Unreadable, so unverifiable, so reported as surviving. See the interface's note.
            true
        }

    /** Result: the loaded Keystore. Input: none. Output: [KeyStore]. */
    private fun loaded(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private companion object {
        /** The platform's own provider id, not a choice. */
        const val PROVIDER = "AndroidKeyStore"
    }
}

/**
 * Every secret in the app, composed from the modules that own them (issue 11.4).
 *
 * Why:  the one place that knows the erase is complete. Each module declares what it keeps; this
 *       adds them up. A new key chain is covered by adding its module's inventory here — one line,
 *       in a function a reviewer can read in full, rather than a deletion scattered through a
 *       hundred-line eraser.
 * What: the database chain, both crypto chains, and the settings file.
 * Result: the eraser's complete work list.
 * Input:  none. Output: [SecretInventory].
 * Changelog: 2026-10-01 — Created for issue 11.4.
 */
internal fun androidEraseInventory(): SecretInventory =
    DatabaseSecrets.inventory + CryptoSecrets.inventory + DataStoreSecrets.inventory
