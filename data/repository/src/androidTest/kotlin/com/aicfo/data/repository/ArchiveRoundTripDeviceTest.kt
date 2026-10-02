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
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.CfoDatabaseFactory
import com.aicfo.core.database.crypto.DatabaseSecrets
import com.aicfo.core.database.entity.AccountEntity
import com.aicfo.core.database.entity.ProfileEntity
import com.aicfo.core.database.entity.TransactionEntity
import com.aicfo.core.datastore.ConsentFeature
import com.aicfo.core.datastore.ConsentState
import com.aicfo.core.datastore.ConsentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * §5.10's archive, round-tripped through real SQLCipher (issue 12.4; §21.5, P-04, FR-SET-003).
 *
 * Why:  issue 12.4's acceptance criterion asks for the export/import round trip to be asserted where
 *       a failure blocks a release, and the existing coverage does not reach it. `ArchiveRepositoryTest`
 *       proves the rows travel — on **unencrypted in-memory Room**, like every JVM suite here.
 *       `BackupRestoreDeviceTest` proves the *encrypted backup* round-trips on a device, which is a
 *       different file, a different cipher and a different code path. The plain JSON archive — the one
 *       a user actually exports from the dashboard and the only copy they can read — had never been
 *       written and read back through SQLCipher on real hardware.
 *
 *       What that reaches and the JVM cannot: SQLCipher's own driver, the real migration chain, and
 *       `withTransaction` on an encrypted connection — where an import that half-applies leaves a
 *       user with neither their old data nor their new.
 * What: seed → export → wipe the database **and its key** → reopen clean → import → every row back,
 *       with amounts exact to the paise.
 * Result: the round trip is a release gate rather than an assumption.
 * Changelog: 2026-10-02 — Created for issue 12.4.
 *
 * Runs in this module's own test package, so the database it creates and destroys is the test's,
 * never the installed app's.
 */
