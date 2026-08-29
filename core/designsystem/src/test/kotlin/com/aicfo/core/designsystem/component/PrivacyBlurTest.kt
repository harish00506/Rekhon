package com.aicfo.core.designsystem.component

import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the privacy mask must and must not reveal (issue 5.3; §23, FR-PRIV-*, P-01).
 *
 * Why:  a blur is not a feature you can eyeball. A mask that hides the digits and keeps the *width*
 *       looks completely convincing in a screenshot and still tells anyone reading over a shoulder
 *       whether they are looking at ₹450 or ₹4,50,000 — which, for a salary or a balance, is most
 *       of what makes the figure sensitive. That failure is invisible to a screenshot test, to a
 *       reviewer skimming the diff, and to the user. So it is asserted here, explicitly, as a
 *       property over magnitudes rather than as one example.
 * What: the fixed-width guarantee, what survives the mask, and what must not.
 * Result: an "improvement" that makes the mask match the number's own length fails the build.
 * Changelog: 2026-08-16 — Created for issue 5.3.
 */
class PrivacyBlurTest {
    /**
     * The assertion this file exists for.
     * Input:  amounts spanning seven orders of magnitude, from one paisa to ten crore.
     * Output: asserts every one masks to the **identical** string.
     */
    @Test
    fun `every amount masks to the same width, whatever its magnitude`() {
        val masks =
            listOf(1L, 100L, 45_000L, 4_50_000L, 1_23_456_78L, 10_00_00_000_00L)
                .map { maskOf(Money(it)) }
                .toSet()

        assertEquals("the mask leaks the order of magnitude: $masks", 1, masks.size)
    }

    /**
     * Input:  a negative amount.
     * Output: asserts the sign survives.
     *
     * Why:    direction is not the secret — nobody is embarrassed that a row was an outflow — and
     *         `CfoAmountText` colours by sign, so dropping the minus would leave colour as the only
     *         signal of direction. That is precisely what that component's doc comment says it
     *         exists to prevent (P-02), and it is unreadable in greyscale or to a colour-blind user.
     */
    @Test
    fun `the sign survives the mask`() {
        assertTrue("an outflow must still read as an outflow", maskOf(Money(-45_000L)).startsWith("-"))
        assertFalse("an inflow must not gain a minus", maskOf(Money(45_000L)).startsWith("-"))
    }

    /**
     * Input:  a negative and a positive amount of the same magnitude.
     * Output: asserts they differ **only** by the sign — the mask body carries nothing else.
     */
    @Test
    fun `the sign is the only thing that varies`() {
        assertEquals(maskOf(Money(45_000L)), maskOf(Money(-45_000L)).removePrefix("-"))
    }

    /**
     * Input:  a masked amount.
     * Output: asserts it carries no digit at all.
     *
     * Why:    the obvious partial-mask mistake — showing the last two paise digits, or the leading
     *         digit "so the user can still recognise their own figure". Any digit is a digit the
     *         person behind them can read too.
     */
    @Test
    fun `the mask contains no digits`() {
        assertTrue("a masked amount must carry no digit", maskOf(Money(1_23_456_78L)).none(Char::isDigit))
    }

    /**
     * Input:  a masked amount.
     * Output: asserts the currency symbol stays, so a masked value still reads as an amount rather
     *         than as a rendering failure — and so a masked column lines up with a real one.
     */
    @Test
    fun `the mask still reads as money`() {
        assertTrue(maskOf(Money(45_000L)).contains("₹"))
    }

    /**
     * Input:  the same amount through the formatter and the mask.
     * Output: asserts they differ. Guards the trivially broken implementation — a `maskOf` that
     *         returned the formatted amount would pass every "starts with", "contains ₹" and
     *         "same width" check above if those were written less carefully.
     */
    @Test
    fun `the mask is not the amount`() {
        val amount = Money(1_23_456_78L)

        assertFalse(maskOf(amount) == MoneyFormatter.format(amount))
    }
}
