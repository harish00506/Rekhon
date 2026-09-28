package com.aicfo.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicfo.core.common.Ok
import com.aicfo.core.common.getOrNull
import com.aicfo.core.database.crypto.FileWrappedPassphraseStore
import com.aicfo.core.database.crypto.KeystoreAeadFactory
import com.aicfo.core.database.crypto.SqlCipherPassphraseManager
import com.aicfo.core.database.entity.ProfileEntity
import kotlinx.coroutines.test.runTest
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom

/**
 * Rotating the key on a real encrypted database (issue 11.1; SEC-003, §23).
 *
 * Why:  the protocol's ordering is proved on the JVM in `PassphraseRotationTest`, but the claim
 *       the acceptance criterion actually makes — *"re-keys the DB without data loss"* — cannot be
 *       proved without SQLCipher and the Keystore, which exist only on a device. Two things have
 *       to be true together and only a device can show it: every row survives, **and** the file is
 *       genuinely on a new key afterwards. A rotation that quietly did nothing would pass the
 *       first test on its own.
 * What: write, rotate, read back, and then try the old key.
 * Result: run on a device, SEC-003's rotation requirement is proved; until then it is a claim.
 * Changelog: 2026-09-28 — Created for issue 11.1.
 */
@RunWith(AndroidJUnit4::class)
class KeyRotationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val profile =
        ProfileEntity(
            id = "profile-rotation",
            displayName = "Rotation",
            timeZoneId = "Asia/Kolkata",
            currencyCode = "INR",
            createdAtUtcMillis = 1_767_225_600_000L,
            updatedAtUtcMillis = 1_767_225_600_000L,
        )

    /** Input: none. Output: a clean slate, so a leftover key from another test cannot mask a bug. */
    @Before
    fun setUp() = deleteDatabaseFiles()

    /** Input: none. Output: the files are removed again. */
    @After
    fun tearDown() = deleteDatabaseFiles()

    @Test
    fun rotation_keeps_every_row_and_leaves_the_file_on_a_new_key() =
        runTest {
            val before = manager().candidates().getOrNull()!!.current.copyOf()
            withDatabase { database -> database.profileDao().upsert(profile) }

            val rotated = CfoDatabaseFactory.rotateKey(context)

            assertTrue("the rotation itself failed: $rotated", rotated is Ok)
            val after = manager().candidates().getOrNull()!!
            assertFalse("the stored key did not change", before.contentEquals(after.current))
            assertNull("a completed rotation must leave nothing staged", after.pending)
            withDatabase { database ->
                assertNotNull("the row did not survive the re-key", database.profileDao().findById(profile.id))
                assertEquals(profile.displayName, database.profileDao().findById(profile.id)?.displayName)
            }
            assertFalse("the old key still opens the file — the re-key never happened", opensWith(before))
            assertTrue("the new key does not open the file", opensWith(after.current))
        }

    @Test
    fun a_rotation_that_cannot_re_key_leaves_the_database_exactly_as_it_was() =
        runTest {
            // The failure the two-slot protocol exists for. The file is untouched, so the old key
            // must still be the stored one — and nothing may be left staged for the next open.
            val before = manager().candidates().getOrNull()!!.current.copyOf()
            withDatabase { database -> database.profileDao().upsert(profile) }

            // A file that is not a SQLCipher database: the re-key cannot succeed against it, and
            // the manager must roll its staging back rather than adopt the key it minted.
            val decoy = File(context.cacheDir, "not-a-database.db").apply { writeBytes(ByteArray(64)) }
            val outcome =
                manager().rotate { change ->
                    com.aicfo.core.database.crypto.DatabaseRekeyer.rekey(decoy, change)
                }

            assertFalse("a failed re-key must not report success", outcome is Ok)
            val after = manager().candidates().getOrNull()!!
            assertArrayEquals("the stored key changed although the file did not", before, after.current)
            assertNull("a failed rotation must not leave a staged key behind", after.pending)
            withDatabase { database ->
                assertNotNull(
                    "the database stopped opening after a failed rotation",
                    database.profileDao().findById(profile.id),
                )
            }
        }

    // --- helpers ------------------------------------------------------------------------------

    /**
     * Result: whatever [block] returns, with the database opened through the production path and
     *         closed afterwards.
     * Why:    `RoomDatabase` is not `AutoCloseable`, and a connection left open would hold the
     *         file while the next step tries to re-key it.
     * Input:  [block]. Output: its result.
     */
    private suspend fun <T> withDatabase(block: suspend (CfoDatabase) -> T): T {
        val database = CfoDatabaseFactory.open(context).getOrNull() ?: error("database failed to open")
        return try {
            block(database)
        } finally {
            database.close()
        }
    }

    /**
     * Result: a manager over the same store and Keystore key the app uses.
     * Why:    the test has to read the stored passphrase to prove it changed; building the manager
     *         the same way the factory does is what makes that the *same* key rather than a copy.
     * Input:  none. Output: [SqlCipherPassphraseManager].
     */
    private fun manager(): SqlCipherPassphraseManager =
        SqlCipherPassphraseManager(
            store = FileWrappedPassphraseStore(File(context.filesDir, "cfo-db-passphrase.bin")),
            aead = KeystoreAeadFactory.create(context),
            random = SecureRandom(),
        )

    /**
     * Result: whether [passphrase] opens the database file at all.
     * Why:    the only way to show a re-key really happened. Asserting the *stored* key changed
     *         proves nothing about the file.
     * Input:  [passphrase]. Output: [Boolean].
     */
    private fun opensWith(passphrase: ByteArray): Boolean =
        try {
            System.loadLibrary("sqlcipher")
            val database =
                SQLiteDatabase
                    .openDatabase(
                        databaseFile().absolutePath,
                        passphrase,
                        null,
                        SQLiteDatabase.OPEN_READONLY,
                        null,
                        null,
                    )
            try {
                database.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
            } finally {
                database.close()
            }
            true
        } catch (_: RuntimeException) {
            false
        }

    private fun databaseFile(): File = context.getDatabasePath(CfoDatabase.FILE_NAME)

    private fun deleteDatabaseFiles() {
        val base = databaseFile()
        listOf(base, File(base.path + "-wal"), File(base.path + "-shm")).forEach { it.delete() }
        File(context.filesDir, "cfo-db-passphrase.bin").delete()
        File(context.filesDir, "cfo-db-passphrase.bin.pending").delete()
    }
}
