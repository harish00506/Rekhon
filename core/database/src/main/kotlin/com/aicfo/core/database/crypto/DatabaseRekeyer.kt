package com.aicfo.core.database.crypto

import android.database.SQLException
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * Re-encrypts the database file under a new key (issue 11.1; SEC-003, §23).
 *
 * Why:  rotating the stored passphrase without re-keying the file is not a rotation — it is a way
 *       to lose a database. This is the other half, and it is deliberately the *only* half that
 *       touches SQLCipher directly: [SqlCipherPassphraseManager] decides, this executes.
 *
 *       It uses SQLCipher's own `changePassword`, not a hand-built `PRAGMA rekey` string. The
 *       library's call is the same operation done by people who wrote the cipher; composing the
 *       SQL ourselves would mean formatting key material into a string, which is both a worse
 *       implementation and one more place a key could be logged.
 * What: open with the old key, re-key, close.
 * Result: `Ok(Unit)` with the file readable only by the new key, or `Err(Crypto)` with the file
 *       untouched — SQLCipher's re-key is transactional, so a failure leaves the old key working.
 * Changelog: 2026-09-28 — Created for issue 11.1.
 *
 * **The database must not be open elsewhere.** A re-key rewrites every page; another connection
 * holding the file would see it change underneath. The caller closes Room first — that ordering is
 * the caller's to keep, and [CfoDatabaseFactory.rotateKey] is where it is kept.
 */
internal object DatabaseRekeyer {
    /**
     * Re-keys the file.
     * Why:    `changePassword` is SQLCipher's `PRAGMA rekey`, which rewrites every page under the
     *         new key inside a transaction. Nothing else in this app may write key material into
     *         SQL text.
     * Result: `Ok(Unit)`, or `Err(Crypto)` naming the exception class — never the key, never the
     *         path, never a message that could carry either (P-01's logging rule).
     * Input:  [file] — the database file; [change] — the key it is on, and the key it should be on.
     * Output: `Result<Unit, AppError>`.
     */
    fun rekey(
        file: File,
        change: PassphraseChange,
    ): Result<Unit, AppError> =
        try {
            System.loadLibrary("sqlcipher")
            SQLiteDatabase
                .openDatabase(file.absolutePath, change.previous, null, SQLiteDatabase.OPEN_READWRITE, null, null)
                .use { database -> database.changePassword(change.current) }
            Ok(Unit)
        } catch (failure: SQLException) {
            // SQLCipher reports a wrong key, a locked file and a corrupt header as different
            // subclasses of `SQLiteException`, all of them unchecked and all of them under
            // `android.database.SQLException`. What the caller needs to know is the same in every
            // case: the file was not re-keyed, so the old key still opens it.
            Err(AppError.Crypto(failure::class.java.simpleName))
        }
}
