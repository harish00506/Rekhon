package com.aicfo.domain.engines.purchase

import com.aicfo.core.model.Money

/**
 * §13.1's seven checks, each answering one question a person would actually ask (issue 10.1).
 *
 * Why:  separated from [GatedPurchaseAdvisorEngine] because deciding and checking are different
 *       jobs, and because the engine had grown past detekt's function count — the right pressure at
 *       the right moment. Each gate here reports **figures**, never sentences: the screen owns the
 *       words (§21.6) and the guardrail checks the values.
 * What: the gates, in the order §13.1 runs them.
 * Result: what the verdict is the worst of.
 * Changelog: 2026-09-25 — Created for issue 10.1, extracted from the engine.
 */
internal class PurchaseGates {
    /**
     * Every gate, in §13.1's order.
     * Result: the seven results. Input: [input]. Output: `List<GateResult>`.
     */
    fun all(input: PurchaseInput): List<GateResult> =
        listOf(
            affordability(input),
            cashFlow(input),
            obligations(input),
            goalImpact(input),
            budgetFit(input),
            opportunityCost(input),
            timing(input),
        )

    /**
     * Can the money absorb it (§13.1 step 1)?
     * Why:    cash and an instalment are different questions. Cash asks what is left afterwards and
     *         whether it crosses the emergency floor — which is a **warning, not a refusal**, in
     *         §13's own words ("verdict ≤ STRETCH"): the money is there, what it costs is the safety
     *         net, and that is the user's call (P-07). An instalment asks whether the month can
     *         carry it at all.
     * Result: the gate. Input: [input]. Output: [GateResult].
     */
    fun affordability(input: PurchaseInput): GateResult =
        if (input.request.method == PaymentMethod.EMI) instalmentAffordability(input) else cashAffordability(input)

    /** Result: the cash form of gate 1. Input: [input]. Output: [GateResult]. */
    fun cashAffordability(input: PurchaseInput): GateResult {
        val signals = input.signals
        val after = signals.liquidFunds - input.request.price
        return GateResult(
            gate = GateId.AFFORDABILITY,
            outcome =
                when {
                    after < Money.ZERO -> GateOutcome.FAIL
                    after < signals.emergencyFloor -> GateOutcome.WARN
                    else -> GateOutcome.PASS
                },
            figures =
                listOf(
                    GateFigure("liquidBefore", amount = signals.liquidFunds),
                    GateFigure("liquidAfter", amount = after),
                    GateFigure("emergencyFloor", amount = signals.emergencyFloor),
                ),
            citations = listOf(PurchaseRules.GATES),
        )
    }

    /**
     * Result: the instalment form of gate 1 — it fails when the month cannot carry the instalment,
     * and warns when the instalment is more than this month's safe-to-spend. Both lines are figures
     * other engines published; no new threshold is minted here. Input: [input]. Output: [GateResult].
     */
    fun instalmentAffordability(input: PurchaseInput): GateResult {
        val signals = input.signals
        val instalment = input.request.monthlyEmi ?: Money.ZERO
        val surplusBefore = signals.monthlyIncome - signals.monthlyObligations - signals.monthlyEssentials
        val surplusAfter = surplusBefore - instalment
        return GateResult(
            gate = GateId.AFFORDABILITY,
            outcome =
                when {
                    surplusAfter < Money.ZERO -> GateOutcome.FAIL
                    instalment > signals.safeToSpend -> GateOutcome.WARN
                    else -> GateOutcome.PASS
                },
            figures =
                listOf(
                    GateFigure("monthlyEmi", amount = instalment),
                    GateFigure("monthlySurplusBefore", amount = surplusBefore),
                    GateFigure("monthlySurplusAfter", amount = surplusAfter),
                    GateFigure("safeToSpend", amount = signals.safeToSpend),
                ),
            citations = listOf(PurchaseRules.GATES, PurchaseRules.SAFE_TO_SPEND),
        )
    }

    /**
     * Does the forecast dip under its buffer because of this (§13.1 step 2)?
     * Why:    the gate reports the crunch days it **adds**, and says separately how many were
     *         already there. Blaming a purchase for a crunch that was coming anyway would make every
     *         verdict NOT_NOW for someone already in trouble — which is exactly when the advice
     *         matters most. With too little history to forecast, it says nothing rather than
     *         guessing.
     * Result: the gate. Input: [input]. Output: [GateResult].
     */
    fun cashFlow(input: PurchaseInput): GateResult {
        val signals = input.signals
        val lowest = signals.forecastLowest
        val after = lowest?.minus(PurchaseMath.monthlyOrWholeOutflow(input))
        return GateResult(
            gate = GateId.CASH_FLOW,
            outcome =
                when {
                    after == null -> GateOutcome.PASS
                    after < Money.ZERO -> GateOutcome.FAIL
                    after < signals.forecastBuffer || signals.forecastCrunchDays > 0 -> GateOutcome.WARN
                    else -> GateOutcome.PASS
                },
            figures =
                listOfNotNull(
                    lowest?.let { GateFigure("forecastLowest", amount = it) },
                    after?.let { GateFigure("forecastLowestAfter", amount = it) },
                    GateFigure("forecastBuffer", amount = signals.forecastBuffer),
                    GateFigure("crunchDaysBefore", count = signals.forecastCrunchDays),
                ),
            citations = listOf(PurchaseRules.GATES, PurchaseRules.CRUNCH),
        )
    }

