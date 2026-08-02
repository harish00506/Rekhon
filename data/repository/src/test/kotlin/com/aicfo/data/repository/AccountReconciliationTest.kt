package com.aicfo.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.FakeIdGenerator
import com.aicfo.core.common.Ok
import com.aicfo.core.common.TestDispatchers
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.TransactionEntity
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Money
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * Tests for reconciliation and the DB-001 integrity job (issue 2.7; FR-ACC-006, DB-001).
 *
 * Why:  FR-ACC-006 says a balance correction is posted as an adjustment *transaction*, "never
 *       silently mutated", and the whole point of that sentence is invisible from reading the code
 *       — you cannot tell a correct implementation from one that quietly edits the row. So the
 *       assertions here are deliberately about the *evidence left behind*: that a row exists, that
 *       it carries the delta and the source, and that history was not touched.
 *
 *       Four properties decide whether this is safe. **The sign is right in both directions** — a
 *       statement above the app balance is an inflow, below is an outflow, and getting that
 *       backwards would silently double every correction. **Zero writes nothing** — a zero-amount
 *       transaction is noise every later engine has to filter. **The adjustment carries the
 *       account's own profile**, or the demo leaks rows the wipe cannot reach (ADR-0006). And
 *       **the cache and the derivation agree afterwards**, which is the invariant DB-001's
 *       integrity job exists to hold.
 * What: [AccountRepository.reconcile] and [AccountRepository.refreshCachedBalances].
 * Result: the reconciliation half of issue 2.7 is proven against a real SQL engine.
 * Changelog: 2026-08-02 — Created for issue 2.7.
 *
 * Unencrypted in-memory Room, for the same reason [AccountRepositoryTest] gives: what is under test
 * is the SQL and the arithmetic, not SQLCipher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AccountReconciliationTest {
    private lateinit var database: CfoDatabase
    private lateinit var repository: AccountRepository
    private val clock = FakeClock(initialMillis = Instant.parse("2026-08-02T04:30:00Z").toEpochMilli())
    private val ids = FakeIdGenerator()
    private val activeProfileId = MutableStateFlow(REAL_PROFILE)

    /** Input: none. Output: a fresh in-memory database and a repository over it. */
    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                CfoDatabase::class.java,
            ).allowMainThreadQueries().build()
        repository =
            RepositoryFactory.accounts(
                database = database,
                clock = clock,
                ids = ids,
                dispatchers = TestDispatchers(UnconfinedTestDispatcher()),
                activeProfileId = activeProfileId,
            )
    }

    /** Input: none. Output: closes the database between tests. */
    @After
    fun tearDown() {
        database.close()
    }

    // --- the delta, in both directions ---------------------------------------------------------

    @Test
    fun `a statement above the app balance posts an inflow adjustment`() =
        runTest {
            val account = repository.create(baseDraft()).expectOk()

            val outcome = repository.reconcile(account.id, statementBalance = Money(1_00_500_00L)).expectOk()

            assertEquals(Money(1_00_000_00L), outcome.before)
            assertEquals(Money(1_00_500_00L), outcome.statement)
            assertEquals(Money(500_00L), outcome.delta)
            assertNotNull("an adjustment must have been written", outcome.adjustmentId)
            assertEquals(Money(500_00L), adjustmentAmount(outcome.adjustmentId!!))
        }

    @Test
    fun `a statement below the app balance posts an outflow adjustment`() =
        runTest {
            // The direction that matters most: a credit card's balance is held negative, so a
            // reconciliation that got the sign backwards would *increase* a debt the user was
            // trying to correct downwards.
            val card =
                repository.create(
                    baseDraft().copy(type = AccountType.CREDIT_CARD, openingBalance = Money(-18_000_00L)),
                ).expectOk()

            val outcome = repository.reconcile(card.id, statementBalance = Money(-19_250_00L)).expectOk()

            assertEquals(Money(-1_250_00L), outcome.delta)
            assertEquals(Money(-1_250_00L), adjustmentAmount(outcome.adjustmentId!!))
        }

    @Test
    fun `the balance equals the statement once the adjustment lands`() =
        runTest {
            val account = repository.create(baseDraft()).expectOk()

            repository.reconcile(account.id, statementBalance = Money(1_00_500_00L)).expectOk()

            // Derived, not read back from the cache — this is the claim the user actually cares
            // about, and it goes through DB-001's subquery like every other read.
            assertEquals(Money(1_00_500_00L), repository.find(account.id).expectOk().balance)
        }

    @Test
    fun `the delta is measured against the derived balance, not the opening one`() =
        runTest {
            val account = repository.create(baseDraft()).expectOk()
            insertTransaction("txn:seed", account.id, amountMinor = -7_500_00L)

            // Opening 1,00,000; one outflow of 7,500; so the app holds 92,500.
            val outcome = repository.reconcile(account.id, statementBalance = Money(93_000_00L)).expectOk()

            assertEquals(Money(92_500_00L), outcome.before)
            assertEquals(Money(500_00L), outcome.delta)
        }

    // --- zero ----------------------------------------------------------------------------------

    @Test
    fun `a matching statement writes nothing at all`() =
        runTest {
            val account = repository.create(baseDraft()).expectOk()
            val idsIssuedBefore = ids.issuedCount

            val outcome = repository.reconcile(account.id, statementBalance = Money(1_00_000_00L)).expectOk()

            assertEquals(Money.ZERO, outcome.delta)
            assertNull("a zero adjustment is noise every later engine has to filter", outcome.adjustmentId)
            assertEquals(0, transactionCount())
            assertEquals("no id may be minted for a row that was never written", idsIssuedBefore, ids.issuedCount)
        }

    @Test
    fun `reconciling twice to the same statement is a no-op the second time`() =
        runTest {
            val account = repository.create(baseDraft()).expectOk()

            repository.reconcile(account.id, statementBalance = Money(1_00_500_00L)).expectOk()
            val second = repository.reconcile(account.id, statementBalance = Money(1_00_500_00L)).expectOk()

            assertEquals(Money.ZERO, second.delta)
            assertEquals("exactly one adjustment, not two", 1, transactionCount())
        }

    // --- what the adjustment row carries -------------------------------------------------------

    @Test
    fun `the adjustment is tagged as a reconciliation, not a manual entry`() =
        runTest {
            // P-02: the source *is* the rule that fired. A row indistinguishable from something the
            // user typed cannot explain itself later.
            val account = repository.create(baseDraft()).expectOk()

            val outcome = repository.reconcile(account.id, statementBalance = Money(1_00_500_00L)).expectOk()

            assertEquals("reconciliation", adjustmentColumn(outcome.adjustmentId!!, "source"))
            assertNull(
                "no prose in the row — it would be un-localised and carry an amount for no reader",
                adjustmentColumn(outcome.adjustmentId!!, "note"),
            )
        }

    @Test
    fun `the adjustment is dated by the injected Clock, in the profile zone`() =
        runTest {
            // TIM-001/TIM-002. 2026-08-02T04:30Z is already 10:00 on the 2nd in Asia/Kolkata; the
            // failure this pins is the 23:30 IST one, where UTC and the profile day disagree.
            clock.setTo(Instant.parse("2026-08-02T18:30:00Z").toEpochMilli())
            val account = repository.create(baseDraft()).expectOk()

            val outcome = repository.reconcile(account.id, statementBalance = Money(1_00_500_00L)).expectOk()

            assertEquals("2026-08-03", outcome.bookedOnIsoDate)
            assertEquals("2026-08-03", adjustmentColumn(outcome.adjustmentId!!, "booked_on_iso_date"))
        }

    @Test
    fun `the adjustment lands under the account's own profile, not the active one`() =
        runTest {
            // ADR-0006's residue rule. Reconciling while the demo is active must not park a row
            // under the real profile — `DemoDao.deleteTransactions` is scoped by profile and would
            // never reach it.
            activeProfileId.value = DEMO_PROFILE
            val demoAccount = repository.create(baseDraft()).expectOk()
            activeProfileId.value = REAL_PROFILE

            val outcome = repository.reconcile(demoAccount.id, statementBalance = Money(1_00_500_00L)).expectOk()

            assertEquals(DEMO_PROFILE, adjustmentColumn(outcome.adjustmentId!!, "profile_id"))
        }

    @Test
    fun `the adjustment takes the account's currency`() =
        runTest {
            val account = repository.create(baseDraft().copy(currencyCode = "USD")).expectOk()

            val outcome = repository.reconcile(account.id, statementBalance = Money(1_00_500_00L)).expectOk()

            assertEquals("USD", adjustmentColumn(outcome.adjustmentId!!, "currency_code"))
        }

    @Test
    fun `history is never edited — the opening balance is untouched`() =
        runTest {
            // The literal words of FR-ACC-006. The correction is additive; the past stays as it was.
            val account = repository.create(baseDraft()).expectOk()

            repository.reconcile(account.id, statementBalance = Money(1_00_500_00L)).expectOk()

            assertEquals(Money(1_00_000_00L), repository.find(account.id).expectOk().openingBalance)
        }

    // --- the accounts it will and will not touch -----------------------------------------------

    @Test
    fun `reconciling an unknown account is NotFound, and writes nothing`() =
        runTest {
            val outcome = repository.reconcile("account:nope", statementBalance = Money(1_00L))

            assertTrue(outcome is Err)
            assertEquals(AppError.NotFound, (outcome as Err).error)
            assertEquals(0, transactionCount())
        }

    @Test
    fun `reconciling a soft-deleted account is NotFound`() =
        runTest {
            val account = repository.create(baseDraft()).expectOk()
            repository.delete(account.id)

            assertTrue(repository.reconcile(account.id, statementBalance = Money(1_00L)) is Err)
        }

    @Test
    fun `an archived account can still be reconciled`() =
        runTest {
            // FR-ACC-007 keeps a closed account's history. Recording its final statement balance is
            // exactly the case where a user needs this, so archiving must not block it.
            val account = repository.create(baseDraft()).expectOk()
            repository.setArchived(account.id, archived = true)

            val outcome = repository.reconcile(account.id, statementBalance = Money(1_00_500_00L)).expectOk()

            assertEquals(Money(500_00L), outcome.delta)
        }

    // --- DB-001's integrity job ----------------------------------------------------------------

    @Test
    fun `an account whose cache has drifted is counted and repaired`() =
        runTest {
            // The cache is seeded at create and never maintained (ADR-0007), so a single transaction
            // is enough to make it a lie. Until this job existed, nothing noticed.
            val account = repository.create(baseDraft()).expectOk()
            insertTransaction("txn:seed", account.id, amountMinor = -7_500_00L)
            assertEquals(1_00_000_00L, cachedBalance(account.id))

            val drifted = repository.refreshCachedBalances().expectOk()

            assertEquals(1, drifted)
            assertEquals(92_500_00L, cachedBalance(account.id))
        }

    @Test
    fun `an account already in step is not counted as drift`() =
        runTest {
            val account = repository.create(baseDraft()).expectOk()

            assertEquals(0, repository.refreshCachedBalances().expectOk())
            assertEquals(1_00_000_00L, cachedBalance(account.id))
        }

    @Test
    fun `a soft-deleted transaction does not count towards the repaired balance`() =
        runTest {
            // The same filter the read path has carried since 2.5. Dropping it here would make the
            // job "repair" every cache to a figure no read agrees with — worse than leaving it stale.
            val account = repository.create(baseDraft()).expectOk()
            insertTransaction("txn:live", account.id, amountMinor = -1_000_00L)
            insertTransaction("txn:gone", account.id, amountMinor = -9_000_00L, deletedAtUtcMillis = 1L)

            repository.refreshCachedBalances().expectOk()

            assertEquals(99_000_00L, cachedBalance(account.id))
        }

    @Test
    fun `a soft-deleted account is left alone`() =
        runTest {
            val account = repository.create(baseDraft()).expectOk()
            insertTransaction("txn:seed", account.id, amountMinor = -7_500_00L)
            repository.delete(account.id)

            assertEquals(0, repository.refreshCachedBalances().expectOk())
            assertEquals("a tombstone is not the job's to touch", 1_00_000_00L, cachedBalance(account.id))
        }

    @Test
    fun `the job stays inside the active profile`() =
        runTest {
            activeProfileId.value = DEMO_PROFILE
            val demoAccount = repository.create(baseDraft()).expectOk()
            insertTransaction("txn:demo", demoAccount.id, amountMinor = -7_500_00L, profileId = DEMO_PROFILE)
            activeProfileId.value = REAL_PROFILE

            assertEquals(0, repository.refreshCachedBalances().expectOk())
            assertEquals(1_00_000_00L, cachedBalance(demoAccount.id))
        }

    @Test
    fun `reconciling leaves the cache correct without waiting for the nightly job`() =
        runTest {
            // The two halves of this issue meeting: a correction the user just made must not leave
            // the column stale until tonight, or the job would report drift the user already fixed.
            val account = repository.create(baseDraft()).expectOk()

            repository.reconcile(account.id, statementBalance = Money(1_00_500_00L)).expectOk()

            assertEquals(1_00_500_00L, cachedBalance(account.id))
            assertEquals(0, repository.refreshCachedBalances().expectOk())
        }

    // --- fixtures ------------------------------------------------------------------------------

    /** Result: the draft every test starts from. Input: none. Output: [AccountDraft]. */
    private fun baseDraft() =
        AccountDraft(
            name = "HDFC Savings",
            type = AccountType.BANK,
            openingBalance = Money(1_00_000_00L),
            currencyCode = "INR",
            institution = "HDFC Bank",
        )

    /**
     * Writes a transaction straight to the DAO.
     * Why:    nothing above the data layer writes transactions yet (Epic 3 owns that), so a balance
     *         that has moved can only be set up from here.
     * Result: one row. Input: [id]; [accountId]; [amountMinor] — signed paise; [deletedAtUtcMillis]
     *         — non-null for a tombstone; [profileId]. Output: none.
     */
    private suspend fun insertTransaction(
        id: String,
        accountId: String,
        amountMinor: Long,
        deletedAtUtcMillis: Long? = null,
        profileId: String = REAL_PROFILE,
    ) {
        database.transactionDao().upsert(
            TransactionEntity(
                id = id,
                profileId = profileId,
                accountId = accountId,
                amountMinor = amountMinor,
                currencyCode = "INR",
                occurredAtUtcMillis = clock.nowUtcMillis(),
                bookedOnIsoDate = clock.today().toString(),
                source = "manual",
                createdAtUtcMillis = clock.nowUtcMillis(),
                updatedAtUtcMillis = clock.nowUtcMillis(),
                deletedAtUtcMillis = deletedAtUtcMillis,
            ),
        )
    }

    /** Result: how many transaction rows exist at all. Input: none. Output: [Int]. */
    private fun transactionCount(): Int =
        database.query("SELECT COUNT(*) FROM transactions", emptyArray()).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    /** Result: the adjustment's signed amount. Input: [id]. Output: [Money]. */
    private fun adjustmentAmount(id: String): Money = Money(adjustmentColumn(id, "amount_minor")!!.toLong())

    /**
     * Reads one column of one transaction as text.
     * Why:    reaches past the repository deliberately — there is no read API for transactions yet,
     *         and the claims here are about the row on disk, not about what a mapper returns.
     * Result: the value, or `null` when the column is null. Input: [id]; [column]. Output: `String?`.
     */
    private fun adjustmentColumn(
        id: String,
        column: String,
    ): String? =
        database.query("SELECT $column FROM transactions WHERE id = ?", arrayOf<Any>(id)).use { cursor ->
            assertTrue("no transaction with id $id", cursor.moveToFirst())
            if (cursor.isNull(0)) null else cursor.getString(0)
        }

    /** Result: the cached column DB-001's job maintains. Input: [accountId]. Output: [Long]. */
    private fun cachedBalance(accountId: String): Long =
        database.query(
            "SELECT current_balance_minor FROM account WHERE id = ?",
            arrayOf<Any>(accountId),
        ).use { cursor ->
            assertTrue("no account with id $accountId", cursor.moveToFirst())
            cursor.getLong(0)
        }

    private companion object {
        const val REAL_PROFILE = "local"
        const val DEMO_PROFILE = "demo"
    }
}

/**
 * Unwraps a result the test expects to have succeeded.
 * Why:    a failure here names the error rather than throwing a bare `ClassCastException` — the same
 *         helper `AccountRepositoryTest` settled on, kept file-private in both.
 * Result: the value. Input: the receiver. Output: [T].
 * Changelog: 2026-08-02 — Created for issue 2.7.
 */
private fun <T> com.aicfo.core.common.Result<T, AppError>.expectOk(): T =
    when (this) {
        is Ok -> value
        is Err -> throw AssertionError("expected Ok, got Err(${error.code})")
    }
