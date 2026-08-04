package com.aicfo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [MoneyFormatter.parse] — typed rupees → [Money] (issue 2.1, MNY-001).
 *
 * Why:  onboarding's quick-setup step (FR-ONB-002) is the first place a user types an amount, and
 *       every later screen that takes one will reuse this. Parsing is where `Double` normally
 *       sneaks into a finance app — `"0.07".toDouble() * 100` is `7.000000000000001` — so the
 *       rejections matter as much as the acceptances: anything this cannot represent exactly must
 *       come back `null` rather than an amount that is nearly right.
 * What: the shapes a user actually types, the shapes that must be refused, and a round trip
 *       against [MoneyFormatter.format].
 * Result: proof that no input produces a wrong amount — only the right one or none.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 */
class MoneyParseTest {
    /** Input: plain rupee amounts. Output: asserts whole rupees become exact paise. */
    @Test
    fun `parses whole rupees`() {
        assertEquals(Money(45_000_00), MoneyFormatter.parse("45000"))
        assertEquals(Money.ZERO, MoneyFormatter.parse("0"))
        assertEquals(Money(1_00), MoneyFormatter.parse("1"))
    }

    /**
     * Input:  amounts with a fraction.
     * Output: asserts one fraction digit means *tens* of paise — `1.5` is ₹1.50, not ₹1.05. Getting
     *         this backwards would understate every such amount by a factor of ten.
     */
    @Test
    fun `parses paise, padding a single fraction digit`() {
        assertEquals(Money(45_000_50), MoneyFormatter.parse("45000.50"))
        assertEquals(Money(1_50), MoneyFormatter.parse("1.5"))
        assertEquals(Money(1_05), MoneyFormatter.parse("1.05"))
        assertEquals(Money(7), MoneyFormatter.parse("0.07"))
    }

    /**
     * Input:  what a user paging between fields actually leaves behind — grouping commas, the
     *         symbol, stray spaces.
     * Output: asserts the decoration is ignored rather than rejected. Refusing an amount the app
     *         itself rendered as `₹1,23,456.78` would be indefensible.
     */
    @Test
    fun `ignores grouping commas, the rupee sign and surrounding space`() {
        assertEquals(Money(1_23_456_78), MoneyFormatter.parse("₹1,23,456.78"))
        assertEquals(Money(45_000_00), MoneyFormatter.parse("  45,000  "))
        assertEquals(Money(45_000_00), MoneyFormatter.parse("45 000"))
    }

    /** Input: negatives, in both orders the symbol can appear. Output: asserts the sign survives. */
    @Test
    fun `parses negative amounts`() {
        assertEquals(Money(-100_00), MoneyFormatter.parse("-100"))
        assertEquals(Money(-1_23_456_78), MoneyFormatter.parse("-₹1,23,456.78"))
        assertEquals(Money(-1_23_456_78), MoneyFormatter.parse("₹-1,23,456.78"))
    }

    /**
     * Input:  empty and blank text.
     * Output: asserts `null`, so a skipped optional field is "not answered" rather than ₹0. The
     *         quick-setup step depends on that distinction: an income of zero is a claim, an
     *         unanswered income is not.
     */
    @Test
    fun `treats empty and blank input as no amount`() {
        assertNull(MoneyFormatter.parse(""))
        assertNull(MoneyFormatter.parse("   "))
        assertNull(MoneyFormatter.parse("₹"))
        assertNull(MoneyFormatter.parse("-"))
        assertNull(MoneyFormatter.parse(".50"))
    }

    /** Input: text that is not a number. Output: asserts every one is refused. */
    @Test
    fun `refuses anything that is not a number`() {
        assertNull(MoneyFormatter.parse("abc"))
        assertNull(MoneyFormatter.parse("45k"))
        assertNull(MoneyFormatter.parse("1.2.3"))
        assertNull(MoneyFormatter.parse("1.2e3"))
        assertNull(MoneyFormatter.parse("1.5a"))
    }

    /**
     * Input:  a non-ASCII digit.
     * Output: asserts it is refused. `Char.isDigit()` is true for Devanagari `१`, which then throws
     *         inside the number conversion — a validation that looks right and crashes. Checked
     *         explicitly because this app is India-first, so such input is plausible, not exotic.
     */
    @Test
    fun `refuses non-ASCII digits instead of crashing on them`() {
        assertNull(MoneyFormatter.parse("१२३"))
    }

    /**
     * Input:  a Devanagari digit in the **paise** half.
     * Output: asserts refusal, like the rupee half above.
     *
     * A separate case because the two halves are checked by two clauses of one `&&` chain, and the
     * rupee case short-circuits before the paise one is ever evaluated — so the rupee test alone
     * leaves the paise guard unexercised. This app is India-first, so `1.१0` is plausible input
     * rather than an exotic one, and `BigInteger` throws on it if the guard is missing.
     */
    @Test
    fun `refuses non-ASCII digits in the paise half too`() {
        assertNull(MoneyFormatter.parse("1.१0"))
    }

    /**
     * Input:  more precision than paise.
     * Output: asserts refusal rather than silent rounding — rounding here would be the app deciding
     *         what the user meant, and MNY-001 puts rounding under explicit HALF_EVEN control, not
     *         in a text parser.
     */
    @Test
    fun `refuses more precision than paise`() {
        assertNull(MoneyFormatter.parse("1.234"))
        assertNull(MoneyFormatter.parse("0.001"))
    }

    /**
     * Input:  amounts at and beyond the [Long] range.
     * Output: asserts the extremes parse exactly and anything past them is refused, never wrapped.
     *         A silently wrapped amount would flip a fortune into a debt.
     */
    @Test
    fun `parses the extremes and refuses anything beyond them`() {
        assertEquals(Money(Long.MAX_VALUE), MoneyFormatter.parse("92233720368547758.07"))
        assertEquals(Money(Long.MIN_VALUE), MoneyFormatter.parse("-92233720368547758.08"))
        assertNull(MoneyFormatter.parse("92233720368547758.08"))
        assertNull(MoneyFormatter.parse("-92233720368547758.09"))
        assertNull(MoneyFormatter.parse("999999999999999999999"))
    }

    /**
     * Input:  every amount the formatter can produce, across the full range.
     * Why:    the property that actually matters — whatever the app shows the user must read back
     *         as the same amount. It covers the pair as a unit, including [Long.MIN_VALUE], whose
     *         magnitude has no positive `Long` form and is the case a hand-written test forgets.
     * Output: asserts `parse(format(m)) == m` for every sample.
     */
    @Test
    fun `every formatted amount parses back to itself`() {
        val samples =
            listOf(
                Long.MIN_VALUE, -1_23_456_78L, -1L, 0L, 1L, 7L, 50L, 999_00L,
                1_00_000_00L, 1_00_00_000_00L, Long.MAX_VALUE,
            )
        samples.forEach { minor ->
            val amount = Money(minor)
            val rendered = MoneyFormatter.format(amount)
            assertEquals(rendered, amount, MoneyFormatter.parse(rendered))
        }
    }
}
