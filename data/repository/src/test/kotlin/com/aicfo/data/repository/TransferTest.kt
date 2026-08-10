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
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Money
import com.aicfo.core.model.TransactionType
import com.aicfo.domain.engines.classification.ClassificationEngineFactory
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
import java.time.Instant

/**
 * Tests for transfers — issue 3.2 (FR-TXN-003, DB-004, MNY-001).
 *
 * Why:  FR-TXN-003 is a MUST with two clauses, and each has a way of failing that no other test in
 *       the codebase would notice. **"A single logical record affecting two accounts atomically"** —
 *       two rows that do not balance invent or destroy money, and a pair written outside one
 *       database transaction can be observed half-done. **"Deleting one side deletes both"** — remove
 *       one leg and the destination account keeps money that came from nowhere, with nothing on
 *       screen explaining it.
 *
 *       There is a third property the SRS does not spell out but the schema now demands: `type` and
 *       the amount's sign both record direction, so **every write path must produce rows where the
 *       two agree**. That is asserted here over every path that writes a transaction at all, because
 *       the `CHECK` constraint §20.2 specifies cannot exist on an upgraded SQLite table.
 * What: the write, the balances it moves, validation, deletion, and the type/sign invariant.
 * Result: a transfer is provably one record, and provably reversible.
 * Changelog: 2026-08-02 — Created for issue 3.2.
 *
 * Unencrypted in-memory Room, deliberately — the same reasoning as [TransactionRepositoryTest]: what
 * is under test is the SQL and the atomicity, not SQLCipher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TransferTest {
    private lateinit var database: CfoDatabase
    private lateinit var repository: TransactionRepository
    private lateinit var accounts: AccountRepository

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
        repository =
            RepositoryFactory.transactions(
                database, clock, ids, dispatchers, activeProfileId, ClassificationEngineFactory.create(),
            )
        // The real AccountRepository: the claim under test is that both balances the accounts screen
        // renders actually move, and a stub could not make it.
        accounts = RepositoryFactory.accounts(database, clock, ids, dispatchers, activeProfileId)
    }

    /** Input: none. Output: closes the database between tests. */
    @After
    fun tearDown() {
        database.close()
    }

    // --- the write ---------------------------------------------------------------------------------

    @Test
    fun `a transfer writes exactly two legs sharing one id`() =
        runTest {
            val (from, to) = twoAccounts()

            val transfer = repository.createTransfer(TransferDraft(from.id, to.id, Money(5_000_00L))).expectOk()

            val legs = legsOf(transfer.id)
            assertEquals("a transfer is exactly two rows", 2, legs.size)
            assertEquals(setOf(transfer.id), legs.map { it.transferId }.toSet())
        }

    @Test
    fun `the returned record is the collapsed transfer, with a positive amount`() =
        runTest {
            val (from, to) = twoAccounts()

            val transfer = repository.createTransfer(TransferDraft(from.id, to.id, Money(5_000_00L))).expectOk()

            assertEquals(from.id, transfer.fromAccountId)
            assertEquals(to.id, transfer.toAccountId)
            // Positive always: the size of the movement, not a signed entry. A negative here would
            // leave a screen asking "which leg is this?".
            assertEquals(Money(5_000_00L), transfer.amount)
            assertEquals(clock.today().toString(), transfer.bookedOn)
        }

    @Test
    fun `the legs are opposite in sign and correctly typed`() =
        runTest {
            val (from, to) = twoAccounts()

            val transfer = repository.createTransfer(TransferDraft(from.id, to.id, Money(5_000_00L))).expectOk()

            val out = legsOf(transfer.id).single { it.accountId == from.id }
            val into = legsOf(transfer.id).single { it.accountId == to.id }
            assertEquals(-5_000_00L, out.amountMinor)
            assertEquals(5_000_00L, into.amountMinor)
            assertEquals(TransactionType.TRANSFER_OUT.storedValue, out.type)
            assertEquals(TransactionType.TRANSFER_IN.storedValue, into.type)
        }

    @Test
    fun `both legs share one booked day and one instant`() =
        runTest {
            // Not cosmetic. Split across days, the pair lands under two headers in the list, and once
            // issue 3.4 allows future dating, net worth's `booked_on_iso_date <=` bound would be
            // wrong for every day in between.
            val (from, to) = twoAccounts()

            val transfer = repository.createTransfer(TransferDraft(from.id, to.id, Money(5_000_00L))).expectOk()

            val legs = legsOf(transfer.id)
            assertEquals(1, legs.map { it.bookedOnIsoDate }.distinct().size)
            assertEquals(1, legs.map { it.occurredAtUtcMillis }.distinct().size)
        }

    @Test
    fun `neither leg carries a category`() =
        runTest {
            // FR-TXN-003: a transfer is not spending. A categorised leg would count the user's own
            // savings against a budget envelope once issue 4.4 lands.
            val (from, to) = twoAccounts()

            val transfer = repository.createTransfer(TransferDraft(from.id, to.id, Money(5_000_00L))).expectOk()

            legsOf(transfer.id).forEach { assertNull(it.categoryId) }
        }

    @Test
    fun `the note is held on both legs, so either one explains itself`() =
        runTest {
            val (from, to) = twoAccounts()

            val transfer =
                repository.createTransfer(
                    TransferDraft(from.id, to.id, Money(5_000_00L), note = "  Rent float  "),
                ).expectOk()

            assertEquals(listOf("Rent float", "Rent float"), legsOf(transfer.id).map { it.note })
        }

    // --- the money ---------------------------------------------------------------------------------

    @Test
    fun `both balances move by exactly the amount, in opposite directions`() =
        runTest {
            val (from, to) = twoAccounts(fromOpening = Money(50_000_00L), toOpening = Money(2_000_00L))

            repository.createTransfer(TransferDraft(from.id, to.id, Money(5_000_00L))).expectOk()

            assertEquals(Money(45_000_00L), accounts.find(from.id).expectOk().balance)
            assertEquals(Money(7_000_00L), accounts.find(to.id).expectOk().balance)
        }

    @Test
    fun `the legs of any transfer sum to exactly zero`() =
        runTest {
            // AC #2, as a property rather than an example: whatever the user types, a transfer must
            // create and destroy nothing. Walks magnitudes from one paise to a crore, including the
            // odd values where a naive halving or a rounding step would drift.
            val amounts =
                listOf(1L, 7L, 99L, 100L, 1_00L, 2_50_00L, 33_333_33L, 1_00_00_000_00L)

            amounts.forEach { minor ->
                val (from, to) = twoAccounts()
                val transfer =
                    repository.createTransfer(TransferDraft(from.id, to.id, Money(minor))).expectOk()

                val legs = legsOf(transfer.id)
                assertEquals(
                    "the legs of a ${Money(minor)} transfer must balance exactly",
                    Money.ZERO,
                    legs.fold(Money.ZERO) { running, leg -> running + Money(leg.amountMinor) },
                )
            }
        }

    @Test
    fun `a transfer leaves the pair's combined balance untouched`() =
        runTest {
            val (from, to) = twoAccounts(fromOpening = Money(50_000_00L), toOpening = Money(2_000_00L))
            val before = accounts.find(from.id).expectOk().balance + accounts.find(to.id).expectOk().balance

            repository.createTransfer(TransferDraft(from.id, to.id, Money(5_000_00L))).expectOk()

            val after = accounts.find(from.id).expectOk().balance + accounts.find(to.id).expectOk().balance
            assertEquals("moving money between your own accounts changes no total", before, after)
        }

    // --- validation --------------------------------------------------------------------------------

    @Test
    fun `the same account on both sides is refused, and nothing is written`() =
        runTest {
            val (from, _) = twoAccounts()

            val result = repository.createTransfer(TransferDraft(from.id, from.id, Money(5_000_00L)))

            assertEquals("toAccountId", ((result as Err).error as AppError.Validation).field)
            assertTrue(repository.liveTransactions().isEmpty())
        }

    @Test
    fun `a zero or negative amount is refused`() =
        runTest {
            // The sign is the repository's to apply. A negative here means the caller decided a
            // direction it does not get to decide.
            val (from, to) = twoAccounts()

            listOf(Money.ZERO, Money(-5_000_00L)).forEach { amount ->
                val result = repository.createTransfer(TransferDraft(from.id, to.id, amount))
                assertEquals("amount", ((result as Err).error as AppError.Validation).field)
            }
            assertTrue(repository.liveTransactions().isEmpty())
        }

    @Test
    fun `an unknown account is NotFound, and nothing is written`() =
        runTest {
            val (from, _) = twoAccounts()

            val result = repository.createTransfer(TransferDraft(from.id, "account:missing", Money(5_000_00L)))

            assertEquals(AppError.NotFound, (result as Err).error)
            // The half-transfer this guards against: the outgoing leg written, the incoming one not.
            assertTrue(repository.liveTransactions().isEmpty())
        }

    @Test
    fun `a soft-deleted account cannot receive a transfer`() =
        runTest {
            val (from, to) = twoAccounts()
            accounts.delete(to.id).expectOk()

            val result = repository.createTransfer(TransferDraft(from.id, to.id, Money(5_000_00L)))

            assertEquals(AppError.NotFound, (result as Err).error)
            assertTrue(repository.liveTransactions().isEmpty())
        }

    @Test
    fun `a transfer between different currencies is refused rather than converted`() =
        runTest {
            // Converting would need the FX rates SS20.1 reserves a table for and no issue has built.
            // Inventing a rate would be the app making up a number (P-03), so it refuses instead.
            val from = newAccount("HDFC Savings")
            val to = newAccount("US Brokerage", currency = "USD")

            val result = repository.createTransfer(TransferDraft(from.id, to.id, Money(5_000_00L)))

            assertEquals(AppError.NotFound, (result as Err).error)
            assertTrue(repository.liveTransactions().isEmpty())
        }

    // --- deletion ----------------------------------------------------------------------------------

    @Test
    fun `deleting the outgoing leg deletes the incoming one too`() =
        runTest {
            val (from, to) = twoAccounts()
            val transfer = repository.createTransfer(TransferDraft(from.id, to.id, Money(5_000_00L))).expectOk()
            val out = legsOf(transfer.id).single { it.accountId == from.id }

            repository.delete(out.id).expectOk()

            assertTrue("FR-TXN-003: deleting one side deletes both", liveLegsOf(transfer.id).isEmpty())
        }

    @Test
    fun `deleting the incoming leg deletes the outgoing one too`() =
        runTest {
            // Both directions, because a user taps whichever row they are looking at and the
            // repository is what decides — the screen never has to know which leg it holds.
            val (from, to) = twoAccounts()
            val transfer = repository.createTransfer(TransferDraft(from.id, to.id, Money(5_000_00L))).expectOk()
            val into = legsOf(transfer.id).single { it.accountId == to.id }

            repository.delete(into.id).expectOk()

            assertTrue(liveLegsOf(transfer.id).isEmpty())
        }

    @Test
    fun `deleting a transfer reverts both balances`() =
        runTest {
            val (from, to) = twoAccounts(fromOpening = Money(50_000_00L), toOpening = Money(2_000_00L))
            val transfer = repository.createTransfer(TransferDraft(from.id, to.id, Money(5_000_00L))).expectOk()

            repository.delete(legsOf(transfer.id).first().id).expectOk()

            assertEquals(Money(50_000_00L), accounts.find(from.id).expectOk().balance)
            assertEquals(Money(2_000_00L), accounts.find(to.id).expectOk().balance)
        }

    @Test
    fun `a deleted transfer is soft-deleted, not removed`() =
        runTest {
            // DB-002: the rows survive as tombstones so an accidental delete is recoverable and a
            // future sync can still see they existed.
            val (from, to) = twoAccounts()
            val transfer = repository.createTransfer(TransferDraft(from.id, to.id, Money(5_000_00L))).expectOk()

            repository.delete(legsOf(transfer.id).first().id).expectOk()

            assertEquals("both rows must still be on disk", 2, legsOf(transfer.id).size)
            legsOf(transfer.id).forEach { assertTrue(it.deletedAtUtcMillis != null) }
        }

    @Test
    fun `deleting a transfer twice reports NotFound the second time`() =
        runTest {
            // The bug the DAO's `AND deleted_at_utc_millis IS NULL` guard exists for: without it the
            // second delete matches the same rows again and the screen is told it succeeded.
            val (from, to) = twoAccounts()
            val transfer = repository.createTransfer(TransferDraft(from.id, to.id, Money(5_000_00L))).expectOk()
            val legId = legsOf(transfer.id).first().id

            repository.delete(legId).expectOk()
            val second = repository.delete(legId)

            assertEquals(AppError.NotFound, (second as Err).error)
        }

    @Test
    fun `deleting a plain transaction removes only that row`() =
        runTest {
            val (from, to) = twoAccounts()
            val plain = repository.create(TransactionDraft(from.id, Money(-250_00L))).expectOk()
            val transfer = repository.createTransfer(TransferDraft(from.id, to.id, Money(5_000_00L))).expectOk()

            repository.delete(plain.id).expectOk()

            assertEquals("the transfer must be untouched", 2, liveLegsOf(transfer.id).size)
        }

    @Test
    fun `deleting an unknown id is NotFound`() =
        runTest {
            assertEquals(AppError.NotFound, (repository.delete("txn:missing") as Err).error)
        }

    // --- the type invariant ------------------------------------------------------------------------

    @Test
    fun `every row every write path produces has a type that agrees with its sign`() =
        runTest {
            // The CHECK constraint SS20.2 specifies, expressed as a test — SQLite cannot carry one on
            // a column added by ALTER TABLE. This walks all four paths that write a transaction:
            // an expense, an income, a reconciliation adjustment, and both transfer legs.
            val (from, to) = twoAccounts(fromOpening = Money(50_000_00L))
            repository.create(TransactionDraft(from.id, Money(-250_00L))).expectOk()
            repository.create(TransactionDraft(from.id, Money(60_000_00L))).expectOk()
            repository.createTransfer(TransferDraft(from.id, to.id, Money(5_000_00L))).expectOk()
            accounts.reconcile(from.id, Money(1_00_000_00L)).expectOk()

            val rows = allRows()
            assertEquals("all four write paths must have produced rows", 5, rows.size)
            rows.forEach { row ->
                val type =
                    TransactionType.fromStored(row.type)
                        ?: error("row ${row.id} stored an unparseable type '${row.type}'")
                assertTrue(
                    "row ${row.id} is typed $type but holds ${row.amountMinor}",
                    type.matches(Money(row.amountMinor)),
                )
            }
        }

    @Test
    fun `an ordinary expense and income are typed from their sign`() =
        runTest {
            val from = newAccount("HDFC Savings")

            val expense = repository.create(TransactionDraft(from.id, Money(-250_00L))).expectOk()
            val income = repository.create(TransactionDraft(from.id, Money(60_000_00L))).expectOk()

            assertEquals(TransactionType.EXPENSE, expense.type)
            assertEquals(TransactionType.INCOME, income.type)
        }

    @Test
    fun `a reconciliation adjustment is typed as an adjustment, not as spending`() =
        runTest {
            // Issue 2.7's rows. Typed as an expense they would land in every spend total the moment
            // one exists, and a balance correction is not something the user bought.
            val account = newAccount("HDFC Savings")

            accounts.reconcile(account.id, Money(90_000_00L)).expectOk()

            assertEquals(
                TransactionType.ADJUSTMENT.storedValue,
                allRows().single().type,
            )
        }

    @Test
    fun `transfers are excluded by the income-and-expense filter`() =
        runTest {
            // What the `type` column is for: "money the user actually spent or received" is a filter
            // on this column, and it must not see either leg of a transfer.
            val (from, to) = twoAccounts(fromOpening = Money(50_000_00L))
            repository.create(TransactionDraft(from.id, Money(-250_00L))).expectOk()
            repository.createTransfer(TransferDraft(from.id, to.id, Money(5_000_00L))).expectOk()

            val spendRows =
                allRows().filter {
                    it.type in setOf(TransactionType.EXPENSE.storedValue, TransactionType.INCOME.storedValue)
                }

            assertEquals(1, spendRows.size)
            assertEquals(-250_00L, spendRows.single().amountMinor)
        }

    // --- reads -------------------------------------------------------------------------------------

    @Test
    fun `a transfer is one row in the list, naming the account on the other side`() =
        runTest {
            // Issue 3.6 moved the collapse from the screen into the query. Until then the repository
            // returned both legs and `List<Transaction>.toRows()` paired them within a loaded day —
            // which paging breaks the moment the legs land in different pages. Now the incoming leg
            // never leaves SQL and the outgoing one carries its counterpart (FR-TXN-003).
            val (from, to) = twoAccounts()
            val transfer = repository.createTransfer(TransferDraft(from.id, to.id, Money(5_000_00L))).expectOk()

            val rows = repository.liveRows()

            assertEquals(1, rows.size)
            val row = rows.single()
            assertEquals(transfer.id, row.transaction.transferId)
            assertEquals(TransactionType.TRANSFER_OUT, row.transaction.type)
            assertEquals(from.id, row.transaction.accountId)
            assertEquals(to.id, row.counterpartAccountId)
        }

    @Test
    fun `filtering by the destination account shows the incoming leg instead`() =
        runTest {
            // The collapse clause stands down when an account filter is set, and it has to: a user
            // who filters to the account money *arrived* in must see it arrive. Dropping the
            // incoming leg unconditionally would make a transfer invisible from one of the two
            // accounts it touched.
            val (from, to) = twoAccounts()
            repository.createTransfer(TransferDraft(from.id, to.id, Money(5_000_00L))).expectOk()

            val rows = repository.liveRows(TransactionFilter(accountId = to.id))

            val row = rows.single()
            assertEquals(TransactionType.TRANSFER_IN, row.transaction.type)
            assertEquals(Money(5_000_00L), row.transaction.amount)
            assertEquals(from.id, row.counterpartAccountId)
        }

    @Test
    fun `a plain transaction carries no transfer id`() =
        runTest {
            val account = newAccount("HDFC Savings")

            repository.create(TransactionDraft(account.id, Money(-250_00L))).expectOk()

            assertNull(repository.liveTransactions().single().transferId)
        }

    // --- fixtures ----------------------------------------------------------------------------------

    /**
     * Result: two live accounts in the real profile, source first. Input: their opening balances.
     * Output: `Pair<Account, Account>`.
     */
    private suspend fun twoAccounts(
        fromOpening: Money = Money(50_000_00L),
        toOpening: Money = Money(2_000_00L),
    ) = newAccount("HDFC Savings", fromOpening) to newAccount("Cash Wallet", toOpening)

    /** Result: one live account. Input: [name], [opening], [currency]. Output: the created `Account`. */
    private suspend fun newAccount(
        name: String,
        opening: Money = Money(50_000_00L),
        currency: String = "INR",
    ) = accounts.create(
        AccountDraft(
            name = name,
            type = AccountType.BANK,
            openingBalance = opening,
            currencyCode = currency,
        ),
    ).expectOk()

    /**
     * Result: every row of a transfer including tombstones, so a test can assert a delete was soft.
     * Input: [transferId]. Output: the rows.
     */
    private fun legsOf(transferId: String) = allRows().filter { it.transferId == transferId }

    /** Result: the rows of a transfer that are still live. Input: [transferId]. Output: the rows. */
    private fun liveLegsOf(transferId: String) = legsOf(transferId).filter { it.deletedAtUtcMillis == null }

    /**
     * Result: every transaction row, straight from SQL. Input: none. Output: the rows.
     *
     * Reaches past the repository on purpose: the ledger read filters soft-deleted rows and maps into
     * the domain model, so neither "the tombstone is still there" nor "the stored type is right" is a
     * claim it can make.
     */
    private fun allRows(): List<RawRow> =
        database.query(
            "SELECT id, account_id, amount_minor, type, transfer_id, category_id, note, " +
                "booked_on_iso_date, occurred_at_utc_millis, deleted_at_utc_millis FROM transactions",
            null,
        )
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            RawRow(
                                id = cursor.getString(0),
                                accountId = cursor.getString(1),
                                amountMinor = cursor.getLong(2),
                                type = cursor.getString(3),
                                transferId = if (cursor.isNull(4)) null else cursor.getString(4),
                                categoryId = if (cursor.isNull(5)) null else cursor.getString(5),
                                note = if (cursor.isNull(6)) null else cursor.getString(6),
                                bookedOnIsoDate = cursor.getString(7),
                                occurredAtUtcMillis = cursor.getLong(8),
                                deletedAtUtcMillis = if (cursor.isNull(9)) null else cursor.getLong(9),
                            ),
                        )
                    }
                }
            }

    /**
     * A transaction row as stored, with only the columns these tests assert on.
     * Why: reusing `TransactionEntity` would mean listing fourteen columns in every cursor read for
     *      the ten this file cares about. Result: a value. Input: the columns. Output: immutable.
     */
    private data class RawRow(
        val id: String,
        val accountId: String,
        val amountMinor: Long,
        val type: String,
        val transferId: String?,
        val categoryId: String?,
        val note: String?,
        val bookedOnIsoDate: String,
        val occurredAtUtcMillis: Long,
        val deletedAtUtcMillis: Long?,
    )

    private companion object {
        const val REAL_PROFILE = "local"
    }
}

/**
 * Unwraps a result the test expects to have succeeded.
 * Result: the value. Input: the receiver. Output: [T].
 * Changelog: 2026-08-02 — Created for issue 3.2.
 */
private fun <T> com.aicfo.core.common.Result<T, AppError>.expectOk(): T =
    when (this) {
        is Ok -> value
        is Err -> throw AssertionError("expected Ok, got Err(${error.code})")
    }
