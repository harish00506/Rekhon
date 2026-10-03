package com.aicfo.domain.engines.insurance

import com.aicfo.core.common.SeededCases
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic under [ProtectionMath], tested where the golden file cannot reach (issue 13.3).
 *
 * Why:  the golden file pins the engine against an independent oracle, which is the stronger gate —
 *       but it can only exercise the eight households and the rulebook's one rate. The compounding
 *       here is the most dangerous arithmetic in the app: thirty years at 12% multiplies a rupee by
 *       about thirty, so a convention chosen carelessly (paying at the start of the year rather than
 *       the end) moves the answer by lakhs without looking wrong. Issue 13.2 learned the general
 *       lesson the same way — *a golden file tests the engine against the data that ships, not the
 *       data that could.*
 * What: the annuity's identities and its boundaries, the per-lakh discriminator including its
 *       divide-by-zero, and the gap floor.
 * Result: every rule in the money math has a test that fails if it is changed.
 * Changelog: 2026-10-03 — Created for issue 13.3.
 */
class ProtectionMathTest {
    // --- the gap floor -----------------------------------------------------------------------------

    /**
     * Input:  a household with more cover than it needs.
     * Output: asserts the gap is zero, not negative. A negative gap would read as credit and invite
     *         somebody to net it against a different shortfall — and a large life policy does not
     *         pay a hospital bill.
     */
    @Test
    fun `being over-covered gives a gap of zero, never a negative one`() {
        assertEquals(Money.ZERO, ProtectionMath.gap(Money(1_000L), Money(5_000L)))
        assertEquals(Money.ZERO, ProtectionMath.gap(Money(1_000L), Money(1_000L)))
        assertEquals(Money(4_000L), ProtectionMath.gap(Money(5_000L), Money(1_000L)))
    }

    /**
     * Input:  2 000 seeded pairs.
     * Output: asserts the gap is never negative and never exceeds what is needed.
     */
    @Test
    fun `a gap is always between zero and what is needed`() {
        SeededCases(seed = 13_301L, count = 2_000).forEach { random ->
            val needed = Money(random.nextLong(0L, 1_00_00_00_000L))
            val existing = Money(random.nextLong(0L, 1_00_00_00_000L))
            val gap = ProtectionMath.gap(needed, existing)

            assertTrue("a gap went negative", gap >= Money.ZERO)
            assertTrue("a gap exceeded the need", gap <= needed)
        }
    }

    // --- the per-lakh discriminator -----------------------------------------------------------------

    /**
     * Input:  the pricing the rulebook's threshold was measured against.
     * Output: asserts the arithmetic reproduces it — ₹12,000 a year for ₹1 crore of cover is ₹120
     *         per lakh, and ₹80,000 for ₹10 lakh is ₹8,000 per lakh. Two orders of magnitude apart,
     *         which is the whole basis of the detector.
     */
    @Test
    fun `premium per lakh reproduces the pricing the threshold was set against`() {
        assertEquals(12_000L, ProtectionMath.premiumPerLakh(Money(12_000_00L), Money(1_00_00_000_00L)))
        assertEquals(8_00_000L, ProtectionMath.premiumPerLakh(Money(80_000_00L), Money(10_00_000_00L)))
        assertEquals(1_40_000L, ProtectionMath.premiumPerLakh(Money(70_000_00L), Money(50_00_000_00L)))
    }

    /**
     * Input:  a policy recorded with no cover.
     * Output: asserts `null` rather than a division by zero or an "infinitely expensive" figure.
     *         Flagging a policy as an investment on the strength of an arithmetic accident is the
     *         kind of fabricated conclusion P-03 exists to prevent.
     */
    @Test
    fun `a policy with no cover has no price per lakh`() {
        assertNull(ProtectionMath.premiumPerLakh(Money(25_000_00L), Money.ZERO))
    }

    /**
     * Input:  1 000 seeded policies, each priced at one premium and at double it.
     * Output: asserts doubling the premium doubles the price per lakh, within the paise rounding
     *         can move. Linearity is what makes the threshold mean the same thing at every policy
     *         size.
     */
    @Test
    fun `doubling the premium doubles the price per lakh`() {
        SeededCases(seed = 13_302L, count = 1_000).forEach { random ->
            val cover = Money(random.nextLong(1_00_000_00L, 2_00_00_000_00L))
            val premium = Money(random.nextLong(1_000_00L, 2_00_000_00L))

            val single = ProtectionMath.premiumPerLakh(premium, cover)!!
            val doubled = ProtectionMath.premiumPerLakh(premium * 2, cover)!!

            assertTrue("doubling gave $doubled, not about ${single * 2}", doubled in (single * 2 - 1)..(single * 2 + 1))
        }
    }

    // --- the compounding ---------------------------------------------------------------------------

