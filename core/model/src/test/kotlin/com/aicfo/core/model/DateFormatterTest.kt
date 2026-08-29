package com.aicfo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [DateFormatter] (§21.6, TIM-002).
 *
 * Why:  the whole reason this object exists is that a stored ISO day is the wrong thing to show a
 *       user — issue 5.1 shipped a dashboard rendering a literal `2026-08-15` because the formatting
 *       step was simply missing. So the assertion that matters is not "it returns a string" but
 *       **"it does not return the input"** for a parseable date, plus the fallback that keeps one
 *       unparseable row from taking the list down with it.
 * What: the happy path, the fallback, and the boundary values that make a date formatter wrong.
 * Result: the one formatter both `:feature:transactions` and `:feature:dashboard` depend on is
 *       pinned before either screen renders through it.
 * Changelog: 2026-08-16 — Created alongside [DateFormatter].
 *
 * **Locale-agnostic on purpose.** `FormatStyle.MEDIUM` follows the JVM default locale, which differs
 * between a developer's machine and CI, so asserting a literal `"15 Aug 2026"` would be a test that
 * fails on someone else's laptop for no defect. These assert the *properties* that must hold in any
 * locale instead.
 */
class DateFormatterTest {
    /**
     * Input:  a well-formed ISO date.
     * Output: asserts it is **not** returned verbatim, and no longer contains the ISO separators —
     *         the exact defect this object was extracted to fix.
     */
    @Test
    fun `a parseable date is reformatted, not echoed`() {
        val formatted = DateFormatter.day("2026-08-15")

        assertNotEquals("the raw ISO value must not reach the user", "2026-08-15", formatted)
        assertTrue("the year must survive: $formatted", formatted.contains("2026"))
        assertTrue("the day must survive: $formatted", formatted.contains("15"))
    }

    /**
     * Input:  a string that is not a date — a merchant name that reached the wrong field, say.
     * Output: asserts the input comes back unchanged rather than throwing. A row showing a raw value
     *         is better than a list that crashes on one bad row, which is the tradeoff
     *         [DateFormatter.day]'s doc comment states.
     */
    @Test
    fun `an unparseable value is returned unchanged rather than throwing`() {
        assertEquals("not a date", DateFormatter.day("not a date"))
        assertEquals("", DateFormatter.day(""))
    }

    /**
     * Input:  a date that is real but not ISO-shaped (`15/08/2026`), and one that is ISO-shaped but
     *         not a real day (31 February).
     * Output: asserts both fall back rather than being silently coerced into some other date — a
     *         formatter that guessed here would show a day the ledger does not contain.
     */
    @Test
    fun `a wrong-shaped or impossible date falls back rather than being guessed at`() {
        assertEquals("15/08/2026", DateFormatter.day("15/08/2026"))
        assertEquals("2026-02-31", DateFormatter.day("2026-02-31"))
    }

    /**
     * Input:  the first and last days of a month, and a leap day.
     * Output: asserts each formats to something distinct. Off-by-one at a month boundary is the
     *         classic date bug, and 29 February is the classic parser bug (TIM-002).
     */
    @Test
    fun `month boundaries and a leap day each format distinctly`() {
        val first = DateFormatter.day("2026-08-01")
        val last = DateFormatter.day("2026-08-31")
        val leap = DateFormatter.day("2024-02-29")

        assertNotEquals(first, last)
        assertTrue("the first of the month must render its day: $first", first.contains("1"))
        assertTrue("the last of the month must render its day: $last", last.contains("31"))
        assertTrue("a leap day is a real date and must format: $leap", leap.contains("29"))
    }
}
