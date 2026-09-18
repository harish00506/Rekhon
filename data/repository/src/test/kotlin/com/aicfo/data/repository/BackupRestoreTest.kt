package com.aicfo.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.TestDispatchers
import com.aicfo.core.crypto.BackupCipherFactory
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.AccountEntity
import com.aicfo.core.database.entity.ProfileEntity
import com.aicfo.core.database.entity.TransactionEntity
import com.aicfo.core.datastore.ConsentFeature
import com.aicfo.core.datastore.ConsentState
import com.aicfo.core.datastore.ConsentStore
import com.aicfo.core.model.AuditEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * Restoring an encrypted backup, against a real SQL engine (issue 8.2; SEC-005, §23.3, P-01).
 *
 * Why:  a backup is only as good as its restore, and the two failures that matter are both silent
 *       until the day they are needed: a restore that loses or bends data (a paisa off is a wrong
 *       balance for ever), and a refused restore that has already deleted something. So every
 *       refusal here asserts the database is exactly as it was, and the success asserts the rows
 *       are the rows, amounts to the paisa.
 * What: backup → wipe → restore round trip; a wrong passphrase, a tampered file, a non-backup, an
 *       archive from another schema and one from another profile — each refused with nothing
 *       touched; passphrase clearing; the audit event; and restore needing no consent.
 * Result: 8.2's acceptance criteria asserted on the JVM; the instrumented twin
 *       (`BackupRestoreDeviceTest`) repeats the round trip on real SQLCipher.
 * Changelog: 2026-09-18 — Created for issue 8.2.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BackupRestoreTest {
    private lateinit var database: CfoDatabase
    private lateinit var backups: BackupRepository
    private lateinit var audit: AuditLogRepository

    private val clock = FakeClock(initialMillis = Instant.parse("2026-09-18T06:00:00Z").toEpochMilli())
    private val dispatcher = UnconfinedTestDispatcher()
    private val dispatchers = TestDispatchers(dispatcher)
    private val activeProfileId = MutableStateFlow(PROFILE)
    private val consents = GrantedBackupConsent()
    private val cipher = BackupCipherFactory.create()

    /** Input: none. Output: a fresh in-memory database and the real backup stack over it. */
    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CfoDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        audit = RepositoryFactory.auditLog(database, clock, dispatchers)
        backups =
            RepositoryFactory.backup(
                archive = RepositoryFactory.archive(database, clock, dispatchers, activeProfileId),
                cipher = cipher,
                consents = consents,
                audit = audit,
                dispatchers = dispatchers,
            )
    }

    /** Input: none. Output: closes the database. */
    @After
    fun tearDown() {
        database.close()
    }

    // --- the round trip ---------------------------------------------------------------------------

    @Test
    fun `backup, wipe, restore gives back every row with every amount exact`() =
        runTest(dispatcher) {
            seed()
            val sealed = backups.create(PASSPHRASE.toCharArray()).expectOk()
            wipe()
            assertEquals("the wipe must have emptied the profile", 0, database.demoDao().countRowsFor(PROFILE))

            val summary = backups.restore(sealed, PASSPHRASE.toCharArray()).expectOk()

            assertEquals(ROWS, summary.rowsImported)
            assertEquals(listOf(account()), database.accountDao().observeForProfile(PROFILE).first())
            // MNY-001: the values a Double would bend, back to the paisa.
            assertEquals(ODD_AMOUNT, database.transactionDao().findById("txn-1")!!.amountMinor)
            assertEquals(LARGE_AMOUNT, database.transactionDao().findById("txn-2")!!.amountMinor)
            assertEquals("2026-09-01", database.transactionDao().findById("txn-1")!!.bookedOnIsoDate)
        }

    @Test
    fun `a restore replaces what is there rather than merging with it`() =
        runTest(dispatcher) {
            seed()
            val sealed = backups.create(PASSPHRASE.toCharArray()).expectOk()
            database.transactionDao().upsert(transaction("txn-after-backup", 1_00L))

            backups.restore(sealed, PASSPHRASE.toCharArray()).expectOk()

            assertEquals(null, database.transactionDao().findById("txn-after-backup"))
        }

    // --- refusals leave everything in place --------------------------------------------------------

    @Test
    fun `a wrong passphrase is refused and nothing is written`() =
        runTest(dispatcher) {
            seed()
            val sealed = backups.create(PASSPHRASE.toCharArray()).expectOk()
            database.transactionDao().upsert(transaction("txn-after-backup", 1_00L))
            val rowsBefore = database.demoDao().countRowsFor(PROFILE)

            val outcome = backups.restore(sealed, "correct horse battery stapler".toCharArray())

            assertEquals(Err(AppError.Crypto("backup.open")), outcome)
            assertEquals(rowsBefore, database.demoDao().countRowsFor(PROFILE))
            assertTrue(database.transactionDao().findById("txn-after-backup") != null)
        }

    @Test
    fun `a tampered backup is refused and nothing is written`() =
        runTest(dispatcher) {
            seed()
            val sealed = backups.create(PASSPHRASE.toCharArray()).expectOk()
            sealed[sealed.size / 2] = (sealed[sealed.size / 2].toInt() xor 0x01).toByte()
            val rowsBefore = database.demoDao().countRowsFor(PROFILE)

            assertEquals(Err(AppError.Crypto("backup.open")), backups.restore(sealed, PASSPHRASE.toCharArray()))
            assertEquals(rowsBefore, database.demoDao().countRowsFor(PROFILE))
        }

    @Test
    fun `a file that is not a backup is refused as a format error`() =
        runTest(dispatcher) {
            seed()
            val rowsBefore = database.demoDao().countRowsFor(PROFILE)

            val outcome = backups.restore("{\"archiveVersion\":1}".encodeToByteArray(), PASSPHRASE.toCharArray())

            assertEquals(Err(AppError.Validation("backup.format")), outcome)
            assertEquals(rowsBefore, database.demoDao().countRowsFor(PROFILE))
        }

    @Test
    fun `a backup from another schema opens but is refused before anything is deleted`() =
        runTest(dispatcher) {
            seed()
            val oldSchema = """{"archiveVersion":1,"schemaVersion":1,"exportedAtUtcMillis":0}"""
            val sealed = (cipher.seal(oldSchema.encodeToByteArray(), PASSPHRASE.toCharArray()) as Ok).value
            val rowsBefore = database.demoDao().countRowsFor(PROFILE)

            val outcome = backups.restore(sealed, PASSPHRASE.toCharArray())

            assertEquals(Err(AppError.Validation("archive.schemaVersion")), outcome)
            assertEquals(rowsBefore, database.demoDao().countRowsFor(PROFILE))
        }

    @Test
    fun `a backup whose contents are not an archive is refused before anything is deleted`() =
        runTest(dispatcher) {
            seed()
            val sealed = (cipher.seal("not json at all".encodeToByteArray(), PASSPHRASE.toCharArray()) as Ok).value
            val rowsBefore = database.demoDao().countRowsFor(PROFILE)

            assertEquals(
                Err(AppError.Validation("archive.unreadable")),
                backups.restore(sealed, PASSPHRASE.toCharArray()),
            )
            assertEquals(rowsBefore, database.demoDao().countRowsFor(PROFILE))
        }

    @Test
    fun `a backup taken in the demo is refused by the real profile`() =
        runTest(dispatcher) {
            activeProfileId.value = DEMO
            database.profileDao().upsert(profile(DEMO))
            val demoBackup = backups.create(PASSPHRASE.toCharArray()).expectOk()
            activeProfileId.value = PROFILE
            seed()
            val rowsBefore = database.demoDao().countRowsFor(PROFILE)

            assertEquals(
                Err(AppError.Validation("archive.profile")),
                backups.restore(demoBackup, PASSPHRASE.toCharArray()),
            )
            assertEquals(rowsBefore, database.demoDao().countRowsFor(PROFILE))
        }

    // --- the passphrase, the audit, the consent ----------------------------------------------------

    @Test
    fun `the passphrase is cleared after a restore, and after a refused one`() =
        runTest(dispatcher) {
            seed()
            val sealed = backups.create(PASSPHRASE.toCharArray()).expectOk()
            val right = PASSPHRASE.toCharArray()
            val wrong = "not the passphrase at all".toCharArray()

            backups.restore(sealed, wrong)
            backups.restore(sealed, right)

            assertTrue(right.all { it == '\u0000' })
            assertTrue(wrong.all { it == '\u0000' })
        }

    @Test
    fun `only a restore that happened is audited`() =
        runTest(dispatcher) {
            seed()
            val sealed = backups.create(PASSPHRASE.toCharArray()).expectOk()
            backups.restore(sealed, "not the passphrase at all".toCharArray())
            backups.restore(sealed, PASSPHRASE.toCharArray()).expectOk()

            val events = audit.observeRecent().first().map { it.event }
            assertEquals(1, events.count { it == AuditEvent.BACKUP_RESTORED })
        }

    @Test
    fun `restoring needs no consent — nothing leaves the device`() =
        runTest(dispatcher) {
            seed()
            val sealed = backups.create(PASSPHRASE.toCharArray()).expectOk()
            consents.granted.value = false

            assertTrue(backups.restore(sealed, PASSPHRASE.toCharArray()) is Ok)
        }

    // --- fixtures ---------------------------------------------------------------------------------

    private suspend fun seed() {
        database.profileDao().upsert(profile(PROFILE))
        database.accountDao().upsert(account())
        database.transactionDao().upsert(transaction("txn-1", ODD_AMOUNT))
        database.transactionDao().upsert(transaction("txn-2", LARGE_AMOUNT))
    }

    /** Clears the profile the way a fresh install would find it: nothing at all. */
    private suspend fun wipe() {
        val demo = database.demoDao()
        demo.deleteTransactions(PROFILE)
        demo.deleteAccounts(PROFILE)
        demo.deleteProfile(PROFILE)
    }

    private fun profile(id: String) =
        ProfileEntity(
            id = id,
            displayName = "Test",
            timeZoneId = "Asia/Kolkata",
            currencyCode = "INR",
            createdAtUtcMillis = CREATED,
            updatedAtUtcMillis = CREATED,
        )

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

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    /** The backup consent, granted unless a test turns it off. */
    private class GrantedBackupConsent : ConsentStore {
        val granted = MutableStateFlow(true)

        override fun observe(feature: ConsentFeature): Flow<Result<ConsentState, AppError>> =
            granted.map { Ok(ConsentState(granted = it)) }

        override fun observeAll(): Flow<Result<Map<ConsentFeature, ConsentState>, AppError>> = emptyFlow()

        override suspend fun grant(feature: ConsentFeature): Result<Unit, AppError> = Ok(Unit)

        override suspend fun revoke(feature: ConsentFeature): Result<Unit, AppError> = Ok(Unit)
    }

    private companion object {
        const val PROFILE = "local"
        const val DEMO = "demo"
        const val PASSPHRASE = "correct horse battery staple"
        const val CREATED = 1_767_225_600_000L

        /** ₹-1,234.57 — not representable exactly as a binary fraction of a rupee. */
        const val ODD_AMOUNT = -1_23_457L

        /** Past 2^53 paise: a Double would round it. */
        const val LARGE_AMOUNT = 9_007_199_254_740_993L

        /** A profile, an account and two transactions. */
        const val ROWS = 4
    }
}
