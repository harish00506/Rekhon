package com.aicfo.domain.engines.nature

import com.aicfo.core.model.CategoryNature
import com.aicfo.core.model.Money
import com.aicfo.core.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §8.3's true-spend split — money math, so a 100% gate (issue 4.3; MNY-001, §21.5).
 *
 * Why:  the fold has no branches worth calling logic and two exclusions that carry all of its risk,
 *       both of which count a rupee twice if they are wrong:
 *
 *       - **every conversion is two legs.** ₹10,000 into an SIP leaves the bank and arrives in the
 *         investment account, and both legs classify as INVESTMENT because both describe the same
 *         becoming. Summing both doubles every SIP the user has ever made, and the number still
 *         looks plausible — which is what makes it dangerous rather than obvious.
 *       - **a self-transfer is not spending.** Moving ₹10,000 between two bank accounts becomes
 *         nothing at all, but the engine still answers with a nature for it, because §8.3 requires
 *         one. Folding that answer in would add a self-transfer to the month's Wants.
 *
 *       The third property is the one the SRS states and this build cannot fully honour: `trueSpend
 *       = NEED + WANT + interest/fees`, and there is no interest term. It is asserted here as
 *       written so that the day the amortisation split lands, this test fails and says why.
 * What: the two exclusions, the sign handling, the exactness, and the stated shortfall.
 * Result: a change that starts double-counting savings fails the build.
 * Changelog: 2026-08-10 — Created for issue 4.3.
 */
class NatureBreakdownTest {
    /** Input: no rows. Output: all zero, and reported as empty rather than as a real month of nothing. */
    @Test
    fun `an empty month is empty, not zero`() {
        val breakdown = natureBreakdown(emptyList())

        assertEquals(Money.ZERO, breakdown.trueSpend)
        assertTrue("an empty month must be distinguishable from a month of zeroes", breakdown.isEmpty)
    }

    /** Input: two expenses. Output: their magnitudes, summed into the right natures. */
    @Test
    fun `expenses are summed by nature, without their sign`() {
        val breakdown =
            natureBreakdown(
                listOf(
                    expense(Money(-4_500_00L), CategoryNature.NEED),
                    expense(Money(-1_200_00L), CategoryNature.WANT),
                ),
            )

        assertEquals(Money(4_500_00L), breakdown.needs)
        assertEquals(Money(1_200_00L), breakdown.wants)
        assertEquals(Money(5_700_00L), breakdown.trueSpend)
        assertFalse(breakdown.isEmpty)
    }

    /**
     * The double-count guard.
     * Input:  both legs of one ₹10,000 SIP, each correctly classified INVESTMENT.
     * Output: ₹10,000 invested, not ₹20,000. The outgoing leg is the one that counts; the arrival is
     *         the same money, and this is the single assertion standing between the app and a
     *         savings rate twice the real one.
     */
    @Test
    fun `a conversion is counted once, not once per leg`() {
        val breakdown =
            natureBreakdown(
                listOf(
                    contribution(TransactionType.TRANSFER_OUT, Money(-10_000_00L), CategoryNature.INVEST),
                    contribution(TransactionType.TRANSFER_IN, Money(10_000_00L), CategoryNature.INVEST),
                ),
            )

        assertEquals(Money(10_000_00L), breakdown.invested)
    }

    /**
     * Input:  a transfer between two of the user's own bank accounts, which no account-type step
     *         claimed, so the engine answered with the flagged fallback.
     * Output: nothing counted. Money moved and became nothing; counting it would put a self-transfer
     *         in the month's Wants and inflate true spend by the whole amount.
     */
    @Test
    fun `a self-transfer counts as nothing`() {
        val breakdown =
            natureBreakdown(
                listOf(contribution(TransactionType.TRANSFER_OUT, Money(-10_000_00L), NatureRules().fallbackNature)),
            )

        assertTrue("a bank-to-bank transfer is not spending", breakdown.isEmpty)
    }