    /**
     * Input:  ₹66,000 a year for 30 years at 12%.
     * Output: asserts ₹1,59,27,957 — the figure §39.1 itself gestures at ("SIP-ed at ~12%
     *         compounds to ₹1.5–2.7Cr over 30 years"). Worked independently in Python before this
     *         test was written, so the engine and the SRS agree about something neither computed
     *         for the other.
     */
    @Test
    fun `the SRS's own illustration reproduces`() {
        val value = ProtectionMath.futureValueOfYearlyInvestment(Money(66_000_00L), 1_200, 30)

        assertEquals(Money(1_59_27_957_17L), value)
        assertTrue("outside §39.1's stated band", value.minor in 1_50_00_000_00L..2_70_00_000_00L)
    }

    /**
     * Input:  a rate of zero.
     * Output: asserts the money is simply added up. The general formula divides by the rate, so
     *         zero is the one input that would throw rather than answer.
     */
    @Test
    fun `a zero return is the money put in, not a division by zero`() {
        assertEquals(Money(30_000L), ProtectionMath.futureValueOfYearlyInvestment(Money(1_000L), 0, 30))
    }

    /** Input: nothing invested. Output: asserts zero, at any rate or horizon. */
    @Test
    fun `investing nothing is worth nothing`() {
        assertEquals(Money.ZERO, ProtectionMath.futureValueOfYearlyInvestment(Money.ZERO, 1_200, 30))
    }

    /**
     * Input:  one year.
     * Output: asserts the payment itself, uncompounded. This is what pins the **ordinary annuity**
     *         convention: the payment lands at the *end* of the year, so a single year earns
     *         nothing. An annuity-due would return the payment plus a year's growth, and silently
     *         choosing the flattering convention is exactly the kind of number P-03 prevents.
     */
    @Test
    fun `one year of an ordinary annuity earns nothing`() {
        assertEquals(Money(1_000L), ProtectionMath.futureValueOfYearlyInvestment(Money(1_000L), 1_200, 1))
    }

    /**
     * Input:  1 000 seeded investments, at a rate and at a higher one.
     * Output: asserts a higher return never gives a smaller value. Monotonicity in the rate is what
     *         makes the whole buy-term-invest-the-rest comparison meaningful.
     */
    @Test
    fun `a higher return never compounds to less`() {
        SeededCases(seed = 13_303L, count = 1_000).forEach { random ->
            val yearly = Money(random.nextLong(1_00L, 10_00_000_00L))
            val rate = random.nextInt(0, 2_000)
            val higher = rate + random.nextInt(1, 500)
            val years = random.nextInt(1, 40)

            assertTrue(
                "raising the rate from $rate to $higher lowered the value",
                ProtectionMath.futureValueOfYearlyInvestment(yearly, higher, years) >=
                    ProtectionMath.futureValueOfYearlyInvestment(yearly, rate, years),
            )
        }
    }

    /**
     * Input:  1 000 seeded investments over a horizon and a longer one.
     * Output: asserts more years never gives less, at a non-negative rate.
     */
    @Test
    fun `a longer horizon never compounds to less`() {
        SeededCases(seed = 13_304L, count = 1_000).forEach { random ->
            val yearly = Money(random.nextLong(1_00L, 10_00_000_00L))
            val rate = random.nextInt(0, 2_000)
            val years = random.nextInt(1, 30)
            val longer = years + random.nextInt(1, 20)

            assertTrue(
                "extending $years years to $longer lowered the value",
                ProtectionMath.futureValueOfYearlyInvestment(yearly, rate, longer) >=
                    ProtectionMath.futureValueOfYearlyInvestment(yearly, rate, years),
            )
        }
    }

    /**
     * Input:  1 000 seeded investments.
     * Output: asserts the value is never less than the money put in, at a non-negative rate. The
     *         simplest sanity property, and the first thing a sign error in the factor breaks.
     */
    @Test
    fun `you never end with less than you put in`() {
        SeededCases(seed = 13_305L, count = 1_000).forEach { random ->
            val yearly = Money(random.nextLong(1_00L, 10_00_000_00L))
            val years = random.nextInt(1, 40)
            val contributed = yearly * years

            assertTrue(
                "the projection fell below the contributions",
                ProtectionMath.futureValueOfYearlyInvestment(yearly, random.nextInt(0, 2_000), years) >= contributed,
            )
        }
    }

    /** Input: a horizon of zero. Output: asserts it is refused — a projection needs a horizon. */
    @Test
    fun `a projection with no horizon is refused`() {
        val failure =
            runCatching { ProtectionMath.futureValueOfYearlyInvestment(Money(1_000L), 1_200, 0) }.exceptionOrNull()

        assertTrue("expected an IllegalArgumentException, got $failure", failure is IllegalArgumentException)
    }

    /** Input: a negative rate. Output: asserts it is refused (MNY-002: a rate is non-negative bps). */
    @Test
    fun `a negative return is refused`() {
        val failure =
            runCatching { ProtectionMath.futureValueOfYearlyInvestment(Money(1_000L), -100, 10) }.exceptionOrNull()

        assertTrue("expected an IllegalArgumentException, got $failure", failure is IllegalArgumentException)
    }
}
