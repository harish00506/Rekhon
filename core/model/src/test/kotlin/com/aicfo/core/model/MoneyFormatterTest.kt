package com.aicfo.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [MoneyFormatter] — Indian digit grouping (issue 1.2 AC3, CLAUDE.md §5).
 *
 * Why:  Indian grouping is 2,2,3 (₹1,23,456.78), not the 3,3,3 every default locale produces. A
 *       wrong grouping is the most visible possible bug in a finance app, and it is invisible to
 *       arithmetic tests.
 * What: the documented example, the grouping boundaries where a comma first appears, zero,
 *       negatives, sub-rupee amounts, and the Long extremes.
 * Result: formatting is proven locale-independent — the same string on any JDK or device.
 * Changelog: 2026-07-25 — Created for issue 1.2.
 */
class MoneyFormatterTest {
    /** Input: the AC's own example. Output: asserts ₹1,23,456.78 exactly. */
    @Test
    fun `formats the acceptance-criteria example`() {
        assertEquals("₹1,23,456.78", MoneyFormatter.format(Money(1_23_456_78)))
    }

    /** Input: amounts either side of each grouping boundary. Output: asserts 2,2,3 grouping. */
    @Test
    fun `groups the Indian way — 2,2,3 not 3,3,3`() {
        assertEquals("₹999.00", MoneyFormatter.format(Money(999_00)))
        assertEquals("₹1,000.00", MoneyFormatter.format(Money(1_000_00)))
        assertEquals("₹99,999.00", MoneyFormatter.format(Money(99_999_00)))
        // The first Indian-only boundary: one lakh groups as 1,00,000 — never 100,000.
        assertEquals("₹1,00,000.00", MoneyFormatter.format(Money(1_00_000_00)))
        // One crore: 1,00,00,000 — never 10,000,000.
        assertEquals("₹1,00,00,000.00", MoneyFormatter.format(Money(1_00_00_000_00)))
    }

    /** Input: zero and sub-rupee amounts. Output: asserts paise always render as two digits. */
    @Test
    fun `always shows two paise digits`() {
        assertEquals("₹0.00", MoneyFormatter.format(Money.ZERO))
        assertEquals("₹0.01", MoneyFormatter.format(Money(1)))
        assertEquals("₹0.50", MoneyFormatter.format(Money(50)))
        assertEquals("₹1.05", MoneyFormatter.format(Money(105)))
    }

    /** Input: refunds. Output: asserts the minus sits before the symbol, grouping unchanged. */
    @Test
    fun `formats negative amounts as refunds`() {
        assertEquals("-₹1,23,456.78", MoneyFormatter.format(Money(-1_23_456_78)))
        assertEquals("-₹0.01", MoneyFormatter.format(Money(-1)))
    }

    /** Input: the Long extremes. Output: asserts formatting never overflows or loses a digit. */
    @Test
    fun `formats the extremes without losing precision`() {
        assertEquals("₹92,23,37,20,36,85,47,758.07", MoneyFormatter.format(Money(Long.MAX_VALUE)))
        assertEquals("-₹92,23,37,20,36,85,47,758.08", MoneyFormatter.format(Money(Long.MIN_VALUE)))
    }
}