@RunWith(AndroidJUnit4::class)
class ArchiveRoundTripDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = FakeClock(initialMillis = EXPORTED_AT)
    private val dispatchers = TestDispatchers(Dispatchers.Default)
    private lateinit var database: CfoDatabase

    /** Input: none. Output: a clean encrypted database, as on a first launch. */
    @Before
    fun setUp() {
        deleteDatabaseFiles()
        database = CfoDatabaseFactory.open(context).expectOk()
    }

    /** Input: none. Output: closes and removes the database and its wrapped key. */
    @After
    fun tearDown() {
        database.close()
        deleteDatabaseFiles()
    }

    @Test
    fun exportThenWipeThenImportGivesBackEveryRowWithExactAmounts() =
        runTest {
            seed()
            val before = database.transactionDao().findById(TXN_ID)
            assertNotNull("the test seeded nothing", before)

            val json = archive().export().expectOk()
            assertTrue("the archive must carry the transaction", json.contains(ODD_AMOUNT.toString()))

            // The real reset: the database file **and the wrapped passphrase** go, which is what a
            // fresh device has. Re-opening generates a new key, so the import runs against a
            // database that cannot have retained anything from the old one.
            database.close()
            deleteDatabaseFiles()
            database = CfoDatabaseFactory.open(context).expectOk()
            assertEquals("the wipe left rows behind", null, database.transactionDao().findById(TXN_ID))

            val summary = archive().import(json).expectOk()

            assertTrue("an import that restored nothing is not a round trip", summary.rowsImported > 0)
            assertEquals(EXPORTED_AT, summary.exportedAtUtcMillis)
            val restored = database.transactionDao().findById(TXN_ID)
            assertNotNull("the transaction did not come back", restored)
            // Exact to the paise (MNY-001). A rounding bug anywhere in the chain shows up here as a
            // figure that is nearly right, which is the worst kind.
            assertEquals(ODD_AMOUNT, restored!!.amountMinor)
            assertEquals(PROFILE, restored.profileId)
            assertEquals(1, database.accountDao().observeForProfile(PROFILE).first().size)
        }

    @Test
    fun anArchiveFromAnIncompatibleSchemaIsRefusedAndChangesNothing() =
        runTest {
            // The failure that matters more than a clean round trip: a refused import must leave the
            // user's data exactly where it was. A wipe-then-fail would lose everything they had.
            seed()
            val tampered = """{"archiveVersion":1,"schemaVersion":999999,"exportedAtUtcMillis":0}"""

            val outcome = archive().import(tampered)

            assertTrue("an archive from a future schema must be refused", outcome is Err)
            assertNotNull("the refused import destroyed existing data", database.transactionDao().findById(TXN_ID))
        }

    // --- the real stack, built by hand ------------------------------------------------------------

    private fun archive(): ArchiveRepository =
        RepositoryFactory.archive(database, clock, dispatchers, flowOf(PROFILE), GrantedConsent)

    private suspend fun seed() {
        database.profileDao().upsert(
            ProfileEntity(
                id = PROFILE,
                displayName = "Round trip",
                timeZoneId = "Asia/Kolkata",
                currencyCode = "INR",
                createdAtUtcMillis = EXPORTED_AT,
                updatedAtUtcMillis = EXPORTED_AT,
            ),
        )
        database.accountDao().upsert(
            AccountEntity(
                id = ACCOUNT_ID,
                profileId = PROFILE,
                name = "HDFC Savings",
                type = "bank",
                openingBalanceMinor = 12_345_678L,
                currentBalanceMinor = 12_345_678L,
                currencyCode = "INR",
                createdAtUtcMillis = EXPORTED_AT,
                updatedAtUtcMillis = EXPORTED_AT,
            ),
        )
        database.transactionDao().upsert(
            TransactionEntity(
                id = TXN_ID,
                profileId = PROFILE,
                accountId = ACCOUNT_ID,
                amountMinor = ODD_AMOUNT,
                currencyCode = "INR",
                occurredAtUtcMillis = EXPORTED_AT,
                bookedOnIsoDate = "2026-09-01",
                source = "manual",
                type = "expense",
                createdAtUtcMillis = EXPORTED_AT,
                updatedAtUtcMillis = EXPORTED_AT,
            ),
        )
    }

    /** Removes the database, its journals and its wrapped passphrase — a fresh install's state. */
    private fun deleteDatabaseFiles() {
        val base = context.getDatabasePath(CfoDatabase.FILE_NAME)
        listOf(base, File(base.path + "-wal"), File(base.path + "-shm")).forEach { it.delete() }
        File(context.filesDir, DatabaseSecrets.PASSPHRASE_FILE).delete()
    }

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val PROFILE = "profile-roundtrip"
        const val ACCOUNT_ID = "account-roundtrip"
        const val TXN_ID = "txn-roundtrip"

        /**
         * A deliberately awkward amount in paise: ₹1,23,456.79.
         *
         * Not a round number, so a cent-vs-rupee slip or a floating-point round trip anywhere in the
         * chain changes it visibly rather than landing back on the same tidy figure (MNY-001).
         */
        const val ODD_AMOUNT = 12_345_679L

        const val EXPORTED_AT = 1_756_684_800_000L
    }

    /** A consent ledger with everything granted; this suite is about the rows, not the gate. */
    private object GrantedConsent : ConsentStore {
        override fun observe(feature: ConsentFeature): Flow<Result<ConsentState, AppError>> =
            flowOf(Ok(ConsentState(granted = true)))

        override fun observeAll(): Flow<Result<Map<ConsentFeature, ConsentState>, AppError>> =
            flowOf(Ok(ConsentFeature.entries.associateWith { ConsentState(granted = true) }))

        override suspend fun grant(feature: ConsentFeature): Result<Unit, AppError> = Ok(Unit)

        override suspend fun revoke(feature: ConsentFeature): Result<Unit, AppError> = Ok(Unit)
    }
}
