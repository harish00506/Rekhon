package com.aicfo.domain.engines.simulator

import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic both simulators stand on (issue 10.3; MNY-001/002, P-08).
 *
 * Why:  three things the comparisons above cannot see directly, and each of them changes an answer
 *       materially. **Growth compounds** — a mutation that earned interest only on the original sum
 *       passed every other test in this module, which is exactly why this one exists. **A month's
 *       interest is the same figure `:domain:engines:loan` computes**, or two screens disagree about
 *       a rupee. And **a rounding residue is not a month's debt**.
 * What: compounding, the shared rounding, the residue rule, and the loan that never clears.
 * Result: the foundations, pinned.
 * Changelog: 2026-09-26 — Created for issue 10.3 after a surviving mutation showed the gap.
 */
class SimulatorMathTest {
    @Test
    fun `growth compounds, so the second year earns more than the first`() {
        val principal = Money(1_00_000_00L)

        val firstYear = SimulatorMath.futureValue(principal, RATE_BPS, months = 12) - principal
        val twoYears = SimulatorMath.futureValue(principal, RATE_BPS, months = 24) - principal
        val secondYear = twoYears - firstYear

        assertTrue("interest must earn interest: $firstYear then $secondYear", secondYear > firstYear)
    }

    @Test
    fun `nothing grows at nothing, and no time grows nothing`() {
        assertEquals(
            Money(1_00_000_00L),
            SimulatorMath.futureValue(Money(1_00_000_00L), annualRateBps = 0, months = 24),
        )
        assertEquals(Money(1_00_000_00L), SimulatorMath.futureValue(Money(1_00_000_00L), RATE_BPS, months = 0))
    }

    @Test
    fun `a month's interest is the figure the loan engine would compute`() {
        // Both call `Money.percentOf(bps, overPeriods = 12)`. Asserting it here means a change to
        // either side has to be deliberate.
        val balance = Money(5_00_000_00L)

        assertEquals(balance.percentOf(RATE_BPS, overPeriods = 12), SimulatorMath.monthlyInterest(balance, RATE_BPS))
    }

    @Test
    fun `a residue under one percent of a payment is rounding, and anything above it is debt`() {
        val payment = Money(20_000_00L)

        assertEquals("three paise is not a month's debt", Money.ZERO, SimulatorMath.settle(Money(3L), payment))
        assertEquals("₹500 is", Money(500_00L), SimulatorMath.settle(Money(500_00L), payment))
        assertEquals("and nothing owed stays nothing", Money.ZERO, SimulatorMath.settle(Money.ZERO, payment))
    }

    @Test
    fun `a payment that cannot cover the interest never clears the balance`() {
        // ₹1,00,000 at 42% owes ₹3,500 a month in interest alone.
        val hopeless = SimulatorMath.amortise(Money(1_00_000_00L), annualRateBps = 4_200, payment = Money(3_000_00L))

        assertTrue(hopeless.neverClears)
        assertEquals(SimulatorMath.NEVER_AMORTISES, hopeless.months)
    }

    @Test
    fun `a balance already at zero takes no months and costs nothing`() {
        val nothing = SimulatorMath.amortise(Money.ZERO, RATE_BPS, payment = Money(1_000_00L))

        assertFalse(nothing.neverClears)
        assertEquals(0, nothing.months)
        assertEquals(Money.ZERO, nothing.totalInterest)
    }

    private companion object {
        const val RATE_BPS = 1_200
    }
}
