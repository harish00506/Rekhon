package com.aicfo.data.repository

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.FakeClock
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.TestDispatchers
import com.aicfo.core.model.Account
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.CreditCard
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Loan
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import com.aicfo.domain.engines.card.BillingCycle
import com.aicfo.domain.engines.card.CardStatus
import com.aicfo.domain.engines.card.Utilisation
import com.aicfo.domain.engines.card.UtilisationBasis
import com.aicfo.domain.engines.loan.AmortisationRow
import com.aicfo.domain.engines.simulator.SimulatorFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The simulators over real debts (issue 10.3; §36, §40.2, FLT-004, P-03, P-07).
 *
 * Why:  the arithmetic is proven in `:domain:engines:simulator`. What only this layer can get wrong
 *       is *which numbers go in* — and every one of them is a figure the app already shows
 *       somewhere else. A simulation that used the origination rate instead of the current one
 *       (FLT-004), or invented an APR for a card that has none recorded (P-03), would be
 *       confidently wrong in a way no engine test could see.
 * What: the debts a household can simulate, the figures handed to each simulator, the cards that
 *       are deliberately left out, and the refusals.
 * Result: a what-if that is about this household.
 * Changelog: 2026-09-26 — Created for issue 10.3.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimulatorRepositoryTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = FakeClock(Instant.parse("2026-09-26T04:30:00Z").toEpochMilli(), ZoneId.of("Asia/Kolkata"))
    private val accounts = MutableStateFlow(ACCOUNTS)
    private val instalments = MutableStateFlow(mapOf(LOAN_ACCOUNT to NEXT_INSTALMENT))
    private val cards = MutableStateFlow(mapOf(CARD_ACCOUNT to cardStatus()))
    private val loans = FakeLoans()
    private val cardTerms = FakeCards()

    @Test
    fun `the debts a household can simulate are its loans and its revolving cards`() =
        runTest(dispatcher) {
            val debts = repository().observeDebts().first()

            assertEquals(listOf("Home loan", "Credit card"), debts.map { it.name })
            assertEquals(Money(20_00_000_00L), debts.first().outstanding)
            assertEquals(
                "the loan's own rate, not one reverse-engineered from the EMI",
                900,
                debts.first().annualRateBps,
            )
            assertEquals(Money(80_000_00L), debts.last().outstanding)
            assertEquals(4_200, debts.last().annualRateBps)
        }

    @Test
    fun `a card with no rate recorded is left out rather than guessed at`() =
        runTest(dispatcher) {
            // P-03: an invented APR would put a fabricated figure into a plan the user might follow.
            cardTerms.aprBps = null

            val debts = repository().observeDebts().first()

            assertEquals(listOf("Home loan"), debts.map { it.name })
        }

    @Test
    fun `a card with nothing on its statement is not a debt`() =
        runTest(dispatcher) {
            cards.value = mapOf(CARD_ACCOUNT to cardStatus(statement = Money.ZERO))

            assertEquals(listOf("Home loan"), repository().observeDebts().first().map { it.name })
        }

    @Test
    fun `prepaying uses the loan's current rate and the balance it still owes`() =
        runTest(dispatcher) {
            val comparison =
                repository().prepayVsInvest(
                    accountId = LOAN_ACCOUNT,
                    lumpSum = Money(2_00_000_00L),
                    expectedReturnBps = 1_200,
                    taxOnReturnsBps = 3_000,
                ).expectOk()

            assertEquals(Money(20_00_000_00L), comparison.outstanding)
            assertEquals(900, comparison.annualRateBps)
            assertEquals(Money(2_00_000_00L), comparison.lumpSum)
            assertTrue("the prepayment shortens the loan", comparison.prepay.monthsSaved > 0)
        }

    @Test
    fun `the payoff plan covers every debt the household has`() =
        runTest(dispatcher) {
            val comparison = repository().payoff(extraMonthly = Money(5_000_00L)).expectOk()

            assertEquals(listOf("Home loan", "Credit card"), comparison.debts.map { it.name })
            assertEquals(listOf("Credit card", "Home loan"), comparison.avalanche.order)
            assertTrue(comparison.avalanche.totalInterest <= comparison.snowball.totalInterest)
        }

    @Test
    fun `asking about a loan that is not there is refused`() =
        runTest(dispatcher) {
            val result =
                repository().prepayVsInvest("account:missing", Money(1_000_00L), 1_200, 0)

            assertEquals(AppError.Validation("simulator.loan"), (result as Err).error)
        }

    @Test
    fun `a household with nothing owed has nothing to simulate`() =
        runTest(dispatcher) {
            instalments.value = emptyMap()
            cards.value = emptyMap()

            val result = repository().payoff(extraMonthly = Money(5_000_00L))

            assertEquals(AppError.Validation("simulator.debts"), (result as Err).error)
        }

    // --- fixtures ---------------------------------------------------------------------------------

    private fun repository(): SimulatorRepository =
        LiveSimulatorRepository(
            accounts = accounts,
            loans = loans,
            cards = cards,
            cardTerms = cardTerms,
            instalments = instalments,
            prepaySimulator = SimulatorFactory.prepayVsInvest(),
            payoffSimulator = SimulatorFactory.debtPayoff(),
            clock = clock,
            dispatchers = TestDispatchers(dispatcher),
        )

    private fun cardStatus(statement: Money = Money(80_000_00L)) =
        CardStatus(
            accountId = CARD_ACCOUNT,
            creditLimit = Money(2_00_000_00L),
            live = Utilisation(UtilisationBasis.STATEMENT, statement, 4_000),
            statement = Utilisation(UtilisationBasis.STATEMENT, statement, 4_000),
            unbilled = Money.ZERO,
            available = Money(1_20_000_00L),
            cycle =
                BillingCycle(
                    statementDate = java.time.LocalDate.parse("2026-09-06"),
                    dueDate = java.time.LocalDate.parse("2026-10-05"),
                    nextStatementDate = java.time.LocalDate.parse("2026-10-06"),
                    daysUntilDue = 9,
                ),
            minimumDue = Money(4_000_00L),
            provenance = EngineProvenance("AI-CARD", "1.0", 0L, listOf(RuleCitation("RULE-CC-UTIL", "1.0"))),
        )

    /** A loan repository that knows one loan — the rate the simulator must read. */
    private class FakeLoans : LoanRepository {
        override fun observeNextInstalments(): Flow<Map<String, AmortisationRow>> = flowOf(emptyMap())

        override suspend fun find(accountId: String): Result<Loan?, AppError> =
            Ok(
                if (accountId == LOAN_ACCOUNT) {
                    Loan(
                        accountId = LOAN_ACCOUNT,
                        principal = Money(25_00_000_00L),
                        annualRateBps = 900,
                        tenureMonths = 240,
                        firstEmiIsoDate = "2020-10-05",
                    )
                } else {
                    null
                },
            )

        override suspend fun save(loan: Loan): Result<Unit, AppError> = error("a simulation writes nothing (P-07)")

        override suspend fun schedule(accountId: String) = error("not read by the simulator")
    }

    /** A card repository that knows one card's terms. */
    private class FakeCards : CreditCardRepository {
        var aprBps: Int? = 4_200

        override fun observeCardStatuses(): Flow<Map<String, CardStatus>> = flowOf(emptyMap())

        override suspend fun find(accountId: String): Result<CreditCard?, AppError> =
            Ok(
                CreditCard(
                    accountId = CARD_ACCOUNT,
                    creditLimit = Money(2_00_000_00L),
                    statementDay = 6,
                    dueDay = 25,
                    lastStatement = Money(80_000_00L),
                    minimumDue = Money(4_000_00L),
                    aprBps = aprBps,
                ),
            )

        override suspend fun save(card: CreditCard): Result<Unit, AppError> =
            error(
                "a simulation writes nothing (P-07)",
            )

        override suspend fun pendingAlerts() = error("not read by the simulator")

        override suspend fun markNotified(alert: com.aicfo.domain.engines.card.CardAlert) = error("not read here")
    }

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val LOAN_ACCOUNT = "account:loan"
        const val CARD_ACCOUNT = "account:card"

        val ACCOUNTS =
            listOf(
                Account(
                    id = LOAN_ACCOUNT,
                    profileId = "local",
                    name = "Home loan",
                    type = AccountType.LOAN,
                    institution = null,
                    openingBalance = Money.ZERO,
                    balance = Money.ZERO,
                    currencyCode = "INR",
                    isArchived = false,
                ),
                Account(
                    id = CARD_ACCOUNT,
                    profileId = "local",
                    name = "Credit card",
                    type = AccountType.CREDIT_CARD,
                    institution = null,
                    openingBalance = Money.ZERO,
                    balance = Money.ZERO,
                    currencyCode = "INR",
                    isArchived = false,
                ),
            )

        val NEXT_INSTALMENT =
            AmortisationRow(
                number = 61,
                dueIsoDate = "2026-10-05",
                amount = Money(22_493_00L),
                principal = Money(7_493_00L),
                interest = Money(15_000_00L),
                openingBalance = Money(20_00_000_00L),
                closingBalance = Money(19_92_507_00L),
            )
    }
}
