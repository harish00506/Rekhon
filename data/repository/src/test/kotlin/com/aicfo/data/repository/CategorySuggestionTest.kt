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
import com.aicfo.domain.engines.classification.ClassificationEngineFactory
import com.aicfo.domain.engines.nature.NatureEngineFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * The data half of Stage-1 auto-categorisation — issue 4.2 (SRS §8.1, ARC-005).
 *
 * Why:  `ClassificationEngineTest` already proves the precedence chain against fixtures, so
 *       re-proving it here would assert nothing new. **What is unproven above SQLite is the
 *       history.** `categoryCountsForMerchant` is the query that turns a ledger into §8.1(a)'s
 *       "user's correction history", and every clause of its `WHERE` is a decision that can only be
 *       tested against a real SQL engine:
 *
 *       - the merchant is matched **normalised on both sides**, so `Swiggy` and `SWIGGY ` are one
 *         merchant. Get this wrong and tier (a) silently never fires, while every engine test keeps
 *         passing;
 *       - a **deleted** transaction teaches nothing, because the user removed it;
 *       - a **split** teaches nothing, because it is the user saying the merchant is several things;
 *       - the **demo profile** teaches a real profile nothing (ADR-0006).
 * What: the seam from the ledger to the engine, and the two ends of it agreeing.
 * Result: the tier that outranks the shipped knowledge base is proven to read what it claims to.
 * Changelog: 2026-08-10 — Created for issue 4.2.
 *
 * Unencrypted in-memory Room, deliberately — the same reasoning as [TransactionRepositoryTest]:
 * what is under test is the SQL, not SQLCipher. The **real** engine rather than a stub, for the
 * reason those tests take the real `AccountRepository`: the claim is that a suggestion arrives on
 * the screen, and a stub engine could not make it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CategorySuggestionTest {
    private lateinit var database: CfoDatabase
    private lateinit var repository: TransactionRepository
    private lateinit var accounts: AccountRepository
    private lateinit var categories: CategoryRepository

    private val clock = FakeClock(initialMillis = Instant.parse("2026-08-10T06:00:00Z").toEpochMilli())
    private val ids = FakeIdGenerator()
    private val activeProfileId = MutableStateFlow(REAL_PROFILE)

    /** Input: none. Output: a fresh in-memory database, the three repositories, and a seeded taxonomy. */
    @Before
    fun setUp() =
        runTest {
            database =
                Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    CfoDatabase::class.java,
                ).allowMainThreadQueries().build()
            val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
            repository =
                RepositoryFactory.transactions(
                    database, clock, ids, dispatchers, activeProfileId, ClassificationEngineFactory.create(),
                    NatureEngineFactory.create(),
                )
            accounts = RepositoryFactory.accounts(database, clock, ids, dispatchers, activeProfileId)
            categories = RepositoryFactory.categories(database, clock, ids, dispatchers, activeProfileId)
            // The real seed, not hand-written rows: the knowledge base's rules resolve by category
            // *name*, so a taxonomy invented here could pass while the shipped one failed.
            categories.ensureSeeded()
        }

    /** Input: none. Output: closes the database between tests. */
    @After
    fun tearDown() {
        database.close()
    }

    // --- the knowledge-base tier, end to end --------------------------------------------------------

    /**
     * Input:  a merchant the shipped rules cover, on a freshly seeded profile.
     * Output: the seeded Dining category, cited by the rule that named it. This is the whole of
     *         ADR-0014's open item closing: a `CLS-MER-*` id that finally resolves to something.
     */
    @Test
    fun `a shipped rule resolves to the seeded category it names`() =
        runTest {
            val suggestion = repository.suggestCategory("SWIGGY*ORDER 7781").expectOk()

            assertNotNull("the knowledge base proposed nothing for a merchant it covers", suggestion)
            assertEquals("Dining", nameOf(suggestion!!.categoryId))
            assertEquals(listOf("CLS-MER-001"), suggestion.provenance.evidence.map { it.ruleId })
        }

    /** Input: a merchant no rule covers and no history mentions. Output: nothing, and no error. */
    @Test
    fun `an unknown merchant proposes nothing`() =
        runTest {
            assertNull(repository.suggestCategory("SHARMA GENERAL STORE").expectOk())
        }

    /** Input: a blank merchant. Output: nothing, without touching the database. */
    @Test
    fun `a blank merchant proposes nothing`() =
        runTest {
            assertNull(repository.suggestCategory("   ").expectOk())
        }

    /**
     * Input:  a suggestion for a merchant whose seeded category has since been deleted.
     * Output: nothing. The engine is handed the *live* taxonomy, so a rule cannot resurrect a
     *         category the user removed (P-07) — and this is the only test that proves the
     *         repository passes the live list rather than the seed.
     */
    @Test
    fun `a deleted category is not proposed`() =
        runTest {
            categories.delete(idOf("Dining"))

            assertNull(repository.suggestCategory("SWIGGY*ORDER 7781").expectOk())
        }

    // --- the history tier ---------------------------------------------------------------------------

    /**
     * §8.1's precedence, proven through the database rather than through a fixture.
     * Input:  one past Swiggy transaction the user filed under Groceries — they order instamart,
     *         not dinner.
     * Output: Groceries, citing `CLS-USER-HISTORY` — the user's own filing outranks `CLS-MER-001`,
     *         which would have said Dining and does say Dining for this exact string in
     *         `a shipped rule resolves to the seeded category it names` above.
     */
    @Test
    fun `a past correction outranks the shipped rule`() =
        runTest {
            val account = newAccount()
            repository.create(draft(account.id, merchant = "SWIGGY*ORDER 7781", categoryId = idOf("Groceries")))

            val suggestion = repository.suggestCategory("SWIGGY*ORDER 7781").expectOk()

            assertEquals("Groceries", nameOf(suggestion!!.categoryId))
            assertEquals(listOf("CLS-USER-HISTORY"), suggestion.provenance.evidence.map { it.ruleId })
        }

    /**
     * The limitation of an exact lookup, pinned so it is a decision rather than a surprise.
     *
     * Why:    §8.1(a) asks for an **exact** (normalised) merchant lookup, and this is what it costs:
     *         a card descriptor that carries an order number is a *different* merchant string every
     *         time, so filing one teaches the next one nothing and Stage 1 falls through to the
     *         knowledge base. It matters least where it would hurt most — a typed merchant and an
     *         SMS counterparty both repeat verbatim — and fixing it means the fuzzy matching §8.1(c)
     *         describes, which is a model, not a `LIKE`. Written down in ENGINE.md.
     * Input:  a filing under one descriptor, asked for under another from the same merchant.
     * Output: the knowledge base's answer, not the user's — and this test is what would notice if
     *         someone loosened the query to `LIKE` without deciding to.
     */
    @Test
    fun `a different descriptor from the same merchant is a different merchant`() =
        runTest {
            val account = newAccount()
            repository.create(draft(account.id, merchant = "SWIGGY*ORDER 7781", categoryId = idOf("Groceries")))

            val suggestion = repository.suggestCategory("SWIGGY*ORDER 9902").expectOk()

            assertEquals("Dining", nameOf(suggestion!!.categoryId))
            assertEquals(listOf("CLS-MER-001"), suggestion.provenance.evidence.map { it.ruleId })
        }

    /**
     * The one that fails silently if the two normalisations ever diverge.
     * Input:  a merchant stored in one case and asked for in another, with stray whitespace.
     * Output: still found. `normaliseMerchant` builds the query argument and `LOWER(TRIM(merchant))`
     *         is the SQL half of the same rule; if either side changed alone, tier (a) would stop
     *         firing and nothing but this test would notice.
     */
    @Test
    fun `the merchant is matched regardless of case and surrounding space`() =
        runTest {
            val account = newAccount()
            repository.create(draft(account.id, merchant = "  Sharma General Store  ", categoryId = idOf("Groceries")))

            val suggestion = repository.suggestCategory("SHARMA GENERAL STORE").expectOk()

            assertEquals("Groceries", nameOf(suggestion!!.categoryId))
        }

    /**
     * Input:  a past filing the user then deleted.
     * Output: nothing. A deleted transaction is a decision withdrawn, and learning from it would
     *         make the delete button fail to undo what it appears to undo.
     */
    @Test
    fun `a deleted transaction teaches nothing`() =
        runTest {
            val account = newAccount()
            val created =
                repository.create(draft(account.id, merchant = "SHARMA GENERAL STORE", categoryId = idOf("Groceries")))
                    .expectOk()
            repository.delete(created.id)

            assertNull(repository.suggestCategory("SHARMA GENERAL STORE").expectOk())
        }

    /**
     * Input:  a split transaction at a merchant, its lines carrying two categories.
     * Output: nothing. The parent carries the merchant and no category; the lines are deliberately
     *         not joined in, because a split is the user saying this merchant is several things at
     *         once — evidence against a single suggestion rather than for one.
     */
    @Test
    fun `a split teaches nothing`() =
        runTest {
            val account = newAccount()
            repository.createSplit(
                SplitDraft(
                    accountId = account.id,
                    amount = Money(-1_000_00L),
                    lines =
                        listOf(
                            SplitLineDraft(Money(-600_00L), idOf("Groceries")),
                            SplitLineDraft(Money(-400_00L), idOf("Dining")),
                        ),
                    merchant = "SHARMA GENERAL STORE",
                ),
            )

            assertNull(repository.suggestCategory("SHARMA GENERAL STORE").expectOk())
        }

    /**
     * Input:  a filing made under the demo profile, asked for from the real one.
     * Output: nothing. Exploring the sample data must not teach the user's own ledger anything
     *         (ADR-0006), and profile scoping is a `WHERE` clause that is easy to omit and
     *         impossible to notice.
     */
    @Test
    fun `the demo profile teaches the real one nothing`() =
        runTest {
            activeProfileId.value = DEMO_PROFILE
            categories.ensureSeeded()
            val demoAccount = newAccount()
            repository.create(
                draft(demoAccount.id, merchant = "SHARMA GENERAL STORE", categoryId = idOf("Groceries")),
            )
            activeProfileId.value = REAL_PROFILE

            assertNull(repository.suggestCategory("SHARMA GENERAL STORE").expectOk())
        }

    // --- helpers ------------------------------------------------------------------------------------

    /** Result: a live account to book against. Input: none. Output: the created account. */
    private suspend fun newAccount() =
        accounts.create(
            AccountDraft(
                name = "HDFC Savings",
                type = AccountType.BANK,
                openingBalance = Money(50_000_00L),
                currencyCode = "INR",
            ),
        ).expectOk()

    /**
     * Result: an expense draft. Input: [accountId]; [merchant]; [categoryId]. Output: [TransactionDraft].
     */
    private fun draft(
        accountId: String,
        merchant: String,
        categoryId: String,
    ) = TransactionDraft(
        accountId = accountId,
        amount = Money(-500_00L),
        categoryId = categoryId,
        merchant = merchant,
    )

    /** Result: the live category with this display name. Input: [name]. Output: its id. */
    private suspend fun idOf(name: String): String = repository.observeCategories().first().first { it.name == name }.id

    /** Result: the display name of a category id, for readable assertions. Output: [String]. */
    private suspend fun nameOf(categoryId: String): String =
        repository.observeCategories().first().first { it.id == categoryId }.name

    private companion object {
        const val REAL_PROFILE = "local"
        const val DEMO_PROFILE = "demo"
    }
}

/**
 * Unwraps a result the test expects to have succeeded.
 * Why:    an `Err` here is a test failure, and `as Ok` would report it as a `ClassCastException`
 *         naming neither the code nor the call — the same helper every repository test in this
 *         module carries, kept file-private so each states its own reason.
 * Result: the value. Input: the receiver. Output: [T]; throws [AssertionError] on an `Err`.
 * Changelog: 2026-08-10 — Created for issue 4.2.
 */
private fun <T> com.aicfo.core.common.Result<T, AppError>.expectOk(): T =
    when (this) {
        is Ok -> value
        is Err -> throw AssertionError("expected Ok, got Err(${error.code})")
    }
