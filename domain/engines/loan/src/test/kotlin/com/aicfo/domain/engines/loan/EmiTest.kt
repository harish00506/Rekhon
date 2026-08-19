package com.aicfo.domain.engines.loan

import com.aicfo.core.model.Loan
import com.aicfo.core.model.Money
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The closed-form instalment, checked against figures computed outside this codebase (issue 6.2;
 * FR-ACC-003, §11, MNY-001/MNY-002).
 *
 * Why:  the EMI is the root of the whole schedule — every one of 240 rows is wrong if it is wrong —
 *       and it is the one number in this engine that cannot be checked by an internal consistency
 *       argument. The schedule's invariants would hold perfectly around a completely wrong
 *       instalment. So these expectations come from an independent 50-significant-digit decimal
 *       implementation of `P·i·(1+i)^n / ((1+i)^n − 1)`, and the headline case is one a reader can
 *       check against any public EMI calculator: ₹30,00,000 at 8.5% over 20 years is ₹26,034.70.
 * What: the formula at ordinary Indian retail terms, the zero-rate branch, both tenure boundaries,
 *       and the amortises-or-not guard.
 * Result: the instalment is proven right, not merely stable.
 * Changelog: 2026-08-19 — Created for issue 6.2.
 */
class EmiTest {
    /**
     * Input:  four sets of real terms.
     * Output: asserts the derived instalment to the paise. The comment on each names the rupee
     *         figure so a diff is readable without a calculator.
     */
    @Test
    fun `the closed form matches an independent calculation`() {
        listOf(
            // ₹30,00,000 · 8.5% · 20 years  ->  ₹26,034.70
            Triple(300_000_000L, 850, 240) to 2_603_470L,
            // ₹5,00,000 · 10.5% · 5 years   ->  ₹10,746.95
            Triple(50_000_000L, 1_050, 60) to 1_074_695L,
            // ₹12,00,000 · 9% · 7 years     ->  ₹19,306.89
            Triple(120_000_000L, 900, 84) to 1_930_689L,
            // ₹100 · 12% · 3 months         ->  ₹34.00, the smallest case that still rounds
            Triple(10_000L, 1_200, 3) to 3_400L,
        ).forEach { (terms, expected) ->
            val (principal, rateBps, tenure) = terms
            assertWithMessage("%s paise at %s bps over %s months", principal, rateBps, tenure)
                .that(Emi.derive(loan(principal, rateBps, tenure)).minor)
                .isEqualTo(expected)
        }
    }

    /**
     * Input:  a 0% loan whose principal does not divide evenly by its tenure.
     * Output: asserts the separate branch is taken and rounds HALF_EVEN — the formula itself is
     *         undefined at a zero rate, because its denominator `(1+0)^n − 1` is zero.
     */
    @Test
    fun `a zero rate takes the division branch`() {
        assertThat(Emi.derive(loan(100_000_000L, 0, 10)).minor).isEqualTo(10_000_000L)
        // ₹10,00,000 over 7 months is ₹1,42,857.142857…, so the instalment rounds down to …14.
        assertThat(Emi.derive(loan(100_000_000L, 0, 7)).minor).isEqualTo(14_285_714L)
    }

    /**
     * Input:  a single-instalment loan and a fifty-year one, the two tenure boundaries `Loan` allows.
     * Output: asserts neither degenerates — a one-month loan repays principal plus one month's
     *         interest, and a 600-month one does not overflow the growth factor.
     */
    @Test
    fun `both tenure boundaries hold`() {
        // One instalment at 12%: ₹1,00,000 + 1% of it = ₹1,01,000.
        assertThat(Emi.derive(loan(10_000_000L, 1_200, 1)).minor).isEqualTo(10_100_000L)

        val fiftyYears = Emi.derive(loan(300_000_000L, 850, 600))
        assertWithMessage("a fifty-year instalment must still be a positive, plausible amount")
            .that(fiftyYears.minor in 1L..300_000_000L).isTrue()
    }

    /**
     * Input:  instalments either side of the first month's interest.
     * Output: asserts the guard is **strictly** greater. An instalment exactly equal to the interest
     *         leaves the balance unchanged for ever, which is the same non-terminating walk as one
     *         that is too small, wearing a friendlier number.
     */
    @Test
    fun `an instalment must exceed the first month's interest`() {
        // ₹30,00,000 at 8.5% owes ₹21,250.00 of interest in month one.
        val subject = loan(300_000_000L, 850, 240)

        assertThat(Emi.amortises(subject, Money(2_125_001))).isTrue()
        assertThat(Emi.amortises(subject, Money(2_125_000))).isFalse()
        assertThat(Emi.amortises(subject, Money(2_124_999))).isFalse()
    }

    /**
     * Input:  a zero-rate loan and a trivially small instalment.
     * Output: asserts an interest-free loan always amortises — there is no interest for the
     *         instalment to fail to cover, and `Loan` has already refused a zero instalment.
     */
    @Test
    fun `an interest-free loan always amortises`() {
        assertThat(Emi.amortises(loan(100_000_000L, 0, 10), Money(1))).isTrue()
    }

    /**
     * Builds a loan with the terms under test.
     * Why:    three of the six fields never vary here, and repeating them would bury the ones that
     *         do. Goes through the real constructor so no case can describe terms the app refuses.
     * Result: a [Loan]. Input: [principal] paise; [rateBps]; [tenure] months. Output: [Loan].
     */
    private fun loan(
        principal: Long,
        rateBps: Int,
        tenure: Int,
    ) = Loan(
        accountId = "account:emi",
        principal = Money(principal),
        annualRateBps = rateBps,
        tenureMonths = tenure,
        firstEmiIsoDate = "2026-09-05",
    )
}
