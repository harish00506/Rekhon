package com.aicfo.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.FakeIdGenerator
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.TestDispatchers
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.budget.BudgetAlert
import com.aicfo.domain.engines.budget.BudgetAlertInput
import com.aicfo.domain.engines.budget.BudgetEngine
import com.aicfo.domain.engines.budget.BudgetEngineFactory
import com.aicfo.domain.engines.budget.BudgetReview
import com.aicfo.domain.engines.budget.BudgetReviewInput
import com.aicfo.domain.engines.budget.BudgetStatus
import com.aicfo.domain.engines.budget.BudgetStatusInput
import com.aicfo.domain.engines.budget.BudgetSuggestion
import com.aicfo.domain.engines.budget.BudgetSuggestionInput
import com.aicfo.domain.engines.classification.ClassificationEngineFactory
import com.aicfo.domain.engines.nature.NatureEngineFactory
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
import java.time.LocalDate

/**
 * What the budget repository does when the engine refuses (issue 4.7; §21.6).
 *
 * Why:  every one of `BudgetEngine`'s four operations is `runCatchingToResult`, so every one can
 *       return `Err` — and until 4.7 all four call sites unwrapped with `(x as Ok).value`, turning
 *       that `Err` into a `ClassCastException` thrown from inside a `combine` transform. Nothing
 *       tested it, because the real engine does not fail for the fixtures the sibling suite feeds
 *       it. **The behaviour under a failing engine is only reachable with a failing engine**, which
 *       is what this file supplies.
 * What: one stub that fails exactly one operation while delegating the rest to the real engine, and
 *       an assertion per path about what a consumer actually sees.
 * Result: the rule stated in `BudgetRepository`'s doc comment is a tested claim rather than a
 *       written intention — including the half that matters most, that a throw never escapes a
 *       suspend function promising a `Result`.
 * Changelog: 2026-08-16 — Created for issue 4.7.
 *
 * **A sibling file rather than a second fixture in `BudgetRepositoryTest`.** That suite's KDoc
 * commits it to the *real* engines ("a stub could not make [the claim]"), and this one needs the
 * opposite. Splitting is this module's established shape for exactly that — `SplitTest`,
 * `TransferTest`, `BulkEditTest` and `CategorySuggestionTest` all came off the same parent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BudgetEngineFailureTest {
    private lateinit var database: CfoDatabase
    private lateinit var transactions: TransactionRepository
    private lateinit var accounts: AccountRepository
    private lateinit var categories: CategoryRepository
    private lateinit var account: String

    // The same instant the sibling suite fixes, so July is the closed month a review looks at.
    private val clock = FakeClock(initialMillis = Instant.parse("2026-08-15T06:00:00Z").toEpochMilli())
    private val ids = FakeIdGenerator()
    private val dispatcher = UnconfinedTestDispatcher()
    private val activeProfileId = MutableStateFlow(REAL_PROFILE)

    /** Input: none. Output: a database, a taxonomy and an account — but no budget repository yet. */
    @Before
    fun setUp() =
        runTest(dispatcher) {
            database =
                Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    CfoDatabase::class.java,
                ).allowMainThreadQueries().build()
            val dispatchers = TestDispatchers(dispatcher)
            transactions =
                RepositoryFactory.transactions(
                    database, clock, ids, dispatchers, activeProfileId, ClassificationEngineFactory.create(),
                    NatureEngineFactory.create(),
                )
            accounts = RepositoryFactory.accounts(database, clock, ids, dispatchers, activeProfileId)
            categories = RepositoryFactory.categories(database, clock, ids, dispatchers, activeProfileId)
            categories.ensureSeeded()
            account =
                accounts.create(
                    AccountDraft(
                        name = "HDFC Savings",
                        type = AccountType.BANK,
                        openingBalance = Money(500_000_00L),
                        currencyCode = "INR",
                    ),
                ).expectOk().id
        }

    /** Input: none. Output: closes the database between tests. */
    @After
    fun tearDown() {
        database.close()
    }

    // --- status: the one that fails loudly ------------------------------------------------------

    /**
     * Input:  a status engine that refuses.
     * Output: asserts the budgets stream fails with [BudgetEngineFailure] carrying the engine's own
     *         `AppError` — **not** a `ClassCastException`, which is what the unchecked cast produced
     *         and which named the wrong file to go looking in. This is the site with no `Ok(null)`
     *         in its contract, so it has nowhere to be absent.
     */
    @Test
    fun `a refused status fails the budgets stream with the engine's error, not a ClassCastException`() =
        runTest(dispatcher) {
            val budgets = repositoryWith(PartlyFailingBudgetEngine(failStatus = true))

            val thrown = runCatching { budgets.observeBudgets().first() }.exceptionOrNull()

            assertTrue("expected the engine's own failure, got $thrown", thrown is BudgetEngineFailure)
            assertEquals(ENGINE_FAILURE, (thrown as BudgetEngineFailure).appError)
            assertTrue("a ClassCastException is the bug 4.7 removed", thrown !is ClassCastException)
        }

    /**
     * Input:  the same refusal, reached through a **suspend** reader instead of a Flow collector.
     * Output: asserts it arrives as `Err`, never as an escaped throw. This is the assertion the whole
     *         design rests on: `categoryBudget` throws on purpose, and §21.6 only holds because
     *         `runCatchingToResult` catches a plain `Exception` and converts it. Had
     *         [BudgetEngineFailure] extended `IllegalStateException` — as `error(...)` does — this
     *         would sail into `viewModelScope` and `CoroutineWorker` as a crash.
     */
    @Test
    fun `a refused status reaches a suspend reader as Err, never as an escaped throw`() =
        runTest(dispatcher) {
            val budgets = repositoryWith(PartlyFailingBudgetEngine(failStatus = true))

            val outcome = runCatching { budgets.pendingAlerts() }

            assertTrue("the throw escaped a Result-returning API", outcome.isSuccess)
            assertTrue("expected Err, got ${outcome.getOrNull()}", outcome.getOrNull() is Err)
        }

    // --- alert, review, suggest: the three that fail quietly -------------------------------------

    /**
     * Input:  a budget genuinely over its band, with an alert engine that refuses.
     * Output: asserts the alert list is empty **and the budget row still carries its real figures**.
     *         The second half is what makes the design defensible rather than merely cheap: a user
     *         whose band could not be decided is reading ₹9,200 of ₹10,000 without a chip on it, not
     *         staring at a blank screen.
     */
    @Test
    fun `a refused alert empties the band list and leaves the figures standing`() =
        runTest(dispatcher) {
            val budgets = repositoryWith(PartlyFailingBudgetEngine(failAlert = true))
            budgets.setBudget(idOf("Groceries"), Money(10_000_00L), rolloverEnabled = false).expectOk()
            expense(Money(-9_200_00L), categoryId = idOf("Groceries"))

            assertTrue("an undecidable band must be absent", budgets.observeAlerts().first().isEmpty())

            val row = budgets.observeBudgets().first().first { it.category.name == "Groceries" }
            assertEquals(Money(9_200_00L), row.status.spent)
            assertEquals(Money(10_000_00L), row.status.budgeted)
        }

    /**
     * Input:  the same refusal, through the synchronous entry point the dashboard uses (issue 5.1).
     * Output: asserts `null` rather than a throw. There is no Flow between this caller and the
     *         repository, so nothing downstream could have caught one.
     */
    @Test
    fun `alertFor returns null for a row that would otherwise be in a band`() =
        runTest(dispatcher) {
            val budgets = repositoryWith(PartlyFailingBudgetEngine(failAlert = true))
            budgets.setBudget(idOf("Groceries"), Money(10_000_00L), rolloverEnabled = false).expectOk()
            expense(Money(-9_200_00L), categoryId = idOf("Groceries"))
            val row = budgets.observeBudgets().first().first { it.category.name == "Groceries" }

            assertNull(budgets.alertFor(row))
        }

    /**
     * Input:  a crossed budget whose alert engine refuses, read the way the worker reads it.
     * Output: asserts `Ok` with nothing to send, **not** `Err`. Pins the contract change 4.7 makes:
     *         `BudgetAlertWorker` maps `Err` to `Result.retry()`, so the old behaviour retried a
     *         deterministic failure once a day for the rest of the month, silently and forever.
     */
    @Test
    fun `a refused alert leaves the worker with nothing to send rather than a permanent retry`() =
        runTest(dispatcher) {
            val budgets = repositoryWith(PartlyFailingBudgetEngine(failAlert = true))
            budgets.setBudget(idOf("Groceries"), Money(10_000_00L), rolloverEnabled = false).expectOk()
            expense(Money(-9_200_00L), categoryId = idOf("Groceries"))

            val pending = budgets.pendingAlerts()

            assertTrue("a retry loop is worse than a no-op", pending is Ok)
            assertTrue((pending as Ok).value.isEmpty())
        }

    /**
     * Input:  a closed month worth reviewing, with a review engine that refuses.
     * Output: asserts no card rather than a failed stream — the third meaning of this `null`, beside
     *         "nothing was budgeted" and "already dismissed".
     */
    @Test
    fun `a refused review shows no card rather than failing the stream`() =
        runTest(dispatcher) {
            val budgets = repositoryWith(PartlyFailingBudgetEngine(failReview = true))
            writeJulyBudget(idOf("Groceries"), Money(10_000_00L))
            expense(Money(-13_000_00L), categoryId = idOf("Groceries"), isoDate = "2026-07-10")

            assertNull(budgets.observeReview().first())
        }

    /**
     * Input:  three months of history, with a suggestion engine that refuses.
     * Output: asserts no offer rather than a failed stream. The engine already answers `Ok(null)`
     *         for want of history at this same call, so a refusal lands in a lane that existed.
     */
    @Test
    fun `a refused suggestion offers nothing rather than failing the stream`() =
        runTest(dispatcher) {
            val budgets = repositoryWith(PartlyFailingBudgetEngine(failSuggest = true))
            expense(Money(-8_000_00L), categoryId = idOf("Groceries"), isoDate = "2026-05-10")
            expense(Money(-9_000_00L), categoryId = idOf("Groceries"), isoDate = "2026-06-10")
            expense(Money(-8_500_00L), categoryId = idOf("Groceries"), isoDate = "2026-07-10")

            assertTrue(budgets.observeSuggestions().first().isEmpty())
        }

    // --- the accept paths, which read those streams before they write ---------------------------

    /**
     * Input:  an accept for a suggestion the engine cannot produce.
     * Output: asserts `NotFound` — the same refusal a category nothing was proposed for already
     *         gets, and emphatically not a crash on `viewModelScope`.
     */
    @Test
    fun `accepting a suggestion the engine cannot produce is NotFound, not a crash`() =
        runTest(dispatcher) {
            val budgets = repositoryWith(PartlyFailingBudgetEngine(failSuggest = true))
            expense(Money(-8_000_00L), categoryId = idOf("Groceries"), isoDate = "2026-05-10")
            expense(Money(-9_000_00L), categoryId = idOf("Groceries"), isoDate = "2026-06-10")
            expense(Money(-8_500_00L), categoryId = idOf("Groceries"), isoDate = "2026-07-10")

            val outcome = runCatching { budgets.acceptSuggestion(idOf("Groceries")) }

            assertTrue("the throw escaped a Result-returning API", outcome.isSuccess)
            assertEquals(AppError.NotFound, (outcome.getOrNull() as Err).error)
        }

    /**
     * Input:  an accept for a review proposal the engine cannot produce.
     * Output: asserts `NotFound`, for the reason above. This is the exact call whose unguarded
     *         version crashed before 2026-08-16.
     */
    @Test
    fun `accepting a review proposal the engine cannot produce is NotFound, not a crash`() =
        runTest(dispatcher) {
            val budgets = repositoryWith(PartlyFailingBudgetEngine(failReview = true))
            writeJulyBudget(idOf("Groceries"), Money(10_000_00L))
            expense(Money(-13_000_00L), categoryId = idOf("Groceries"), isoDate = "2026-07-10")

            val outcome = runCatching { budgets.acceptReviewProposal(idOf("Groceries")) }

            assertTrue("the throw escaped a Result-returning API", outcome.isSuccess)
            assertEquals(AppError.NotFound, (outcome.getOrNull() as Err).error)
        }

    // --- helpers ------------------------------------------------------------------------------------

    /** Result: a repository over the test database, with [engine] in place of the real one. */
    private fun repositoryWith(engine: BudgetEngine): BudgetRepository =
        RepositoryFactory.budgets(database, engine, clock, TestDispatchers(dispatcher), activeProfileId)

    private suspend fun expense(
        amount: Money,
        categoryId: String? = null,
        isoDate: String? = null,
    ): String =
        transactions.create(
            TransactionDraft(
                accountId = account,
                amount = amount,
                categoryId = categoryId,
                bookedOn = isoDate?.let(LocalDate::parse),
            ),
        ).expectOk().id

    private suspend fun idOf(name: String): String = categories.observeCategories().first().first { it.name == name }.id

    /** Writes a July budget directly, since `setBudget` can only reach the current month. */
    private suspend fun writeJulyBudget(
        categoryId: String,
        amount: Money,
    ) {
        val period = "2026-07-01"
        database.budgetDao().upsert(
            com.aicfo.core.database.entity.BudgetEntity(
                id = categoryBudgetId(REAL_PROFILE, categoryId, period),
                profileId = REAL_PROFILE,
                categoryId = categoryId,
                periodStartIsoDate = period,
                amountMinor = amount.minor,
                rolloverEnabled = false,
                source = BudgetRepository.SOURCE_MANUAL,
                createdAtUtcMillis = clock.nowUtcMillis(),
                updatedAtUtcMillis = clock.nowUtcMillis(),
            ),
        )
    }

    private companion object {
        const val REAL_PROFILE = "local"

        /**
         * The only error these four methods can actually return (issue 4.7).
         *
         * `runCatchingToResult` **rethrows** `IllegalStateException`/`IllegalArgumentException`, so
         * an engine `require`/`check` crashes rather than becoming `Err`. `:domain:engines:budget`
         * is pure Kotlin with no I/O and no crypto, which rules out `Storage` and `Crypto` too. What
         * is left is an `ArithmeticException` from `Math.addExact` on an amount near `Long.MAX_VALUE`
         * — arriving, as here, wrapped in `Unexpected`.
         */
        val ENGINE_FAILURE = AppError.Unexpected("ArithmeticException")
    }
}

