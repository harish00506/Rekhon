package com.aicfo.domain.engines.purchase

import com.aicfo.core.model.Money
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The advisor's arithmetic, in one place (issue 10.1; §13.1, MNY-001/002).
 *
 * Why:  the gates and the alternatives ask the same questions — what leaves the bank, how long the
 *       goals slip, what a ratio is in basis points — and two answers to one question is how two
 *       parts of a card come to disagree. Extracted from the engine when it grew past detekt's
 *       function count, which was the right pressure: this is a different job from deciding.
 * What: the conversions and the one compounding formula.
 * Result: every figure on a card comes from here or from a signal, never from a screen.
 * Changelog: 2026-09-25 — Created for issue 10.1.
 */
internal object PurchaseMath {
    /** Result: what leaves the bank now — nothing, for an instalment. Input: [input]. */
    fun cashOutflow(input: PurchaseInput): Money =
        if (input.request.method == PaymentMethod.EMI) Money.ZERO else input.request.price

    /** Result: what the forecast feels first — the instalment, or the whole price. Input: [input]. */
    fun monthlyOrWholeOutflow(input: PurchaseInput): Money =
        if (input.request.method == PaymentMethod.EMI) input.request.monthlyEmi ?: Money.ZERO else input.request.price

    /** Result: the goal delay in whole days, or 0 when nothing is being saved. Input: [input]. */
    fun goalDelayDays(input: PurchaseInput): Int {
        val monthly = input.signals.goalContributionsMonthly
        if (monthly <= Money.ZERO) return 0
        val days = input.request.price.minor * input.rules.daysPerMonth
        return ((days + monthly.minor / 2) / monthly.minor).toInt()
    }

    /** Result: months of essentials covered, in tenths; 0 when essentials are unknown. */
    fun runwayTenths(
        liquid: Money,
        essentials: Money,
    ): Int {
        if (essentials <= Money.ZERO) return 0
        return ((liquid.minor * TENTHS + essentials.minor / 2) / essentials.minor).toInt()
    }

    /** Result: whole months of saving needed to cover [shortfall] at [monthly], rounded up. */
    fun monthsToSave(
        shortfall: Money,
        monthly: Money,
    ): Long = (shortfall.minor + monthly.minor - 1) / monthly.minor

    /**
     * Result: `amount` in basis points of `of`, or `null` when there is nothing to divide by.
     * Input:  [amount]; [of]. Output: `Int?`.
     */
    fun ratioBps(
        amount: Money,
        of: Money,
    ): Int? = if (of <= Money.ZERO) null else ((amount.minor * BPS) / of.minor).toInt()

    /**
     * Future value at a fixed annual rate (RULE-PA-OPPCOST).
     * Why:    `BigDecimal`, and exact: the rate is a decimal fraction of a percent and the horizons
     *         are whole years, so nothing here needs a binary float. Rounded half-even to the paise
     *         at the end, once (MNY-001).
     * Result: the future value. Input: [price]; [annualBps]; [years]. Output: [Money].
     */
    fun futureValue(
        price: Money,
        annualBps: Int,
        years: Int,
    ): Money {
        val growth = BigDecimal.ONE + BigDecimal(annualBps).movePointLeft(BPS_DECIMALS)
        val value = BigDecimal(price.minor).multiply(growth.pow(years))
        return Money(value.setScale(0, RoundingMode.HALF_EVEN).toLong())
    }

    /** Result: whether RULE-COOL-OFF's 1% of annual income is passed. Input: [input]. */
    fun coolOffSuggested(input: PurchaseInput): Boolean {
        val annual = input.signals.monthlyIncome * MONTHS_IN_YEAR
        val trigger = (annual.minor * input.rules.coolOffTriggerBpsOfAnnualIncome) / BPS
        return annual > Money.ZERO && input.request.price.minor > trigger
    }

    // --- validation and provenance --------------------------------------------------------------

    private const val BPS = 10_000L
    private const val BPS_DECIMALS = 4
    private const val TENTHS = 10L
    private const val MONTHS_IN_YEAR = 12
}
