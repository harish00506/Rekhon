package com.aicfo.domain.engines.simulator

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prepay the loan, or invest the same money (issue 10.3; SRS §36 stage 6/7, FLT-004,
 * RULE-PREPAY-VS-INVEST, P-02, P-07).
 *
 * Why:  this is the question people get wrong in both directions — paying off a 7% home loan while
 *       ignoring an 18% card, or chasing 12% returns while a 14% loan runs. The simulator's job is
 *       to put the two numbers side by side **and show the rate at which the answer flips**, so the
 *       user decides on arithmetic rather than on feeling (P-07: it simulates, it never moves a
 *       rupee).
 * What: the interest a prepayment saves and the months it removes; what the same money would earn
 *       after tax; which is ahead; the breakeven return; and the refusals.
 * Result: a regression in any of them names itself.
 * Changelog: 2026-09-26 — Created for issue 10.3.
 */
class PrepayVsInvestSimulatorTest {
    private val simulator = SimulatorFactory.prepayVsInvest()

    @Test
    fun `prepaying removes interest, and the simulator says how much`() {
        // ₹20,00,000 left at 9% over 180 months; ₹2,00,000 spare.
        val comparison = simulate()

        assertTrue("a prepayment always saves something", comparison.prepay.interestSaved > Money.ZERO)
        assertTrue("and always shortens the loan", comparison.prepay.monthsSaved > 0)
        assertEquals(
            "the saving is the difference between the two interest totals",
            comparison.baseline.totalInterest - comparison.prepay.totalInterest,
            comparison.prepay.interestSaved,
        )
    }

    @Test
    fun `the loan is judged at its current rate, not the one it started at`() {
        // FLT-004: a repo-linked loan's effective rate is the only one that matters here.
        val cheap = simulate(annualRateBps = 700).prepay.interestSaved
        val dear = simulate(annualRateBps = 1_200).prepay.interestSaved

        assertTrue("a dearer loan saves more when prepaid: $cheap vs $dear", dear > cheap)
    }

    @Test
    fun `investing is judged after tax, not before`() {
        val untaxed = simulate(taxOnReturnsBps = 0).invest.afterTaxGain
        val taxed = simulate(taxOnReturnsBps = 3_000).invest.afterTaxGain

        assertTrue("tax reduces what investing earns: $untaxed vs $taxed", taxed < untaxed)
    }

    @Test
    fun `the verdict is whichever is ahead, and by how much`() {
        // A 12% loan against an 8% expectation: paying the loan wins.
        val payTheLoan = simulate(annualRateBps = 1_200, expectedReturnBps = 800)
        assertEquals(SimulatorVerdict.PREPAY_AHEAD, payTheLoan.verdict)
        assertEquals(
            payTheLoan.prepay.interestSaved - payTheLoan.invest.afterTaxGain,
            payTheLoan.advantage,
        )

        // A 7% loan against a 12% expectation, untaxed: investing wins.
        val invest = simulate(annualRateBps = 700, expectedReturnBps = 1_200, taxOnReturnsBps = 0)
        assertEquals(SimulatorVerdict.INVEST_AHEAD, invest.verdict)
        assertTrue(invest.advantage > Money.ZERO)
    }

    @Test
    fun `the breakeven return is the rate at which the answer flips`() {
        val comparison = simulate(annualRateBps = 900, expectedReturnBps = 800)

        val breakeven = comparison.breakevenReturnBps
        assertTrue("a breakeven is always reported", breakeven > 0)
        // Below it, prepaying wins; above it, investing does. The simulator is asked either side of
        // its own answer, which is the only honest way to check a breakeven.
        assertEquals(
            SimulatorVerdict.PREPAY_AHEAD,
            simulate(annualRateBps = 900, expectedReturnBps = breakeven - MARGIN_BPS).verdict,
        )
        assertEquals(
            SimulatorVerdict.INVEST_AHEAD,
            simulate(annualRateBps = 900, expectedReturnBps = breakeven + MARGIN_BPS).verdict,
        )
    }