/**
 * A [BudgetEngine] that refuses exactly one operation and really computes the rest (issue 4.7).
 *
 * Why:  the claims under test are all of the form "*this* degraded and *the rest still worked*" — a
 *       band vanished while its figures stayed, a review disappeared while the budgets list did not.
 *       A wholly-dead stub could not distinguish those from a repository that simply returned
 *       nothing, so every operation a test has not flagged delegates to the real engine.
 * What: per-operation failure flags over a real [BudgetEngineFactory] instance.
 * Result: one failing engine call at a time, which is the only shape that proves isolation.
 * Changelog: 2026-08-16 — Created for issue 4.7.
 *
 * Follows the `FailingPinCredentialStore`/`FailingStore` shape `:core:crypto` and `:core:database`
 * tests already use for the same job.
 *
 * Input:  [failStatus], [failAlert], [failReview], [failSuggest]. Output: a [BudgetEngine].
 */
private class PartlyFailingBudgetEngine(
    private val failStatus: Boolean = false,
    private val failAlert: Boolean = false,
    private val failReview: Boolean = false,
    private val failSuggest: Boolean = false,
) : BudgetEngine {
    private val real: BudgetEngine = BudgetEngineFactory.create()

    override fun suggest(input: BudgetSuggestionInput): Result<BudgetSuggestion?, AppError> =
        if (failSuggest) Err(AppError.Unexpected("ArithmeticException")) else real.suggest(input)

    override fun status(input: BudgetStatusInput): Result<BudgetStatus, AppError> =
        if (failStatus) Err(AppError.Unexpected("ArithmeticException")) else real.status(input)

    override fun alert(input: BudgetAlertInput): Result<BudgetAlert?, AppError> =
        if (failAlert) Err(AppError.Unexpected("ArithmeticException")) else real.alert(input)

    override fun review(input: BudgetReviewInput): Result<BudgetReview?, AppError> =
        if (failReview) Err(AppError.Unexpected("ArithmeticException")) else real.review(input)
}

/**
 * Unwraps a result the test expects to have succeeded.
 * Result: the value. Input: the receiver. Output: [T]; throws [AssertionError] on an `Err`.
 * Changelog: 2026-08-16 — Created for issue 4.7, matching the sibling suites' file-private helper.
 */
private fun <T> Result<T, AppError>.expectOk(): T =
    when (this) {
        is Ok -> value
        is Err -> throw AssertionError("expected Ok, got Err(${error.code})")
    }
