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
import com.aicfo.domain.engines.emergencyfund.EmergencyFundPlan
import com.aicfo.domain.engines.emergencyfund.EmergencyStatus
import com.aicfo.domain.engines.emergencyfund.EssentialsBasis
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

/**
 * The data half of the emergency fund — issue 7.2 (SRS §10.1, ARC-005).
 *
 * Why:  `EmergencyFundEngineTest`, the property test and the golden file already prove the
 *       arithmetic against fixtures, so repeating it here would assert nothing new. **What is
 *       unproven above SQLite is what this class owns** — the three judgements the repository makes
 *       before the engine sees anything, each of which fails while still returning a plausible
 *       number:
 *
 *       - **which months count.** Include the live month and every essentials figure is dragged down
 *         by however far into it the user happens to be; the target shrinks a little every day of
 *         the month and jumps back on the 1st.
 *       - **the median rather than the mean.** One annual insurance premium in six months lifts a
 *         mean by a sixth of the premium and a median by nothing. Both look like money.
 *       - **the fallback.** A day-one user must get the envelope, not a zero — a zero would size a
 *         ₹0 target and report a fully funded emergency fund to somebody with nothing saved.
 *       - **what counts as liquid.** A ₹10,00,000 mutual-fund holding is not an emergency fund, and
 *         counting it would tell a user with no savings at all that they are in surplus.
 * What: the median across months, the envelope fallback, the unknown case, the liquid filter, and
 *       the exclusion of the live month.
 * Result: the first runway figure in the app is proven against a real SQL engine.
 * Changelog: 2026-09-02 — Created for issue 7.2.
 *
 * Unencrypted in-memory Room and the **real** engines rather than stubs, the reasoning
 * `GoalRepositoryTest` gives: the claim is that a runway reaches the screen, and a stub could not
 * make it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EmergencyFundRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var transactions: TransactionRepository
    private lateinit var accounts: AccountRepository
    private lateinit var categories: CategoryRepository
    private lateinit var quickSetup: QuickSetupRepository
    private lateinit var emergencyFund: EmergencyFundRepository

    // Mid-month on purpose: the history window is the *closed* months behind this one, and a clock
    // on the 1st or the 31st would let an off-by-one bound pass unnoticed.
    private val clock = FakeClock(initialMillis = Instant.parse("2026-09-14T06:00:00Z").toEpochMilli())
    private val ids = FakeIdGenerator()

    // One dispatcher, one scheduler, shared by setUp and every test. `combine` yields
    // between sources, and two schedulers make that throw rather than emit.
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
            emergencyFund =
                RepositoryFactory.emergencyFund(
                    transactions, accounts, quickSetup, EmergencyFundEngineFactory.create(), clock, dispatchers,
                )
        }

    /** Input: none. Output: closes the database between tests. */
    @After
    fun tearDown() {
        database.close()
    }

    /**
     * Input:  three closed months of groceries — ₹30,000, ₹40,000 and one outlier month of
     *         ₹1,00,000 — with a steady ₹50,000 salary each month.
     * Output: essentials of **₹40,000, the median**, not ₹56,666.67, the mean.
     *
     * The assertion this class exists for. A mean would be defensible, would look like money, and
     * would size the target 42% too high off one month somebody replaced a fridge in.
     */
    @Test
    fun `essentials are the median of the closed months, so one unusual month does not size the target`() =
        runTest(dispatcher) {
            val bank = seedThreeMonths()

            val plan = plan()

            assertEquals(EssentialsBasis.OBSERVED_MEDIAN, plan.essentialsBasis)
            assertEquals("the median, not the ₹56,666.67 mean", Money(40_000_00L), plan.monthlyEssentials)
            // Steady salary: a cv of zero, so no volatility bump and M is the base six months.
            assertEquals(0, plan.incomeCvBps)
            assertEquals(6, plan.multiplierMonths)
            assertEquals(Money(2_40_000_00L), plan.target)
            // ₹2,00,000 opening − ₹1,70,000 of groceries + ₹1,50,000 of salary.
            assertEquals(Money(1_80_000_00L), plan.liquidFunds)
            assertEquals(listOf(bank.name), plan.liquidAccountNames)
            // 4.5 months of cover: above the urgent band, below the target.
            assertEquals(45_000, plan.runwayMonthsBps)
            assertEquals(EmergencyStatus.BUILDING, plan.status)
            assertEquals(Money(60_000_00L), plan.shortfall)
            assertEquals(Money(10_000_00L), plan.topUpMonthly)
        }

    /**
     * Input:  the same three months, plus a fourth month of groceries inside the **live** month.
     * Output: the same essentials as above — the live month changed nothing.
     *
     * A live month is partly unspent by definition. Counting it would make the target drift down
     * through every month and jump back on the 1st, which is the kind of wrong nobody reports as a
     * bug because each individual reading looks reasonable.
     */
    @Test
    fun `the live month is excluded from the history`() =
        runTest(dispatcher) {
            seedThreeMonths()
            // Two days into the app's "today" of 2026-09-14, and far larger than any closed month.
            expense(bankId(), Money(-3_00_000_00L), LocalDate.parse("2026-09-02"))

            val plan = plan()

            assertEquals(Money(40_000_00L), plan.monthlyEssentials)
            assertEquals(EssentialsBasis.OBSERVED_MEDIAN, plan.essentialsBasis)
        }

    /**
     * Input:  a declared needs envelope and only **two** months of history.
     * Output: the envelope, labelled `DECLARED_ENVELOPE`.
     *
     * `min_months_observed` is three. Two months is not a habit, and taking a median of two would
     * hand a target the same confidence as one built from six.
     */
    @Test
    fun `too little history falls back to the envelope the user declared at onboarding`() =
        runTest(dispatcher) {
            val bank = newBank()
            expense(bank.id, Money(-30_000_00L), LocalDate.parse("2026-07-10"))
            expense(bank.id, Money(-40_000_00L), LocalDate.parse("2026-08-10"))
            seedEnvelopes()

            val plan = plan()

            assertEquals(EssentialsBasis.DECLARED_ENVELOPE, plan.essentialsBasis)
            assertEquals(Money(50_000_00L), plan.monthlyEssentials)
            assertEquals(Money(3_00_000_00L), plan.target)
        }

    /**
     * Input:  no history and no envelope.
     * Output: `UNKNOWN`, a null essentials and a null runway — **not a zero target**.
     *
     * A zero target is the dangerous answer: `liquidFunds >= target` would be true for a user with
     * nothing saved at all, and the screen would congratulate them.
     */
    @Test
    fun `a fresh profile reports unknown rather than a zero target`() =
        runTest(dispatcher) {
            newBank()

            val plan = plan()

            assertEquals(EmergencyStatus.UNKNOWN, plan.status)
            assertNull(plan.monthlyEssentials)
            assertNull(plan.runwayMonthsBps)
            assertEquals(EssentialsBasis.NONE, plan.essentialsBasis)
            assertEquals(Money.ZERO, plan.target)
            assertTrue("an unanswered question must not read as a funded fund", !plan.isFunded)
        }

    /**
     * Input:  a bank account, a cash wallet, a large investment account and an empty second bank.
     * Output: only the bank and the cash count, and only they are named as evidence.
     *
     * §10.1 would also count a breakable FD and a liquid mutual fund, through a per-account
     * liquidity tier this schema does not have. Rather than guess one from `AccountType` — which
     * would decide for every user at once that every FD is breakable, or that none is — the
     * investment account is left out and the omission is visible in the names (ADR-0034).
     */
    @Test
    fun `only bank and cash accounts with a positive balance count as liquid`() =
        runTest(dispatcher) {
            val bank = newBank()
            val wallet = newAccount(AccountType.CASH, "Cash Wallet", Money(5_000_00L))
            newAccount(AccountType.INVESTMENT, "Groww portfolio", Money(10_00_000_00L))
            newAccount(AccountType.BANK, "Dormant savings", Money.ZERO)
            seedEnvelopes()

            val plan = plan()

            // In `observeAccounts`' own order, not this test's — the evidence list is rendered as
            // given, so its order has to be the repository's and stay stable (P-08).
            assertEquals(
                "a mutual-fund holding is not an emergency fund, and an empty account is not liquidity",
                listOf(wallet.name, bank.name),
                plan.liquidAccountNames,
            )
            assertEquals(Money(2_05_000_00L), plan.liquidFunds)
        }

    /**
     * Input:  a lumpy salary across the same three months.
     * Output: a non-zero cv and a **larger** multiplier than the steady case above.
     *
     * The one term that makes M personal, proven end to end rather than only against fixtures: the
     * income series has to survive the ledger, the `INCOME` filter and the month grouping to get
     * here at all.
     */
    @Test
    fun `a lumpy income raises the multiplier through the real ledger`() =
        runTest(dispatcher) {
            val bank = newBank()
            listOf("2026-06", "2026-07", "2026-08").forEachIndexed { index, month ->
                expense(bank.id, Money(-40_000_00L), LocalDate.parse("$month-10"))
                income(bank.id, LUMPY_SALARIES[index], LocalDate.parse("$month-01"))
            }

            val plan = plan()

            assertTrue("a lumpy income read as steady: cv was ${plan.incomeCvBps}", (plan.incomeCvBps ?: 0) > 0)
            assertTrue("the volatility bump never reached M: ${plan.multiplierMonths}", plan.multiplierMonths > 6)
        }

    // --- helpers ----------------------------------------------------------------------------------

    /** Result: the current assessment. Input: none. Output: [EmergencyFundPlan]. */
    private suspend fun plan(): EmergencyFundPlan = emergencyFund.observeEmergencyFund().first()

    /**
     * Seeds three closed months: groceries of ₹30,000 / ₹40,000 / ₹1,00,000 and a steady salary.
     * Result: the bank account. Input: none. Output: the account.
     */
    private suspend fun seedThreeMonths(): Account {
        val bank = newBank()
        val groceries = idOf("Groceries")
        listOf("2026-06" to 30_000_00L, "2026-07" to 40_000_00L, "2026-08" to 1_00_000_00L)
            .forEach { (month, amount) ->
                expense(bank.id, Money(-amount), LocalDate.parse("$month-10"), groceries)
                income(bank.id, Money(50_000_00L), LocalDate.parse("$month-01"))
            }
        return bank
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
     */
    private suspend fun seedEnvelopes() {
        // Through the real engine rather than a hand-built plan, the reasoning
        // `QuickSetupRepositoryTest` gives: a fixture would let this pass against an envelope the
        // engine never produces. ₹1,00,000 income with ₹20,000 rent keeps needs inside RULE-50-30-20's
        // 50% band with no metro flex, so the NEED envelope is exactly ₹50,000.
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

        /** ₹20,000 / ₹50,000 / ₹80,000 — the same mean as the steady case, a far wider spread. */
        val LUMPY_SALARIES = listOf(Money(20_000_00L), Money(50_000_00L), Money(80_000_00L))
    }
}

/**
 * Unwraps a result the test expects to have succeeded.
 * Why:    an `Err` here is a test failure, and `as Ok` would report it as a `ClassCastException`
 *         naming neither the code nor the call — the helper every repository test in this module
 *         carries, kept file-private so each states its own reason.
 * Result: the value. Input: the receiver. Output: [T]; throws [AssertionError] on an `Err`.
 * Changelog: 2026-09-02 — Created for issue 7.2.
 */
private fun <T> com.aicfo.core.common.Result<T, AppError>.expectOk(): T =
    when (this) {
        is Ok -> value
        is Err -> throw AssertionError("expected Ok, got Err(${error.code})")
    }