    @Test
    fun `tax raises the return an investment has to earn to be worth it`() {
        val untaxed = simulate(taxOnReturnsBps = 0).breakevenReturnBps
        val taxed = simulate(taxOnReturnsBps = 3_000).breakevenReturnBps

        assertTrue("a taxed investment must earn more to match the same loan: $untaxed vs $taxed", taxed > untaxed)
    }

    @Test
    fun `a prepayment that clears the loan outright is handled, not crashed`() {
        val comparison = simulate(lumpSum = Money(25_00_000_00L))

        assertEquals("nothing is left to pay", 0, comparison.prepay.remainingMonths)
        assertEquals(Money.ZERO, comparison.prepay.totalInterest)
        assertEquals(comparison.baseline.months, comparison.prepay.monthsSaved)
    }

    @Test
    fun `the comparison shows every figure it used (P-02)`() {
        val comparison = simulate()

        assertEquals(Money(20_00_000_00L), comparison.outstanding)
        assertEquals(Money(2_00_000_00L), comparison.lumpSum)
        assertEquals(900, comparison.annualRateBps)
        assertEquals(1_200, comparison.expectedReturnBps)
        assertEquals(3_000, comparison.taxOnReturnsBps)
        assertTrue(comparison.baseline.months > 0)
    }

    @Test
    fun `impossible inputs are refused by field`() {
        assertEquals(AppError.Validation("prepay.outstanding"), error(input(outstanding = Money(-1L))))
        assertEquals(AppError.Validation("prepay.lumpSum"), error(input(lumpSum = Money(-1L))))
        assertEquals(AppError.Validation("prepay.emi"), error(input(emi = Money(1_00L))))
        assertEquals(AppError.Validation("prepay.annualRateBps"), error(input(annualRateBps = -1)))
    }

    @Test
    fun `provenance names the engine and its rule`() {
        val provenance = simulate().provenance

        assertEquals("AI-SIM", provenance.engineId)
        assertEquals("1.0", provenance.engineVersion)
        assertEquals(NOW, provenance.computedAtUtcMillis)
        assertEquals(listOf(SimulatorRules.PREPAY_VS_INVEST), provenance.evidence)
    }

    @Test
    fun `nothing about the loan is changed by asking`() {
        // P-07 in the plainest form: two identical questions give two identical answers, because
        // the simulator holds no state and moves no money.
        assertEquals(simulate(), simulate())
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private fun input(
        outstanding: Money = Money(20_00_000_00L),
        annualRateBps: Int = 900,
        remainingMonths: Int = 180,
        emi: Money = Money(20_285_00L),
        lumpSum: Money = Money(2_00_000_00L),
    ) = PrepayInput(
        outstanding = outstanding,
        annualRateBps = annualRateBps,
        remainingMonths = remainingMonths,
        emi = emi,
        lumpSum = lumpSum,
        expectedReturnBps = 1_200,
        taxOnReturnsBps = 3_000,
        nowUtcMillis = NOW,
    )

    private fun simulate(
        annualRateBps: Int = 900,
        expectedReturnBps: Int = 1_200,
        taxOnReturnsBps: Int = 3_000,
        lumpSum: Money = Money(2_00_000_00L),
    ): PrepayComparison =
        simulator.simulate(
            input(annualRateBps = annualRateBps, lumpSum = lumpSum)
                .copy(expectedReturnBps = expectedReturnBps, taxOnReturnsBps = taxOnReturnsBps),
        ).expectOk()

    private fun error(input: PrepayInput): AppError =
        when (val result = simulator.simulate(input)) {
            is Ok -> throw AssertionError("expected Err, got ${result.value.verdict}")
            is Err -> result.error
        }

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val NOW = 1_790_000_000_000L

        /** Half a percent either side of the breakeven — comfortably past any rounding. */
        const val MARGIN_BPS = 50
    }
}
