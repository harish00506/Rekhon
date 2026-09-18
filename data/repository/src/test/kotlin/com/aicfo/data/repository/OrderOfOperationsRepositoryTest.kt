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
import com.aicfo.core.model.CreditCard
import com.aicfo.core.model.Loan
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.card.CardEngineFactory
import com.aicfo.domain.engines.classification.ClassificationEngineFactory
import com.aicfo.domain.engines.emergencyfund.EmergencyFundEngineFactory
import com.aicfo.domain.engines.goals.GoalEngineFactory
import com.aicfo.domain.engines.goals.GoalWaterfallEngineFactory
import com.aicfo.domain.engines.goals.SurplusBasis
import com.aicfo.domain.engines.loan.LoanEngineFactory
import com.aicfo.domain.engines.nature.NatureEngineFactory
import com.aicfo.domain.engines.orderofoperations.DebtKind
import com.aicfo.domain.engines.orderofoperations.DebtPosition
import com.aicfo.domain.engines.orderofoperations.FooStage
import com.aicfo.domain.engines.orderofoperations.OrderOfOperations
import com.aicfo.domain.engines.orderofoperations.OrderOfOperationsEngineFactory
import com.aicfo.domain.engines.orderofoperations.StageOutcome
import com.aicfo.domain.engines.orderofoperations.StageReason
import com.aicfo.domain.engines.orderofoperations.StageStatus
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
 * The data half of AI-FOO — issue 7.5 (SRS §36, ARC-005, ADR-0037).
 *
 * Why:  the engine's unit, golden and property tests already prove the ranking against fixtures, so
 *       repeating them here would assert nothing new. **What is unproven above SQLite is what this
 *       repository resolves**, and every part of it fails while still returning a plausible ranking:
 *
 *       - **the debt join.** A card's APR lives in `credit_card`, a loan's rate in `loan`, and the
 *         balance in `account`. Join the wrong way and a card owing ₹65,000 reads as owing nothing,
 *         or as owing −₹65,000.
 *       - **what is left out.** An archived card, a loan with no terms, a payable, another profile's
 *         debt — each one included by mistake shifts money onto a debt the user cannot act on.
 *       - **what is reused, not re-derived.** The surplus and the goals' need must be exactly the
 *         waterfall's, or the dashboard and the goals screen disagree about how much money exists.
 *       - **the unsized fund.** EMF reports a zero shortfall when it cannot size the fund; passing
 *         that zero on would call an unknown fund complete.
 * What: the join and its exclusions, the reuse, the unsized fund, a live rate edit, a profile switch,
 *       and the overflow fallback.
 * Result: the household ranking is proven against a real SQL engine.
 * Changelog: 2026-09-17 — Created for issue 7.5.
 *
 * Unencrypted in-memory Room and the **real** engines, `GoalWaterfallRepositoryTest`'s reasoning:
 * the claim is that a ranking reaches the screen, and a stub could not make it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OrderOfOperationsRepositoryTest {
    private lateinit var database: CfoDatabase
    private lateinit var transactions: TransactionRepository
    private lateinit var accounts: AccountRepository
    private lateinit var categories: CategoryRepository
    private lateinit var goals: GoalRepository
    private lateinit var cards: CreditCardRepository
    private lateinit var loans: LoanRepository
    private lateinit var waterfall: GoalWaterfallRepository
    private lateinit var ranking: OrderOfOperationsRepository

    // Mid-month, as in GoalWaterfallRepositoryTest: the surplus is taken over the closed months behind.
    private val clock = FakeClock(initialMillis = Instant.parse("2026-09-14T06:00:00Z").toEpochMilli())
    private val ids = FakeIdGenerator()

    // One dispatcher and one scheduler for setUp and every test: `combine` yields between sources.
    private val dispatcher = UnconfinedTestDispatcher()
    private val activeProfileId = MutableStateFlow(REAL_PROFILE)

    /** Input: none. Output: a fresh in-memory database, every repository, and a seeded taxonomy. */
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
            val quickSetup = RepositoryFactory.quickSetup(database, clock, dispatchers, activeProfileId)
            categories.ensureSeeded()
            goals =
                RepositoryFactory.goals(database, GoalEngineFactory.create(), clock, ids, dispatchers, activeProfileId)
            cards =
                RepositoryFactory.creditCards(
                    database, CardEngineFactory.create(), clock, ids, dispatchers, activeProfileId,
                )
            loans = RepositoryFactory.loans(database, LoanEngineFactory.create(), clock, dispatchers, activeProfileId)
            buildRanking(quickSetup, dispatchers)
        }

    /**
     * Builds the emergency fund, the waterfall and the ranking over the repositories [setUp] made —
     * the same composition `RepositoryModule` wires in `:app`.
     * Input:  [quickSetup]; [dispatchers]. Output: none (sets [waterfall] and [ranking]).
     */
    private fun buildRanking(
        quickSetup: QuickSetupRepository,
        dispatchers: TestDispatchers,
    ) {
        val emergencyFund =
            RepositoryFactory.emergencyFund(
                transactions,
                accounts,
                quickSetup,
                EmergencyFundEngineFactory.create(),
                clock,
                dispatchers,
            )
        ranking =
            RepositoryFactory.orderOfOperations(
                database = database,
                goals = goals,
                surplus = RepositoryFactory.surplus(transactions, quickSetup, dispatchers),
                emergencyFund = emergencyFund,
                engine = OrderOfOperationsEngineFactory.create(),
                clock = clock,
                dispatchers = dispatchers,
                activeProfileId = activeProfileId,
            )
        waterfall =
            RepositoryFactory.goalWaterfall(
                goals = goals,
                ranking = ranking,
                emergencyFund = emergencyFund,
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

    // --- an empty profile ---------------------------------------------------------------------

    /**
     * Input:  a profile with nothing in it.
     * Output: a full ranking with no surplus, and an emergency fund reported as unsized — not as done.
     */
    @Test
    fun `an empty profile ranks all eight stages and calls the fund unsized, not complete`() =
        runTest(dispatcher) {
            val result = rank()

            assertEquals(FooStage.entries, result.stages.map { it.stage })
            assertEquals(SurplusBasis.NONE, result.surplusBasis)
            assertNull(result.monthlySurplus)
            val fund = stage(result, FooStage.FULL_EMERGENCY)
            assertEquals(StageReason.EMERGENCY_UNSIZED, fund.reason)
            assertNull("an unsized fund has no known shortfall", fund.need)
            assertEquals(FooStage.STARTER_BUFFER, result.topAction?.stage)
        }

    // --- the debt join ------------------------------------------------------------------------

    /**
     * Input:  a card owing ₹65,000 at 36%, a card owing ₹20,000 with no APR, an 11% car loan owing
     *         ₹4,00,000, and an 8.5% home loan owing ₹25,00,000.
     * Output: each lands in its band with its balance flipped positive and its own name.
     */
    @Test
    fun `every debt is joined to its rate and lands in its band`() =
        runTest(dispatcher) {
            val hdfc = card("HDFC Card", owing = Money(65_000_00L), aprBps = 3_600)
            val axis = card("Axis Card", owing = Money(20_000_00L), aprBps = null)
            val car = loan("Car Loan", owing = Money(4_00_000_00L), rateBps = 1_100)
            val home = loan("Home Loan", owing = Money(25_00_000_00L), rateBps = 850)

            val result = rank()

            assertEquals(
                listOf(
                    DebtPosition(axis.id, "Axis Card", DebtKind.CARD, Money(20_000_00L), null),
                    DebtPosition(hdfc.id, "HDFC Card", DebtKind.CARD, Money(65_000_00L), 3_600),
                ),
                stage(result, FooStage.KILL_FIRE_DEBT).debts,
            )
            assertEquals(StageReason.FIRE_DEBT_CARD_RATE_UNKNOWN, stage(result, FooStage.KILL_FIRE_DEBT).reason)
            assertEquals(
                listOf(DebtPosition(car.id, "Car Loan", DebtKind.LOAN, Money(4_00_000_00L), 1_100)),
                stage(result, FooStage.GREY_ZONE_DEBT).debts,
            )
            assertEquals(
                listOf(DebtPosition(home.id, "Home Loan", DebtKind.LOAN, Money(25_00_000_00L), 850)),
                stage(result, FooStage.LOW_RATE_DEBT).debts,
            )
        }

    /**
     * Input:  a credit-card account with no card terms saved at all.
     * Output: still a debt, with an unknown rate — the account type alone says it is a card.
     */
    @Test
    fun `a card account with no terms saved is still a debt, rate unknown`() =
        runTest(dispatcher) {
            val bare = newAccount("Bare Card", AccountType.CREDIT_CARD, Money(-5_000_00L))

            val fire = stage(rank(), FooStage.KILL_FIRE_DEBT)

            assertEquals(listOf(DebtPosition(bare.id, "Bare Card", DebtKind.CARD, Money(5_000_00L), null)), fire.debts)
        }

    /**
     * Input:  a loan account with no loan terms, an archived card, a card in credit, and a payable.
     * Output: none of them is ranked — no rate to place it by, retired, owing nothing, or not a
     *         debt this engine can price.
     */
    @Test
    fun `debts the engine cannot act on are left out`() =
        runTest(dispatcher) {
            newAccount("Loan, no terms", AccountType.LOAN, Money(-1_00_000_00L))
            val archived = card("Old Card", owing = Money(9_000_00L), aprBps = 3_600)
            accounts.setArchived(archived.id, archived = true).expectOk()
            card("Card in credit", owing = Money(-2_000_00L), aprBps = 3_600)
            newAccount("Owed to Ravi", AccountType.PAYABLE, Money(-3_000_00L))

            val result = rank()

            listOf(FooStage.KILL_FIRE_DEBT, FooStage.GREY_ZONE_DEBT, FooStage.LOW_RATE_DEBT).forEach { foo ->
                assertEquals("$foo", StageStatus.NOT_APPLICABLE, stage(result, foo).status)
                assertTrue("$foo", stage(result, foo).debts.isEmpty())
            }
        }

    /** Input: a card's APR edited from 36% to 12%. Output: the ranking re-emits with it in the grey band. */
    @Test
    fun `editing a card's rate moves it between bands on the next emission`() =
        runTest(dispatcher) {
            val hdfc = card("HDFC Card", owing = Money(65_000_00L), aprBps = 3_600)
            assertEquals(listOf(hdfc.id), stage(rank(), FooStage.KILL_FIRE_DEBT).debts.map { it.accountId })

            cards.save(terms(hdfc.id, aprBps = 1_200)).expectOk()

            val result = rank()
            assertTrue(stage(result, FooStage.KILL_FIRE_DEBT).debts.isEmpty())
            assertEquals(listOf(hdfc.id), stage(result, FooStage.GREY_ZONE_DEBT).debts.map { it.accountId })
        }

    /** Input: a debt on one profile, then a switch to another. Output: the other profile sees none. */
    @Test
    fun `another profile's debts are never ranked`() =
        runTest(dispatcher) {
            card("HDFC Card", owing = Money(65_000_00L), aprBps = 3_600)
            assertEquals(1, stage(rank(), FooStage.KILL_FIRE_DEBT).debts.size)

            activeProfileId.value = DEMO_PROFILE

            assertTrue(stage(rank(), FooStage.KILL_FIRE_DEBT).debts.isEmpty())
        }

    // --- reuse, not re-derivation ---------------------------------------------------------------

    /**
     * Input:  three closed months of income and spending, and one goal.
     * Output: the surplus, its basis and the goals' need are exactly the waterfall's.
     */
    @Test
    fun `the surplus and the goals' need are the waterfall's, not a second calculation`() =
        runTest(dispatcher) {
            seedThreeMonths()
            goals.save(
                GoalDraft(name = "Goa trip", target = Money(1_20_000_00L), targetDateIso = "2027-09-01"),
            ).expectOk()

            val plan = waterfall.observeWaterfall().first()
            val result = rank()

            assertEquals(SurplusBasis.OBSERVED_MEDIAN, result.surplusBasis)
            assertEquals(plan.monthlySurplus, result.monthlySurplus)
            assertEquals(plan.totalRequiredMonthly, stage(result, FooStage.GOAL_INVESTING).need)
            assertTrue(plan.totalRequiredMonthly > Money.ZERO)
        }

    // --- the fallback -------------------------------------------------------------------------

    /**
     * Input:  one card owing just over half of `Long.MAX_VALUE`, then a second.
     * Output: the first is ranked as fire debt; with the second the sum overflows, and a ranking
     *         still arrives — without the debts — rather than the flow failing or never emitting.
     *
     * The first half matters: without it, a bug that silently dropped huge balances would satisfy
     * the second half's assertions without the fallback ever running.
     */
    @Test
    fun `sums that overflow fall back to a ranking without them`() =
        runTest(dispatcher) {
            val huge = Money(Long.MAX_VALUE / 2 + 10)
            val first = card("Huge A", owing = huge, aprBps = 3_600)
            assertEquals(listOf(first.id), stage(rank(), FooStage.KILL_FIRE_DEBT).debts.map { it.accountId })

            card("Huge B", owing = huge, aprBps = 3_600)
            val result = rank()

            assertEquals(StageStatus.NOT_APPLICABLE, stage(result, FooStage.KILL_FIRE_DEBT).status)
            assertEquals(SurplusBasis.NONE, result.surplusBasis)
            assertEquals(FooStage.entries, result.stages.map { it.stage })
        }

    // --- fixtures -----------------------------------------------------------------------------

    private suspend fun rank(): OrderOfOperations = ranking.observe().first()

    private fun stage(
        result: OrderOfOperations,
        foo: FooStage,
    ): StageOutcome = result.stages.single { it.stage == foo }

    /** Result: a card account owing [owing], with terms at [aprBps]. A negative [owing] is credit. */
    private suspend fun card(
        name: String,
        owing: Money,
        aprBps: Int?,
    ): Account {
        val account = newAccount(name, AccountType.CREDIT_CARD, Money.ZERO - owing)
        cards.save(terms(account.id, aprBps)).expectOk()
        return account
    }

    /** Result: card terms for [accountId] — a ₹2,00,000 limit, due on the 26th, at [aprBps]. */
    private fun terms(
        accountId: String,
        aprBps: Int?,
    ) = CreditCard(
        accountId = accountId,
        creditLimit = Money(2_00_000_00L),
        statementDay = 6,
        dueDay = 26,
        aprBps = aprBps,
    )

    /** Result: a loan account owing [owing], with terms at [rateBps]. */
    private suspend fun loan(
        name: String,
        owing: Money,
        rateBps: Int,
    ): Account {
        val account = newAccount(name, AccountType.LOAN, Money.ZERO - owing)
        loans.save(
            Loan(
                accountId = account.id,
                principal = owing,
                annualRateBps = rateBps,
                tenureMonths = 60,
                firstEmiIsoDate = "2026-01-05",
            ),
        ).expectOk()
        return account
    }

    /** Result: a saved account. Input: [name]; [type]; [opening]. Output: the account. */
    private suspend fun newAccount(
        name: String,
        type: AccountType,
        opening: Money,
    ): Account =
        accounts.create(
            AccountDraft(name = name, type = type, openingBalance = opening, currencyCode = "INR"),
        ).expectOk()

    /**
     * Seeds three closed months: ₹1,00,000 income and ₹30,000 / ₹40,000 / ₹90,000 of groceries —
     * `GoalWaterfallRepositoryTest`'s household, so the surplus is a real observed median.
     */
    private suspend fun seedThreeMonths() {
        val bank = newAccount("HDFC Savings", AccountType.BANK, Money(2_00_000_00L))
        val groceries = categories.observeCategories().first().first { it.name == "Groceries" }.id
        listOf("2026-06" to 30_000_00L, "2026-07" to 40_000_00L, "2026-08" to 90_000_00L).forEach { (month, spent) ->
            transactions.create(
                TransactionDraft(
                    accountId = bank.id,
                    amount = Money(1_00_000_00L),
                    bookedOn = LocalDate.parse("$month-01"),
                ),
            ).expectOk()
            transactions.create(
                TransactionDraft(
                    accountId = bank.id,
                    amount = Money(-spent),
                    categoryId = groceries,
                    bookedOn = LocalDate.parse("$month-10"),
                ),
            ).expectOk()
        }
    }

    private companion object {
        const val REAL_PROFILE = "local"
        const val DEMO_PROFILE = "demo"
    }
}

/**
 * Unwraps a result the test expects to have succeeded.
 * Why:    an `Err` here is a test failure, and `as Ok` would report it as a `ClassCastException`
 *         naming neither the code nor the call. Matched on the type so an `Ok(null)` is not a failure.
 * Result: the value. Input: the receiver. Output: [T]; throws [AssertionError] on an `Err`.
 * Changelog: 2026-09-17 — Created for issue 7.5.
 */
private fun <T> com.aicfo.core.common.Result<T, AppError>.expectOk(): T =
    when (this) {
        is Ok -> value
        is Err -> throw AssertionError("expected Ok, got Err(${error.code})")
    }
