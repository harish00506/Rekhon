package com.aicfo.domain.engines.simulator

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * AI-SIM's promises, over many generated households (issue 10.3; §21.5, §40.2, P-08).
 *
 * Why:  the example tests pin particular loans and particular debt piles. These pin what must be
 *       true of **every** one — including the claim §40.2 rests its default on: that avalanche
 *       never costs more interest than snowball. If that failed even once, the app would be
 *       recommending the dearer strategy.
 * What: 300 seeded cases per property.
 * Result: a broken promise names its case.
 * Changelog: 2026-09-26 — Created for issue 10.3.
 */
class SimulatorPropertyTest {
    private val prepay = SimulatorFactory.prepayVsInvest()
    private val payoff = SimulatorFactory.debtPayoff()

    @Test
    fun `prepaying never costs more interest than leaving the loan alone`() {
        repeat(CASES) { case ->
            val comparison = prepay.simulate(loan(Random(case))).expectOk()

            assertTrue("case $case", comparison.prepay.totalInterest <= comparison.baseline.totalInterest)
            assertTrue("case $case", comparison.prepay.interestSaved >= Money.ZERO)
            assertTrue("case $case", comparison.prepay.monthsSaved >= 0)
        }
    }

    @Test
    fun `a larger prepayment never saves less`() {
        repeat(CASES) { case ->
            val random = Random(case)
            val smaller = loan(random)
            val larger = smaller.copy(lumpSum = smaller.lumpSum + Money(50_000_00L))

            val saved = prepay.simulate(smaller).expectOk().prepay.interestSaved
            val savedMore = prepay.simulate(larger).expectOk().prepay.interestSaved

            assertTrue("case $case: $saved then $savedMore", savedMore >= saved)
        }
    }

    @Test
    fun `the verdict always agrees with the two figures beside it`() {
        repeat(CASES) { case ->
            val comparison = prepay.simulate(loan(Random(case))).expectOk()

            val expected =
                when {
                    comparison.prepay.interestSaved > comparison.invest.afterTaxGain -> SimulatorVerdict.PREPAY_AHEAD
                    comparison.invest.afterTaxGain > comparison.prepay.interestSaved -> SimulatorVerdict.INVEST_AHEAD
                    else -> SimulatorVerdict.LEVEL
                }
            assertEquals("case $case", expected, comparison.verdict)
        }
    }

    @Test
    fun `avalanche never costs more interest than snowball`() {
        // The claim CRD-005 makes avalanche the default on. It has to hold every time.
        repeat(CASES) { case ->
            val comparison = payoff.simulate(debts(Random(case))).expectOk()

            val dearest = comparison.avalanche.totalInterest
            val smallest = comparison.snowball.totalInterest
            assertTrue("case $case: avalanche $dearest vs snowball $smallest", dearest <= smallest)
            assertTrue("case $case", comparison.interestSavedByAvalanche >= Money.ZERO)
        }
    }

    @Test
    fun `both strategies clear every debt, each exactly once`() {
        repeat(CASES) { case ->
            val input = debts(Random(case))
            val comparison = payoff.simulate(input).expectOk()
            val names = input.debts.map { it.name }.sorted()

            assertEquals("case $case", names, comparison.avalanche.order.sorted())
            assertEquals("case $case", names, comparison.snowball.order.sorted())
        }
    }

    @Test
    fun `the same question is answered the same way twice`() {
        repeat(CASES) { case ->
            val loan = loan(Random(case))
            val debts = debts(Random(case))

            assertEquals("case $case", prepay.simulate(loan).expectOk(), prepay.simulate(loan).expectOk())
            assertEquals("case $case", payoff.simulate(debts).expectOk(), payoff.simulate(debts).expectOk())
        }
    }

    /** Result: a loan whose EMI comfortably covers its interest. Input: [random]. */
    private fun loan(random: Random): PrepayInput {
        val outstanding = Money(random.nextLong(1_00_000_00L, 50_00_000_00L))
        val rate = random.nextInt(600, 2_400)
        // A payment of at least 2% of the balance clears any rate this generator produces.
        val emi = Money(maxOf(outstanding.minor / 50, 5_000_00L))
        return PrepayInput(
            outstanding = outstanding,
            annualRateBps = rate,
            remainingMonths = random.nextInt(12, 240),
            emi = emi,
            lumpSum = Money(random.nextLong(10_000_00L, outstanding.minor / 2)),
            expectedReturnBps = random.nextInt(0, 1_800),
            taxOnReturnsBps = random.nextInt(0, 4_000),
            nowUtcMillis = 0L,
        )
    }

    /** Result: two to four debts, each with a minimum that covers its interest. Input: [random]. */
    private fun debts(random: Random): PayoffInput {
        val debts =
            (1..random.nextInt(2, 5)).map { index ->
                val balance = Money(random.nextLong(10_000_00L, 3_00_000_00L))
                val rate = random.nextInt(800, 4_500)
                Debt(
                    name = "Debt $index",
                    balance = balance,
                    annualRateBps = rate,
                    minimumPayment = Money(maxOf(balance.minor / 20, 2_000_00L)),
                )
            }
        return PayoffInput(debts, Money(random.nextLong(0L, 20_000_00L)), nowUtcMillis = 0L)
    }

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val CASES = 300
    }
}
