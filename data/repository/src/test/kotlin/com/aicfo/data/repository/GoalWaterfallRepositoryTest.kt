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
import com.aicfo.core.model.Account
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.classification.ClassificationEngineFactory
import com.aicfo.domain.engines.emergencyfund.EmergencyFundEngineFactory
import com.aicfo.domain.engines.goals.Feasibility
import com.aicfo.domain.engines.goals.GoalEngineFactory
import com.aicfo.domain.engines.goals.GoalWaterfall
import com.aicfo.domain.engines.goals.GoalWaterfallEngineFactory
import com.aicfo.domain.engines.goals.SurplusBasis
import com.aicfo.domain.engines.nature.NatureEngineFactory
import com.aicfo.domain.engines.quicksetup.QuickSetupEngineFactory
import com.aicfo.domain.engines.quicksetup.QuickSetupInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

/**
 * The data half of the waterfall — issue 7.3 (SRS §15.1, ARC-005, ADR-0035).
 *
 * Why:  the golden file, the property test and `GoalWaterfallEngineTest` already prove the
 *       allocation against fixtures, so repeating it here would assert nothing new. **What is
 *       unproven above SQLite is the substitution this repository makes**, and every part of it
 *       fails while still returning a plausible number:
 *
 *       - **what a surplus is.** §15.1 wants the P50 *forecast* surplus and there is no forecast
 *         engine, so this is the P50 of `income − (needs + wants)` across closed months. Subtract
 *         `invested` too and the app hides the money it is trying to allocate; use a mean and one
 *         unusual month rewrites the plan; include the live month and the surplus sags a little
 *         further every day and jumps back on the 1st.
 *       - **the fallback and the absence.** A day-one user gets the declared INVEST envelope, and a
 *         user with neither gets `UNKNOWN` — never a zero, which would report every goal they own
 *         as impossible on their first afternoon with the app.
 *       - **the gate's threshold.** `RULE-EMERG-FIRST` reaches the engine from `QuickSetupRules`,
 *         the repository's one mirror of the row, and it has to survive a real `EmergencyFundPlan`
 *         to get there — the runway is in basis points of a month, and a units mistake would let a
 *         three-month gate fire at thirty thousand months or never at all.
 *       - **the order.** `sort_order` decides who is funded first, and it has to survive the DAO,
 *         the projection and the engine in the order the user dragged it into.
 * What: the three surplus bases, the negative median, the live-month exclusion, both sides of the
 *       gate, and a reorder round-trip that changes who goes short.
 * Result: the first contribution plan in the app is proven against a real SQL engine.
 * Changelog: 2026-09-03 — Created for issue 7.3.
 *
 * Unencrypted in-memory Room and the **real** engines rather than stubs, the reasoning
 * `EmergencyFundRepositoryTest` gives: the claim is that a plan reaches the screen, and a stub could
 * not make it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GoalWaterfallRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var transactions: TransactionRepository
    private lateinit var accounts: AccountRepository
    private lateinit var categories: CategoryRepository
    private lateinit var quickSetup: QuickSetupRepository
    private lateinit var goals: GoalRepository
    private lateinit var waterfall: GoalWaterfallRepository

    // Mid-month on purpose: the history window is the *closed* months behind this one, and a clock
    // on the 1st or the 31st would let an off-by-one bound pass unnoticed.
    private val clock = FakeClock(initialMillis = Instant.parse("2026-09-14T06:00:00Z").toEpochMilli())
    private val ids = FakeIdGenerator()

    // One dispatcher, one scheduler, shared by setUp and every test. `combine` yields between
    // sources, and two schedulers make that throw rather than emit.
    private val dispatcher = UnconfinedTestDispatcher()
    private val activeProfileId = MutableStateFlow(REAL_PROFILE)

    /** Input: none. Output: a fresh in-memory database, the repositories, and a seeded taxonomy. */
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
                    database, clock, ids, dispatchers, activeProfileId,
                    ClassificationEngineFactory.create(), NatureEngineFactory.create(),
                )
            accounts = RepositoryFactory.accounts(database, clock, ids, dispatchers, activeProfileId)
            categories = RepositoryFactory.categories(database, clock, ids, dispatchers, activeProfileId)
            quickSetup = RepositoryFactory.quickSetup(database, clock, dispatchers, activeProfileId)
            categories.ensureSeeded()
            goals =
                RepositoryFactory.goals(
                    database, GoalEngineFactory.create(), clock, ids, dispatchers, activeProfileId,
                )
            waterfall =
                RepositoryFactory.goalWaterfall(
                    goals = goals,
                    transactions = transactions,
                    emergencyFund =
                        RepositoryFactory.emergencyFund(
                            transactions, accounts, quickSetup,
                            EmergencyFundEngineFactory.create(), clock, dispatchers,
                        ),
                    quickSetup = quickSetup,
                    engine = GoalWaterfallEngineFactory.create(),
                    clock = clock,
                    dispatchers = dispatchers,
                )
        }

    /** Input: none. Output: closes the database between tests. */
    @After
    fun tearDown() {
        database.close()
    }

    /**
     * Input:  three closed months earning ₹1,00,000 and spending ₹30,000 / ₹40,000 / ₹90,000.
     * Output: a surplus of **₹60,000, the median**, not ₹46,666.67, the mean.
     *
     * The assertion this class exists for. A mean would be defensible, would look like money, and
     * would understate the surplus by 22% off one month somebody replaced a fridge in — telling the
     * user their goals are further out of reach than they are.
     */
    @Test
    fun `the surplus is the median of the closed months, not the mean`() =
        runTest(dispatcher) {
            seedThreeMonths()

            val plan = plan()

            assertEquals(SurplusBasis.OBSERVED_MEDIAN, plan.surplusBasis)
            assertEquals("the median, not the ₹46,666.67 mean", Money(60_000_00L), plan.monthlySurplus)
        }

    /**
     * Input:  the same three months, plus a large expense inside the **live** month.
     * Output: the same surplus — the live month changed nothing.
     *
     * A live month is partly unspent by definition. Counting it would make the surplus sag through
     * every month and jump back on the 1st, which is the kind of wrong nobody reports as a bug
     * because each individual reading looks reasonable.
     */
    @Test
    fun `the live month is excluded from the surplus history`() =
        runTest(dispatcher) {
            seedThreeMonths()
            expense(bankId(), Money(-3_00_000_00L), LocalDate.parse("2026-09-02"))

            assertEquals(Money(60_000_00L), plan().monthlySurplus)
        }

    /**
     * Input:  three closed months whose spend includes an `INVEST` transfer to a mutual fund.
     * Output: the invested money is **still counted as surplus**.
     *
     * The subtraction that would be wrong. Investing *is* goal funding — netting it out would hide
     * the very money this plan allocates and tell the user to find a surplus they had already found.
     * Only `needs` and `wants` are consumption.
     */
    @Test
    fun `money already invested still counts towards the surplus`() =
        runTest(dispatcher) {
            val bank = seedThreeMonths()
            val fund = newAccount(AccountType.INVESTMENT, "Groww portfolio", Money.ZERO)
            // A transfer into an investment account is INVEST, not a want (issue 4.3 / ADR-0016).
            transactions.createTransfer(
                TransferDraft(
                    fromAccountId = bank.id,
                    toAccountId = fund.id,
                    amount = Money(20_000_00L),
                    bookedOn = LocalDate.parse("2026-07-15"),
                ),
            ).expectOk()

            assertEquals(
                "an INVEST outflow is goal funding, not consumption — netting it out would hide it",
                Money(60_000_00L),
                plan().monthlySurplus,
            )
        }

    /**
     * Input:  a profile spending more than it earns across three closed months.
     * Output: a **negative** surplus, reported as such, and nothing allocated.
     *
     * Unlike the essentials median, a surplus median may legitimately be negative, and saying so is
     * more use than clamping it out of sight. The engine still pours nothing — you cannot allocate
     * money that is not there — but the headline tells the truth about why.
     */
    @Test
    fun `a profile spending more than it earns reports a negative surplus`() =
        runTest(dispatcher) {
            val bank = newBank()
            listOf("2026-06", "2026-07", "2026-08").forEach { month ->
                income(bank.id, Money(40_000_00L), LocalDate.parse("$month-01"))
                expense(bank.id, Money(-50_000_00L), LocalDate.parse("$month-10"), idOf("Groceries"))
            }
            newGoal("Kerala trip", target = Money(1_20_000_00L), on = "2027-09-14")

            val plan = plan()

            assertEquals(Money(-10_000_00L), plan.monthlySurplus)
            assertEquals(Feasibility.INFEASIBLE, plan.feasibility)
            assertEquals(Money.ZERO, plan.totalAllocated)
            assertEquals("nothing to leave over either", Money.ZERO, plan.unallocated)
        }

    /**
     * Input:  a declared INVEST envelope and only **two** months of history.
     * Output: the envelope, labelled `DECLARED_ENVELOPE`.
     *
     * `min_months_observed` is three — borrowed from `RULE-EMF-MULT` rather than minted again
     * (ADR-0035). Two months is not a habit, and a median of two would carry the same confidence as
     * one built from six.
     */
    @Test
    fun `too little history falls back to the INVEST envelope declared at onboarding`() =
        runTest(dispatcher) {
            val bank = newBank()
            income(bank.id, Money(1_00_000_00L), LocalDate.parse("2026-07-01"))
            income(bank.id, Money(1_00_000_00L), LocalDate.parse("2026-08-01"))
            seedEnvelopes()

            val plan = plan()

            assertEquals(SurplusBasis.DECLARED_ENVELOPE, plan.surplusBasis)
            assertEquals(Money(20_000_00L), plan.monthlySurplus)
        }

    /**
     * Input:  no history and no envelope, with one goal.
     * Output: `UNKNOWN` and a null surplus — **not a zero, and not "impossible"**.
     *
     * Issue 7.2's lesson in its next costume. A zero surplus is a finding; missing data is not. A
     * user on their first afternoon with the app must not be told every goal they own cannot happen.
     */
    @Test
    fun `a fresh profile reports unknown rather than an impossible plan`() =
        runTest(dispatcher) {
            newBank()
            newGoal("Kerala trip", target = Money(1_20_000_00L), on = "2027-09-14")

            val plan = plan()

            assertEquals(Feasibility.UNKNOWN, plan.feasibility)
            assertNull(plan.monthlySurplus)
            assertEquals(SurplusBasis.NONE, plan.surplusBasis)
        }

    /**
     * Input:  three months of surplus, one goal, and **no liquid savings at all**.
     * Output: `RULE-EMERG-FIRST` fires and the goal is held at zero.
     *
     * The gate's threshold reaches the engine from `QuickSetupRules` — the repository's one mirror
     * of `RULE-EMERG-FIRST` (ADR-0017 trigger 2, ADR-0035) — and the runway reaches it from a real
     * `EmergencyFundPlan` in basis points of a month. A units mistake anywhere on that path would
     * make the gate fire always or never, and this is the test that would notice.
     */
    @Test
    fun `RULE-EMERG-FIRST holds the goals while there is no runway`() =
        runTest(dispatcher) {
            seedThreeMonthsSpendingEverything()
            newGoal("Kerala trip", target = Money(1_20_000_00L), on = "2027-09-14")

            val plan = plan()

            assertTrue("the gate must fire with no buffer at all", plan.emergencyFirstApplied)
            assertEquals(Money.ZERO, plan.totalAllocated)
            assertTrue("and the line must say why", plan.lines.single().blockedByEmergencyFund)
        }

    /**
     * Input:  the same months, but a bank balance deep enough to clear the three-month gate.
     * Output: the gate stands down and the goal is funded.
     *
     * The other side of the branch above, because a gate that never opens is as wrong as one that
     * never closes and looks identical in a test that only checks the closed case.
     */
    @Test
    fun `a deep enough runway lets the goals through`() =
        runTest(dispatcher) {
            seedThreeMonths()
            newGoal("Kerala trip", target = Money(1_20_000_00L), on = "2027-09-14")

            val plan = plan()

            assertFalse("₹6,20,000 against ₹40,000 of needs is far past three months", plan.emergencyFirstApplied)
            assertEquals(Feasibility.FEASIBLE, plan.feasibility)
            assertEquals(Money(10_000_00L), plan.lines.single().allocatedMonthly)
        }

    /**
     * Input:  two goals whose claims together exceed the surplus, then a reorder.
     * Output: **the other goal goes short.**
     *
     * FR-GOAL-005's whole promise, end to end: the drag reaches `sort_order`, survives the DAO's
     * `ORDER BY`, survives the projection, and changes who the waterfall reaches. Every step of that
     * path is somewhere the order could be silently re-derived, which is why this asserts the
     * outcome rather than the column.
     */
    @Test
    fun `reordering the goals changes which one goes short`() =
        runTest(dispatcher) {
            seedThreeMonths()
            val trip = newGoal("Kerala trip", target = Money(6_00_000_00L), on = "2027-09-14")
            val laptop = newGoal("New laptop", target = Money(6_00_000_00L), on = "2027-09-14")

            // ₹60,000 of surplus against two claims of ₹50,000 each: the first is funded, the
            // second gets the ₹10,000 left.
            val before = plan()
            assertEquals(listOf(trip, laptop), before.lines.map { it.goalId })
            assertEquals(Money(50_000_00L), before.lines[0].allocatedMonthly)
            assertEquals(Money(10_000_00L), before.lines[1].allocatedMonthly)

            goals.reorder(listOf(laptop, trip)).expectOk()

            val after = plan()
            assertEquals(listOf(laptop, trip), after.lines.map { it.goalId })
            assertEquals(Money(50_000_00L), after.lines[0].allocatedMonthly)
            assertEquals("the trip is now the one that goes short", Money(10_000_00L), after.lines[1].allocatedMonthly)
            assertEquals("and the same money went round either way", before.totalAllocated, after.totalAllocated)
        }

    /**
     * Input:  a reorder naming a goal that no longer exists.
     * Output: `Ok`, and the goals that do exist are ordered as asked.
     *
     * A goal deleted on another screen mid-drag must not fail a reorder that is still correct for
     * everything else. The alternative is a plan the user rearranged and the app silently discarded.
     */
    @Test
    fun `a reorder naming a deleted goal still orders the ones that remain`() =
        runTest(dispatcher) {
            seedThreeMonths()
            val trip = newGoal("Kerala trip", target = Money(6_00_000_00L), on = "2027-09-14")
            val laptop = newGoal("New laptop", target = Money(6_00_000_00L), on = "2027-09-14")

            goals.reorder(listOf("goal:vanished", laptop, trip)).expectOk()

            assertEquals(listOf(laptop, trip), plan().lines.map { it.goalId })
        }

    // --- helpers ----------------------------------------------------------------------------------

    /** Result: the current plan. Input: none. Output: [GoalWaterfall]. */
    private suspend fun plan(): GoalWaterfall = waterfall.observeWaterfall().first()

    /**
     * Seeds three closed months: ₹1,00,000 income and needs of ₹30,000 / ₹40,000 / ₹90,000.
     * Result: the bank account, holding ₹6,20,000 by the end — a runway well clear of the gate.
     * Input:  none. Output: the account.
     */
    private suspend fun seedThreeMonths(): Account {
        val bank = newBank()
        val groceries = idOf("Groceries")
        listOf("2026-06" to 30_000_00L, "2026-07" to 40_000_00L, "2026-08" to 90_000_00L)
            .forEach { (month, amount) ->
                income(bank.id, Money(1_00_000_00L), LocalDate.parse("$month-01"))
                expense(bank.id, Money(-amount), LocalDate.parse("$month-10"), groceries)
            }
        return bank
    }

    /**
     * Seeds three closed months that leave a surplus but no savings, so the gate fires.
     * Result: none. Input: none. Output: none.
     *
     * The account opens empty and every month's income is spent down to a small margin, so there is
     * a positive surplus to allocate and almost no runway to allocate it past.
     */
    private suspend fun seedThreeMonthsSpendingEverything() {
        val bank = newAccount(AccountType.BANK, "HDFC Savings", Money.ZERO)
        val groceries = idOf("Groceries")
        listOf("2026-06", "2026-07", "2026-08").forEach { month ->
            income(bank.id, Money(1_00_000_00L), LocalDate.parse("$month-01"))
            expense(bank.id, Money(-70_000_00L), LocalDate.parse("$month-10"), groceries)
            expense(bank.id, Money(-25_000_00L), LocalDate.parse("$month-20"))
        }
    }

    /** Result: a bank account opened with ₹2,00,000. Input: none. Output: the account. */
    private suspend fun newBank() = newAccount(AccountType.BANK, "HDFC Savings", Money(2_00_000_00L))

    /** Result: a saved account. Input: [type]; [name]; [opening]. Output: the account. */
    private suspend fun newAccount(
        type: AccountType,
        name: String,
        opening: Money,
    ) = accounts.create(
        AccountDraft(name = name, type = type, openingBalance = opening, currencyCode = "INR"),
    ).expectOk()

    /** Result: a saved goal's id. Input: [name]; [target]; [on] — the ISO target date. */
    private suspend fun newGoal(
        name: String,
        target: Money,
        on: String,
    ): String = goals.save(GoalDraft(name = name, target = target, targetDateIso = on)).expectOk()

    /** Result: the seeded bank's id. Input: none. Output: [String]. */
    private suspend fun bankId(): String = accounts.observeAccounts().first().first { it.name == "HDFC Savings" }.id

    /** Result: a saved expense. Input: [accountId]; [amount]; [on]; [categoryId]. Output: none. */
    private suspend fun expense(
        accountId: String,
        amount: Money,
        on: LocalDate,
        categoryId: String? = null,
    ) {
        transactions.create(
            TransactionDraft(accountId = accountId, amount = amount, categoryId = categoryId, bookedOn = on),
        ).expectOk()
    }

    /** Result: a saved income row — a positive amount is `INCOME`. Input: [accountId]; [amount]; [on]. */
    private suspend fun income(
        accountId: String,
        amount: Money,
        on: LocalDate,
    ) {
        transactions.create(TransactionDraft(accountId = accountId, amount = amount, bookedOn = on)).expectOk()
    }

    /** Result: the seeded category's id. Input: [name]. Output: [String]. */
    private suspend fun idOf(name: String): String = categories.observeCategories().first().first { it.name == name }.id

    /**
     * Writes the onboarding envelopes, so the fallback has something to fall back to.
     * Result: none. Input: none. Output: none.
     *
     * Through the real engine rather than a hand-built plan, the reasoning `QuickSetupRepositoryTest`
     * gives: a fixture would let this pass against an envelope the engine never produces. The
     * declared ₹20,000 of typical savings is what becomes the INVEST envelope.
     */
    private suspend fun seedEnvelopes() {
        val plan =
            (
                QuickSetupEngineFactory.create().plan(
                    QuickSetupInput(
                        monthlyIncome = Money(1_00_000_00L),
                        rentOrEmi = Money(20_000_00L),
                        typicalSavings = Money(20_000_00L),
                        periodStartIsoDate = "2026-09-01",
                        nowUtcMillis = clock.nowUtcMillis(),
                    ),
                ) as Ok
            ).value
        quickSetup.applySeeds(
            plan,
            ProfileSeed(displayName = "Arjun", timeZoneId = "Asia/Kolkata", currencyCode = "INR"),
        ).expectOk()
    }

    private companion object {
        const val REAL_PROFILE = "local"
    }
}

/**
 * Unwraps a result the test expects to have succeeded.
 * Why:    an `Err` here is a test failure, and `as Ok` would report it as a `ClassCastException`
 *         naming neither the code nor the call — the helper every repository test in this module
 *         carries, kept file-private so each states its own reason.
 * Result: the value. Input: the receiver. Output: [T]; throws [AssertionError] on an `Err`.
 * Changelog: 2026-09-03 — Created for issue 7.3.
 */
private fun <T> com.aicfo.core.common.Result<T, AppError>.expectOk(): T =
    when (this) {
        is Ok -> value
        is Err -> throw AssertionError("expected Ok, got Err(${error.code})")
    }
