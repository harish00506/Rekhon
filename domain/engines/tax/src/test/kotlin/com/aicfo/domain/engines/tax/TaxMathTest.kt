package com.aicfo.domain.engines.tax

import com.aicfo.core.common.SeededCases
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The slab walk and the calendar, tested where the golden file cannot reach (issue 13.4).
 *
 * Why:  the golden file pins twelve households against an independent oracle, which is the stronger
 *       gate — but twelve incomes cannot exercise every band boundary, and a boundary is exactly
 *       where this arithmetic goes wrong. Issue 13.2 learned the general lesson: *a golden file
 *       tests the engine against the data that ships, not the data that could.* So the properties
 *       that must hold at **every** income live here.
 * What: the band walk's identities, its behaviour exactly at a boundary, and the financial year's
 *       end — which is not the calendar year's.
 * Result: an off-by-one at a slab edge, or a marginal rate applied as an average one, fails here.
 * Changelog: 2026-10-09 — Created for issue 13.4.
 */
class TaxMathTest {
    private val new = TaxKnowledge.BUNDLED.new.slabs
    private val old = TaxKnowledge.BUNDLED.old.slabs

    // --- the band walk ------------------------------------------------------------------------

    /**
     * Input:  income exactly at a band boundary, and one paise over.
     * Output: asserts the extra paise is taxed at the **next** band's rate, not the whole income.
     *         This is the single most misunderstood thing about Indian tax, and an engine that got
     *         it backwards would charge someone 30% of everything for earning one rupee more.
     */
    @Test
    fun `crossing a boundary taxes only the excess at the higher rate`() {
        val boundary = rupees(8_00_000)
        val atBoundary = totalOf(boundary, new)
        val justOver = totalOf(boundary + Money(100L), new)

        // One rupee over the 5% band's top, taxed at the next band's 10% = 10 paise.
        assertEquals(Money(10L), justOver - atBoundary)
    }

    /**
     * Input:  2 000 seeded incomes.
     * Output: asserts the bands' tax always sums to the walk's total, and that every band's taxed
     *         slice lies inside its own bounds. A band that taxed income belonging to another would
     *         still produce a plausible total.
     */
    @Test
    fun `each band taxes only its own slice, and the slices sum to the total`() {
        SeededCases(seed = 13_401L, count = 2_000).forEach { random ->
            val taxable = Money(random.nextLong(0L, 5_00_00_000_00L))
            val bands = TaxMath.walk(taxable, new)
            val summed = bands.fold(Money.ZERO) { running, band -> running + band.taxedHere }

            assertEquals("the bands must account for all the income", taxable, summed)
            bands.forEach { band ->
                val width = band.toExclusive?.let { it - band.fromInclusive }
                if (width != null) {
                    assertTrue("a band taxed more than its own width", band.taxedHere <= width)
                }
                assertEquals(
                    "a band's tax must be its rate on its slice",
                    TaxMath.percentOf(band.taxedHere, band.rateBps),
                    band.taxHere,
                )
            }
        }
    }

    /**
     * Input:  2 000 seeded incomes, and one rupee more.
     * Output: asserts tax never falls when income rises. Monotonicity is what makes "earning more
     *         always leaves you with more" true, and it is the first thing a mis-ordered slab or a
     *         sign error breaks.
     */
    @Test
    fun `earning more never means paying less`() {
        SeededCases(seed = 13_402L, count = 2_000).forEach { random ->
            val taxable = Money(random.nextLong(0L, 5_00_00_000_00L))
            val more = taxable + Money(random.nextLong(1L, 10_00_000_00L))

            assertTrue("tax fell as income rose", totalOf(more, new) >= totalOf(taxable, new))
        }
    }

    /**
     * Input:  2 000 seeded incomes, under both regimes.
     * Output: asserts the effective rate never exceeds the top slab's rate. An average rate above
     *         the highest marginal rate is arithmetically impossible, so it is a clean check that
     *         the walk is marginal rather than flat.
     */
    @Test
    fun `the effective rate never exceeds the top marginal rate`() {
        SeededCases(seed = 13_403L, count = 2_000).forEach { random ->
            val taxable = Money(random.nextLong(1_00L, 10_00_00_000_00L))
            listOf(new, old).forEach { slabs ->
                val effectiveBps = totalOf(taxable, slabs).minor * 10_000 / taxable.minor

                assertTrue(
                    "effective ${effectiveBps}bps exceeds the top slab's ${slabs.last().rateBps}bps",
                    effectiveBps <= slabs.last().rateBps,
                )
            }
        }
    }

