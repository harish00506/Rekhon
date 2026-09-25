package com.aicfo.feature.advisor

import androidx.annotation.StringRes
import com.aicfo.domain.engines.purchase.GateId
import com.aicfo.domain.engines.purchase.GateOutcome
import com.aicfo.domain.engines.purchase.PaymentMethod
import com.aicfo.domain.engines.purchase.Urgency
import com.aicfo.domain.engines.purchase.Verdict

/**
 * Turns AI-PA's findings into the screen's words (issue 10.1; §21.6).
 *
 * Why:  the engine names a gate `AFFORDABILITY` and a figure `liquidAfter`, and never an English
 *       sentence — so something has to map those to `strings.xml`, and doing it in one object keeps
 *       the mapping testable and the screen free of `when` blocks. A key with no label is a
 *       compile-time hole here rather than a blank line on someone's phone.
 * What: a string resource per verdict, gate, outcome, method, urgency and figure key.
 * Result: the card reads as English without the engine ever writing any.
 * Changelog: 2026-09-25 — Created for issue 10.1.
 */
internal object AdvisorLabels {
    /** Result: the banner's word for a verdict. Input: [verdict]. */
    @StringRes
    fun verdict(verdict: Verdict): Int =
        when (verdict) {
            Verdict.COMFORTABLE -> R.string.advisor_verdict_comfortable
            Verdict.STRETCH -> R.string.advisor_verdict_stretch
            Verdict.NOT_NOW -> R.string.advisor_verdict_not_now
        }

    /** Result: the one-line summary under the banner (§13.2). Input: [verdict]. */
    @StringRes
    fun summary(verdict: Verdict): Int =
        when (verdict) {
            Verdict.COMFORTABLE -> R.string.advisor_verdict_comfortable_summary
            Verdict.STRETCH -> R.string.advisor_verdict_stretch_summary
            Verdict.NOT_NOW -> R.string.advisor_verdict_not_now_summary
        }

    /** Result: the gate's name in the table. Input: [gate]. */
    @StringRes
    fun gate(gate: GateId): Int =
        when (gate) {
            GateId.AFFORDABILITY -> R.string.advisor_gate_affordability
            GateId.CASH_FLOW -> R.string.advisor_gate_cash_flow
            GateId.OBLIGATIONS -> R.string.advisor_gate_obligations
            GateId.GOAL_IMPACT -> R.string.advisor_gate_goal_impact
            GateId.BUDGET_FIT -> R.string.advisor_gate_budget_fit
            GateId.OPPORTUNITY_COST -> R.string.advisor_gate_opportunity_cost
            GateId.TIMING -> R.string.advisor_gate_timing
        }

    /** Result: pass, warn or fail, in the user's words. Input: [outcome]. */
    @StringRes
    fun outcome(outcome: GateOutcome): Int =
        when (outcome) {
            GateOutcome.PASS -> R.string.advisor_outcome_pass
            GateOutcome.WARN -> R.string.advisor_outcome_warn
            GateOutcome.FAIL -> R.string.advisor_outcome_fail
        }

    /** Result: the payment method's chip. Input: [method]. */
    @StringRes
    fun method(method: PaymentMethod): Int =
        when (method) {
            PaymentMethod.CASH -> R.string.advisor_method_cash
            PaymentMethod.CARD -> R.string.advisor_method_card
            PaymentMethod.EMI -> R.string.advisor_method_emi
        }

    /** Result: the urgency's chip. Input: [urgency]. */
    @StringRes
    fun urgency(urgency: Urgency): Int =
        when (urgency) {
            Urgency.ROUTINE -> R.string.advisor_urgency_routine
            Urgency.SOON -> R.string.advisor_urgency_soon
            Urgency.URGENT -> R.string.advisor_urgency_urgent
        }

    /**
     * Result: the label for a figure the engine named, or `null` for one this screen does not show.
     * Why:    `null` rather than a fallback of the raw key: a key nobody labelled should be
     *         invisible, not printed as `forecastLowestAfter` on someone's phone.
     * Input:  [key] — the engine's stable figure key. Output: `Int?`.
     */
    @Suppress("CyclomaticComplexMethod") // one arm per figure the card can show; a map would hide it
    @StringRes
    fun figure(key: String): Int? =
        when (key) {
            "liquidBefore" -> R.string.advisor_figure_liquid_before
            "liquidAfter" -> R.string.advisor_figure_liquid_after
            "emergencyFloor" -> R.string.advisor_figure_emergency_floor
            "monthlyEmi" -> R.string.advisor_figure_monthly_emi
            "monthlySurplusBefore" -> R.string.advisor_figure_surplus_before
            "monthlySurplusAfter" -> R.string.advisor_figure_surplus_after
            "safeToSpend" -> R.string.advisor_figure_safe_to_spend
            "forecastLowest" -> R.string.advisor_figure_forecast_lowest
            "forecastLowestAfter" -> R.string.advisor_figure_forecast_lowest_after
            "forecastBuffer" -> R.string.advisor_figure_forecast_buffer
            "obligationsBefore" -> R.string.advisor_figure_obligations_before
            "obligationsAfter" -> R.string.advisor_figure_obligations_after
            "monthlyObligations" -> R.string.advisor_figure_monthly_obligations
            "addedEmi" -> R.string.advisor_figure_added_emi
            "goalContributionsMonthly" -> R.string.advisor_figure_goal_monthly
            "categoryRemaining" -> R.string.advisor_figure_category_remaining
            "futureValue5y" -> R.string.advisor_figure_future_value_5y
            "futureValue10y" -> R.string.advisor_figure_future_value_10y
            "expectedReturn" -> R.string.advisor_figure_expected_return
            "cheaperMonth" -> R.string.advisor_figure_cheaper_month
            "cheaperMonthSaving" -> R.string.advisor_figure_cheaper_saving
            else -> null
        }

    /** The two figures the card words as a sentence rather than a labelled value (they are plurals). */
    const val CRUNCH_DAYS_BEFORE = "crunchDaysBefore"

    /** The goal delay, worded as a plural sentence. */
    const val GOAL_DELAY_DAYS = "goalDelayDays"
}
