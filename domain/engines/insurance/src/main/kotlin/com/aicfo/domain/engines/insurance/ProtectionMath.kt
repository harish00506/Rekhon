package com.aicfo.domain.engines.insurance

import com.aicfo.core.model.Money
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * The arithmetic AI-INS is made of (issue 13.3; §39.1, MNY-001, MNY-002).
 *
 * Why:  kept apart from [RuleProtectionEngine] so the compounding can be tested on its own. The
 *       future-value sum is the only place in this engine where a small error compounds into a
 *       large one — thirty years at 12% multiplies a rupee by about thirty, so a rounding mistake
 *       made per-year rather than at the end is visible in the answer. All of it is integer money
 *       in and integer money out, with `BigDecimal` only in between (MNY-001).
 * What: cover arithmetic, premium per lakh, and the future value of a yearly investment.
 * Result: the engine above reads as a sequence of §39.1's decisions rather than as arithmetic.
 * Changelog: 2026-10-03 — Created for issue 13.3.
 */
internal object ProtectionMath {
    /**
     * Multiplies an amount by a whole number of times.
     * Why:    `Money.times` exists and is exact; this exists to name what §39.1 means by "15 ×
     *         annual income" at the call site.
     * Result: [amount] × [multiple]. Input: [amount]; [multiple] — positive. Output: [Money].
     */
    fun timesIncome(
        amount: Money,
        multiple: Int,
    ): Money = amount * multiple

    /**
     * The gap between what is needed and what is held, floored at zero.
     * Why:    being over-covered is not a negative gap. A negative figure would read as credit and
     *         invite somebody to net it against a different shortfall, which is not how insurance
     *         works — a large life policy does not pay a hospital bill.
     * Result: `max(needed - existing, 0)`. Input: [needed]; [existing]. Output: [Money].
     */
    fun gap(
        needed: Money,
        existing: Money,
    ): Money = if (needed > existing) needed - existing else Money.ZERO

    /**
     * What a policy costs per lakh of cover per year — the endowment detector's discriminator.
     *
     * Why:    comparing premiums directly tells you nothing, because a big policy costs more. Per
     *         lakh of cover, term and endowment are an order of magnitude apart, and that gap is
     *         what `RULE-TERM-VS-ENDOW` reads. Measured against real pricing: term runs about ₹120
     *         per lakh at age 30 and ₹1,400 at 60; endowments and ULIPs run ₹8,000–10,000.
     * Result: paise of annual premium per lakh of cover, rounded half-even. `null` when the cover
     *         is zero — a policy with no cover has no price per unit of cover, and reporting a
     *         division by zero as "infinitely expensive" would flag it as an investment on the
     *         strength of an arithmetic accident.
     * Input:  [annualPremium]; [cover]. Output: `Long?`.
     */
    fun premiumPerLakh(
        annualPremium: Money,
        cover: Money,
    ): Long? {
        if (cover.minor <= 0L) return null
        return BigDecimal.valueOf(annualPremium.minor)
            .multiply(BigDecimal.valueOf(ProtectionRules.PAISE_PER_LAKH))
            .divide(BigDecimal.valueOf(cover.minor), 0, RoundingMode.HALF_EVEN)
            .longValueExact()
    }

    /**
     * What a yearly investment is worth after a number of years.
     *
     * Why:    this is §39.1's "SIP-ed at ~12% compounds to ₹1.5–2.7Cr over 30 years", computed
     *         rather than quoted. It is an **ordinary annuity**: the payment is made at the end of
     *         each year, so the first one compounds for `years - 1` and the last not at all. Stated
     *         because the alternative — paying at the start — gives a figure about 12% higher at
     *         these rates, and a comparison that silently chose the flattering convention would be
     *         the kind of number P-03 exists to prevent.
     *
     *         `FV = PMT × ((1 + r)^n − 1) / r`, with `r` from basis points (MNY-002).
     * Result: paise, rounded half-even once at the end. `Money.ZERO` when nothing is invested or
     *         the rate is zero over no years.
     * Input:  [yearly] — the payment, non-negative; [rateBps] — the annual return in basis points,
     *   non-negative; [years] — positive.
     * Output: [Money].
     */
    fun futureValueOfYearlyInvestment(
        yearly: Money,
        rateBps: Int,
        years: Int,
    ): Money {
        require(years > 0) { "a projection needs a horizon, was $years years" }
        require(rateBps >= 0) { "a return is non-negative basis points (MNY-002), was $rateBps" }

        return when {
            yearly.minor <= 0L -> Money.ZERO
            // The general formula divides by the rate, so zero is the one input it cannot take.
            // Nothing compounds, so the answer is simply the money put in.
            rateBps == 0 -> yearly * years
            else -> compounded(yearly, rateBps, years)
        }
    }

    /**
     * The annuity factor applied, for a non-zero rate.
     * Why:    split out so [futureValueOfYearlyInvestment] reads as its three cases rather than as
     *         two guards and a block of arithmetic.
     * Result: paise, rounded half-even once at the end.
     * Input:  [yearly]; [rateBps] — positive; [years] — positive. Output: [Money].
     */
    private fun compounded(
        yearly: Money,
        rateBps: Int,
        years: Int,
    ): Money {
        val rate = BigDecimal.valueOf(rateBps.toLong()).divide(BPS_DENOMINATOR, MATH)
        val growth = BigDecimal.ONE.add(rate).pow(years, MATH)
        val factor = growth.subtract(BigDecimal.ONE).divide(rate, MATH)
        return Money(
            BigDecimal.valueOf(yearly.minor)
                .multiply(factor, MATH)
                .setScale(0, RoundingMode.HALF_EVEN)
                .longValueExact(),
        )
    }

    /** 10 000 basis points = 100% (MNY-002). */
    private const val BPS_IN_FULL = 10_000L

    /** The same, as the divisor the rate arithmetic uses. */
    private val BPS_DENOMINATOR = BigDecimal.valueOf(BPS_IN_FULL)

    /**
     * Enough precision that thirty years of compounding is exact to the paise, and bounded so the
     * intermediate cannot grow without limit. 34 digits is `DECIMAL128`.
     */
    private val MATH = MathContext.DECIMAL128
}
