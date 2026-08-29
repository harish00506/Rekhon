package com.aicfo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [MoneyFormatter.mask] — the privacy blur's masked amount (issue 5.5; ADR-0022, P-01).
 *
 * Why:  the mask has exactly one job that arithmetic cannot check — **it must leak nothing about
 *       the number it hides**, including its magnitude. A mask sized to the digits would pass any
 *       "does it contain a digit?" test and still tell a stranger over your shoulder whether the
 *       balance is in hundreds or in lakhs, which on a home screen is the whole exposure. So the
 *       property under test is that every magnitude produces the *same string*, not merely a
 *       digit-free one.
 *
 *       These moved here with the function (issue 5.5). `:core:designsystem`'s `PrivacyBlurTest`
 *       still runs unchanged against `maskOf`, which now delegates — that pair is the proof the
 *       move changed no behaviour.
 * What: identical output across six orders of magnitude, the sign, and the digit sweep.
 * Result: the widget and the app provably mask to one width, because they call one function.
 * Changelog: 2026-08-17 — Created for issue 5.5.
 */
class MoneyMaskTest {
    /** Input: the documented examples. Output: asserts the exact masked strings. */
    @Test
    fun `masks to the documented form`() {
        assertEquals("₹•••••••", MoneyFormatter.mask(Money(1_23_456_78)))
        assertEquals("-₹•••••••", MoneyFormatter.mask(Money(-1_23_456_78)))
    }

    /**
     * Input: amounts spanning paise to crores. Output: asserts every one masks to the same string —
     * the order-of-magnitude leak ADR-0022 exists to prevent.
     */
    @Test
    fun `is the same width for every magnitude`() {
        val amounts = listOf(1L, 450_00L, 99_999_00L, 1_00_000_00L, 1_00_00_000_00L, Long.MAX_VALUE)
        val masked = amounts.map { MoneyFormatter.mask(Money(it)) }.distinct()
        assertEquals(listOf("₹•••••••"), masked)
    }

    /**
     * Input: the [Long] extremes and zero. Output: asserts the sign survives and nothing throws —
     * `Long.MIN_VALUE` has no positive counterpart, which is where a naive `abs()` would blow up.
     */
    @Test
    fun `keeps the sign at the boundaries`() {
        assertEquals("₹•••••••", MoneyFormatter.mask(Money.ZERO))
        assertEquals("₹•••••••", MoneyFormatter.mask(Money(Long.MAX_VALUE)))
        assertEquals("-₹•••••••", MoneyFormatter.mask(Money(Long.MIN_VALUE)))
    }

    /**
     * Input: a distinctive amount whose digits would be recognisable if any survived.
     * Output: asserts no ASCII digit appears — the assertion the widget's own test repeats against
     * every string it renders.
     */
    @Test
    fun `contains no digit`() {
        val masked = MoneyFormatter.mask(Money(9_87_654_32))
        assertFalse(masked.any { it in '0'..'9' })
        // And it still reads as an amount rather than as an error, which is why ₹ stays.
        assertTrue(masked.contains("₹"))
    }
}