    /**
     * Input:  a salary credit and a balance adjustment.
     * Output: nothing counted. §8.3 asks what money *became*, and neither is money leaving.
     */
    @Test
    fun `income and adjustments are not spending`() {
        val breakdown =
            natureBreakdown(
                listOf(
                    contribution(TransactionType.INCOME, Money(85_000_00L), CategoryNature.NEED),
                    contribution(TransactionType.ADJUSTMENT, Money(-250_00L), CategoryNature.WANT),
                ),
            )

        assertTrue(breakdown.isEmpty)
    }

    /**
     * The shortfall, asserted as written so it cannot be forgotten.
     * Input:  an EMI and an ordinary need.
     * Output: the EMI is **reported** in [NatureBreakdown.liabilities] and contributes **nothing** to
     *         true spend. §8.3's formula wants the interest half of it, which needs the amortisation
     *         split this build does not have (ADR-0016). The day that lands, this test fails and
     *         points at the reason.
     */
    @Test
    fun `an EMI is reported and excluded from true spend, pending the interest split`() {
        val breakdown =
            natureBreakdown(
                listOf(
                    contribution(TransactionType.TRANSFER_OUT, Money(-18_000_00L), CategoryNature.LIABILITY),
                    expense(Money(-4_500_00L), CategoryNature.NEED),
                ),
            )

        assertEquals(Money(18_000_00L), breakdown.liabilities)
        assertEquals("true spend must not yet include any part of an EMI", Money(4_500_00L), breakdown.trueSpend)
    }

    /**
     * Input:  a month with an odd number of paise in every nature.
     * Output: the totals are exact. There is no division in this fold and no rounding anywhere, so
     *         the sum of the parts is the sum of the inputs to the paise (MNY-001) — asserted rather
     *         than assumed, because "we never divide" is a claim a later change can break silently.
     */
    @Test
    fun `the totals are exact to the paise`() {
        val rows =
            listOf(
                expense(Money(-1_00_01L), CategoryNature.NEED),
                expense(Money(-2_00_03L), CategoryNature.NEED),
                expense(Money(-3_00_07L), CategoryNature.WANT),
            )

        val breakdown = natureBreakdown(rows)

        assertEquals(Money(3_00_04L), breakdown.needs)
        assertEquals(Money(3_00_07L), breakdown.wants)
        assertEquals(Money(6_00_11L), breakdown.trueSpend)
    }

    /**
     * Input:  a rule set that counts ASSET as true spend rather than as a conversion.
     * Output: the totals follow the rules rather than a hardcoded set — the seam a knowledge-base
     *         loader will use, and the proof that §8.3's `true_spend_natures` row is doing work.
     */
    @Test
    fun `which natures are spend comes from the rules`() {
        val rows = listOf(contribution(TransactionType.TRANSFER_OUT, Money(-80_000_00L), CategoryNature.ASSET))
        val assetIsNotAConversion =
            NatureRules(conversionNatures = setOf(CategoryNature.INVEST))

        assertTrue(
            "with ASSET no longer a conversion, the transfer leg is not countable",
            natureBreakdown(rows, assetIsNotAConversion).isEmpty,
        )
        assertEquals(Money(80_000_00L), natureBreakdown(rows).assets)
    }

    // --- helpers -----------------------------------------------------------------------------------

    /** Result: an expense contribution. Input: [amount]; [nature]. Output: [NatureContribution]. */
    private fun expense(
        amount: Money,
        nature: CategoryNature,
    ) = contribution(TransactionType.EXPENSE, amount, nature)

    /** Result: a contribution. Input: [type]; [amount]; [nature]. Output: [NatureContribution]. */
    private fun contribution(
        type: TransactionType,
        amount: Money,
        nature: CategoryNature,
    ) = NatureContribution(type = type, amount = amount, nature = nature)
}
