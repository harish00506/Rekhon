package com.aicfo.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.TestDispatchers
import com.aicfo.core.crypto.BackupCipherFactory
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.CfoDatabaseFactory
import com.aicfo.core.database.entity.AccountEntity
import com.aicfo.core.database.entity.ProfileEntity
import com.aicfo.core.database.entity.TransactionEntity
import com.aicfo.core.datastore.ConsentFeature
import com.aicfo.core.datastore.ConsentState
import com.aicfo.core.datastore.ConsentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Backup → wipe → restore on a real device, through real SQLCipher (issue 8.2; SEC-005, §21.5).
 *
 * Why:  the acceptance criterion names an **instrumented** round trip, and for a reason every JVM
 *       test here misses: they all run on unencrypted in-memory Room. This one takes the backup from
 *       the encrypted database file, **deletes that file and its passphrase** — what a fresh
 *       install has — opens a brand-new encrypted database, and restores into it. It also runs
 *       Argon2id at the shipping cost (64 MiB) on an Android heap, which no JVM test can.
 * What: the round trip with exact paise, and a wrong passphrase on the rebuilt device writing
 *       nothing.
 * Result: "proven restorable" (§21.5) on the hardware path rather than asserted about it.
 * Changelog: 2026-09-18 — Created for issue 8.2.
 *
 * Runs in this module's own test package, so the database it creates and deletes is the test's,
 * never the installed app's.
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = FakeClock(initialMillis = CREATED)
    private val dispatchers = TestDispatchers(Dispatchers.Default)
    private lateinit var database: CfoDatabase

    /** Input: none. Output: a clean encrypted database, as on a first launch. */
    @Before
    fun setUp() {
        deleteDatabaseFiles()
        database = open()
    }

    /** Input: none. Output: closes and removes the database. */
    @After
    fun tearDown() {
        database.close()
        deleteDatabaseFiles()
    }

    /**
     * Input:  a profile, an account and two transactions in the encrypted database.
     * Output: asserts that after the database file and its key file are deleted and a new one is
     *         opened, the restore brings back every row, the amounts to the paisa.
     */
    @Test
    fun aBackupRestoresOntoAFreshEncryptedDatabase() =
        runTest {
            seed()
            val sealed = backups().create(PASSPHRASE.toCharArray()).expectOk()

            database.close()
            deleteDatabaseFiles()
            database = open()
            assertEquals("the fresh database must start empty", 0, database.demoDao().countRowsFor(PROFILE))

            val summary = backups().restore(sealed, PASSPHRASE.toCharArray()).expectOk()

            assertEquals(ROWS, summary.rowsImported)
            assertEquals(listOf(account()), database.accountDao().observeForProfile(PROFILE).first())
            assertEquals(ODD_AMOUNT, database.transactionDao().findById("txn-1")!!.amountMinor)
            assertEquals(LARGE_AMOUNT, database.transactionDao().findById("txn-2")!!.amountMinor)
        }

    /**
     * Input:  a backup, and a fresh database already holding one row.
     * Output: asserts a wrong passphrase is refused and the row count is unchanged — fail-secure,
     *         no partial write.
     */
    @Test
    fun aWrongPassphraseWritesNothingOnTheDevice() =
        runTest {
            seed()
            val sealed = backups().create(PASSPHRASE.toCharArray()).expectOk()
            database.transactionDao().upsert(transaction("txn-after", 1_00L))
            val rowsBefore = database.demoDao().countRowsFor(PROFILE)

            val outcome = backups().restore(sealed, "correct horse battery stapler".toCharArray())

            assertEquals(Err(AppError.Crypto("backup.open")), outcome)
            assertEquals(rowsBefore, database.demoDao().countRowsFor(PROFILE))
        }

    // --- the real stack, built by hand ------------------------------------------------------------

    private fun open(): CfoDatabase = CfoDatabaseFactory.open(context).expectOk()

    private fun backups(): BackupRepository =
        RepositoryFactory.backup(
            archive = RepositoryFactory.archive(database, clock, dispatchers, flowOf(PROFILE)),
            cipher = BackupCipherFactory.create(),
            consents = GrantedConsent,
            audit = RepositoryFactory.auditLog(database, clock, dispatchers),
            dispatchers = dispatchers,
        )

    private suspend fun seed() {
        database.profileDao().upsert(
            ProfileEntity(
                id = PROFILE,
                displayName = "Test",
                timeZoneId = "Asia/Kolkata",
                currencyCode = "INR",
                createdAtUtcMillis = CREATED,
                updatedAtUtcMillis = CREATED,
            ),
        )
        database.accountDao().upsert(account())
        database.transactionDao().upsert(transaction("txn-1", ODD_AMOUNT))
        database.transactionDao().upsert(transaction("txn-2", LARGE_AMOUNT))
    }

    private fun account() =
        AccountEntity(
            id = "account-1",
            profileId = PROFILE,
            name = "HDFC Savings",
            type = "bank",
            openingBalanceMinor = 12_345_678L,
            currentBalanceMinor = 12_345_678L,
            currencyCode = "INR",
            createdAtUtcMillis = CREATED,
            updatedAtUtcMillis = CREATED,
        )

    private fun transaction(
        id: String,
        amountMinor: Long,
    ) = TransactionEntity(
        id = id,
        profileId = PROFILE,
        accountId = "account-1",
        amountMinor = amountMinor,
        currencyCode = "INR",
        occurredAtUtcMillis = CREATED,
        bookedOnIsoDate = "2026-09-01",
        source = "manual",
        type = "expense",
        createdAtUtcMillis = CREATED,
        updatedAtUtcMillis = CREATED,
    )

    /** Removes the database, its journals and its wrapped passphrase — a fresh install's state. */
    private fun deleteDatabaseFiles() {
        val base = context.getDatabasePath(CfoDatabase.FILE_NAME)
        listOf(base, File(base.path + "-wal"), File(base.path + "-shm")).forEach { it.delete() }
        File(context.filesDir, "cfo-db-passphrase.bin").delete()
    }

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    /** The backup consent, granted: this test is about the round trip, not the gate. */
    private object GrantedConsent : ConsentStore {
        override fun observe(feature: ConsentFeature): Flow<Result<ConsentState, AppError>> =
            flowOf(Ok(ConsentState(granted = true)))

        override fun observeAll(): Flow<Result<Map<ConsentFeature, ConsentState>, AppError>> = emptyFlow()

        override suspend fun grant(feature: ConsentFeature): Result<Unit, AppError> = Ok(Unit)

        override suspend fun revoke(feature: ConsentFeature): Result<Unit, AppError> = Ok(Unit)
    }

    private companion object {
        const val PROFILE = "local"
        const val PASSPHRASE = "correct horse battery staple"
        const val CREATED = 1_767_225_600_000L
        const val ODD_AMOUNT = -1_23_457L
        const val LARGE_AMOUNT = 9_007_199_254_740_993L
        const val ROWS = 4
    }
}
