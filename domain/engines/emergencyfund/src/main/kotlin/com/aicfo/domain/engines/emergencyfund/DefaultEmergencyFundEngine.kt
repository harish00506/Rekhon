package com.aicfo.domain.engines.emergencyfund

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation

/**
 * `RULE-EMF-MULT`, `RULE-EMF-COACH` and the arithmetic of §10.1 (issue 7.2).
 *
 * Why:  `internal` so nothing outside this module can name it (ARC-003); reached through
 *       [EmergencyFundEngineFactory].
 * What: the multiplier from the income's volatility, the target it implies, the runway the liquid
 *       funds buy, the shortfall, the top-up that closes it, and the coach band.
 * Result: an [EmergencyFundPlan]. Every figure is exact `Long` paise or integer basis points;
 *         **nothing here constructs a `Double`**, including the standard deviation — see
 *         [integerSquareRoot].
 * Changelog: 2026-09-02 — Created for issue 7.2.
 */
internal class DefaultEmergencyFundEngine : EmergencyFundEngine {
    /**
     * Input:  [input] — essentials, income history, liquid funds, the day, the instant, the rules.
     * Output: `Result<EmergencyFundPlan, AppError>`.
     * Result: `Ok` for any input whose terms fit in a `Long`; `Err(AppError.Unexpected)` only when
     *         `Money` refuses to wrap an overflow (MNY-001), which `runCatchingToResult` converts
     *         rather than letting it cross a layer boundary.
     */
    override fun assess(input: EmergencyFundInput): Result<EmergencyFundPlan, AppError> =
        runCatchingToResult {
            val rules = input.rules
            val cvBps = coefficientOfVariationBps(input.monthlyIncomes, rules.minMonthsObserved)
            val months = rules.multiplierFor(cvBps)
            val wasClamped = months != rules.unclampedMultiplierFor(cvBps)
            val essentials = input.monthlyEssentials
            val target = essentials?.times(months) ?: Money.ZERO
            val shortfall = maxOf(Money.ZERO, target - input.liquidFunds)
            val runwayBps = runwayMonthsBps(input.liquidFunds, essentials)

            EmergencyFundPlan(
                monthlyEssentials = essentials,
                essentialsBasis = input.essentialsBasis,
                incomeCvBps = cvBps,
                multiplierMonths = months,
                multiplierWasClamped = wasClamped,
                target = target,
                liquidFunds = input.liquidFunds,
                fundedRatioBps = ratioBps(input.liquidFunds, target),
                shortfall = shortfall,
                runwayMonthsBps = runwayBps,
                // `split` refuses a zero part count; `months` is clamped to at least
                // `runwayMinMonths`, so the guard here is against the *shortfall* being zero, where
                // splitting nothing into six parts is arithmetically fine but semantically noise.
                topUpMonthly = if (shortfall == Money.ZERO) Money.ZERO else shortfall.split(months).max(),
                status = statusFor(runwayBps, input.liquidFunds, target, months, rules),
                liquidAccountNames = input.liquidAccountNames,
                essentialCategoryNames = input.essentialCategoryNames,
                provenance =
                    EngineProvenance(
                        engineId = ENGINE_ID,
                        engineVersion = ENGINE_VERSION,
                        computedAtUtcMillis = input.nowUtcMillis,
                        evidence = evidenceFor(wasClamped),
                        inputWindow = input.today.toString(),
                        // No confidenceBps: this is arithmetic, not an inference. How much history
                        // it rests on is said by `essentialsBasis` and by a null `incomeCvBps`,
                        // which are facts rather than a score.
                    ),
            )
        }

    /**
     * The rules this assessment actually applied.
     *
     * Why:    `RULE-RUNWAY-M` is cited **only when the clamp changed the answer**, the discipline
     *         `QuickSetupRules` keeps. Citing a rule that did not fire is the quiet kind of wrong:
     *         it survives every test, and it tells the user a threshold shaped their number when it
     *         did not (P-02).
     * Result: two citations, or three. Never empty — [EmergencyFundPlan]'s `init` refuses that.
     * Input:  [wasClamped]. Output: the evidence list.
     */
    private fun evidenceFor(wasClamped: Boolean): List<RuleCitation> =
        buildList {
            add(EmergencyFundRules.MULTIPLIER)
            add(EmergencyFundRules.COACH)
            if (wasClamped) add(EmergencyFundRules.RUNWAY_CLAMP)
        }

    /**
     * How volatile the income has been, as a coefficient of variation in basis points.
     *
     * Why:    §10.1's multiplier is only *personal* because of this term, and it is the one term the
     *         app can measure from data it already holds. A cv is the right shape because it is
     *         scale-free: ₹5,000 of month-to-month swing means something very different on ₹30,000
     *         a month than on ₹3,00,000, and a raw standard deviation would call the second more
     *         volatile than the first.
     * What:   population standard deviation over mean, all in `Long` paise, scaled to basis points.
     *
     *         **Population, not sample (÷n rather than ÷n−1).** This describes the months actually
     *         observed rather than inferring a parameter of some wider population the user does not
     *         have — and with `min_months_observed` as low as 3 the Bessel correction would inflate
     *         the reading by a fifth for no gain in truth.
     * Result: cv × 10 000. **Null when there is too little history or the mean is not positive** —
     *         reported as unknown rather than as zero, because "steady" and "unmeasured" are
     *         different claims and only one of them is safe to act on (P-03).
     * Input:  [incomes] — whole closed months, in any order (the statistic is order-free);
     *         [minObserved] — the fewest months a reading may rest on.
     * Output: `Int?` — basis points, never negative.
     */
    private fun coefficientOfVariationBps(
        incomes: List<Money>,
        minObserved: Int,
    ): Int? {
        if (incomes.size < minObserved) return null
        val count = incomes.size.toLong()
        val total = incomes.fold(0L) { running, month -> Math.addExact(running, month.minor) }
        val mean = total / count
        if (mean <= 0L) return null
        val variance =
            incomes.fold(0L) { running, month ->
                val deviation = month.minor - mean
                Math.addExact(running, Math.multiplyExact(deviation, deviation))
            } / count
        val standardDeviation = integerSquareRoot(variance)
        return boundedBps(standardDeviation, mean)
    }