    /**
     * Would EMIs and rent pass the lines a lender uses (§13.1 step 3, RULE-EMI-40)?
     * Why:    40% warns and 50% fails, and neither number is this engine's — they are the Indian
     *         lending heuristics the rulebook holds. Paying cash adds no obligation, so the gate
     *         still reports the ratio but has nothing to object to.
     * Result: the gate. Input: [input]. Output: [GateResult].
     */
    fun obligations(input: PurchaseInput): GateResult {
        val signals = input.signals
        val added =
            if (input.request.method == PaymentMethod.EMI) input.request.monthlyEmi ?: Money.ZERO else Money.ZERO
        val before = PurchaseMath.ratioBps(signals.monthlyObligations, signals.monthlyIncome)
        val after = PurchaseMath.ratioBps(signals.monthlyObligations + added, signals.monthlyIncome)
        return GateResult(
            gate = GateId.OBLIGATIONS,
            outcome =
                when {
                    after == null -> GateOutcome.PASS
                    after >= input.rules.obligationFailBps -> GateOutcome.FAIL
                    after >= input.rules.obligationWarnBps -> GateOutcome.WARN
                    else -> GateOutcome.PASS
                },
            figures =
                listOfNotNull(
                    before?.let { GateFigure("obligationsBefore", bps = it) },
                    after?.let { GateFigure("obligationsAfter", bps = it) },
                    GateFigure("monthlyObligations", amount = signals.monthlyObligations),
                    GateFigure("addedEmi", amount = added),
                ),
            citations = listOf(PurchaseRules.GATES, PurchaseRules.OBLIGATIONS),
        )
    }

    /**
     * How much later do the goals arrive (§13.1 step 4)?
     * Why:    money spent is money not saved, and the honest unit is time. The delay is the price
     *         divided by everything going to goals each month — so it is the same delay for every
     *         goal, which is what apportioning the price across them by contribution share works out
     *         to. A delay of more than a month is worth a warning; a few days is not.
     * Result: the gate. Input: [input]. Output: [GateResult].
     */
    fun goalImpact(input: PurchaseInput): GateResult {
        val delay = PurchaseMath.goalDelayDays(input)
        return GateResult(
            gate = GateId.GOAL_IMPACT,
            outcome = if (delay > input.rules.daysPerMonth) GateOutcome.WARN else GateOutcome.PASS,
            figures =
                listOf(
                    GateFigure("goalDelayDays", count = delay),
                    GateFigure("goalContributionsMonthly", amount = input.signals.goalContributionsMonthly),
                ),
            citations = listOf(PurchaseRules.GATES),
        )
    }

    /**
     * Is there room in the category this month (§13.1 step 5)?
     * Result: the gate — silent when no budget is set, because an unset budget is not a judgement.
     * Input:  [input]. Output: [GateResult].
     */
    fun budgetFit(input: PurchaseInput): GateResult {
        val remaining = input.signals.categoryRemaining
        return GateResult(
            gate = GateId.BUDGET_FIT,
            outcome = if (remaining != null && input.request.price > remaining) GateOutcome.WARN else GateOutcome.PASS,
            figures = listOfNotNull(remaining?.let { GateFigure("categoryRemaining", amount = it) }),
            citations = listOf(PurchaseRules.GATES),
        )
    }

    /**
     * What the money would have become instead (§13.1 step 6, RULE-PA-OPPCOST).
     * Why:    shown, **never** used to fail a gate: an app that refused purchases because the money
     *         could have been invested would refuse every purchase. It is context, not a verdict.
     * Result: the gate, always passing. Input: [input]. Output: [GateResult].
     */
    fun opportunityCost(input: PurchaseInput): GateResult =
        GateResult(
            gate = GateId.OPPORTUNITY_COST,
            outcome = GateOutcome.PASS,
            figures =
                input.rules.opportunityHorizonYears.map { years ->
                    GateFigure(
                        "futureValue${years}y",
                        amount = PurchaseMath.futureValue(input.request.price, input.rules.expectedReturnBps, years),
                    )
                } + GateFigure("expectedReturn", bps = input.rules.expectedReturnBps),
            citations = listOf(PurchaseRules.OPPORTUNITY_COST),
        )

    /**
     * Is there a month ahead where this costs less (§13.1 step 7)?
     * Result: the gate — a warning naming the month and what it would save, or silence.
     * Input:  [input]. Output: [GateResult].
     */
    fun timing(input: PurchaseInput): GateResult {
        val cheaper = input.signals.cheaperMonth
        return GateResult(
            gate = GateId.TIMING,
            outcome = if (cheaper == null) GateOutcome.PASS else GateOutcome.WARN,
            figures =
                listOfNotNull(
                    cheaper?.let { GateFigure("cheaperMonth", text = it.month) },
                    cheaper?.let { GateFigure("cheaperMonthSaving", amount = it.saving) },
                ),
            citations = listOf(PurchaseRules.GATES),
        )
    }
}
