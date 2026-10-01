package com.aicfo.core.database

import android.content.Context
import android.database.SQLException
import androidx.room.Room
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.flatMap
import com.aicfo.core.common.map
import com.aicfo.core.database.crypto.DatabaseRekeyer
import com.aicfo.core.database.crypto.DatabaseSecrets
import com.aicfo.core.database.crypto.FileWrappedPassphraseStore
import com.aicfo.core.database.crypto.KeystoreAeadFactory
import com.aicfo.core.database.crypto.PassphraseCandidates
import com.aicfo.core.database.crypto.SqlCipherPassphraseManager
import com.aicfo.core.database.migration.Migrations
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.security.SecureRandom

/**
 * Opens the encrypted database (SEC-003, DB-003, P-04).
 *
 * Why:  this is where the two halves meet — the Keystore-wrapped passphrase from
 *       [SqlCipherPassphraseManager] and Room's builder — and it is the one place that must not
 *       take a shortcut. Two shortcuts in particular would be invisible in testing and fatal in
 *       production: opening an unencrypted database (the data would sit in plaintext on the
 *       device), and allowing a destructive migration fallback (a schema mistake would silently
 *       delete a user's entire financial history, with no server copy to restore from).
 * What: resolves the passphrase, hands it to SQLCipher's open helper, and builds Room over it.
 * Result: `Ok(CfoDatabase)` on an encrypted file, or a typed error — nothing throws across this
 *       boundary (§21.6).
 * Changelog: 2026-07-25 — Created for issue 1.6.
 *
 * **Offline by construction (P-04):** nothing here touches the network, so the database opens and
 * round-trips identically in airplane mode. There is no path that could behave otherwise.
 */
object CfoDatabaseFactory {
    /**
     * Opens (creating on first run) the encrypted database.
     * Why:    every caller needs the same passphrase resolution and the same Room configuration;
     *         duplicating either is how one code path ends up opening an unencrypted file.
     * What:   unwraps the passphrase via the Keystore-backed AEAD, then builds Room on
     *         SQLCipher's [SupportOpenHelperFactory].
     * Result: `Ok(database)`, or `Err(AppError.Crypto)` if the key cannot be unwrapped —
     *         deliberately **not** a fresh empty database, which would look to the user like their
     *         data had been wiped.
     * Input:  [context] — any context; the application context is used.
     * Output: `Result<CfoDatabase, AppError>`.
     */
    fun open(context: Context): Result<CfoDatabase, AppError> {
        val application = context.applicationContext
        val manager = manager(application)
        return manager.candidates().flatMap { candidates ->
            openWith(application, candidates).flatMap { opened ->
                // Which key actually worked is the only evidence of whether an interrupted
                // rotation had taken effect, so it is reported back before anything else happens.
                manager.confirm(opened.passphrase).map {
                    // The candidate that did not open the file opens nothing at all: it is either
                    // a superseded key or one that was never applied. Zeroing it keeps a dead key
                    // out of the heap for the rest of the session (security review, issue 11.1).
                    candidates.discardAllBut(opened.passphrase)
                    opened.database
                }
            }
        }
    }

    /**
     * Rotates the database key: new passphrase, re-keyed file, promoted only when both agree.
     *
     * Why:    SEC-003 asks for a rotation path, and it has to exist as *one* call. Split across
     *         two — rotate the key here, re-key the file there — it would be a matter of time
     *         before something did the first without the second and locked a user out of their own
     *         data permanently. [SqlCipherPassphraseManager.rotate] owns the ordering; this
     *         supplies the re-key it calls in the middle.
     * What:   runs the protocol over the real database file.
     * Result: `Ok(Unit)` with the file on a new key; `Err` with the file on the old one, which
     *         still opens.
     * Input:  [context] — any context. **No `CfoDatabase` may be open**: a re-key rewrites every
     *         page, and another connection would be reading the file as it changed.
     * Output: `Result<Unit, AppError>`.
     * Changelog: 2026-09-28 — Created for issue 11.1.
     */
    fun rotateKey(context: Context): Result<Unit, AppError> {
        val application = context.applicationContext
        val file = application.getDatabasePath(CfoDatabase.FILE_NAME)
        return manager(application)
            .rotate { change -> DatabaseRekeyer.rekey(file, change) }
            .map { }
    }

