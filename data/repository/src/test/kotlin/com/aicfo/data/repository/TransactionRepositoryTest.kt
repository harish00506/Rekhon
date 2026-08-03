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
import com.aicfo.core.database.entity.CategoryEntity
import com.aicfo.core.database.entity.TransactionEntity
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Money
import com.aicfo.core.model.TransactionSource
import com.aicfo.core.model.TransactionType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant

/**
 * Tests for [TransactionRepository] — the persistence half of issue 3.1 (FR-TXN-002, FR-TXN-009).
 *
 * Why:  five properties decide whether the rest of Epic 3 can be built on this class, and not one of
 *       them is visible from reading it. **The account's balance moves by exactly the amount** —
 *       that is DB-001's whole derivation, and it is the only proof that `create` does not need to
 *       write a balance. **A rejected draft writes nothing** — a validation error that had already
 *       inserted a row would be worse than no validation at all. **An unknown account is refused** —
 *       SQLite has no foreign key here, so nothing but this class stops an orphan. **Profiles do not
 *       leak**, because the demo lives in a second profile. And **the booked day is the profile
 *       zone's**, not UTC's, which only shows up at the hours where the two disagree.
 * What: the write path, validation, the two read paths, and the demo/real switch.
 * Result: the capture path is proven against a real SQL engine before any UI sits on it.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 *
 * Unencrypted in-memory Room, deliberately — the same reasoning as [AccountRepositoryTest]: what is
 * under test is the SQL and the derivation, not SQLCipher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TransactionRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var repository: TransactionRepository
    private lateinit var accounts: AccountRepository

    // 2026-08-02T18:00Z is 2026-08-02T23:30 in Asia/Kolkata — the same calendar day in both zones.
    // `advanceBy(Duration.ofHours(1))` from here crosses IST's midnight but not UTC's, which is the
    // case TIM-002 exists for and the one `books a transaction on the profile-zone day` pins.
    private val clock = FakeClock(initialMillis = Instant.parse("2026-08-02T18:00:00Z").toEpochMilli())
    private val ids = FakeIdGenerator()
    private val activeProfileId = MutableStateFlow(REAL_PROFILE)

    /** Input: none. Output: a fresh in-memory database and both repositories over it. */
    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                CfoDatabase::class.java,
            ).allowMainThreadQueries().build()
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        repository = RepositoryFactory.transactions(database, clock, ids, dispatchers, activeProfileId)
        // The real AccountRepository rather than a stub: the claim under test is that a transaction
        // moves the balance the accounts screen actually renders, and a stub could not make it.
        accounts = RepositoryFactory.accounts(database, clock, ids, dispatchers, activeProfileId)
    }

    /** Input: none. Output: closes the database between tests. */
    @After
    fun tearDown() {
        database.close()
    }

    // --- create ------------------------------------------------------------------------------------

    @Test
    fun `creating a transaction returns it with a generated id and the manual source`() =
        runTest {
            val account = newAccount()

            val created = repository.create(TransactionDraft(account.id, Money(-250_00L)))

            assertTrue(created is Ok)
            val transaction = (created as Ok).value
            // FakeIdGenerator's counter is shared across prefixes, so `newAccount` took 1 and this
            // is 2. The prefix is what matters: it is TransactionRepository.ID_PREFIX, unchanged
            // from the literal issue 2.7 has already written into real databases.
            assertEquals("txn:2", transaction.id)
            assertEquals(account.id, transaction.accountId)
            assertEquals(Money(-250_00L), transaction.amount)
            // FR-TXN-009: the source is stamped by the app, never chosen by the caller.
            assertEquals(TransactionSource.MANUAL, transaction.source)
        }

    @Test
    fun `an expense moves the account's derived balance by exactly its amount`() =
        runTest {
            // This is DB-001's derivation and the reason `create` writes no balance: the row it
            // inserts IS the balance update (ADR-0007).
            val account = newAccount(opening = Money(1_00_000_00L))

            repository.create(TransactionDraft(account.id, Money(-250_00L))).expectOk()

            assertEquals(Money(99_750_00L), accounts.find(account.id).expectOk().balance)
        }

    @Test
    fun `an income moves it the other way, and the opening balance is untouched`() =
        runTest {
            val account = newAccount(opening = Money(1_00_000_00L))

            repository.create(TransactionDraft(account.id, Money(60_000_00L))).expectOk()

            val reloaded = accounts.find(account.id).expectOk()
            assertEquals(Money(1_60_000_00L), reloaded.balance)
            // The sign is the only thing that distinguishes the two directions, and history is
            // append-only: nothing about the account row changed.
            assertEquals(Money(1_00_000_00L), reloaded.openingBalance)
        }

    @Test
    fun `several transactions sum into the balance`() =
        runTest {
            val account = newAccount(opening = Money(10_000_00L))

            repository.create(TransactionDraft(account.id, Money(-250_00L))).expectOk()
            repository.create(TransactionDraft(account.id, Money(-1_200_00L))).expectOk()
            repository.create(TransactionDraft(account.id, Money(500_00L))).expectOk()

            assertEquals(Money(9_050_00L), accounts.find(account.id).expectOk().balance)
        }

    @Test
    fun `the transaction takes the account's currency, not the caller's word for it`() =
        runTest {
            val account = newAccount()

            val transaction = repository.create(TransactionDraft(account.id, Money(-250_00L))).expectOk()

            assertEquals("INR", rawRow(transaction.id)?.currencyCode)
        }

    @Test
    fun `a transaction lands in the account's profile, so a demo wipe can reach it`() =
        runTest {
            // The same choice `writeAdjustment` makes in AccountRepository (ADR-0006): the row must
            // follow the account, not whichever profile happened to be active.
            val account = newAccount()

            val transaction = repository.create(TransactionDraft(account.id, Money(-250_00L))).expectOk()

            assertEquals(REAL_PROFILE, rawRow(transaction.id)?.profileId)
        }

    @Test
    fun `merchant and note are trimmed, and a blank one becomes null`() =
        runTest {
            val account = newAccount()

            val transaction =
                repository.create(
                    TransactionDraft(account.id, Money(-250_00L), merchant = "  Chai Point  ", note = "   "),
                ).expectOk()

            // Trimmed rather than merely checked, so " Chai Point " and "Chai Point" cannot become
            // two merchants that look identical on screen.
            assertEquals("Chai Point", transaction.merchant)
            assertNull(transaction.note)
        }

    @Test
    fun `a category id is stored when given and null when not`() =
        runTest {
            val account = newAccount()
            database.categoryDao().upsert(category("category:fuel", "Fuel"))

            val categorised =
                repository.create(TransactionDraft(account.id, Money(-1_200_00L), categoryId = "category:fuel"))
                    .expectOk()
            val uncategorised = repository.create(TransactionDraft(account.id, Money(-250_00L))).expectOk()

            assertEquals("category:fuel", categorised.categoryId)
            // Not an edge case: a real profile has no categories at all until issue 4.1.
            assertNull(uncategorised.categoryId)
        }

    // --- time --------------------------------------------------------------------------------------

    @Test
    fun `books a transaction on the profile-zone day, not the UTC day`() =
        runTest {
            // 2026-08-02T19:00Z is 2026-08-03T00:30 in Asia/Kolkata. Reading UTC here would book the
            // spend into the previous day's budget and the previous day's total (TIM-002).
            val account = newAccount()
            clock.advanceBy(Duration.ofHours(1))

            val transaction = repository.create(TransactionDraft(account.id, Money(-250_00L))).expectOk()

            assertEquals("2026-08-03", transaction.bookedOn)
        }

    @Test
    fun `stamps the instant from the injected clock, never the wall clock`() =
        runTest {
            // TIM-001, and the determinism half of P-08: the same input gives the same row.
            val account = newAccount()

            val transaction = repository.create(TransactionDraft(account.id, Money(-250_00L))).expectOk()

            assertEquals(clock.nowUtcMillis(), transaction.occurredAtUtcMillis)
            assertEquals(clock.nowUtcMillis(), rawRow(transaction.id)?.createdAtUtcMillis)
        }

    // --- validation --------------------------------------------------------------------------------

    @Test
    fun `a zero amount is rejected, and nothing is written`() =
        runTest {
            // A zero row would sit in every list and every total contributing nothing, and is far
            // more likely to be an empty field the user tapped Save on than something they meant.
            val account = newAccount()

            val result = repository.create(TransactionDraft(account.id, Money.ZERO))

            assertEquals("amount", (result as Err).error.let { (it as AppError.Validation).field })
            assertTrue(repository.observeRecent().first().isEmpty())
        }

    @Test
    fun `a blank account id is rejected as a validation error, not a lookup`() =
        runTest {
            val result = repository.create(TransactionDraft("   ", Money(-250_00L)))

            assertEquals("accountId", (result as Err).error.let { (it as AppError.Validation).field })
        }

    @Test
    fun `an unknown account is NotFound, and nothing is written`() =
        runTest {
            // SQLite has no foreign key on this column, so nothing but the repository's own lookup
            // stops an orphan — a row counting towards no balance and showing in no history.
            val result = repository.create(TransactionDraft("account:missing", Money(-250_00L)))

            assertEquals(AppError.NotFound, (result as Err).error)
            assertTrue(repository.observeRecent().first().isEmpty())
        }

    @Test
    fun `a soft-deleted account is NotFound`() =
        runTest {
            val account = newAccount()
            accounts.delete(account.id).expectOk()

            val result = repository.create(TransactionDraft(account.id, Money(-250_00L)))

            assertEquals(AppError.NotFound, (result as Err).error)
        }

    @Test
    fun `an archived account still accepts transactions`() =
        runTest {
            // FR-ACC-007 keeps a closed account's history; recording a final payment on one is
            // exactly when a user needs this, and `findWithBalance` deliberately finds archived rows.
            val account = newAccount()
            accounts.setArchived(account.id, archived = true).expectOk()

            assertTrue(repository.create(TransactionDraft(account.id, Money(-250_00L))) is Ok)
        }

    // --- observeRecent -----------------------------------------------------------------------------

    @Test
    fun `the recent list is empty on a fresh profile`() =
        runTest {
            assertTrue(repository.observeRecent().first().isEmpty())
        }

    @Test
    fun `the recent list is newest first`() =
        runTest {
            val account = newAccount()
            repository.create(TransactionDraft(account.id, Money(-100_00L), note = "first")).expectOk()
            clock.advanceBy(Duration.ofMinutes(5))
            repository.create(TransactionDraft(account.id, Money(-200_00L), note = "second")).expectOk()

            assertEquals(listOf("second", "first"), repository.observeRecent().first().map { it.note })
        }

    @Test
    fun `a soft-deleted transaction leaves the recent list`() =
        runTest {
            val account = newAccount()
            val transaction = repository.create(TransactionDraft(account.id, Money(-250_00L))).expectOk()

            database.transactionDao().softDelete(transaction.id, clock.nowUtcMillis())

            assertTrue(repository.observeRecent().first().isEmpty())
        }

    @Test
    fun `a transaction older than the window falls out of the recent list`() =
        runTest {
            val account = newAccount()
            repository.create(TransactionDraft(account.id, Money(-250_00L))).expectOk()

            assertEquals(1, repository.observeRecent().first().size)
            clock.advanceBy(Duration.ofDays(TransactionRepository.RECENT_WINDOW_DAYS + 1))

            // Out of the list, but not out of the balance — issue 3.6 builds the full history.
            assertTrue(repository.observeRecent().first().isEmpty())
            assertEquals(Money(99_750_00L), accounts.find(account.id).expectOk().balance)
        }

    @Test
    fun `a transaction from inside the window is listed`() =
        runTest {
            // The boundary tests above only ever look at a row booked *today*, which cannot tell a
            // working 30-day window from one that is accidentally a single day wide. This is the
            // case the emulator run turned up as empty on a demo profile full of history.
            val account = newAccount()
            database.transactionDao().upsert(
                rawTransaction("txn:old", account.id, TransactionSource.MANUAL.storedValue)
                    .copy(bookedOnIsoDate = clock.today().minusDays(10).toString()),
            )

            assertEquals(listOf("txn:old"), repository.observeRecent().first().map { it.id })
        }

    @Test
    fun `the demo's own history is inside the window`() =
        runTest {
            // Reproduces what the emulator run showed: a freshly-seeded demo whose recent list came
            // back empty even though its account balances plainly derived from transactions. The
            // dataset seeds three months up to today, so a 30-day window must catch a month of it.
            val demo =
                RepositoryFactory.demoMode(
                    database = database,
                    settingsStore = FakeSettingsStore(),
                    clock = clock,
                    dispatchers = TestDispatchers(UnconfinedTestDispatcher()),
                )
            demo.enter()
            activeProfileId.value = DemoModeRepository.DEMO_PROFILE_ID

            assertTrue(
                "the demo seeds three months of history; the 30-day window must show some of it",
                repository.observeRecent().first().isNotEmpty(),
            )
        }

    @Test
    fun `a row with an unrecognised source is dropped rather than thrown on`() =
        runTest {
            // Forward compatibility: an old build reading a database a newer one wrote shows fewer
            // rows, never an exception. The same shape `AccountType.fromStored` guarantees.
            //
            // The example was `recurring-auto` until issue 3.5, which added that source for real —
            // hyphenated it is still unknown, but using the one value the enum had just gained made
            // the test read as though it were asserting the opposite of what it means.
            val account = newAccount()
            database.transactionDao().upsert(rawTransaction("txn:future", account.id, "account_aggregator"))
            val transaction = repository.create(TransactionDraft(account.id, Money(-250_00L))).expectOk()

            assertEquals(listOf(transaction.id), repository.observeRecent().first().map { it.id })
        }

    @Test
    fun `every write path stamps a source, so there is nothing to backfill`() =
        runTest {
            // **Issue 3.5's criteria ask for "a default backfilled for manual entries". There is
            // nothing to backfill**, and this is the assertion that says so rather than a migration
            // that would do nothing: `transactions.source` has been `TEXT NOT NULL` since schema v1
            // and every path that writes a row sets it explicitly. A row with no source cannot exist
            // — the compiler will not build one — so what is worth pinning is that every path
            // produces a value this build can still *read* (FR-TXN-009).
            val from = newAccount()
            val to = newAccount()
            repository.create(TransactionDraft(from.id, Money(-250_00L))).expectOk()
            repository.createTransfer(TransferDraft(from.id, to.id, Money(1_000_00L))).expectOk()
            repository.createSplit(
                SplitDraft(
                    accountId = from.id,
                    amount = Money(-1_000_00L),
                    lines = listOf(SplitLineDraft(Money(-600_00L)), SplitLineDraft(Money(-400_00L))),
                ),
            ).expectOk()
            // The fourth path, and the one whose row this issue exists to explain (issue 2.7).
            accounts.reconcile(from.id, Money(99_999_00L)).expectOk()

            val stored = storedSources()
            assertEquals("four write paths, five rows (a transfer writes two)", 5, stored.size)
            assertTrue(
                "a stored source this build cannot parse would drop the row from every read",
                stored.all { TransactionSource.fromStored(it) != null },
            )
            assertEquals(
                setOf(TransactionSource.MANUAL.storedValue, TransactionSource.RECONCILIATION.storedValue),
                stored.toSet(),
            )
        }

    // --- observeCategories -------------------------------------------------------------------------

    @Test
    fun `a real profile has no categories until issue 4-1`() =
        runTest {
            // Asserted rather than assumed: the add screen hides its chip row on this, and a future
            // seeding change that quietly filled it would change the tap count FR-TXN-002 pins.
            assertTrue(repository.observeCategories().first().isEmpty())
        }

    @Test
    fun `categories are read for the active profile, name-ordered`() =
        runTest {
            database.categoryDao().upsertAll(
                listOf(category("category:fuel", "Fuel"), category("category:dining", "Dining")),
            )

            assertEquals(listOf("Dining", "Fuel"), repository.observeCategories().first().map { it.name })
        }

    // --- profile isolation -------------------------------------------------------------------------

    @Test
    fun `the demo's transactions are invisible to the real profile`() =
        runTest {
            val account = newAccount()
            repository.create(TransactionDraft(account.id, Money(-250_00L))).expectOk()

            activeProfileId.value = DEMO_PROFILE

            assertTrue(repository.observeRecent().first().isEmpty())
        }

    @Test
    fun `switching profile switches the list`() =
        runTest {
            val account = newAccount()
            repository.create(TransactionDraft(account.id, Money(-250_00L))).expectOk()

            activeProfileId.value = DEMO_PROFILE
            assertTrue(repository.observeRecent().first().isEmpty())
            activeProfileId.value = REAL_PROFILE

            assertEquals(1, repository.observeRecent().first().size)
        }

    @Test
    fun `a category from another profile is not offered`() =
        runTest {
            database.categoryDao().upsert(category("category:demo", "Demo only", profileId = DEMO_PROFILE))

            assertTrue(repository.observeCategories().first().isEmpty())
        }

    // --- fixtures ----------------------------------------------------------------------------------

    /**
     * Creates an account to transact against.
     * Why:    every test here needs one, and the transaction is what is under test, not the account.
     * Result: a live bank account in the real profile. Input: [opening] — its opening balance.
     * Output: the created `Account`.
     * Changelog: 2026-08-02 — Created for issue 3.1.
     */
    private suspend fun newAccount(opening: Money = Money(1_00_000_00L)) =
        accounts.create(
            AccountDraft(
                name = "HDFC Savings",
                type = AccountType.BANK,
                openingBalance = opening,
                currencyCode = "INR",
            ),
        ).expectOk()

    /**
     * Result: a category row written straight to the DAO — the repository cannot create one until
     *         issue 4.1. Input: [id], [name], [profileId]. Output: a [CategoryEntity].
     */
    private fun category(
        id: String,
        name: String,
        profileId: String = REAL_PROFILE,
    ) = CategoryEntity(
        id = id,
        profileId = profileId,
        name = name,
        nature = "want",
        isSystem = true,
        createdAtUtcMillis = clock.nowUtcMillis(),
        updatedAtUtcMillis = clock.nowUtcMillis(),
    )

    /**
     * Result: a transaction written straight to the DAO, bypassing the repository — for rows it could
     *         not itself create, such as one carrying a source from a future build.
     * Input:  [id], [accountId], [source]. Output: a [TransactionEntity].
     */
    private fun rawTransaction(
        id: String,
        accountId: String,
        source: String,
        type: String = TransactionType.EXPENSE.storedValue,
    ) = TransactionEntity(
        id = id,
        profileId = REAL_PROFILE,
        accountId = accountId,
        amountMinor = -100_00L,
        currencyCode = "INR",
        occurredAtUtcMillis = clock.nowUtcMillis(),
        bookedOnIsoDate = clock.today().toString(),
        source = source,
        type = type,
        createdAtUtcMillis = clock.nowUtcMillis(),
        updatedAtUtcMillis = clock.nowUtcMillis(),
    )

    /**
     * Result: the raw row including tombstones, or null. Input: [id]. Output: `TransactionEntity?`.
     *
     * Reaches past the repository on purpose: [TransactionRepository.observeRecent] maps rows into
     * the domain model, so "the stored profile and currency are right" is not a claim it can make.
     */
    private fun rawRow(id: String): TransactionEntity? =
        database.query("SELECT * FROM transactions WHERE id = ?", arrayOf<Any>(id)).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            TransactionEntity(
                id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                profileId = cursor.getString(cursor.getColumnIndexOrThrow("profile_id")),
                accountId = cursor.getString(cursor.getColumnIndexOrThrow("account_id")),
                amountMinor = cursor.getLong(cursor.getColumnIndexOrThrow("amount_minor")),
                currencyCode = cursor.getString(cursor.getColumnIndexOrThrow("currency_code")),
                occurredAtUtcMillis = cursor.getLong(cursor.getColumnIndexOrThrow("occurred_at_utc_millis")),
                bookedOnIsoDate = cursor.getString(cursor.getColumnIndexOrThrow("booked_on_iso_date")),
                source = cursor.getString(cursor.getColumnIndexOrThrow("source")),
                type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                transferId =
                    cursor.getColumnIndexOrThrow("transfer_id").let { index ->
                        if (cursor.isNull(index)) null else cursor.getString(index)
                    },
                createdAtUtcMillis = cursor.getLong(cursor.getColumnIndexOrThrow("created_at_utc_millis")),
                updatedAtUtcMillis = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at_utc_millis")),
            )
        }

    /**
     * Result: the `source` of every stored transaction, tombstones included. Input: none.
     *         Output: `List<String>`.
     *
     * Reaches past the repository on purpose (issue 3.5): its reads map rows into the domain model
     * and **drop any whose source this build cannot parse**, so a mapped read is precisely the one
     * thing that cannot answer "is every stored source readable?".
     */
    private fun storedSources(): List<String> =
        database.query("SELECT source FROM transactions", emptyArray()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0))
                }
            }
        }

    private companion object {
        const val REAL_PROFILE = "local"
        const val DEMO_PROFILE = "demo"
    }
}

/**
 * Unwraps a result the test expects to have succeeded.
 * Why:    `(result as Ok).value` at thirty call sites buries the assertion that matters. A failure
 *         here names the error rather than throwing a bare `ClassCastException`. Declared per file
 *         because the same helper in [AccountRepositoryTest] is private to that file.
 * Result: the value. Input: the receiver. Output: [T].
 * Changelog: 2026-08-02 — Created for issue 3.1.
 */
private fun <T> com.aicfo.core.common.Result<T, AppError>.expectOk(): T =
    when (this) {
        is Ok -> value
        is Err -> throw AssertionError("expected Ok, got Err(${error.code})")
    }
