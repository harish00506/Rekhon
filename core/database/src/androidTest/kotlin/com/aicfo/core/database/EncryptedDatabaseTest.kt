package com.aicfo.core.database

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicfo.core.common.getOrNull
import com.aicfo.core.database.entity.AccountEntity
import com.aicfo.core.database.entity.ProfileEntity
import com.aicfo.core.database.entity.TransactionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The encrypted round-trip — issue 1.6's central acceptance criterion (SEC-003, DB-003, P-04).
 *
 * Why:  SQLCipher and the Android Keystore exist only on a device, so this is the **only** test
 *       that can prove the claim the whole issue rests on: that the data is actually encrypted on
 *       disk and still reads back exactly. The unit tests cover the key decisions; they cannot
 *       cover whether the file is really ciphertext.
 * What: a full open → write → read cycle through the real Keystore, an assertion that the file on
 *       disk is not a readable SQLite database, and the soft-delete and money-precision invariants.
 * Result: run this on a device and issue 1.6's AC is proved; until then it is unproved.
 * Changelog: 2026-07-25 — Created for issue 1.6.
 *
 * **NOT YET RUN.** The machine this was written on has no device or emulator (`adb devices` is
 * empty, and the SDK has no AVD installed), so these assertions have never executed. They are
 * written to run unchanged the moment one exists — `./gradlew :core:database:connectedDebugAndroidTest`.
 * Nothing in the tracker claims otherwise.
 *
 * No network is used anywhere in this path, which is why the airplane-mode requirement (P-04) is
 * satisfied by construction rather than by a toggle: there is no code that could behave differently.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var database: CfoDatabase

    private val profile =
        ProfileEntity(
            id = "profile-1",
            displayName = "Test",
            timeZoneId = "Asia/Kolkata",
            currencyCode = "INR",
            createdAtUtcMillis = 1_767_225_600_000L,
            updatedAtUtcMillis = 1_767_225_600_000L,
        )

    /** Input: none. Output: opens the encrypted database, failing the test if the key path breaks. */
    @Before
    fun setUp() {
        deleteDatabaseFiles()
        database = CfoDatabaseFactory.open(context).getOrNull() ?: error("database failed to open")
    }

    /** Input: none. Output: closes and removes the database so each test starts clean. */
    @After
    fun tearDown() {
        database.close()
        deleteDatabaseFiles()
    }

    /**
     * Input:  a profile, an account and a transaction written through the DAOs.
     * Output: asserts every value reads back byte-identical — in particular that amounts survive
     *         as exact `Long` paise (MNY-001) and the booked date stays an ISO string (TIM-002).
     */
    @Test
    fun writesAndReadsBackThroughTheEncryptedFile() =
        runTest {
            val account =
                AccountEntity(
                    id = "account-1",
                    profileId = profile.id,
                    name = "HDFC Savings",
                    type = "bank",
                    openingBalanceMinor = 12_345_678L,
                    currentBalanceMinor = 12_345_678L,
                    currencyCode = "INR",
                    createdAtUtcMillis = profile.createdAtUtcMillis,
                    updatedAtUtcMillis = profile.createdAtUtcMillis,
                )
            val transaction =
                TransactionEntity(
                    id = "txn-1",
                    profileId = profile.id,
                    accountId = account.id,
                    amountMinor = -1_23_456_78L,
                    currencyCode = "INR",
                    occurredAtUtcMillis = 1_767_312_000_000L,
                    bookedOnIsoDate = "2026-01-02",
                    source = "manual",
                    createdAtUtcMillis = profile.createdAtUtcMillis,
                    updatedAtUtcMillis = profile.createdAtUtcMillis,
                )

            database.profileDao().upsert(profile)
            database.accountDao().upsert(account)
            database.transactionDao().upsert(transaction)

            assertEquals(profile, database.profileDao().findById(profile.id))
            assertEquals(listOf(account), database.accountDao().observeForProfile(profile.id).first())
            val readBack = database.transactionDao().findById(transaction.id)
            assertNotNull(readBack)
            // Exactness is the point: a Double round-trip would lose this value.
            assertEquals(-1_23_456_78L, readBack!!.amountMinor)
            assertEquals("2026-01-02", readBack.bookedOnIsoDate)
        }

    /**
     * Input:  the database file on disk after a write.
     * Output: asserts it is **not** a readable SQLite file and does not contain the plaintext we
     *         just stored. An unencrypted SQLite database begins with the ASCII header
     *         `SQLite format 3`; a SQLCipher one is indistinguishable from random bytes. This is
     *         the assertion that would catch the worst possible regression — silently opening an
     *         unencrypted database.
     */
    @Test
    fun theFileOnDiskIsCiphertext() =
        runTest {
            database.profileDao().upsert(profile.copy(displayName = "Recognisable Name"))
            database.close()

            val bytes = databaseFile().readBytes()
            val header = String(bytes.copyOfRange(0, SQLITE_HEADER.length.coerceAtMost(bytes.size)))
            assertFalse("the database must not be plain SQLite", header.startsWith(SQLITE_HEADER))
            assertFalse(
                "stored text must not be readable in the raw file",
                String(bytes, Charsets.ISO_8859_1).contains("Recognisable Name"),
            )
            assertTrue("the database file must exist and be non-trivial", bytes.size > MINIMUM_DB_BYTES)
        }

    /**
     * Input:  a soft-deleted account.
     * Output: asserts it disappears from reads while the row itself survives — DB-003's
     *         recoverability, and the reason every query filters `deleted_at_utc_millis`.
     */
    @Test
    fun softDeletedRowsAreHiddenButRetained() =
        runTest {
            val account =
                AccountEntity(
                    id = "account-2",
                    profileId = profile.id,
                    name = "Cash",
                    type = "cash",
                    openingBalanceMinor = 0L,
                    currentBalanceMinor = 0L,
                    currencyCode = "INR",
                    createdAtUtcMillis = profile.createdAtUtcMillis,
                    updatedAtUtcMillis = profile.createdAtUtcMillis,
                )
            database.profileDao().upsert(profile)
            database.accountDao().upsert(account)

            database.accountDao().softDelete(account.id, deletedAtUtcMillis = 1_767_400_000_000L)

            assertTrue(database.accountDao().observeForProfile(profile.id).first().isEmpty())
            val raw =
                database.openHelper.readableDatabase
                    .query("SELECT COUNT(*) FROM account WHERE id = '${account.id}'")
            raw.use {
                it.moveToFirst()
                assertEquals("the row must be retained, not removed", 1, it.getInt(0))
            }
        }

    /**
     * Input:  a second open of the same database.
     * Output: asserts the passphrase is reused rather than regenerated — the failure that would
     *         make an existing database permanently unopenable.
     */
    @Test
    fun reopensWithTheSamePassphrase() =
        runTest {
            database.profileDao().upsert(profile)
            database.close()

            val reopened = CfoDatabaseFactory.open(context).getOrNull()
            assertNotNull("the database must reopen with the stored key", reopened)
            assertEquals(profile, reopened!!.profileDao().findById(profile.id))
            reopened.close()
        }

    private fun databaseFile(): File = context.getDatabasePath(CfoDatabase.FILE_NAME)

    private fun deleteDatabaseFiles() {
        val base = databaseFile()
        listOf(base, File(base.path + "-wal"), File(base.path + "-shm")).forEach { it.delete() }
        File(context.filesDir, "cfo-db-passphrase.bin").delete()
    }

    private companion object {
        const val SQLITE_HEADER = "SQLite format 3"
        const val MINIMUM_DB_BYTES = 512
    }
}
