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
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Money
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
 * Tests multi-select recategorise, retag, delete and undo — issue 3.6's write half (FR-TXN-008).
 *
 * Why:  a bulk edit is the one place in this app where a single tap changes many rows, so the
 *       failure modes are proportionally worse. Five properties decide whether it can be offered:
 *
 *       **Undo restores exactly what was deleted, siblings included.** Deleting one leg of a
 *       transfer takes the other (FR-TXN-003); an undo that restored only the selection would leave
 *       the money the transfer moved sitting in one account with no counterpart — money created
 *       from nothing, by the button labelled Undo.
 *
 *       **A transfer leg and a split parent cannot be given a category.** FR-TXN-003 and FR-TXN-004
 *       each forbid it for their own reason, and a selection is a rough instrument: the user swept
 *       up twenty rows and two of them happen to be a transfer.
 *
 *       **Skipping is not failing.** Those two rows are passed over; the other eighteen are still
 *       recategorised, and the count says eighteen.
 *
 *       **Retag is a set, so applying it twice changes nothing.** An accumulating retag would double
 *       every link on a double-tap.
 *
 *       **Balances follow.** A bulk delete moves them and an undo moves them back, with no balance
 *       write anywhere (DB-001, ADR-0007).
 * What: one test per property, plus the empty-selection and repeat-delete edges.
 * Result: the bulk path is proven against a real SQL engine before any UI sits on it.
 * Changelog: 2026-08-04 — Created for issue 3.6 (FR-TXN-008).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BulkEditTest {
    private lateinit var database: CfoDatabase
    private lateinit var repository: TransactionRepository
    private lateinit var accounts: AccountRepository

    private val clock = FakeClock(initialMillis = Instant.parse("2026-08-02T06:00:00Z").toEpochMilli())
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
        // The real AccountRepository rather than a stub: the claim under test is that a bulk delete
        // moves the balance the accounts screen actually renders, and a stub could not make it.
        accounts = RepositoryFactory.accounts(database, clock, ids, dispatchers, activeProfileId)
    }

    /** Input: none. Output: closes the database between tests. */
    @After
    fun tearDown() {
        database.close()
    }

    // --- recategorise ------------------------------------------------------------------------------

    @Test
    fun `recategorising a selection sets the category on every eligible row`() =
        runTest {
            val account = newAccount()
            val food = newCategory("Food")
            val first = create(account, -250_00L)
            val second = create(account, -900_00L)

            assertEquals(2, repository.recategoriseAll(listOf(first, second), food).expectOk())

            assertTrue(repository.liveTransactions().all { it.categoryId == food })
        }

    @Test
    fun `recategorising with a null category clears it`() =
        runTest {
            val account = newAccount()
            val food = newCategory("Food")
            val id = create(account, -250_00L, categoryId = food)

            assertEquals(1, repository.recategoriseAll(listOf(id), null).expectOk())

            assertNull(repository.liveTransactions().single().categoryId)
        }

    @Test
    fun `a transfer leg in the selection is skipped, and the rest still go through`() =
        runTest {
            // FR-TXN-003: a transfer is not spending, so a leg has no category — a categorised leg
            // would count the user's own savings against a food budget once issue 4.4 lands.
            val hdfc = newAccount("HDFC Savings")
            val cash = newAccount("Cash Wallet")
            val food = newCategory("Food")
            val spend = create(hdfc, -250_00L)
            repository.createTransfer(TransferDraft(hdfc, cash, Money(5_000_00L))).expectOk()
            val legIds = database.transactionDao().findTransferSiblingIds(listOf(spend)) + everyStoredId()

            val changed = repository.recategoriseAll(legIds.distinct(), food).expectOk()

            assertEquals("only the plain transaction is eligible", 1, changed)
            assertEquals(food, repository.liveTransactions().single { it.id == spend }.categoryId)
            assertTrue(
                "no transfer leg may carry a category",
                storedCategoryIdsOfTransferLegs().all { it == null },
            )
        }

    @Test
    fun `a split parent in the selection is skipped`() =
        runTest {
            // FR-TXN-004: the lines carry the categories, so a category on the parent as well is a
            // second, contradictory answer to "what was this?".
            val account = newAccount()
            val food = newCategory("Food")
            val split =
                repository.createSplit(
                    SplitDraft(
                        accountId = account,
                        amount = Money(-1_000_00L),
                        lines = listOf(SplitLineDraft(Money(-600_00L)), SplitLineDraft(Money(-400_00L))),
                    ),
                ).expectOk()

            assertEquals(0, repository.recategoriseAll(listOf(split.id), food).expectOk())

            assertNull(repository.liveTransactions().single().categoryId)
        }

    @Test
    fun `recategorising nothing is refused rather than silently doing nothing`() =
        runTest {
            val outcome = repository.recategoriseAll(emptyList(), null)

            assertEquals("validation", (outcome as Err).error.code)
        }

    // --- retag -------------------------------------------------------------------------------------

    @Test
    fun `retagging attaches the labels and creates the ones the profile lacks`() =
        runTest {
            val account = newAccount()
            val first = create(account, -250_00L)
            val second = create(account, -900_00L)

            assertEquals(2, repository.retagAll(listOf(first, second), listOf("goa-trip", "work")).expectOk())

            assertEquals(listOf("goa-trip", "work"), repository.observeTags().first().map { it.name })
            assertTrue(
                repository.liveTransactions().all { row -> row.tags.map { it.name } == listOf("goa-trip", "work") },
            )
        }

    @Test
    fun `retagging twice with the same labels changes nothing`() =
        runTest {
            // A set, not an accumulation: this is what makes a double-tap safe.
            val account = newAccount()
            val id = create(account, -250_00L)

            repository.retagAll(listOf(id), listOf("travel")).expectOk()
            repository.retagAll(listOf(id), listOf("travel")).expectOk()

            assertEquals(listOf("travel"), repository.liveTransactions().single().tags.map { it.name })
            assertEquals(1, repository.observeTags().first().size)
        }

    @Test
    fun `a label differing only in case reuses the existing tag`() =
        runTest {
            // Two rows for `Travel` and `travel` would split a tag's transactions across two chips
            // the user cannot tell apart — and the unique index would reject the second write.
            val account = newAccount()
            val first = create(account, -250_00L)
            val second = create(account, -900_00L)

            repository.retagAll(listOf(first), listOf("Travel")).expectOk()
            repository.retagAll(listOf(second), listOf("travel")).expectOk()

            assertEquals(1, repository.observeTags().first().size)
        }

    @Test
    fun `retagging with no labels removes every tag`() =
        runTest {
            val account = newAccount()
            val id = create(account, -250_00L)
            repository.retagAll(listOf(id), listOf("travel")).expectOk()

            repository.retagAll(listOf(id), emptyList()).expectOk()

            assertTrue(repository.liveTransactions().single().tags.isEmpty())
        }

    // --- delete and undo ---------------------------------------------------------------------------

    @Test
    fun `a bulk delete removes every row and moves the balance`() =
        runTest {
            val account = newAccount()
            val first = create(account, -250_00L)
            val second = create(account, -900_00L)

            val removed = repository.deleteAll(listOf(first, second)).expectOk()

            assertEquals(setOf(first, second), removed.toSet())
            assertTrue(repository.liveTransactions().isEmpty())
            assertEquals(Money(100_000_00L), accounts.find(account).expectOk().balance)
        }

    @Test
    fun `undo restores the rows and the balance`() =
        runTest {
            val account = newAccount()
            val first = create(account, -250_00L)
            val second = create(account, -900_00L)
            val removed = repository.deleteAll(listOf(first, second)).expectOk()

            assertEquals(2, repository.restoreAll(removed).expectOk())

            assertEquals(setOf(first, second), repository.liveTransactions().map { it.id }.toSet())
            assertEquals(Money(98_850_00L), accounts.find(account).expectOk().balance)
        }

    @Test
    fun `deleting one leg of a transfer reports both, and undo restores both`() =
        runTest {
            // The property this whole batch exists for. An undo that restored only the selection
            // would leave the destination account holding money that came from nowhere.
            val hdfc = newAccount("HDFC Savings")
            val cash = newAccount("Cash Wallet")
            repository.createTransfer(TransferDraft(hdfc, cash, Money(5_000_00L))).expectOk()
            val oneLeg = repository.liveRows().single().transaction.id

            val removed = repository.deleteAll(listOf(oneLeg)).expectOk()

            assertEquals("both legs must be named, not just the one selected", 2, removed.size)
            assertEquals(Money(100_000_00L), accounts.find(hdfc).expectOk().balance)
            assertEquals(Money(100_000_00L), accounts.find(cash).expectOk().balance)

            repository.restoreAll(removed).expectOk()

            assertEquals(Money(95_000_00L), accounts.find(hdfc).expectOk().balance)
            assertEquals(Money(105_000_00L), accounts.find(cash).expectOk().balance)
        }

    @Test
    fun `undo brings a split's lines back with its parent`() =
        runTest {
            // A restored parent whose lines stayed deleted is an amount attributed to nothing.
            val account = newAccount()
            val split =
                repository.createSplit(
                    SplitDraft(
                        accountId = account,
                        amount = Money(-1_000_00L),
                        lines = listOf(SplitLineDraft(Money(-600_00L)), SplitLineDraft(Money(-400_00L))),
                    ),
                ).expectOk()

            val removed = repository.deleteAll(listOf(split.id)).expectOk()
            repository.restoreAll(removed).expectOk()

            assertEquals(2, repository.liveTransactions().single().splits.size)
        }

    @Test
    fun `deleting the same selection twice reports the second attempt honestly`() =
        runTest {
            val account = newAccount()
            val id = create(account, -250_00L)
            repository.deleteAll(listOf(id)).expectOk()

            val second = repository.deleteAll(listOf(id))

            assertEquals("not_found", (second as Err).error.code)
        }

    @Test
    fun `deleting nothing is refused rather than silently doing nothing`() =
        runTest {
            assertEquals("validation", (repository.deleteAll(emptyList()) as Err).error.code)
        }

    @Test
    fun `restoring something that was never deleted is refused`() =
        runTest {
            val account = newAccount()
            val id = create(account, -250_00L)

            assertEquals("not_found", (repository.restoreAll(listOf(id)) as Err).error.code)
        }

    // --- fixtures ----------------------------------------------------------------------------------

    /** Result: a live account's id. Input: [name]. Output: [String]. */
    private suspend fun newAccount(name: String = "HDFC Savings"): String =
        accounts.create(
            AccountDraft(
                name = name,
                type = AccountType.BANK,
                openingBalance = Money(100_000_00L),
                currencyCode = "INR",
            ),
        ).expectOk().id

    /** Result: a live category's id. Input: [name]. Output: [String]. */
    private suspend fun newCategory(name: String): String {
        val id = "cat:${name.lowercase()}"
        database.categoryDao().upsert(
            CategoryEntity(
                id = id,
                profileId = REAL_PROFILE,
                name = name,
                nature = "want",
                isSystem = true,
                createdAtUtcMillis = clock.nowUtcMillis(),
                updatedAtUtcMillis = clock.nowUtcMillis(),
            ),
        )
        return id
    }

    /** Result: a written transaction's id. Input: the draft's fields. Output: [String]. */
    private suspend fun create(
        accountId: String,
        amountMinor: Long,
        categoryId: String? = null,
    ): String =
        repository.create(
            TransactionDraft(accountId = accountId, amount = Money(amountMinor), categoryId = categoryId),
        ).expectOk().id

    /**
     * Result: every stored transaction id, tombstones included. Input: none. Output: `List<String>`.
     *
     * Reaches past the repository on purpose: the list collapses a transfer to one leg, so a mapped
     * read cannot name the leg the selection is meant to sweep up by accident.
     */
    private fun everyStoredId(): List<String> =
        database.query("SELECT id FROM transactions", emptyArray()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0))
                }
            }
        }

    /**
     * Result: the stored `category_id` of every transfer leg. Input: none. Output: `List<String?>`.
     *
     * Reaches past the repository for the same reason: the claim is about the column, and the model
     * the list returns never shows a leg that the query dropped.
     */
    private fun storedCategoryIdsOfTransferLegs(): List<String?> =
        database.query("SELECT category_id FROM transactions WHERE transfer_id IS NOT NULL", emptyArray())
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(if (cursor.isNull(0)) null else cursor.getString(0))
                    }
                }
            }

    private companion object {
        const val REAL_PROFILE = "local"
    }
}

/**
 * Unwraps a result the test expects to have succeeded.
 * Why:    a failure here names the error rather than throwing a bare `ClassCastException`. Declared
 *         per file, matching the convention the other repository suites already follow.
 * Result: the value. Input: the receiver. Output: [T].
 * Changelog: 2026-08-04 — Created for issue 3.6.
 */
private fun <T> com.aicfo.core.common.Result<T, AppError>.expectOk(): T =
    when (this) {
        is Ok -> value
        is Err -> throw AssertionError("expected Ok, got Err(${error.code})")
    }