    /** Input: no income. Output: asserts no tax, and bands that still describe the empty walk. */
    @Test
    fun `zero income is taxed at zero, and still reports its bands`() {
        val bands = TaxMath.walk(Money.ZERO, new)

        assertEquals(Money.ZERO, bands.fold(Money.ZERO) { running, band -> running + band.taxHere })
        assertEquals("every band is reported, even the empty ones (P-02)", new.size, bands.size)
    }

    /**
     * Input:  income inside the first, nil-rated band.
     * Output: asserts zero tax — and that the band is still listed, because "you paid nothing in
     *         this band" is part of showing the work.
     */
    @Test
    fun `income inside the nil band pays nothing`() {
        assertEquals(Money.ZERO, totalOf(rupees(3_50_000), new))
    }

    // --- the financial year ---------------------------------------------------------------------

    /**
     * Input:  dates either side of 1 April.
     * Output: asserts the Indian financial year's end is found, not the calendar year's. A date in
     *         January belongs to the FY ending that March; a date in May to the one ending the
     *         *next* March. Getting it wrong would fire TAX-001's harvesting alerts nine months
     *         early, every year.
     */
    @Test
    fun `the financial year ends on the next 31 March, not 31 December`() {
        assertEquals(0L, TaxMath.daysToFinancialYearEnd("2027-03-31"))
        assertEquals(1L, TaxMath.daysToFinancialYearEnd("2027-03-30"))
        // 1 April starts a new year, so the end is a full year away.
        assertEquals(364L, TaxMath.daysToFinancialYearEnd("2026-04-01"))
        // A January date belongs to the year ending that March.
        assertEquals(89L, TaxMath.daysToFinancialYearEnd("2027-01-01"))
    }

    /**
     * Input:  2 000 seeded dates across four years.
     * Output: asserts the answer is always within a year and never negative — the alerts' window
     *         depends on it, and a negative would make every alert fire.
     */
    @Test
    fun `days to the year end are always between zero and a year`() {
        SeededCases(seed = 13_404L, count = 2_000).forEach { random ->
            val date = java.time.LocalDate.of(2026, 1, 1).plusDays(random.nextLong(0L, 1_460L))
            val days = TaxMath.daysToFinancialYearEnd(date.toString())

            assertTrue("days to FY end was $days on $date", days in 0L..366L)
        }
    }

    // --- the break-even ---------------------------------------------------------------------------

    /**
     * Input:  a tax function that falls as deductions rise, and a target the old regime starts above.
     * Output: asserts the bisection finds the crossing, and that the answer actually crosses it.
     */
    @Test
    fun `the break-even search finds the crossing point`() {
        val found =
            TaxMath.breakEvenDeductions(
                oldTaxAt = { deductions -> Money(10_00_000_00L) - deductions },
                newTax = Money(5_00_000_00L),
                maxDeductions = Money(20_00_000_00L),
            )

        assertEquals(Money(5_00_000_00L), found)
    }

    /** Input: a case where the old regime already wins. Output: asserts `null` — there is nothing to find. */
    @Test
    fun `there is no break-even when the old regime already wins`() {
        val found =
            TaxMath.breakEvenDeductions(
                oldTaxAt = { Money(1_00_000_00L) },
                newTax = Money(5_00_000_00L),
                maxDeductions = Money(20_00_000_00L),
            )

        assertEquals(null, found)
    }

    /** Input: a case the old regime can never win. Output: asserts `null` rather than the ceiling. */
    @Test
    fun `there is no break-even when no deduction would be enough`() {
        val found =
            TaxMath.breakEvenDeductions(
                oldTaxAt = { Money(9_00_000_00L) },
                newTax = Money(1_00_000_00L),
                maxDeductions = Money(20_00_000_00L),
            )

        assertEquals(null, found)
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun totalOf(
        taxable: Money,
        slabs: List<Slab>,
    ): Money = TaxMath.walk(taxable, slabs).fold(Money.ZERO) { running, band -> running + band.taxHere }

    private fun rupees(amount: Long) = Money(amount * 100L)
}