    /**
     * Result: the passphrase manager for this installation. Input: [application]. Output: the
     *         manager.
     * Why:    two entry points need exactly the same store, AEAD and random source; building it
     *         twice by hand is how one of them ends up pointing at a different file.
     * Changelog: 2026-09-28 — Created for issue 11.1.
     */
    private fun manager(application: Context): SqlCipherPassphraseManager =
        SqlCipherPassphraseManager(
            store = FileWrappedPassphraseStore(File(application.filesDir, DatabaseSecrets.PASSPHRASE_FILE)),
            aead = KeystoreAeadFactory.create(application),
            random = SecureRandom(),
        )

    /**
     * Opens the file with whichever candidate it accepts.
     * Why:    a rotation interrupted by process death leaves the file on one key and the store
     *         naming the other (issue 11.1). Trying the current key and then the staged one is
     *         what turns that from lost data into a slower open. Room opens lazily, so the
     *         connection is forced here — otherwise the wrong key would be discovered later, from
     *         somewhere that cannot try the other one.
     * Result: `Ok(OpenedDatabase)`, or `Err(Crypto)` when no candidate opens it — which means the
     *         key is genuinely gone, and a fresh empty database would be a lie (see [open]).
     * Input:  [application]; [candidates]. Output: `Result<OpenedDatabase, AppError>`.
     * Changelog: 2026-09-28 — Created for issue 11.1.
     */
    private fun openWith(
        application: Context,
        candidates: PassphraseCandidates,
    ): Result<OpenedDatabase, AppError> =
        tryOpen(application, candidates.current)
            ?: candidates.pending?.let { tryOpen(application, it) }
            ?: Err(AppError.Crypto("no_passphrase_opens_the_database"))

    /**
     * Result: the opened database, or `null` when this key is not the file's.
     * Why:    `null` rather than a `Result`, because "this candidate did not work" is not yet a
     *         failure — there may be another one to try.
     * Input:  [application]; [passphrase]. Output: `Ok(OpenedDatabase)?`.
     * Changelog: 2026-09-28 — Created for issue 11.1.
     */
    private fun tryOpen(
        application: Context,
        passphrase: ByteArray,
    ): Result<OpenedDatabase, AppError>? =
        try {
            val database = build(application, passphrase)
            // Room is lazy: without a read, a wrong key would surface on the first query instead
            // of here, where the other candidate is still in reach.
            database.openHelper.readableDatabase
            Ok(OpenedDatabase(database, passphrase))
        } catch (_: SQLException) {
            // A wrong key reaches us as a SQLCipher exception under `android.database.SQLException`.
            // Anything else — a missing native library, a full disk — is not "try the other key".
            null
        }

    /**
     * Builds the Room instance over an encrypted SQLite file.
     * Why:    `SupportOpenHelperFactory` is what makes the file SQLCipher-encrypted rather than
     *         plain SQLite; without it Room would happily open an unencrypted database and
     *         nothing would look wrong.
     * Result: a configured [CfoDatabase]. No destructive-migration fallback is set (DB-003), so a
     *         missing migration fails loudly at open instead of dropping tables.
     * Input:  [context] — the application context; [passphrase] — the unwrapped key bytes.
     * Output: [CfoDatabase].
     */
    private fun build(
        context: Context,
        passphrase: ByteArray,
    ): CfoDatabase {
        System.loadLibrary("sqlcipher")
        val builder =
            Room
                .databaseBuilder(context, CfoDatabase::class.java, CfoDatabase.FILE_NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
        // Every hand-written migration, in order. Registered by iterating rather than spreading the
        // array, so `Migrations.ALL` stays the single list and adding one is still a one-line edit.
        // Without this an installation on an older schema throws at open — which is the correct
        // failure, but only because there is no destructive fallback to swallow it (DB-003).
        Migrations.ALL.forEach { builder.addMigrations(it) }
        return builder.build()
    }
}

/**
 * A database and the key that opened it (issue 11.1).
 * Why: [CfoDatabaseFactory] has to report back *which* candidate worked, and pairing the two
 *         means it cannot report the wrong one.
 * Result: an internal holder, never exposed.
 * Input:  [database]; [passphrase] — the key the file accepted. Output: an immutable value.
 * Changelog: 2026-09-28 — Created for issue 11.1.
 */
private class OpenedDatabase(
    val database: CfoDatabase,
    val passphrase: ByteArray,
)
