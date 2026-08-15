package com.aicfo.domain.engines.budget

import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money

/**
 * FR-BUD-004's band decision, kept apart from the suggestion and pace math (issue 4.5).
 *
 * Why:  the three questions `BudgetEngine` answers are related but not the same, and this one is the
 *       only one whose output decides whether to *interrupt a person*. Holding it in its own unit
 *       means the whole "should the user be told?" rule is readable in one screen, and it is also
 *       what keeps [DefaultBudgetEngine] from becoming the file where every budget concern lands.
 * What: the ratio, the band comparison, and the provenance that names the row behind them.
 * Result: a [BudgetAlert] or `null`, reproducible from its inputs alone (P-08).
 * Changelog: 2026-08-13 — Created for issue 4.5 (FR-BUD-004).
 *
 * `internal` — the public seam is [BudgetEngine.alert] (ARC-003). **No `Double` anywhere**
 * (MNY-001/MNY-002) and **no clock** (TIM-001): the instant is the caller's.
 */
internal object BudgetAlertBands {
    /** The engine that owns this decision; repeated here so provenance reads the same either way. */
    private const val ENGINE_ID = "budget-planner"

    /** Bump when the band arithmetic changes (AI-ARC-006). */
    private const val ENGINE_VERSION = "1.0"

    /**
     * Decides which band, if any, this budget has reached.
     *
     * Why:    both comparisons are `>=` — the rule says 80%, not "past 80%", so the boundary itself
     *         is inside the band. EXCEEDED is tested first because the bands overlap by definition:
     *         everything past 100% is also past 80%, and testing the warn band first would report
     *         every overspend as a warning.
     * Result: a [BudgetAlert], or `null` below the warn band and for an unset budget.
     * Input:  [input] — the budget, the spend, and the bands to apply.
     * Output: [BudgetAlert]?.
     */
    fun evaluate(input: BudgetAlertInput): BudgetAlert? {
        // An unset budget has no bands. This is also the only thing standing between the ratio below
        // and a division by zero, which is why it is the first line and not a branch inside it.
        if (input.budgeted == Money.ZERO) return null

        val usedBps = usedBps(input.spent, input.budgeted)
        val band =
            when {
                usedBps >= input.rules.exceededPct * BPS_PER_PERCENT -> BudgetAlertBand.EXCEEDED
                usedBps >= input.rules.warnPct * BPS_PER_PERCENT -> BudgetAlertBand.WARN
                else -> return null
            }

        return BudgetAlert(
            band = band,
            usedBps = usedBps,
            budgeted = input.budgeted,
            spent = input.spent,
            // Coerced at zero, and not defensively. `exceeded_pct` is a rule row, so a profile may
            // set it below 100 — at which point the band is reached while the budget still has money
            // in it, and the honest amount past the budget is nothing. Subtracting without the floor
            // would hand the notifier a negative "overspend" to render.
            overspentBy =
                if (band == BudgetAlertBand.EXCEEDED) {
                    (input.spent - input.budgeted).coerceAtLeast(Money.ZERO)
                } else {
                    null
                },
            provenance = provenance(input),
        )
    }

    /**
     * How much of the budget has been spent, in basis points.
     * Why:    the band comparison has to be exact at the boundary, and the boundary is where every
     *         off-by-one on this feature lives. Integer paise divided by integer paise, scaled by
     *         10 000 first so the division truncates only once. Truncation is the honest direction:
     *         79.99% of a budget is not 80% of it, so a user is never interrupted a rupee early.
     * Result: 0 or more; 10 000 at exactly the budget.
     * Input:  [spent]; [budgeted] — non-zero, guaranteed by [evaluate]'s guard.
     * Output: [Int], saturated at [Int.MAX_VALUE]. A ₹1 budget with ₹10 crore spent against it
     *         genuinely overflows an `Int` of basis points; saturating keeps the band right (it is
     *         EXCEEDED either way) instead of wrapping negative and reporting no alert at all.
     */
    private fun usedBps(
        spent: Money,
        budgeted: Money,
    ): Int = (spent.minor * BPS_FULL / budgeted.minor).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /**
     * Provenance for an alert (AI-ARC-003).
     * Why:    `RULE-BUD-ALERT` alone, not `RULE-BUD-PACE` — the bands come from that row and nothing
     *         on this path reads pace. Citing both would point the user's "why am I seeing this?" at
     *         a rule that had no part in the decision.
     * Result: engine id/version, the caller's instant and the cited row.
     * Input:  [input]. Output: [EngineProvenance].
     */
    private fun provenance(input: BudgetAlertInput): EngineProvenance =
        EngineProvenance(
            engineId = ENGINE_ID,
            engineVersion = ENGINE_VERSION,
            computedAtUtcMillis = input.nowUtcMillis,
            evidence = listOf(BudgetRules.ALERT),
        )
}
