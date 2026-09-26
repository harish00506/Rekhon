package com.aicfo.domain.engines.simulator

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Loan
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.loan.LoanEngineFactory
import com.aicfo.domain.engines.loan.LoanTermsInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which debt first — avalanche or snowball (issue 10.3; SRS §40.2 CRD-005, RULE-PAYOFF-ORDER,
 * P-02, P-07).
 *
 * Why:  the two strategies disagree, and the disagreement is the whole point: avalanche costs less
 *       and snowball feels better, so §40.2 says show **both** with the interest and date deltas and
 *       let the user pick. The simulator must therefore be right about both, and honest about what
 *       the cheaper one actually saves.
 * What: the order each strategy pays in; the rollover of freed minimums; the total interest and
 *       debt-free date for each; the deltas; and the refusals.
 * Result: two plans a user can choose between on arithmetic.
 * Changelog: 2026-09-26 — Created for issue 10.3.
 */
class DebtPayoffSimulatorTest {
    private val simulator = SimulatorFactory.debtPayoff()

    @Test
    fun `avalanche attacks the dearest debt first, and the order is when each is cleared`() {
        // The card at 42% is targeted first and so clears first. The phone EMI clears **second**
        // even though it is never targeted, because ₹18,000 at ₹1,600 a month runs out before a
        // ₹2,00,000 loan at ₹7,000 does. `order` is the order debts *leave*, not the order they are
        // attacked — the first draft of this test confused the two.
        val plan = simulate().avalanche

        assertEquals(listOf("Card", "Phone EMI", "Personal loan"), plan.order)
    }

    @Test
    fun `snowball clears the smallest debt first`() {
        val plan = simulate().snowball

        assertEquals(listOf("Phone EMI", "Card", "Personal loan"), plan.order)
    }

    @Test
    fun `avalanche never costs more interest than snowball`() {
        // The whole reason avalanche is the default (CRD-005).
        val comparison = simulate()

        assertTrue(comparison.avalanche.totalInterest <= comparison.snowball.totalInterest)
        assertEquals(
            comparison.snowball.totalInterest - comparison.avalanche.totalInterest,
            comparison.interestSavedByAvalanche,
        )
    }

    @Test
    fun `both plans report when the last rupee is paid, and the difference between them`() {
        val comparison = simulate()

        assertTrue(comparison.avalanche.months > 0)
        assertTrue(comparison.snowball.months > 0)
        assertEquals(
            comparison.snowball.months - comparison.avalanche.months,
            comparison.monthsSavedByAvalanche,
        )
    }

    @Test
    fun `a freed minimum rolls into the next debt`() {
        // The rollover is what makes either strategy work; without it the last debt is paid at its
        // own minimum for ever. With ₹5,000 extra, three debts clear far sooner than the longest of
        // them would alone.
        val withRollover = simulate().avalanche.months
        val longestAlone = simulate(extraMonthly = Money.ZERO).avalanche.months

        assertTrue("the extra and the rollover must shorten the plan", withRollover < longestAlone)
    }

    @Test
    fun `one debt is the same arithmetic the loan engine does`() {
        // The anti-drift test: with a single debt paid at exactly its EMI, the simulator's interest
        // must equal the amortisation schedule `:domain:engines:loan` already owns. Two definitions
        // of monthly interest is precisely the bug this catches.
        val loan =
            Loan(
                accountId = "account:1",
                principal = Money(5_00_000_00L),
                annualRateBps = 1_200,
                tenureMonths = 36,
                firstEmiIsoDate = "2026-10-05",
            )
        val schedule = LoanEngineFactory.create().schedule(LoanTermsInput(loan)).expectOk()
        val emi = schedule.rows.first().amount

        val plan =
            simulator.simulate(
                PayoffInput(
                    debts = listOf(Debt("Loan", Money(5_00_000_00L), 1_200, emi)),
                    extraMonthly = Money.ZERO,
                    nowUtcMillis = NOW,
                ),
            ).expectOk().avalanche

        assertEquals("the same months", schedule.rows.size, plan.months)
        assertEquals(
            "the same interest",
            schedule.rows.fold(Money.ZERO) { sum, row -> sum + row.interest },
            plan.totalInterest,
        )
    }

    @Test
    fun `a debt whose minimum cannot cover its interest is refused, not simulated for ever`() {
        val result =
            simulator.simulate(
                PayoffInput(
                    debts = listOf(Debt("Card", Money(1_00_000_00L), 4_200, Money(100_00L))),
                    extraMonthly = Money.ZERO,
                    nowUtcMillis = NOW,
                ),
            )

        assertEquals(AppError.Validation("payoff.minimum"), (result as Err).error)
    }

    @Test
    fun `impossible inputs are refused by field`() {
        assertEquals(AppError.Validation("payoff.debts"), error(input(debts = emptyList())))
        assertEquals(AppError.Validation("payoff.extraMonthly"), error(input(extraMonthly = Money(-1L))))
        assertEquals(
            AppError.Validation("payoff.balance"),
            error(input(debts = listOf(Debt("Card", Money(-1L), 4_200, Money(5_000_00L))))),
        )
    }

    @Test
    fun `every plan shows the debts it started from (P-02)`() {
        val comparison = simulate()

        assertEquals(3, comparison.debts.size)
        assertEquals(Money(5_000_00L), comparison.extraMonthly)
        assertEquals(listOf("Card", "Personal loan", "Phone EMI"), comparison.debts.map { it.name })
    }

    @Test
    fun `provenance names the engine and its rule`() {
        val provenance = simulate().provenance

        assertEquals("AI-SIM", provenance.engineId)
        assertEquals("1.0", provenance.engineVersion)
        assertEquals(listOf(SimulatorRules.PAYOFF_ORDER), provenance.evidence)
    }

    @Test
    fun `the same debts are simulated the same way twice`() {
        assertEquals(simulate(), simulate())
    }

    // --- fixtures ----------------------------------------------------------------------------------

    /**
     * Three debts a household might actually carry: a card at 42% with ₹80,000 on it, a personal
     * loan at 16% with ₹2,00,000, and a phone EMI at 14% with ₹18,000 — so the dearest and the
     * smallest are different debts, which is the only case where the two strategies disagree.
     */
    private fun input(
        debts: List<Debt> = DEBTS,
        extraMonthly: Money = Money(5_000_00L),
    ) = PayoffInput(debts = debts, extraMonthly = extraMonthly, nowUtcMillis = NOW)

    private fun simulate(extraMonthly: Money = Money(5_000_00L)): PayoffComparison =
        simulator.simulate(input(extraMonthly = extraMonthly)).expectOk()

    private fun error(input: PayoffInput): AppError =
        when (val result = simulator.simulate(input)) {
            is Ok -> throw AssertionError("expected Err, got ${result.value.avalanche.months}")
            is Err -> result.error
        }

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val NOW = 1_790_000_000_000L

        val DEBTS =
            listOf(
                Debt("Card", Money(80_000_00L), 4_200, Money(4_000_00L)),
                Debt("Personal loan", Money(2_00_000_00L), 1_600, Money(7_000_00L)),
                Debt("Phone EMI", Money(18_000_00L), 1_400, Money(1_600_00L)),
            )
    }
}
