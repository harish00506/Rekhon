package com.aicfo.widget

import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

private const val PENDING = "Not yet worked out"

/**
 * Tests for [amountText] — what the widget is allowed to put on a home screen (issue 5.5; P-01/P-03).
 *
 * Why:  the widget is the app's most exposed surface. It renders with no app lock in front of it,
 *       to anyone who can see the phone, and unlike a notification it stays there. Two failures
 *       would matter and neither is caught by a screenshot:
 *
 *       **A digit surviving the blur.** `DashboardPrivacyBlurTest` sweeps the dashboard for
 *       `₹`-plus-digit; this is the widget's equivalent, and it is stronger because [amountText] is
 *       a plain function — the sweep can be exhaustive over the actual branch table rather than
 *       over whichever strings a rendering harness chooses to expose.
 *
 *       **A `₹0.00` that no engine produced.** `SafeToSpendRepository` emits `null` for a profile
 *       with no income basis, and `NetWorthRepository` has nothing before the first snapshot. If
 *       either became a zero here, the widget would state a figure the app does not hold (P-03).
 * What: the three branches, their precedence, and the digit sweep across magnitudes and signs.
 * Result: the one function that can leak an amount is proved not to.
 * Changelog: 2026-08-17 — Created for issue 5.5.
 */
class WidgetTextTest {
    /** Input: a computed figure, blur off. Output: asserts the normal Indian-grouped rendering. */
    @Test
    fun `renders the formatted amount when nothing is hiding it`() {
        assertEquals("₹1,23,456.78", amountText(Money(1_23_456_78), blurred = false, pending = PENDING))
    }

    /**
     * Input: a computed figure, blur on. Output: asserts the fixed-width mask, and that the mask
     * is the *same* for a small and a large amount — the order-of-magnitude leak ADR-0022 names.
     */
    @Test
    fun `masks the amount when the blur is on`() {
        assertEquals("₹•••••••", amountText(Money(450_00), blurred = true, pending = PENDING))
        assertEquals("₹•••••••", amountText(Money(45_00_000_00), blurred = true, pending = PENDING))
    }

    /**
     * Input: every sign and magnitude the two figures can take, blurred.
     * Output: asserts no ASCII digit appears in any of them. This is the assertion the feature
     * exists to guarantee; if it ever fails, the widget is showing money it was told to hide.
     */
    @Test
    fun `emits no digit at all while blurred`() {
        val amounts =
            listOf(
                Money.ZERO,
                Money(1),
                Money(-1),
                Money(450_00),
                Money(-9_87_654_32),
                Money(45_00_000_00),
                Money(Long.MAX_VALUE),
                Money(Long.MIN_VALUE),
            )
        amounts.forEach { amount ->
            val text = amountText(amount, blurred = true, pending = PENDING)
            assertFalse("leaked a digit for $amount: $text", text.any { it in '0'..'9' })
        }
    }

    /**
     * Input: no figure, blur off and on. Output: asserts the pending label both times — and
     * explicitly that it is not `₹0.00`. Absence is not zero (P-03).
     */
    @Test
    fun `says pending rather than zero when there is no figure`() {
        assertEquals(PENDING, amountText(null, blurred = false, pending = PENDING))
        assertEquals(PENDING, amountText(null, blurred = true, pending = PENDING))
    }

    /**
     * Input: a real zero and a real negative. Output: asserts both render as amounts.
     *
     * The boundary the previous test sits against: `Money.ZERO` is a *computed* zero — a month
     * where exactly nothing is left — and a negative Safe-to-Spend is the answer that matters most.
     * Neither may be mistaken for "no figure".
     */
    @Test
    fun `a computed zero and a negative are real answers`() {
        assertEquals("₹0.00", amountText(Money.ZERO, blurred = false, pending = PENDING))
        assertEquals("-₹2,500.00", amountText(Money(-2_500_00), blurred = false, pending = PENDING))
    }
}
