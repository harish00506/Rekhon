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
import com.aicfo.domain.engines.budget.BudgetAlertBand
import com.aicfo.domain.engines.budget.BudgetEngineFactory
import com.aicfo.domain.engines.budget.VarianceDirection
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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * The data half of budgets — issue 4.4 (SRS §5.5, ARC-005).
 *
 * Why:  `BudgetEngineTest` and the golden file already prove the arithmetic against fixtures, so
 *       repeating it here would assert nothing new. **What is unproven above SQLite is the spend
 *       total**, and it is the input every figure on the screen is derived from. It has four ways to
 *       be wrong while still returning plausible rows:
 *
 *       - a **split** transaction counted by its parent (which has no category) or, worse, counted
 *         twice — once as the parent and once as its lines;
 *       - a **transfer** counted as spending, which would make any month with a rebalance unusable;
 *       - a **soft-deleted** row or line still counted, since deletion is a column here (DB-002);
 *       - a **future-dated** row counted as an actual, against FR-TXN-010.
 *
 *       Every one of those produces a number that looks like a number. None of them throws.
 * What: the spend query's four exclusions, the parent/child rollup, rollover across a month
 *       boundary, and the write paths' provenance.
 * Result: the first per-category spend total in the app is proven against a real SQL engine.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 *            2026-08-15 — Issue 4.6: added the monthly review cases.
 *
 * Unencrypted in-memory Room and the **real** engines rather than stubs, the same reasoning
 * [NatureRepositoryTest] gives: the claim is that a budget figure reaches the screen, and a stub
 * could not make it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BudgetRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var budgets: BudgetRepository
    private lateinit var transactions: TransactionRepository
    private lateinit var accounts: AccountRepository
    private lateinit var categories: CategoryRepository
    private lateinit var account: String

    // Mid-month on purpose, and on a 31-day month: the pace figures divide by the month's length,
    // and a clock on the 1st or the last day would let an off-by-one bound pass unnoticed.
    private val clock = FakeClock(initialMillis = Instant.parse("2026-08-15T06:00:00Z").toEpochMilli())
    private val ids = FakeIdGenerator()

    // One dispatcher, one scheduler, shared with every `runTest` below. `observeBudgets` combines
    // three Flows behind a `flowOn`, and combine dispatches across that boundary — which makes a
    // per-test scheduler and a per-fixture one visible to each other, and kotlinx-coroutines-test
    // refuses that outright. The older repository tests here only `map` behind `flowOn`, which never
    // crosses, so they get away with constructing the dispatcher inline.
    private val dispatcher = UnconfinedTestDispatcher()
    private val activeProfileId = MutableStateFlow(REAL_PROFILE)

    /** Input: none. Output: a fresh in-memory database, the repositories, a taxonomy and an account. */
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
            budgets =
                RepositoryFactory.budgets(
                    database, BudgetEngineFactory.create(), clock, dispatchers, activeProfileId,
                )
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

    // --- the spend total, and the four ways it can be wrong ------------------------------------

    /**
     * Input:  two ordinary grocery expenses.
     * Output: their sum, positive. The simplest path, and the one that proves the query reaches the
     *         screen at all — every exclusion below is only meaningful if this works.
     */
    @Test
    fun `spending in a category is summed, as a positive amount`() =
        runTest(dispatcher) {
            expense(Money(-1_200_00L), categoryId = idOf("Groceries"))
            expense(Money(-800_00L), categoryId = idOf("Groceries"))

            assertEquals(Money(2_000_00L), spentOn("Groceries"))
        }

    /**
     * The reason this issue touched the DAO at all (ADR-0009, ADR-0018).
     * Input:  one ₹4,000 payment split ₹3,000 groceries / ₹1,000 dining.
     * Output: each budget sees its own line and **the parent is not counted at all**. Reading the
     *         parent's `category_id` would credit ₹0 to both, because a split transaction has none —
     *         which would make every split invisible to the feature whose job is to notice spending.
     */
    @Test
    fun `a split payment is counted by its lines, not by its parent`() =
        runTest(dispatcher) {
            transactions.createSplit(
                SplitDraft(
                    accountId = account,
                    amount = Money(-4_000_00L),
                    lines =
                        listOf(
                            SplitLineDraft(amount = Money(-3_000_00L), categoryId = idOf("Groceries")),
                            SplitLineDraft(amount = Money(-1_000_00L), categoryId = idOf("Dining")),
                        ),
                ),
            ).expectOk()

            assertEquals(Money(3_000_00L), spentOn("Groceries"))
            assertEquals(Money(1_000_00L), spentOn("Dining"))
            // The total across every category is the payment, once — the double-count check.
            assertEquals(Money(4_000_00L), totalSpend())
        }

    /**
     * Input:  a transfer between two of the user's own accounts.
     * Output: no spending anywhere. Moving ₹50,000 from savings to current is not consumption, and a
     *         budget that counted it would be unusable in any month someone rebalances.
     */
    @Test
    fun `a transfer between the user's own accounts is not spending`() =
        runTest(dispatcher) {
            val second =
                accounts.create(
                    AccountDraft(
                        name = "ICICI Current",
                        type = AccountType.BANK,
                        openingBalance = Money.ZERO,
                        currencyCode = "INR",
                    ),
                ).expectOk()
            transactions.createTransfer(
                TransferDraft(fromAccountId = account, toAccountId = second.id, amount = Money(50_000_00L)),
            ).expectOk()

            assertEquals(Money.ZERO, totalSpend())
        }

    /**
     * Input:  a grocery expense, then deleted.
     * Output: nothing counted. Deletion is a column here (DB-002), so a query that forgot the clause
     *         would keep charging the user for a transaction they can no longer see.
     */
    @Test
    fun `a deleted transaction stops counting`() =
        runTest(dispatcher) {
            val id = expense(Money(-1_200_00L), categoryId = idOf("Groceries"))
            assertEquals(Money(1_200_00L), spentOn("Groceries"))

            transactions.delete(id).expectOk()

            assertEquals(Money.ZERO, spentOn("Groceries"))
        }

    /**
     * Input:  a split payment, then deleted.
     * Output: neither line counts. The lines are soft-deleted in their own table, so this is a
     *         second `deleted_at_utc_millis` clause — on the `UNION ALL`'s other leg, where forgetting
     *         it would leave a deleted payment charged to two budgets.
     */
    @Test
    fun `deleting a split payment stops both of its lines counting`() =
        runTest(dispatcher) {
            val id =
                transactions.createSplit(
                    SplitDraft(
                        accountId = account,
                        amount = Money(-4_000_00L),
                        lines =
                            listOf(
                                SplitLineDraft(amount = Money(-3_000_00L), categoryId = idOf("Groceries")),
                                SplitLineDraft(amount = Money(-1_000_00L), categoryId = idOf("Dining")),
                            ),
                    ),
                ).expectOk().id

            transactions.delete(id).expectOk()

            assertEquals(Money.ZERO, totalSpend())
        }

    /**
     * Input:  an expense dated later this month (FR-TXN-010).
     * Output: not counted as actual spending. The exclusion is the caller's window rather than a
     *         clause — `MonthWindow.actualsEndIsoDate` stops at today — so this pins the behaviour
     *         rather than the mechanism.
     */
    @Test
    fun `a future-dated transaction is not spent yet`() =
        runTest(dispatcher) {
            expense(Money(-1_200_00L), categoryId = idOf("Groceries"), isoDate = "2026-08-28")

            assertEquals(Money.ZERO, spentOn("Groceries"))
        }

    /**
     * Input:  income arriving in a budgeted category.
     * Output: not counted. A refund is `income`, and `ABS` exists to make an outflow positive — not
     *         to fold two directions into one total, which would let a refund read as spending.
     */
    @Test
    fun `income is not spending, whatever category it carries`() =
        runTest(dispatcher) {
            transactions.create(
                TransactionDraft(
                    accountId = account,
                    amount = Money(5_000_00L),
                    categoryId = idOf("Groceries"),
                ),
            ).expectOk()

            assertEquals(Money.ZERO, spentOn("Groceries"))
        }

    /** Input: spending on the demo profile. Output: the real profile sees none of it (ADR-0006). */
    @Test
    fun `one profile's spending never reaches another's budgets`() =
        runTest(dispatcher) {
            expense(Money(-1_200_00L), categoryId = idOf("Groceries"))
            activeProfileId.value = DEMO_PROFILE

            assertEquals(Money.ZERO, totalSpend())
        }

    // --- FR-BUD-004: alerts, and being told exactly once ----------------------------------------

    /**
     * Input:  a ₹10,000 budget with ₹8,000 spent, then ₹10,100.
     * Output: asserts the banner reports WARN, then EXCEEDED — and that it keeps reporting, because
     *         a crossed band stays true whether or not a notification went out (P-02).
     */
    @Test
    fun `a crossed band shows on the banner and follows the spending up`() =
        runTest(dispatcher) {
            budgets.setBudget(idOf("Groceries"), Money(10_000_00L), rolloverEnabled = false).expectOk()
            expense(Money(-8_000_00L), categoryId = idOf("Groceries"))

            assertEquals(BudgetAlertBand.WARN, alertFor("Groceries")!!.alert.band)

            expense(Money(-2_100_00L), categoryId = idOf("Groceries"))

            val exceeded = alertFor("Groceries")!!.alert
            assertEquals(BudgetAlertBand.EXCEEDED, exceeded.band)
            assertEquals(Money(100_00L), exceeded.overspentBy)
        }

    /** Input: a budget well inside its plan. Output: asserts nothing is alerted (no false positives). */
    @Test
    fun `a budget below the warn band raises nothing`() =
        runTest(dispatcher) {
            budgets.setBudget(idOf("Groceries"), Money(10_000_00L), rolloverEnabled = false).expectOk()
            expense(Money(-7_900_00L), categoryId = idOf("Groceries"))

            assertNull(alertFor("Groceries"))
        }

    /**
     * Input:  an unbudgeted category with spending in it.
     * Output: asserts silence. Every category appears in `observeBudgets` including the unbudgeted
     *         ones, so without the guard this would alert about a plan the user never made — on
     *         every category they have, at once.
     */
    @Test
    fun `an unbudgeted category is never alerted about`() =
        runTest(dispatcher) {
            expense(Money(-9_000_00L), categoryId = idOf("Groceries"))

            assertNull(alertFor("Groceries"))
            assertTrue(budgets.observeAlerts().first().isEmpty())
        }

    /**
     * Input:  a crossed band, claimed once and then claimed again.
     * Output: asserts the first claim succeeds and the second is refused — the once-per-band promise,
     *         tested at the seam the worker actually calls rather than at the index.
     */
    @Test
    fun `the same band can only be claimed once`() =
        runTest(dispatcher) {
            budgets.setBudget(idOf("Groceries"), Money(10_000_00L), rolloverEnabled = false).expectOk()
            expense(Money(-8_000_00L), categoryId = idOf("Groceries"))
            val alert = alertFor("Groceries")!!

            assertTrue("the first claim must go through", budgets.markNotified(alert).expectOk())
            assertTrue("the second must not", !budgets.markNotified(alert).expectOk())
            assertTrue("and nothing is pending afterwards", budgets.pendingAlerts().expectOk().isEmpty())
        }

    /**
     * Input:  a warn that has already been sent, then spending past 100%.
     * Output: asserts the second band is pending and claimable. This is the case a single "alerted"
     *         boolean on the budget row would have swallowed — and it is the more important of the
     *         two messages.
     */
    @Test
    fun `crossing the second band notifies again`() =
        runTest(dispatcher) {
            budgets.setBudget(idOf("Groceries"), Money(10_000_00L), rolloverEnabled = false).expectOk()
            expense(Money(-8_000_00L), categoryId = idOf("Groceries"))
            budgets.markNotified(alertFor("Groceries")!!).expectOk()

            expense(Money(-2_100_00L), categoryId = idOf("Groceries"))

            val pending = budgets.pendingAlerts().expectOk()
            assertEquals(1, pending.size)
            assertEquals(BudgetAlertBand.EXCEEDED, pending.single().alert.band)
            assertTrue(budgets.markNotified(pending.single()).expectOk())
        }

    /**
     * Input:  a band notified in August, then the clock moved into September.
     * Output: asserts it is pending again. The month is part of the key on purpose — "once per
     *         month" would otherwise be "once, ever", and a user would hear about their grocery
     *         budget in August and never again.
     */
    @Test
    fun `a new month makes the same band pending again`() =
        runTest(dispatcher) {
            budgets.setBudget(idOf("Groceries"), Money(10_000_00L), rolloverEnabled = false).expectOk()
            expense(Money(-8_000_00L), categoryId = idOf("Groceries"))
            budgets.markNotified(alertFor("Groceries")!!).expectOk()

            clock.advanceBy(Duration.ofDays(SEPTEMBER_DAYS_AHEAD))
            budgets.setBudget(idOf("Groceries"), Money(10_000_00L), rolloverEnabled = false).expectOk()
            expense(Money(-8_000_00L), categoryId = idOf("Groceries"), isoDate = "2026-09-10")

            val pending = budgets.pendingAlerts().expectOk()
            assertEquals("September's warn is a different alert from August's", 1, pending.size)
            assertEquals(BudgetAlertBand.WARN, pending.single().alert.band)
        }

    /**
     * Input:  a band claimed on the real profile, then a switch to the demo.
     * Output: asserts the demo sees nothing — neither the alert nor the claim (ADR-0006, P-01).
     */
    @Test
    fun `one profile's alerts never reach another`() =
        runTest(dispatcher) {
            budgets.setBudget(idOf("Groceries"), Money(10_000_00L), rolloverEnabled = false).expectOk()
            expense(Money(-8_000_00L), categoryId = idOf("Groceries"))
            budgets.markNotified(alertFor("Groceries")!!).expectOk()

            activeProfileId.value = DEMO_PROFILE

            assertTrue(budgets.observeAlerts().first().isEmpty())
            assertTrue(budgets.pendingAlerts().expectOk().isEmpty())
        }

    /**
     * Input:  a claimed alert.
     * Output: asserts the stored row cites the rule and version behind the band (AI-ARC-006, P-02).
     *         A record that a person was interrupted, without what interrupted them, is not an audit
     *         trail — it is a timestamp.
     */
    @Test
    fun `a claimed alert records the rule that fired`() =
        runTest(dispatcher) {
            budgets.setBudget(idOf("Groceries"), Money(10_000_00L), rolloverEnabled = false).expectOk()
            expense(Money(-8_000_00L), categoryId = idOf("Groceries"))
            budgets.markNotified(alertFor("Groceries")!!).expectOk()

            val row = database.budgetAlertDao().forMonth(REAL_PROFILE, CURRENT_PERIOD).single()

            assertEquals("RULE-BUD-ALERT", row.ruleId)
            assertEquals("1.0", row.ruleVersion)
            assertEquals(CURRENT_PERIOD, row.monthStartIsoDate)
            assertEquals(clock.nowUtcMillis(), row.notifiedAtUtcMillis)
        }

    // --- monthly review (issue 4.6; §5.5) -------------------------------------------------------

    /**
     * Input:  a July grocery budget of ₹10,000 with ₹13,000 spent (30% over) and three months of
     *         history before it (April–June, median ₹8,500 — the exact fixture
     *         `three months of history produce a suggestion that cites its rule` uses).
     * Output: the review reports Groceries as `OVER` and material, with a proposal priced at the
     *         same median a plain suggestion would show — the whole point of routing the review's
     *         proposal through [BudgetEngine.suggest] rather than a second copy of the formula.
     */
    @Test
    fun `a material overspend is reported with a proposal citing both rules`() =
        runTest(dispatcher) {
            expense(Money(-8_000_00L), categoryId = idOf("Groceries"), isoDate = "2026-04-10")
            expense(Money(-9_000_00L), categoryId = idOf("Groceries"), isoDate = "2026-05-10")
            expense(Money(-8_500_00L), categoryId = idOf("Groceries"), isoDate = "2026-06-10")
            writeJulyBudget(idOf("Groceries"), Money(10_000_00L), rolloverEnabled = false)
            expense(Money(-13_000_00L), categoryId = idOf("Groceries"), isoDate = "2026-07-10")

            val review = budgets.observeReview().first()

            assertEquals(REVIEWED_PERIOD, review?.monthStartIsoDate)
            assertEquals(listOf("RULE-BUD-REVIEW"), review?.provenance?.evidence?.map { it.ruleId })
            val groceries = review?.categories?.first { it.categoryName == "Groceries" }
            assertEquals(VarianceDirection.OVER, groceries?.direction)
            assertEquals(1, review?.materialCategories?.size)
            assertEquals(Money(8_500_00L), groceries?.proposal?.amount)
        }

    /**
     * Input:  a July budget spent within 15% of plan.
     * Output: the category appears in the review — every budgeted category does — but not among
     *         [BudgetReview.materialCategories], and carries no proposal. A review that reported
     *         every on-plan category as a finding would teach the user to ignore it.
     */
    @Test
    fun `a budget kept within variance is reported but not as a finding`() =
        runTest(dispatcher) {
            writeJulyBudget(idOf("Groceries"), Money(10_000_00L), rolloverEnabled = false)
            expense(Money(-10_500_00L), categoryId = idOf("Groceries"), isoDate = "2026-07-10")

            val review = budgets.observeReview().first()

            val groceries = review?.categories?.first { it.categoryName == "Groceries" }
            assertEquals(VarianceDirection.ON_PLAN, groceries?.direction)
            assertNull("an on-plan row is not a finding", groceries?.proposal)
            assertTrue(review?.materialCategories.isNullOrEmpty())
        }

    /** Input: no budget existed last month. Output: `null` — nothing to review is not an error. */
    @Test
    fun `nothing budgeted last month means nothing to review`() =
        runTest(dispatcher) {
            expense(Money(-1_000_00L), categoryId = idOf("Groceries"), isoDate = "2026-07-10")

            assertNull(budgets.observeReview().first())
        }

    /**
     * Input:  a reviewable month, dismissed, then dismissed again.
     * Output: the card disappears after the first dismissal, the first call reports it claimed the
     *         review, and the second — finding a claim already on record — reports `false` rather
     *         than mistaking "already claimed" for "nothing to claim" (the two collapse to the same
     *         `null` from [BudgetRepository.observeReview]).
     */
    @Test
    fun `dismissing a review hides it, once`() =
        runTest(dispatcher) {
            writeJulyBudget(idOf("Groceries"), Money(10_000_00L), rolloverEnabled = false)
            expense(Money(-13_000_00L), categoryId = idOf("Groceries"), isoDate = "2026-07-10")
            assertTrue("a review must exist before it can be dismissed", budgets.observeReview().first() != null)

            assertTrue("the first dismissal must claim it", budgets.dismissReview().expectOk())
            assertNull("the card is gone once claimed", budgets.observeReview().first())
            assertTrue("the second dismissal must not re-claim it", !budgets.dismissReview().expectOk())
        }

    /** Input: `dismissReview` with nothing budgeted last month. Output: `false`, and nothing written. */
    @Test
    fun `dismissing when there was nothing to review claims nothing`() =
        runTest(dispatcher) {
            assertTrue(!budgets.dismissReview().expectOk())
            assertEquals(0, budgetReviewRowCount())
        }

    /**
     * Input:  the review from the first test, accepted.
     * Output: a budget at the proposed amount lands in **August**, the month the user is standing
     *         in, not July — a proposal prices the month ahead, and July is already closed and
     *         cannot be re-budgeted. Recorded as `suggested`, citing `RULE-BUD-SUGGEST`, the same
     *         pairing [BudgetRepository.acceptSuggestion] stores, for the same audit reason.
     */
    @Test
    fun `accepting a review proposal budgets the current month, not the reviewed one`() =
        runTest(dispatcher) {
            expense(Money(-8_000_00L), categoryId = idOf("Groceries"), isoDate = "2026-04-10")
            expense(Money(-9_000_00L), categoryId = idOf("Groceries"), isoDate = "2026-05-10")
            expense(Money(-8_500_00L), categoryId = idOf("Groceries"), isoDate = "2026-06-10")
            writeJulyBudget(idOf("Groceries"), Money(10_000_00L), rolloverEnabled = false)
            expense(Money(-13_000_00L), categoryId = idOf("Groceries"), isoDate = "2026-07-10")

            budgets.acceptReviewProposal(idOf("Groceries")).expectOk()

            val row = budgetFor("Groceries")
            assertEquals(Money(8_500_00L), row.status.budgeted)
            assertEquals(BudgetRepository.SOURCE_SUGGESTED, row.source)
            assertEquals("RULE-BUD-SUGGEST", ruleIdOf(idOf("Groceries")))
            val storedId = categoryBudgetId(REAL_PROFILE, idOf("Groceries"), CURRENT_PERIOD)
            val stored = database.budgetDao().findById(storedId)
            assertEquals(
                "the write must land on August, not the reviewed July",
                CURRENT_PERIOD,
                stored?.periodStartIsoDate,
            )
        }

    /**
     * Input:  a category with no material proposal — either on-plan or too little history.
     * Output: `NotFound`, the same refusal [BudgetRepository.acceptSuggestion] gives for a category
     *         nothing was proposed for, rather than a zero or a stale budget.
     */
    @Test
    fun `accepting a review proposal that does not exist reports it`() =
        runTest(dispatcher) {
            writeJulyBudget(idOf("Groceries"), Money(10_000_00L), rolloverEnabled = false)
            expense(Money(-10_500_00L), categoryId = idOf("Groceries"), isoDate = "2026-07-10")

            assertEquals(AppError.NotFound, (budgets.acceptReviewProposal(idOf("Groceries")) as Err).error)
        }

    /**
     * Input:  a review dismissed on the real profile, then a switch to the demo.
     * Output: the demo sees nothing to review — `enter()` seeds no budgets in a closed month — and
     *         the real profile's claim stays invisible to it (ADR-0006, P-01).
     */
    @Test
    fun `one profile's review never reaches another`() =
        runTest(dispatcher) {
            writeJulyBudget(idOf("Groceries"), Money(10_000_00L), rolloverEnabled = false)
            expense(Money(-13_000_00L), categoryId = idOf("Groceries"), isoDate = "2026-07-10")
            budgets.dismissReview().expectOk()

            activeProfileId.value = DEMO_PROFILE

            assertNull(budgets.observeReview().first())
        }

    // --- budgets, status and provenance ---------------------------------------------------------

    /**
     * Input:  a ₹10,000 grocery budget with ₹4,000 spent, on the 15th of a 31-day month.
     * Output: the four FR-BUD-003 figures, with the safe pace computed from the *month's* shape —
     *         the one thing the engine cannot know and the repository must supply correctly.
     */
    @Test
    fun `a budget reports spent, remaining and a pace derived from the real month`() =
        runTest(dispatcher) {
            budgets.setBudget(idOf("Groceries"), Money(10_000_00L), rolloverEnabled = false).expectOk()
            expense(Money(-4_000_00L), categoryId = idOf("Groceries"))

            val row = budgetFor("Groceries")

            assertEquals(Money(10_000_00L), row.status.budgeted)
            assertEquals(Money(4_000_00L), row.status.spent)
            assertEquals(Money(6_000_00L), row.status.remaining)
            // 15 of 31 days = 4838 bps, rounded down; 10_000_00 x 4838 / 10_000.
            assertEquals(Money(4_838_00L), row.status.safePaceToDate)
            assertEquals(BudgetRepository.SOURCE_MANUAL, row.source)
        }

    /**
     * Input:  a category with no budget but with spending.
     * Output: a row all the same, marked unbudgeted. A screen that listed only budgeted categories
     *         would hide exactly the spending a user most needs to see — the category they have not
     *         thought about yet.
     */
    @Test
    fun `a category with spending and no budget still appears`() =
        runTest(dispatcher) {
            expense(Money(-2_500_00L), categoryId = idOf("Dining"))

            val row = budgetFor("Dining")

            assertTrue("a category with no budget must read as unbudgeted", row.isUnbudgeted)
            assertEquals(Money(2_500_00L), row.status.spent)
            assertTrue("any spending against a zero budget is an overspend", row.status.isOverspent)
        }

    /**
     * Input:  the same budget saved twice with different amounts.
     * Output: one row, holding the second amount. The id is derived from profile + category +
     *         period, so `REPLACE` updates rather than mints (P-08) — without that, editing a budget
     *         would silently double the planned total.
     */
    @Test
    fun `saving a budget twice updates one row rather than making two`() =
        runTest(dispatcher) {
            budgets.setBudget(idOf("Groceries"), Money(10_000_00L), rolloverEnabled = false).expectOk()
            budgets.setBudget(idOf("Groceries"), Money(12_000_00L), rolloverEnabled = false).expectOk()

            assertEquals(Money(12_000_00L), budgetFor("Groceries").status.budgeted)
            assertEquals(1, budgetRowCount())
        }

    /**
     * Input:  a budget re-saved after being created.
     * Output: the creation stamp survives. `REPLACE` writes a whole row, so without carrying it
     *         forward "when was this budget first set?" would silently become the last edit's date.
     */
    @Test
    fun `editing a budget preserves when it was first created`() =
        runTest(dispatcher) {
            budgets.setBudget(idOf("Groceries"), Money(10_000_00L), rolloverEnabled = false).expectOk()
            val created = createdStampOf(idOf("Groceries"))
            clock.advanceBy(Duration.ofDays(1))

            budgets.setBudget(idOf("Groceries"), Money(12_000_00L), rolloverEnabled = false).expectOk()

            assertEquals(created, createdStampOf(idOf("Groceries")))
        }

    /** Input: a negative budget. Output: rejected, rather than stored as a cap that can never be met. */
    @Test
    fun `a negative budget is refused`() =
        runTest(dispatcher) {
            val outcome = budgets.setBudget(idOf("Groceries"), Money(-1L), rolloverEnabled = false)

            assertEquals(AppError.Validation("amount"), (outcome as Err).error)
        }

    /** Input: a budget for a category that does not exist. Output: rejected inside the transaction. */
    @Test
    fun `a budget for a category that does not exist is refused`() =
        runTest(dispatcher) {
            val outcome = budgets.setBudget("category:ghost", Money(1_000_00L), rolloverEnabled = false)

            assertEquals(AppError.Validation("categoryId"), (outcome as Err).error)
        }

    /** Input: a saved budget, then deleted. Output: the category goes back to unbudgeted (DB-002). */
    @Test
    fun `deleting a budget leaves the category unbudgeted`() =
        runTest(dispatcher) {
            budgets.setBudget(idOf("Groceries"), Money(10_000_00L), rolloverEnabled = false).expectOk()

            budgets.deleteBudget(budgetFor("Groceries").id!!).expectOk()

            assertTrue(budgetFor("Groceries").isUnbudgeted)
        }

    /** Input: a budget id that names nothing. Output: `NotFound`, not a silent success. */
    @Test
    fun `deleting a budget that is not there reports it`() =
        runTest(dispatcher) {
            assertEquals(AppError.NotFound, (budgets.deleteBudget("budget:ghost") as Err).error)
        }

    // --- rollover (FR-BUD-001) --------------------------------------------------------------------

    /**
     * Input:  a July budget of ₹10,000 with ₹6,000 spent and rollover on, read in August.
     * Output: August is measured against ₹14,000. This is the only figure in the feature that reads
     *         a second month, and getting the *previous* month's window wrong is invisible — it
     *         simply carries nothing, which looks exactly like rollover being switched off.
     */
    @Test
    fun `an unspent budget carries into the next month when rollover is on`() =
        runTest(dispatcher) {
            writeJulyBudget(idOf("Groceries"), Money(10_000_00L), rolloverEnabled = true)
            expense(Money(-6_000_00L), categoryId = idOf("Groceries"), isoDate = "2026-07-10")
            budgets.setBudget(idOf("Groceries"), Money(10_000_00L), rolloverEnabled = true).expectOk()

            val row = budgetFor("Groceries")

            assertEquals(Money(4_000_00L), row.status.carriedOver)
            assertEquals(Money(14_000_00L), row.status.budgeted)
        }

    /**
     * Input:  a July budget that was **overspent**, with rollover on.
     * Output: nothing carries. Rolling a deficit forward would silently shrink a budget the user
     *         set, turning one bad month into two without ever saying so.
     */
    @Test
    fun `an overspent month carries no deficit forward`() =
        runTest(dispatcher) {
            writeJulyBudget(idOf("Groceries"), Money(10_000_00L), rolloverEnabled = true)
            expense(Money(-13_000_00L), categoryId = idOf("Groceries"), isoDate = "2026-07-10")
            budgets.setBudget(idOf("Groceries"), Money(10_000_00L), rolloverEnabled = true).expectOk()

            assertEquals(Money.ZERO, budgetFor("Groceries").status.carriedOver)
        }

    /** Input: last month's leftover with rollover **off**. Output: nothing carries — it is opt-in. */
    @Test
    fun `nothing carries when rollover is switched off`() =
        runTest(dispatcher) {
            writeJulyBudget(idOf("Groceries"), Money(10_000_00L), rolloverEnabled = false)
            budgets.setBudget(idOf("Groceries"), Money(10_000_00L), rolloverEnabled = false).expectOk()

            assertEquals(Money.ZERO, budgetFor("Groceries").status.carriedOver)
        }

    // --- suggestions (FR-BUD-002) -----------------------------------------------------------------

    /**
     * Input:  three closed months of grocery spending — ₹8,000, ₹9,000, ₹8,500.
     * Output: a suggestion built on their median, citing the rule (P-02). Groceries in August has no
     *         seasonal prior, so this isolates the history half of FR-BUD-002 from the calendar half.
     */
    @Test
    fun `three months of history produce a suggestion that cites its rule`() =
        runTest(dispatcher) {
            expense(Money(-8_000_00L), categoryId = idOf("Groceries"), isoDate = "2026-05-10")
            expense(Money(-9_000_00L), categoryId = idOf("Groceries"), isoDate = "2026-06-10")
            expense(Money(-8_500_00L), categoryId = idOf("Groceries"), isoDate = "2026-07-10")

            val suggestion = suggestionFor("Groceries")

            assertEquals(Money(8_500_00L), suggestion.suggestion.medianAmount)
            assertEquals(Money(8_500_00L), suggestion.suggestion.amount)
            assertEquals(listOf("RULE-BUD-SUGGEST"), suggestion.suggestion.provenance.evidence.map { it.ruleId })
        }

    /**
     * Input:  a category that already has a budget.
     * Output: no suggestion for it. A budget is a decision the user has already made, and
     *         re-proposing over it would be the app arguing with them (P-07).
     */
    @Test
    fun `a category that already has a budget is not suggested for`() =
        runTest(dispatcher) {
            expense(Money(-8_000_00L), categoryId = idOf("Groceries"), isoDate = "2026-05-10")
            expense(Money(-9_000_00L), categoryId = idOf("Groceries"), isoDate = "2026-06-10")
            budgets.setBudget(idOf("Groceries"), Money(10_000_00L), rolloverEnabled = false).expectOk()

            assertNull(budgets.observeSuggestions().first().firstOrNull { it.category.name == "Groceries" })
        }

    /** Input: a single month of history. Output: no suggestion — a median of one month is that month. */
    @Test
    fun `one month of history is not enough to suggest anything`() =
        runTest(dispatcher) {
            expense(Money(-8_000_00L), categoryId = idOf("Groceries"), isoDate = "2026-07-10")

            assertNull(budgets.observeSuggestions().first().firstOrNull { it.category.name == "Groceries" })
        }

    /**
     * Input:  a suggestion, accepted.
     * Output: a budget at the suggested amount, recorded as `suggested` **and carrying the rule that
     *         produced it**. That pairing is the audit trail §29's governance clause asks for — it is
     *         what later distinguishes an amount the app proposed from one the user chose
     *         (AI-ARC-006).
     */
    @Test
    fun `accepting a suggestion stores the amount, its source and its rule`() =
        runTest(dispatcher) {
            expense(Money(-8_000_00L), categoryId = idOf("Groceries"), isoDate = "2026-05-10")
            expense(Money(-9_000_00L), categoryId = idOf("Groceries"), isoDate = "2026-06-10")
            expense(Money(-8_500_00L), categoryId = idOf("Groceries"), isoDate = "2026-07-10")

            budgets.acceptSuggestion(idOf("Groceries")).expectOk()

            val row = budgetFor("Groceries")
            assertEquals(Money(8_500_00L), row.status.budgeted)
            assertEquals(BudgetRepository.SOURCE_SUGGESTED, row.source)
            assertEquals("RULE-BUD-SUGGEST", ruleIdOf(idOf("Groceries")))
        }

    /** Input: accepting where nothing is suggestible. Output: `NotFound` rather than a zero budget. */
    @Test
    fun `accepting a suggestion that does not exist reports it`() =
        runTest(dispatcher) {
            assertEquals(AppError.NotFound, (budgets.acceptSuggestion(idOf("Groceries")) as Err).error)
        }

    /**
     * Input:  three months of *split* spending on dining.
     * Output: the suggestion is built on the lines. The monthly query is the second consumer of the
     *         `UNION ALL`, and it would be entirely possible to make the current-month total
     *         split-aware and leave the history reading parents — in which case every suggestion for
     *         a category the user splits into would be zero.
     */
    @Test
    fun `suggestions are built from split lines too`() =
        runTest(dispatcher) {
            listOf("2026-05-10", "2026-06-10", "2026-07-10").forEach { date ->
                transactions.createSplit(
                    SplitDraft(
                        accountId = account,
                        amount = Money(-4_000_00L),
                        bookedOn = LocalDate.parse(date),
                        lines =
                            listOf(
                                SplitLineDraft(amount = Money(-3_000_00L), categoryId = idOf("Groceries")),
                                SplitLineDraft(amount = Money(-1_000_00L), categoryId = idOf("Dining")),
                            ),
                    ),
                ).expectOk()
            }

            assertEquals(Money(1_000_00L), suggestionFor("Dining").suggestion.medianAmount)
        }

    // --- helpers ------------------------------------------------------------------------------------

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

    private suspend fun budgetFor(name: String): CategoryBudget =
        budgets.observeBudgets().first().first { it.category.name == name }

    private suspend fun alertFor(name: String): CategoryBudgetAlert? =
        budgets.observeAlerts().first().firstOrNull { it.category.name == name }

    private suspend fun suggestionFor(name: String): CategoryBudgetSuggestion =
        budgets.observeSuggestions().first().first { it.category.name == name }

    private suspend fun spentOn(name: String): Money = budgetFor(name).status.spent

    private suspend fun totalSpend(): Money =
        budgets.observeBudgets().first().fold(Money.ZERO) { total, row -> total + row.status.spent }

    /**
     * Writes a budget into a month the repository's writers cannot reach.
     * Why:    `setBudget` always writes the *current* month, which is correct — a user budgets the
     *         month they are in. Rollover needs a previous month to exist, so the fixture writes one
     *         directly. Using the same derived id the repository would keeps the row indistinguishable
     *         from one the app wrote.
     * Input:  [categoryId], [amount], [rolloverEnabled]. Output: none.
     */
    private suspend fun writeJulyBudget(
        categoryId: String,
        amount: Money,
        rolloverEnabled: Boolean,
    ) {
        val period = "2026-07-01"
        database.budgetDao().upsert(
            com.aicfo.core.database.entity.BudgetEntity(
                id = categoryBudgetId(REAL_PROFILE, categoryId, period),
                profileId = REAL_PROFILE,
                categoryId = categoryId,
                periodStartIsoDate = period,
                amountMinor = amount.minor,
                rolloverEnabled = rolloverEnabled,
                source = BudgetRepository.SOURCE_MANUAL,
                createdAtUtcMillis = clock.nowUtcMillis(),
                updatedAtUtcMillis = clock.nowUtcMillis(),
            ),
        )
    }

    private suspend fun createdStampOf(categoryId: String): Long =
        checkNotNull(
            database.budgetDao().findById(categoryBudgetId(REAL_PROFILE, categoryId, CURRENT_PERIOD)),
        ).createdAtUtcMillis

    private suspend fun ruleIdOf(categoryId: String): String? =
        database.budgetDao().findById(categoryBudgetId(REAL_PROFILE, categoryId, CURRENT_PERIOD))?.ruleId

    private fun budgetRowCount(): Int =
        database.query("SELECT COUNT(*) FROM budget WHERE category_id IS NOT NULL", emptyArray()).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun budgetReviewRowCount(): Int =
        database.query("SELECT COUNT(*) FROM budget_review", emptyArray()).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private companion object {
        const val REAL_PROFILE = "local"
        const val DEMO_PROFILE = "demo"
        const val CURRENT_PERIOD = "2026-08-01"

        /** July: the closed month `observeReview` looks at when the clock reads 15 August 2026. */
        const val REVIEWED_PERIOD = "2026-07-01"

        /** From 15 Aug to 10 Sep — far enough to change the month, which is what the key turns on. */
        const val SEPTEMBER_DAYS_AHEAD = 26L
    }
}

/**
 * Unwraps a result the test expects to have succeeded.
 * Why:    an `Err` here is a test failure, and `as Ok` would report it as a `ClassCastException`
 *         naming neither the code nor the call — the helper every repository test in this module
 *         carries, kept file-private so each states its own reason.
 * Result: the value. Input: the receiver. Output: [T]; throws [AssertionError] on an `Err`.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 */
private fun <T> com.aicfo.core.common.Result<T, AppError>.expectOk(): T =
    when (this) {
        is Ok -> value
        is Err -> throw AssertionError("expected Ok, was $error")
    }
