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
import com.aicfo.core.model.TransactionSource
import com.aicfo.core.model.TransactionType
import com.aicfo.domain.engines.classification.ClassificationEngineFactory
import com.aicfo.domain.engines.nature.NatureEngineFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant

/**
 * Tests search, filters, paging and day totals — the read half of issue 3.6 (FR-TXN-007).
 *
 * Why:  FR-TXN-007 is one requirement with seven facets and one statement of SQL answering all of
 *       them, which is exactly the shape where a facet can be silently wrong. Four properties
 *       decide whether the list can be trusted, and not one is visible from reading the query:
 *
 *       **Each facet narrows on its own and they compose.** A clause wired in the wrong direction
 *       (`>=` for `<=`) still returns rows, just the wrong ones — a bug a smoke test would not see.
 *
 *       **The amount bounds are on the magnitude.** Under a signed comparison "between ₹100 and
 *       ₹500" would exclude every expense, which is most of a ledger.
 *
 *       **A day total is right across a page boundary.** This is the one claim that cannot be made
 *       by loading a page: the fixture deliberately puts more than one page of transactions in a
 *       single day, so a total folded from loaded rows would be visibly short.
 *
 *       **The search text is data, not SQL.** `%` typed into the search box must find the row whose
 *       note contains a literal `%`, not every row.
 * What: one test per facet, the combinations, paging, and the totals.
 * Result: the list's query is proven against a real SQL engine before any UI sits on it.
 * Changelog: 2026-08-04 — Created for issue 3.6 (FR-TXN-007).
 *
 * Unencrypted in-memory Room, deliberately — the same reasoning as `TransactionRepositoryTest`:
 * what is under test is the SQL, not SQLCipher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TransactionSearchTest {
    private lateinit var database: CfoDatabase
    private lateinit var repository: TransactionRepository

    private val clock = FakeClock(initialMillis = Instant.parse("2026-08-02T06:00:00Z").toEpochMilli())
    private val ids = FakeIdGenerator()
    private val activeProfileId = MutableStateFlow(REAL_PROFILE)

    /** Input: none. Output: a fresh in-memory database and the repository over it. */
    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                CfoDatabase::class.java,
            ).allowMainThreadQueries().build()
        repository =
            RepositoryFactory.transactions(
                database,
                clock,
                ids,
                TestDispatchers(UnconfinedTestDispatcher()),
                activeProfileId,
                ClassificationEngineFactory.create(),
                NatureEngineFactory.create(),
            )
    }

    /** Input: none. Output: closes the database between tests. */
    @After
    fun tearDown() {
        database.close()
    }

    // --- search ------------------------------------------------------------------------------------

    @Test
    fun `search matches the payee`() =
        runTest {
            val account = newAccount()
            create(account, -250_00L, merchant = "Chai Point")
            create(account, -900_00L, merchant = "Big Bazaar")

            val found = repository.liveTransactions(TransactionFilter(query = "chai"))

            assertEquals(listOf("Chai Point"), found.map { it.merchant })
        }

    @Test
    fun `search matches the note`() =
        runTest {
            val account = newAccount()
            create(account, -250_00L, note = "team lunch")
            create(account, -900_00L, note = "groceries")

            assertEquals(
                listOf("team lunch"),
                repository.liveTransactions(TransactionFilter(query = "lunch")).map { it.note },
            )
        }

    @Test
    fun `search matches an exact amount, not the digits inside other amounts`() =
        runTest {
            // FR-TXN-007's "amount". A substring match on the stored paise would make "250" find
            // ₹2.50, ₹1,250 and ₹25,000 as well — three wrong answers for one right one.
            val account = newAccount()
            create(account, -250_00L, note = "the one")
            create(account, -2_50L, note = "too small")
            create(account, -1_250_00L, note = "too big")

            assertEquals(
                listOf("the one"),
                repository.liveTransactions(TransactionFilter(query = "250")).map { it.note },
            )
        }

    @Test
    fun `search matches a tag`() =
        runTest {
            val account = newAccount()
            val tagged = create(account, -250_00L, note = "flight")
            create(account, -900_00L, note = "unrelated")
            repository.retagAll(listOf(tagged), listOf("goa-trip")).expectOk()

            assertEquals(
                listOf("flight"),
                repository.liveTransactions(TransactionFilter(query = "goa")).map { it.note },
            )
        }

    @Test
    fun `a wildcard typed into the search box is matched literally`() =
        runTest {
            // The user's text is data, not syntax: `%` is a LIKE wildcard, so unescaped this search
            // becomes `%50%%` and matches anything containing "50" at all.
            //
            // **The decoy has to contain "50" for this to be a test.** The first version paired
            // "50% off" with "full price" and passed with the escaping deliberately removed — the
            // decoy simply had no digits, so both the right query and the wrong one excluded it. A
            // guard that cannot fail is not a guard (see the governance audit, 2026-07-25).
            val account = newAccount()
            create(account, -250_00L, note = "50% off")
            create(account, -900_00L, note = "50 rupees delivery")

            assertEquals(
                listOf("50% off"),
                repository.liveTransactions(TransactionFilter(query = "50%")).map { it.note },
            )
        }

    @Test
    fun `a blank search is not a search`() =
        runTest {
            val account = newAccount()
            create(account, -250_00L)
            create(account, -900_00L)

            assertEquals(2, repository.liveTransactions(TransactionFilter(query = "   ")).size)
        }

    // --- filters -----------------------------------------------------------------------------------

    @Test
    fun `filtering by account narrows to that account`() =
        runTest {
            val hdfc = newAccount("HDFC Savings")
            val cash = newAccount("Cash Wallet")
            create(hdfc, -250_00L, note = "hdfc")
            create(cash, -900_00L, note = "cash")

            assertEquals(
                listOf("hdfc"),
                repository.liveTransactions(TransactionFilter(accountId = hdfc)).map { it.note },
            )
        }

    @Test
    fun `filtering by category narrows to that category`() =
        runTest {
            val account = newAccount()
            val food = newCategory("Food")
            create(account, -250_00L, categoryId = food, note = "food")
            create(account, -900_00L, note = "uncategorised")

            assertEquals(
                listOf("food"),
                repository.liveTransactions(TransactionFilter(categoryId = food)).map { it.note },
            )
        }

    @Test
    fun `filtering by type separates income from spending`() =
        runTest {
            val account = newAccount()
            create(account, -250_00L, note = "spend")
            create(account, 50_000_00L, note = "salary")

            assertEquals(
                listOf("salary"),
                repository.liveTransactions(TransactionFilter(type = TransactionType.INCOME)).map { it.note },
            )
        }

    @Test
    fun `filtering by source narrows to that provenance`() =
        runTest {
            val account = newAccount()
            create(account, -250_00L, note = "typed")

            assertEquals(
                1,
                repository.liveTransactions(TransactionFilter(source = TransactionSource.MANUAL)).size,
            )
            assertEquals(
                0,
                repository.liveTransactions(TransactionFilter(source = TransactionSource.OCR)).size,
            )
        }

    @Test
    fun `the amount range is a bound on the magnitude, so it catches expenses`() =
        runTest {
            // MNY-001: an expense is negative. Under a signed comparison "between ₹100 and ₹500"
            // would match nothing at all, which is most of a real ledger silently missing.
            val account = newAccount()
            create(account, -50_00L, note = "below")
            create(account, -250_00L, note = "inside")
            create(account, -900_00L, note = "above")

            val found =
                repository.liveTransactions(
                    TransactionFilter(minAmount = Money(100_00L), maxAmount = Money(500_00L)),
                )

            assertEquals(listOf("inside"), found.map { it.note })
        }

    @Test
    fun `the date range bounds both ends`() =
        runTest {
            val account = newAccount()
            val today = clock.today()
            createOn(account, -250_00L, today.minusDays(10), note = "older")
            createOn(account, -250_00L, today.minusDays(5), note = "inside")
            createOn(account, -250_00L, today.minusDays(1), note = "newer")

            val found =
                repository.liveTransactions(
                    TransactionFilter(fromDate = today.minusDays(7), toDate = today.minusDays(3)),
                )

            assertEquals(listOf("inside"), found.map { it.note })
        }

    @Test
    fun `facets compose rather than replacing one another`() =
        runTest {
            val hdfc = newAccount("HDFC Savings")
            val cash = newAccount("Cash Wallet")
            create(hdfc, -250_00L, merchant = "Chai Point", note = "wanted")
            create(cash, -250_00L, merchant = "Chai Point", note = "wrong account")
            create(hdfc, -900_00L, merchant = "Chai Point", note = "wrong amount")

            val found =
                repository.liveTransactions(
                    TransactionFilter(
                        query = "chai",
                        accountId = hdfc,
                        maxAmount = Money(500_00L),
                    ),
                )

            assertEquals(listOf("wanted"), found.map { it.note })
        }

    @Test
    fun `a filter never reaches into another profile`() =
        runTest {
            val account = newAccount()
            create(account, -250_00L, merchant = "Chai Point")
            activeProfileId.value = DEMO_PROFILE

            assertTrue(repository.liveTransactions(TransactionFilter(query = "chai")).isEmpty())
        }

    @Test
    fun `a scheduled row stays out of the list unless the filter asks for it`() =
        runTest {
            // FR-TXN-010: future-dated rows are excluded from actuals. The old windowed read got
            // this from its upper bound; when the window went, the bound had to be stated — and a
            // date filter that names the future must still override it.
            val account = newAccount()
            val nextWeek = clock.today().plusWeeks(1)
            createOn(account, -250_00L, nextWeek, note = "rent")

            assertTrue(repository.liveTransactions().isEmpty())
            assertEquals(
                listOf("rent"),
                repository.liveTransactions(TransactionFilter(toDate = nextWeek)).map { it.note },
            )
        }

    // --- paging and day totals ---------------------------------------------------------------------

    @Test
    fun `paging returns every row exactly once, in order`() =
        runTest {
            val account = newAccount()
            val expected =
                (1..PAGE_AND_A_HALF).map { index ->
                    clock.advanceBy(Duration.ofMinutes(1))
                    create(account, -100_00L, note = "row $index")
                }

            val listed = repository.liveTransactions().map { it.id }

            assertEquals(PAGE_AND_A_HALF, listed.size)
            assertEquals(listed.size, listed.toSet().size)
            // Newest first, so the ids arrive in the reverse of the order they were written.
            assertEquals(expected.reversed(), listed)
        }

    @Test
    fun `a day total is the whole day, not the loaded page`() =
        runTest {
            // The claim paging makes hardest to keep. Every row here is booked on one day and there
            // are more of them than fit in a page, so a header folded from loaded rows would state
            // roughly the first page's worth and be wrong by the rest.
            val account = newAccount()
            repeat(PAGE_AND_A_HALF) {
                clock.advanceBy(Duration.ofMinutes(1))
                create(account, -100_00L)
            }

            val totals = repository.observeDayTotals(TransactionFilter()).first()

            assertEquals(Money(-100_00L * PAGE_AND_A_HALF), totals.getValue(clock.today().toString()))
        }

    @Test
    fun `a transfer moves no day total`() =
        runTest {
            val hdfc = newAccount("HDFC Savings")
            val cash = newAccount("Cash Wallet")
            create(hdfc, -250_00L)
            repository.createTransfer(TransferDraft(hdfc, cash, Money(5_000_00L))).expectOk()

            val totals = repository.observeDayTotals(TransactionFilter()).first()

            // The spend and nothing else: the legs are -X and +X, so a transfer contributes zero —
            // which is what the list has shown since issue 3.2.
            assertEquals(Money(-250_00L), totals.getValue(clock.today().toString()))
        }

    @Test
    fun `day totals honour the same filter as the rows`() =
        runTest {
            val hdfc = newAccount("HDFC Savings")
            val cash = newAccount("Cash Wallet")
            create(hdfc, -250_00L)
            create(cash, -900_00L)

            val totals = repository.observeDayTotals(TransactionFilter(accountId = hdfc)).first()

            assertEquals(Money(-250_00L), totals.getValue(clock.today().toString()))
        }

    // --- chips -------------------------------------------------------------------------------------

    @Test
    fun `the source chips come from the whole ledger, in enum order`() =
        runTest {
            val account = newAccount()
            create(account, -250_00L)
            database.transactionDao().upsert(
                rawTransaction("txn:ocr", account, TransactionSource.OCR.storedValue),
            )

            assertEquals(
                listOf(TransactionSource.MANUAL, TransactionSource.OCR),
                repository.observeSources().first(),
            )
        }

    @Test
    fun `an unreadable stored source is dropped from the chips rather than thrown on`() =
        runTest {
            val account = newAccount()
            create(account, -250_00L)
            database.transactionDao().upsert(rawTransaction("txn:future", account, "account_aggregator"))

            assertEquals(listOf(TransactionSource.MANUAL), repository.observeSources().first())
        }

    // --- fixtures ----------------------------------------------------------------------------------

    /** Result: a live account's id. Input: [name]. Output: [String]. */
    private suspend fun newAccount(name: String = "HDFC Savings"): String {
        val accounts =
            RepositoryFactory.accounts(
                database,
                clock,
                ids,
                TestDispatchers(UnconfinedTestDispatcher()),
                activeProfileId,
            )
        return accounts.create(
            AccountDraft(
                name = name,
                type = AccountType.BANK,
                openingBalance = Money(100_000_00L),
                currencyCode = "INR",
            ),
        ).expectOk().id
    }

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
        merchant: String? = null,
        note: String? = null,
        categoryId: String? = null,
    ): String =
        repository.create(
            TransactionDraft(
                accountId = accountId,
                amount = Money(amountMinor),
                categoryId = categoryId,
                merchant = merchant,
                note = note,
            ),
        ).expectOk().id

    /**
     * Result: a transaction booked on a given day, written straight to the DAO. Output: [String].
     *
     * Bypasses the repository because `create` refuses a booked day in the past (issue 3.4), and a
     * date-range test needs history on both sides of its bounds.
     */
    private suspend fun createOn(
        accountId: String,
        amountMinor: Long,
        bookedOn: java.time.LocalDate,
        note: String? = null,
    ): String {
        val id = "txn:${bookedOn}_${note.orEmpty()}"
        database.transactionDao().upsert(
            rawTransaction(id, accountId, TransactionSource.MANUAL.storedValue)
                .copy(amountMinor = amountMinor, bookedOnIsoDate = bookedOn.toString(), note = note),
        )
        return id
    }

    /**
     * Result: a transaction written straight to the DAO, bypassing the repository — for rows it
     *         could not itself create. Input: [id], [accountId], [source]. Output: the entity.
     */
    private fun rawTransaction(
        id: String,
        accountId: String,
        source: String,
    ) = com.aicfo.core.database.entity.TransactionEntity(
        id = id,
        profileId = REAL_PROFILE,
        accountId = accountId,
        amountMinor = -100_00L,
        currencyCode = "INR",
        occurredAtUtcMillis = clock.nowUtcMillis(),
        bookedOnIsoDate = clock.today().toString(),
        source = source,
        type = TransactionType.EXPENSE.storedValue,
        createdAtUtcMillis = clock.nowUtcMillis(),
        updatedAtUtcMillis = clock.nowUtcMillis(),
    )

    private companion object {
        const val REAL_PROFILE = "local"
        const val DEMO_PROFILE = "demo"

        /**
         * More rows than one page holds, so a page boundary is genuinely crossed.
         *
         * Derived from `TransactionRepository.PAGE_SIZE` rather than written as a literal: a later
         * change to the page size must not quietly turn these into single-page tests, which would
         * pass while asserting nothing about paging at all.
         */
        val PAGE_AND_A_HALF = TransactionRepository.PAGE_SIZE + TransactionRepository.PAGE_SIZE / 2
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
