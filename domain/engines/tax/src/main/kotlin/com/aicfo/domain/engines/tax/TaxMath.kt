package com.aicfo.domain.engines.tax

import com.aicfo.core.model.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * The arithmetic AI-TAX is made of (issue 13.4; §38, MNY-001, MNY-002).
 *
 * Why:  kept apart from [SlabTaxEngine] so the slab walk can be tested on its own. It is the one
 *       piece here that is easy to get subtly wrong — an off-by-one at a band edge taxes a whole
 *       band at the wrong rate, and the error is invisible at every income except the few thousand
 *       rupees either side of the boundary. So it is a pure function with its own property tests.
 * What: the band walk, the percentage of a rupee amount, and the days to the financial year's end.
 * Result: the engine above reads as §38's decisions rather than as arithmetic.
 * Changelog: 2026-10-09 — Created for issue 13.4.
 */
internal object TaxMath {
    /**
     * Walks the slabs, taxing each band's slice at its own rate.
     *
     * Why:    India's slabs are **marginal**: income in a band is taxed at that band's rate, not the
     *         whole income at the top rate. Getting that backwards is the single most common
     *         misunderstanding of Indian tax, and an engine that got it wrong would confirm it.
     * Result: one [SlabBand] per slab, including the ones where nothing was taxed — a band showing
     *         zero is information (it says the income did not reach there), and dropping it would
     *         make the working harder to check (P-02).
     * Input:  [taxable] — non-negative paise; [slabs] — ascending, the last open-ended.
     * Output: the bands, in order.
     */
    fun walk(
        taxable: Money,
        slabs: List<Slab>,
    ): List<SlabBand> {
        var lower = Money.ZERO
        return slabs.map { slab ->
            val upper = slab.uptoExclusive
            val top = if (upper == null || taxable < upper) taxable else upper
            val taxedHere = if (top > lower) top - lower else Money.ZERO
            val band =
                SlabBand(
                    fromInclusive = lower,
                    toExclusive = upper,
                    rateBps = slab.rateBps,
                    taxedHere = taxedHere,
                    taxHere = percentOf(taxedHere, slab.rateBps),
                )
            lower = upper ?: lower
            band
        }
    }

    /**
     * A rate applied to an amount.
     * Why:    `Money.percentOf` already does this with half-even rounding, which is the house rule
     *         (MNY-001). Named here so the call sites read as tax rather than as a percentage.
     * Result: the tax, rounded to the paise. Input: [amount]; [rateBps]. Output: [Money].
     */
    fun percentOf(
        amount: Money,
        rateBps: Int,
    ): Money = if (amount <= Money.ZERO) Money.ZERO else amount.percentOf(rateBps)

    /**
     * The smaller of two amounts — a cap, applied.
     * Result: `min(amount, cap)`. Input: [amount]; [cap]. Output: [Money].
     */
    fun cappedAt(
        amount: Money,
        cap: Money,
    ): Money = if (amount < cap) amount else cap

    /**
     * Days from a date to the end of the Indian financial year it falls in.
     *
     * Why:    TAX-001's alerts are all about the 31st of March, and the Indian financial year does
     *         not align with the calendar one — a date in January 2027 belongs to FY 2026-27, whose
     *         year-end is 31 March 2027, while a date in May 2026 belongs to FY 2026-27 too. Getting
     *         this wrong would fire the harvesting alerts nine months early.
     * Result: whole days, zero on 31 March itself.
     * Input:  [isoDate] — `yyyy-MM-dd`. Output: [Long].
     */
    fun daysToFinancialYearEnd(isoDate: String): Long {
        val today = LocalDate.parse(isoDate)
        val yearEnd =
            if (today.monthValue >= FY_START_MONTH) {
                LocalDate.of(today.year + 1, FY_END_MONTH, FY_END_DAY)
            } else {
                LocalDate.of(today.year, FY_END_MONTH, FY_END_DAY)
            }
        return java.time.temporal.ChronoUnit.DAYS.between(today, yearEnd)
    }

    /** Result: true when [value] parses as ISO `yyyy-MM-dd` (TIM-002). */
    fun isIsoDate(value: String): Boolean =
        try {
            LocalDate.parse(value)
            true
        } catch (expected: java.time.format.DateTimeParseException) {
            false
        }

    /**
     * The deductions at which the old regime's total tax would equal the new regime's.
     *
     * Why:    §38.1 asks for the break-even "shown in rupees", and it is the most useful single
     *         number here: "you need ₹2.4 lakh of deductions for the old regime to win" is
     *         actionable in a way that "the new regime wins by ₹18,000" is not.
     * What:   a bisection rather than algebra, because the rebate and the cess make the function
     *         piecewise — algebra would need a case per slab boundary and would break the next time
     *         a Budget adds one. 40 steps over a bounded range converges to the paise and is fixed,
     *         so the answer is reproducible (P-08).
     * Result: the extra deductions needed, or `null` when the old regime already wins at the
     *         deductions actually claimed.
     * Input:  [oldTaxAt] — total old-regime tax for a given itemised-deduction total;
     *   [newTax] — the new regime's total, which does not move with these deductions;
     *   [maxDeductions] — the search ceiling.
     * Output: `Money?`.
     */
    fun breakEvenDeductions(
        oldTaxAt: (Money) -> Money,
        newTax: Money,
        maxDeductions: Money,
    ): Money? {
        if (oldTaxAt(Money.ZERO) <= newTax) return null
        if (oldTaxAt(maxDeductions) > newTax) return null

        var low = 0L
        var high = maxDeductions.minor
        repeat(BISECTION_STEPS) {
            val mid = low + (high - low) / 2
            if (oldTaxAt(Money(mid)) > newTax) low = mid else high = mid
        }
        return Money(high)
    }

    /**
     * Rupees as paise, for the few places a knowledge-base figure is quoted in rupees.
     * Result: [rupees] × 100. Input: [rupees]. Output: [Money].
     */
    fun fromRupees(rupees: Long): Money = Money(rupees * TaxKnowledge.PAISE_PER_RUPEE)

    /** Result: an amount as whole rupees, rounded half-even — for a screen, never for arithmetic. */
    fun toWholeRupees(amount: Money): Long =
        BigDecimal.valueOf(amount.minor)
            .divide(BigDecimal.valueOf(TaxKnowledge.PAISE_PER_RUPEE), 0, RoundingMode.HALF_EVEN)
            .longValueExact()

    /** The Indian financial year starts on 1 April. */
    private const val FY_START_MONTH = 4

    /** And ends on 31 March. */
    private const val FY_END_MONTH = 3
    private const val FY_END_DAY = 31

    /**
     * Enough bisection steps to converge to the paise over any realistic deduction range, and
     * fixed so the answer is reproducible (P-08). The same choice AI-SIM made for its breakeven.
     */
    private const val BISECTION_STEPS = 40
}
