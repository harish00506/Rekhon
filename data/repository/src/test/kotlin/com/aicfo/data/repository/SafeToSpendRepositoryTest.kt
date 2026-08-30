package com.aicfo.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aicfo.core.common.AppError
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.FakeIdGenerator
import com.aicfo.core.common.Ok
import com.aicfo.core.common.TestDispatchers
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.BudgetEntity
import com.aicfo.core.database.entity.RecurringRuleEntity
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.classification.ClassificationEngineFactory
import com.aicfo.domain.engines.goals.GoalEngineFactory
import com.aicfo.domain.engines.nature.NatureEngineFactory
import com.aicfo.domain.engines.quicksetup.BudgetNature
import com.aicfo.domain.engines.safetospend.SafeToSpend
import com.aicfo.domain.engines.safetospend.SafeToSpendComponent
import com.aicfo.domain.engines.safetospend.SafeToSpendEngineFactory
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
import java.time.LocalDate

/**
 * The data half of Safe-to-Spend — issue 5.2 (SRS §5.2, §14, ARC-005).
 *
 * Why:  `SafeToSpendEngineTest` and the golden file already prove `RULE-STS`'s arithmetic against
 *       fixtures, so repeating it here would assert nothing new. **What is unproven above SQLite is
 *       which rows go into each term**, and every one of the five has a way to be wrong that still
 *       produces a plausible number:
 *
 *       - the **income basis** falling through to the ledger when envelopes exist, or producing a
 *         confident ₹0 for a profile that declared nothing;
 *       - a scheduled row **outside the month** — `observeUpcoming` reaches ninety days — reducing
 *         what is safe to spend today;
 *       - a scheduled **income** counted as a commitment;
 *       - the quick-setup **salary rule**, which is a recurring rule with a positive amount, counted
 *         as a bill and subtracted from the user's own spending money;
 *       - a bill the user **also scheduled** counted twice.
 *
 *       None of those throws. All of them move the headline figure on the app's home screen.
 * What: the income basis and its fallback, each term's window and sign, the deduplication, and the
 *       absence a profile with no income basis must produce.
 * Result: the first real figure on the dashboard is proven against a real SQL engine.
 * Changelog: 2026-08-16 — Created for issue 5.2.
 *
 * Unencrypted in-memory Room and the **real** engines rather than stubs, the same reasoning
 * [BudgetRepositoryTest] gives: the claim is that a Safe-to-Spend figure reaches the screen, and a
 * stub could not make it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SafeToSpendRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var safeToSpend: SafeToSpendRepository
    private lateinit var transactions: TransactionRepository
    private lateinit var goals: GoalRepository
    private lateinit var accounts: AccountRepository
    private lateinit var categories: CategoryRepository
    private lateinit var account: String

    // Mid-month on a 31-day month: the window's upper bound is month end and its lower bound is
    // tomorrow, so a clock on the 1st or the last day would let an off-by-one bound pass unnoticed.
    private val clock = FakeClock(initialMillis = Instant.parse("2026-08-15T06:00:00Z").toEpochMilli())
    private val ids = FakeIdGenerator()

    // One dispatcher, one scheduler, shared with every `runTest` below — `observeSafeToSpend`
    // combines five Flows behind a `flowOn`, and combine dispatches across that boundary, which
    // kotlinx-coroutines-test refuses when two schedulers meet. The reason BudgetRepositoryTest
    // records for its three-way combine, one issue and two flows later.
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
            val quickSetup = RepositoryFactory.quickSetup(database, clock, dispatchers, activeProfileId)
            goals = goalRepository(dispatchers)
            safeToSpend =
                RepositoryFactory.safeToSpend(
                    database = database,
                    transactions = transactions,
                    quickSetup = quickSetup,
                    goals = goals,
                    engine = SafeToSpendEngineFactory.create(),
                    clock = clock,
                    dispatchers = dispatchers,
                    activeProfileId = activeProfileId,
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

    // --- the income basis ------------------------------------------------------------------------

    /**
     * Input:  a profile with no envelopes and nothing credited this month.
     * Output: **`null`**, not a figure.
     *
     * Why:    the case P-03 turns on. Computing from an income of ₹0 would produce a confident
     *         negative number built on a fact nobody supplied, and a confident ₹0 is
     *         indistinguishable from a real month with nothing left.
     */
    @Test
    fun `a profile with no income basis has no figure at all`() =
        runTest(dispatcher) {
            assertNull(figure())
        }

    /**
     * Input:  envelopes totalling ₹80,000 and nothing else.
     * Output: income less the 5% buffer. The envelopes are `RULE-STS.income_basis`'s first choice.
     */
    @Test
    fun `the envelope total is the income basis`() =
        runTest(dispatcher) {
            seedEnvelopes(needs = rupees(40_000), wants = rupees(25_000), invest = rupees(15_000))

            assertEquals(rupees(80_000), lineFor(SafeToSpendComponent.INCOME))
            // 80 000 − 4 000 buffer − 15 000 savings still owed = 61 000
            assertEquals(rupees(61_000), requireFigure().amount)
        }

    /**
     * Input:  no envelopes, but ₹60,000 credited this month.
     * Output: the ledger's income is used instead — the fallback for a profile that skipped quick
     *         setup, which would otherwise have no dashboard figure at all.
     */
    @Test
    fun `the ledger's income is the fallback when nothing was declared`() =
        runTest(dispatcher) {
            income(rupees(60_000))

            assertEquals(rupees(60_000), lineFor(SafeToSpendComponent.INCOME))
            assertEquals(rupees(57_000), requireFigure().amount)
        }

    /**
     * Input:  envelopes **and** a salary credit that disagrees with them.
     * Output: the envelopes win.
     *
     * Why:    the whole reason `RULE-STS.income_basis` prefers them — a figure driven by the ledger
     *         would read one number for the twenty-seven days before payday and another after it,
     *         measuring the salary calendar rather than the user's position.
     */
    @Test
    fun `the declared budget beats the ledger when both exist`() =
        runTest(dispatcher) {
            seedEnvelopes(needs = rupees(80_000))
            income(rupees(95_000))

            assertEquals(rupees(80_000), lineFor(SafeToSpendComponent.INCOME))
        }

    // --- what the month has already claimed -------------------------------------------------------

    /**
     * Input:  ₹12,400 of grocery spending this month.
     * Output: it is subtracted, as §8.3's true spend. The bridge from issue 4.3's classification to
     *         this figure — if the nature breakdown were read wrongly, this is where it shows.
     */
    @Test
    fun `what has already been spent is subtracted`() =
        runTest(dispatcher) {
            seedEnvelopes(needs = rupees(80_000))
            expense(rupees(-12_400), categoryId = idOf("Groceries"))

            assertEquals(rupees(12_400), lineFor(SafeToSpendComponent.SPENT))
            assertEquals(rupees(63_600), requireFigure().amount)
        }

    /**
     * Input:  a bill scheduled for the 28th of this month.
     * Output: it is subtracted, though nothing has been paid. FR-TXN-010 keeps future-dated rows out
     *         of actuals; a commitment the user has already made is still a claim on the month.
     */
    @Test
    fun `a bill scheduled inside the month is subtracted exactly once`() =
        runTest(dispatcher) {
            seedEnvelopes(needs = rupees(80_000))
            expense(rupees(-18_000), isoDate = "2026-08-28", merchant = "Landlord")

            assertEquals(rupees(18_000), lineFor(SafeToSpendComponent.SCHEDULED))
            // The assertion that matters, and the one whose absence hid a real bug through a whole
            // green test run: `observeNatureBreakdown` used to span the *whole* month, so this row
            // was in `trueSpend` as well and the ₹18,000 came off twice. It is bounded at today now
            // (FR-TXN-010) — a payment scheduled for the 28th is not money spent on the 15th.
            assertNull("a future-dated row is not 'already spent'", lineFor(SafeToSpendComponent.SPENT))
            // 80 000 − 4 000 buffer − 18 000 = 58 000, not 40 000.
            assertEquals(rupees(58_000), requireFigure().amount)
        }

    /**
     * Input:  a premium scheduled for October.
     * Output: **not** subtracted.
     *
     * Why:    `observeUpcoming` reaches ninety days because the forecast wants it; Safe-to-Spend is
     *         a question about *this* month. Without the window bound, next quarter's insurance
     *         would reduce what is safe to spend today.
     */
    @Test
    fun `a bill scheduled after month end is not subtracted`() =
        runTest(dispatcher) {
            seedEnvelopes(needs = rupees(80_000))
            expense(rupees(-18_000), isoDate = "2026-10-05")

            assertNull(lineFor(SafeToSpendComponent.SCHEDULED))
            assertEquals(rupees(76_000), requireFigure().amount)
        }

    /**
     * Input:  a salary credit scheduled for the 30th.
     * Output: not treated as a commitment. Only expenses are claims on the month; income arriving is
     *         not something to be subtracted from what is left.
     */
    @Test
    fun `scheduled income is not a commitment`() =
        runTest(dispatcher) {
            seedEnvelopes(needs = rupees(80_000))
            income(rupees(20_000), isoDate = "2026-08-30")

            assertNull(lineFor(SafeToSpendComponent.SCHEDULED))
        }

    /**
     * Input:  a confirmed ₹2,499 subscription due on the 25th.
     * Output: subtracted. The bills the app knows about and the user has forgotten — the case the
     *         figure exists to catch.
     */
    @Test
    fun `a confirmed recurring bill due this month is subtracted`() =
        runTest(dispatcher) {
            seedEnvelopes(needs = rupees(80_000))
            seedRule(name = "Netflix", amount = rupees(-2_499), dueOn = "2026-08-25")

            assertEquals(rupees(2_499), lineFor(SafeToSpendComponent.RECURRING))
        }

    /**
     * Input:  an unconfirmed proposal and a rule due next month.
     * Output: neither counts. A proposal binds nobody (P-07), and next month is not this one.
     */
    @Test
    fun `an unconfirmed rule and a rule due next month are both ignored`() =
        runTest(dispatcher) {
            seedEnvelopes(needs = rupees(80_000))
            seedRule(name = "Gym", amount = rupees(-1_500), dueOn = "2026-08-25", confirmed = false)
            seedRule(name = "Insurance", amount = rupees(-9_000), dueOn = "2026-09-03")

            assertNull(lineFor(SafeToSpendComponent.RECURRING))
        }

    /**
     * The salary trap.
     * Input:  the quick-setup `income` rule — a recurring rule with a **positive** amount (issue 2.3).
     * Output: not counted as a bill.
     *
     * Why:    treating it as one would subtract the user's own salary from their spending money,
     *         roughly halving the figure, silently, for every user who completed onboarding.
     */
    @Test
    fun `the quick-setup salary rule is not a bill`() =
        runTest(dispatcher) {
            seedEnvelopes(needs = rupees(80_000))
            seedRule(name = null, amount = rupees(75_000), dueOn = "2026-08-31", seedKind = "income")

            assertNull(lineFor(SafeToSpendComponent.RECURRING))
            assertEquals(rupees(76_000), requireFigure().amount)
        }

    /**
     * The double-count this repository is most likely to get wrong.
     * Input:  rent scheduled for the 28th **and** a confirmed rent rule due the 28th.
     * Output: counted once.
     *
     * Why:    the user has told the app the same thing twice, and a naive sum would take rent off
     *         Safe-to-Spend twice — quietly, by an amount large enough to make the figure useless.
     */
    @Test
    fun `a bill that is also scheduled is counted once`() =
        runTest(dispatcher) {
            seedEnvelopes(needs = rupees(80_000))
            expense(rupees(-25_000), isoDate = "2026-08-28", merchant = "Landlord")
            seedRule(name = "landlord", amount = rupees(-25_000), dueOn = "2026-08-28")

            assertEquals(rupees(25_000), lineFor(SafeToSpendComponent.SCHEDULED))
            assertNull("the rule duplicates the scheduled row", lineFor(SafeToSpendComponent.RECURRING))
        }

    /**
     * Input:  one merchant billed twice in the same month, one of the two also scheduled.
     * Output: the other still counts.
     *
     * Why:    the deduplication matches on **name and date together**. Matching on the merchant
     *         alone would silently discard a genuine second commitment — a top-up beside a
     *         subscription — which is the failure mode of the obvious fix.
     */
    @Test
    fun `a second bill from the same merchant on another day still counts`() =
        runTest(dispatcher) {
            seedEnvelopes(needs = rupees(80_000))
            expense(rupees(-500), isoDate = "2026-08-20", merchant = "Jio")
            seedRule(name = "Jio", amount = rupees(-500), dueOn = "2026-08-20")
            seedRule(name = "Jio", amount = rupees(-999), dueOn = "2026-08-27", idSuffix = "second")

            assertEquals(rupees(999), lineFor(SafeToSpendComponent.RECURRING))
        }

    // --- goal contributions -----------------------------------------------------------------------

    /**
     * The netting trap, asserted so nobody "fixes" it back.
     * Input:  a ₹15,000 savings envelope with ₹9,000 already invested this month.
     * Output: the **full ₹15,000** is still subtracted, and the ₹9,000 appears nowhere else.
     *
     * Why:    netting the envelope against what has already been saved looks obviously right and is
     *         wrong. §8.3's `trueSpend` is `NEED + WANT`, so it *already excludes* every conversion —
     *         the ₹9,000 SIP is in neither term. Netting would therefore leave it deducted from
     *         nothing at all, and Safe-to-Spend would **rise** by exactly the amount the user had
     *         just saved. Taking the envelope whole counts each planned rupee once, and keeps the
     *         figure steady instead of stepping up on the day the SIP debits.
     */
    @Test
    fun `saving does not increase what is safe to spend`() =
        runTest(dispatcher) {
            seedEnvelopes(needs = rupees(65_000), invest = rupees(15_000))
            val before = requireFigure().amount

            expense(rupees(-9_000), categoryId = idOf("Investment"))

            assertEquals(rupees(15_000), lineFor(SafeToSpendComponent.GOALS))
            assertNull("a conversion is not true spend (§8.3)", lineFor(SafeToSpendComponent.SPENT))
            assertEquals("making a planned saving must not move the figure", before, requireFigure().amount)
        }

    // --- the goals term (issue 7.1; ADR-0021's debt) ----------------------------------------------

    /**
     * Input:  an INVEST envelope and no goals at all.
     * Output: asserts the deduction is still the envelope.
     *
     * **The regression this pair exists to prevent.** ADR-0021 assigned issue 7.1 the job of
     * replacing the envelope stand-in with the real goals figure, and a straight replacement would
     * have made Safe-to-Spend jump *upwards* by the whole envelope for every existing user who has
     * not set a goal — optimistic in exactly the direction §5.2 exists to guard against. The term is
     * the greater of the two, so it can only ever hold the figure down.
     */
    @Test
    fun `with no goals set, the deduction is still the declared savings envelope`() =
        runTest(dispatcher) {
            seedEnvelopes(needs = rupees(65_000), invest = rupees(15_000))

            assertEquals(rupees(15_000), lineFor(SafeToSpendComponent.GOALS))
        }

    /**
     * Input:  a goal needing more each month than the envelope declares.
     * Output: asserts the goals figure takes over.
     */
    @Test
    fun `a goal needing more than the envelope raises the deduction to what the goal needs`() =
        runTest(dispatcher) {
            // ₹2,40,000 over the 12 months to the date is ₹20,000 a month, above the ₹15,000 envelope.
            seedEnvelopes(needs = rupees(65_000), invest = rupees(15_000))
            goals.save(
                GoalDraft(
                    name = "Kerala trip",
                    target = rupees(240_000),
                    targetDateIso = clock.today().plusMonths(12).toString(),
                ),
            )

            assertEquals(rupees(20_000), lineFor(SafeToSpendComponent.GOALS))
        }

    /**
     * Input:  a goal needing less each month than the envelope declares.
     * Output: asserts the envelope still stands.
     *
     * A user's declared monthly saving does not stop being planned saving because the goal they
     * named happens to need less than all of it.
     */
    @Test
    fun `a goal needing less than the envelope leaves the envelope in place`() =
        runTest(dispatcher) {
            seedEnvelopes(needs = rupees(65_000), invest = rupees(15_000))
            goals.save(
                GoalDraft(
                    name = "New laptop",
                    target = rupees(120_000),
                    targetDateIso = clock.today().plusMonths(12).toString(),
                ),
            )

            assertEquals(rupees(15_000), lineFor(SafeToSpendComponent.GOALS))
        }

    // --- provenance and the live contract ---------------------------------------------------------

    /**
     * Input:  an ordinary month.
     * Output: the window is this calendar month in the profile zone (TIM-002), and the citation is
     *         `RULE-STS` — what the card shows the user (P-02, AI-ARC-003).
     */
    @Test
    fun `the figure carries this month's window and cites RULE-STS`() =
        runTest(dispatcher) {
            seedEnvelopes(needs = rupees(80_000))
            val result = requireFigure()

            assertEquals("2026-08-01..2026-08-31", result.provenance.inputWindow)
            assertEquals("RULE-STS", result.provenance.evidence.single().ruleId)
        }

    /**
     * Input:  a figure, then a new expense.
     * Output: the flow re-emits a lower figure.
     *
     * Why:    the loop that closes through the database — a write invalidates the query, the Flow
     *         re-emits, the screen updates. A repository that returned a snapshot would leave the
     *         home screen stale until the app was relaunched, which is what happened to net worth in
     *         issue 2.6 and was only caught by running it.
     */
    @Test
    fun `the figure follows the ledger`() =
        runTest(dispatcher) {
            seedEnvelopes(needs = rupees(80_000))
            val before = requireFigure().amount

            expense(rupees(-3_000), categoryId = idOf("Groceries"))

            assertEquals(before - rupees(3_000), requireFigure().amount)
        }

    /**
     * Input:  the demo profile, which has neither envelopes nor income of its own.
     * Output: no figure. The demo's isolation (ADR-0006) reaches this read like every other, so
     *         entering it cannot show the real profile's number.
     */
    @Test
    fun `the figure follows the active profile`() =
        runTest(dispatcher) {
            seedEnvelopes(needs = rupees(80_000))
            assertNotNull(figure())

            activeProfileId.value = DEMO_PROFILE

            assertNull(figure())
        }

    // --- fixtures ----------------------------------------------------------------------------------

    /**
     * The real goals repository over the real engine (issue 7.1).
     * Why:    extracted so [setUp] stays inside detekt's 40-line limit. Real rather than a fake, so
     *         the `maxOf` in `SafeToSpendRepository` is exercised rather than stubbed.
     * Result: a [GoalRepository]. Input: [dispatchers]. Output: the repository.
     */
    private fun goalRepository(dispatchers: DispatcherProvider): GoalRepository =
        RepositoryFactory.goals(database, GoalEngineFactory.create(), clock, ids, dispatchers, activeProfileId)

    /** Result: the current figure, or `null`. Input: none. Output: `SafeToSpend?`. */
    private suspend fun figure(): SafeToSpend? = safeToSpend.observeSafeToSpend().first()

    /** Result: the current figure, failing the test when absent. Output: [SafeToSpend]. */

    private suspend fun requireFigure(): SafeToSpend =
        checkNotNull(figure()) { "expected a Safe-to-Spend figure, got the absence" }

    /**
     * Result: one breakdown line's amount, or `null` when the term is absent — which is how the
     *         engine represents a term of zero. Input: [component]. Output: `Money?`.
     */
    private suspend fun lineFor(component: SafeToSpendComponent): Money? =
        requireFigure().lines.firstOrNull { it.component == component }?.amount

    /**
     * Writes budget envelopes straight to the DAO, with the ids the app would derive.
     * Why:    `applySeeds` needs a whole `QuickSetupPlan` and a profile; the terms under test here
     *         are the envelope *amounts*, and the derived ids keep the rows indistinguishable from
     *         ones the app wrote (the argument `writeJulyBudget` makes in [BudgetRepositoryTest]).
     * Input:  [needs], [wants], [invest] — omit a nature to leave that envelope absent. Output: none.
     */
    private suspend fun seedEnvelopes(
        needs: Money = Money.ZERO,
        wants: Money = Money.ZERO,
        invest: Money = Money.ZERO,
    ) {
        listOf(BudgetNature.NEED to needs, BudgetNature.WANT to wants, BudgetNature.INVEST to invest)
            .filter { (_, amount) -> amount != Money.ZERO }
            .forEach { (nature, amount) ->
                database.budgetDao().upsert(
                    BudgetEntity(
                        id = budgetId(REAL_PROFILE, nature, CURRENT_PERIOD),
                        profileId = REAL_PROFILE,
                        nature = nature.storedValue,
                        periodStartIsoDate = CURRENT_PERIOD,
                        amountMinor = amount.minor,
                        source = SOURCE_QUICK_SETUP,
                        createdAtUtcMillis = clock.nowUtcMillis(),
                        updatedAtUtcMillis = clock.nowUtcMillis(),
                    ),
                )
            }
    }

    /**
     * Writes one recurring rule.
     * Input:  [name] — the merchant, `null` for a quick-setup seed row; [amount] — **signed**, so a
     *         positive amount is an inflow; [dueOn]; [confirmed]; [seedKind]; [idSuffix] — lets one
     *         merchant hold two rules. Output: none.
     */
    @Suppress("LongParameterList") // A fixture; each argument is one thing a test varies.
    private suspend fun seedRule(
        name: String?,
        amount: Money,
        dueOn: String,
        confirmed: Boolean = true,
        seedKind: String? = null,
        idSuffix: String = "first",
    ) {
        database.recurringRuleDao().upsertAll(
            listOf(
                RecurringRuleEntity(
                    id = "$REAL_PROFILE:recurring:${name.orEmpty().lowercase()}:$idSuffix",
                    profileId = REAL_PROFILE,
                    name = name,
                    seedKind = seedKind,
                    amountMinor = amount.minor,
                    cadence = "monthly",
                    nextDueIsoDate = dueOn,
                    source = if (seedKind == null) "detected" else SOURCE_QUICK_SETUP,
                    isConfirmed = confirmed,
                    createdAtUtcMillis = clock.nowUtcMillis(),
                    updatedAtUtcMillis = clock.nowUtcMillis(),
                ),
            ),
        )
    }

    /** Result: the new transaction's id. Input: [amount] — signed; [categoryId]; [isoDate]; [merchant]. */
    private suspend fun expense(
        amount: Money,
        categoryId: String? = null,
        isoDate: String? = null,
        merchant: String? = null,
    ): String =
        transactions.create(
            TransactionDraft(
                accountId = account,
                amount = amount,
                categoryId = categoryId,
                merchant = merchant,
                bookedOn = isoDate?.let(LocalDate::parse),
            ),
        ).expectOk().id

    /** Result: the new credit's id. Input: [amount] — positive; [isoDate]. Output: the id. */
    private suspend fun income(
        amount: Money,
        isoDate: String? = null,
    ): String =
        transactions.create(
            TransactionDraft(
                accountId = account,
                amount = amount,
                bookedOn = isoDate?.let(LocalDate::parse),
            ),
        ).expectOk().id

    private suspend fun idOf(name: String): String = categories.observeCategories().first().first { it.name == name }.id

    private companion object {
        const val REAL_PROFILE = "local"
        const val DEMO_PROFILE = "demo"
        const val CURRENT_PERIOD = "2026-08-01"

        /** Result: the amount in paise. Input: whole rupees, signed. Output: [Money] (MNY-001). */
        fun rupees(whole: Long): Money = Money(whole * 100L)
    }
}

/**
 * Unwraps a result the test expects to have succeeded.
 * Why:    an `Err` here is a test failure, and `as Ok` would report it as a `ClassCastException`
 *         naming neither the code nor the call — the helper every repository test in this module
 *         carries, kept file-private so each states its own reason.
 * Result: the value. Input: the receiver. Output: [T]; throws [AssertionError] on an `Err`.
 * Changelog: 2026-08-16 — Created for issue 5.2.
 */
private fun <T> com.aicfo.core.common.Result<T, AppError>.expectOk(): T =
    when (this) {
        is Ok -> value
        else -> throw AssertionError("expected Ok, got $this")
    }