    /**
     * The integer square root of [value], by Newton's method.
     *
     * Why:    a standard deviation is a square root, and `Math.sqrt` returns a `Double` — which
     *         MNY-002 does not admit and which would make this engine's answer depend on the
     *         platform's floating-point rounding, breaking P-08's "fixed input, fixed output". This
     *         is exact, terminating, and identical on every JVM.
     * What:   Newton's iteration on `Long`, converging downward to the floor of the true root.
     * Result: `floor(sqrt(value))`; zero for a non-positive input. Flooring rounds the cv **down**,
     *         which understates volatility and so can only ever make the multiplier smaller — the
     *         direction that never inflates a target on rounding alone.
     * Input:  [value] — a variance in paise², never negative.
     * Output: the root, as a `Long`.
     */
    private fun integerSquareRoot(value: Long): Long {
        if (value <= 0L) return 0L
        var guess = value
        var next = (guess + 1L) / 2L
        while (next < guess) {
            guess = next
            next = (guess + value / guess) / 2L
        }
        return guess
    }

    /**
     * Months of essentials the liquid funds buy — §10.1's headline metric.
     *
     * Why:    the number the user actually feels. "₹2,40,000 saved" means nothing without the
     *         spending it has to cover; "four months" means everything.
     * Result: basis points of a month — **15 000 is 1.5 months**. Null when the essentials are
     *         unknown or zero, where the division has no meaning and a large number would be a lie.
     * Input:  [liquidFunds]; [essentials]. Output: `Int?`.
     */
    private fun runwayMonthsBps(
        liquidFunds: Money,
        essentials: Money?,
    ): Int? = essentials?.takeIf { it > Money.ZERO }?.let { boundedBps(liquidFunds.minor, it.minor) }

    /**
     * One amount as a share of another, in basis points.
     *
     * Result: `numerator ÷ denominator × 10 000`, floored. **Zero when the denominator is not
     *         positive** — an unknown target is 0% funded rather than infinitely funded, which is
     *         the reading that cannot mislead.
     * Input:  [numerator]; [denominator]. Output: basis points.
     */
    private fun ratioBps(
        numerator: Money,
        denominator: Money,
    ): Int = if (denominator <= Money.ZERO) 0 else boundedBps(numerator.minor, denominator.minor)

    /**
     * The shared bps division, with its two overflow edges handled once.
     *
     * Why:    both ratios above divide one paise figure by another and scale by 10 000, and both can
     *         meet the same two edges: `multiplyExact` overflowing on an absurd numerator, and a
     *         quotient too large for the `Int` the result type uses. Writing it twice would be two
     *         chances to guard only one of them.
     * Result: basis points, capped at [Int.MAX_VALUE]. The cap is reached only past 214 748 months
     *         of runway — about eighteen thousand years — where the exact figure is not information.
     * Input:  [numerator]; [denominator], which the caller has already checked is positive.
     * Output: basis points as an `Int`.
     */
    private fun boundedBps(
        numerator: Long,
        denominator: Long,
    ): Int {
        val scaled = Math.multiplyExact(numerator, EmergencyFundRules.BPS_FULL.toLong())
        return (scaled / denominator).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * The one verdict a card leads with, from `RULE-EMF-COACH`.
     *
     * Why:    the order matters and is not arbitrary. **Unknown is checked first**, because every
     *         test below compares against a target that does not exist yet, and a zero target would
     *         otherwise make an empty fund read as fully funded. **Surplus before funded**, because
     *         a surplus is also funded and the more specific verdict is the useful one. **Funded
     *         before the runway bands**, because covering the target is the goal, and a user with a
     *         deliberately small target should not be told they are urgent for clearing it.
     * What:   a `when`, in that order.
     * Result: [EmergencyStatus].
     * Input:  [runwayBps]; [liquidFunds]; [target]; [months] — M after the clamp; [rules].
     * Output: [EmergencyStatus].
     */
    private fun statusFor(
        runwayBps: Int?,
        liquidFunds: Money,
        target: Money,
        months: Int,
        rules: EmergencyFundRules,
    ): EmergencyStatus {
        if (runwayBps == null || target <= Money.ZERO) return EmergencyStatus.UNKNOWN
        val surplusBps = (months + rules.surplusAboveTargetMonths) * EmergencyFundRules.BPS_FULL
        return when {
            runwayBps > surplusBps -> EmergencyStatus.SURPLUS
            liquidFunds >= target -> EmergencyStatus.FUNDED
            runwayBps >= rules.urgentBelowMonths * EmergencyFundRules.BPS_FULL -> EmergencyStatus.BUILDING
            else -> EmergencyStatus.URGENT
        }
    }

    private companion object {
        /** Matches the `AI-EMF` row in `ai/orchestrator/engine-registry.yaml`. */
        const val ENGINE_ID = "AI-EMF"

        /** Bumped whenever the formula changes, so a stored result stays reproducible (AI-ARC-006). */
        const val ENGINE_VERSION = "1.0"
    }
}
