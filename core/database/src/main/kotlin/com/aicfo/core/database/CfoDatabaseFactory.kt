package com.aicfo.core.database

import android.content.Context
import androidx.room.Room
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.common.map
import com.aicfo.core.database.crypto.FileWrappedPassphraseStore
import com.aicfo.core.database.crypto.KeystoreAeadFactory
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
    /** Where the wrapped passphrase lives, inside app-private storage. */
    private const val PASSPHRASE_FILE = "cfo-db-passphrase.bin"

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
        val manager =
            SqlCipherPassphraseManager(
                store = FileWrappedPassphraseStore(File(application.filesDir, PASSPHRASE_FILE)),
                aead = KeystoreAeadFactory.create(application),
                random = SecureRandom(),
            )
        return manager.getOrCreate().map { passphrase -> build(application, passphrase) }
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
