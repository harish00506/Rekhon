package com.aicfo.domain.engines.purchase

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money

/**
 * §13.1's pipeline, as written (issue 10.1; AI-PA, P-02, P-07, P-08, ADR-0049).
 *
 * Why:  seven gates, each answering one question a person would actually ask, and the verdict is
 *       the worst answer among them. The order is §13's and it is not arbitrary: affordability
 *       first, because "is the money there" precedes every subtler worry, and timing last, because
 *       "it is cheaper in November" is only interesting once the rest has been weighed.
 * What: validate → run the gates → take the worst → soften for urgency within limits → assemble the
 *       impact strip and the alternatives.
 * Result: a [PurchaseVerdictCard].
 * Changelog: 2026-09-25 — Created for issue 10.1.
 *
 * No clock and no I/O (P-08): the caller supplies the date and the published figures, so a card
 * written in March still reproduces in December. `internal` per ARC-003.
 */
internal class GatedPurchaseAdvisorEngine : PurchaseAdvisorEngine {
    private val gates = PurchaseGates()

    override fun advise(input: PurchaseInput): Result<PurchaseVerdictCard, AppError> {
        validate(input)?.let { return Err(it) }
        val gates = gates.all(input)
        val hardFail = gates.any { it.outcome == GateOutcome.FAIL }
        return Ok(
            PurchaseVerdictCard(
                request = input.request,
                verdict = verdict(gates, input, hardFail),
                gates = gates,
                impact = impact(input),
                alternatives = alternatives(input),
                hardFail = hardFail,
                provenance = provenance(input, gates),
            ),
        )
    }

    // --- the verdict, the strip and the alternatives -------------------------------------------

    /**
     * The worst of the gates, softened for urgency within the rulebook's limits (RULE-PA-GATES).
     * Why:    urgency lifts a stretch to comfortable — a broken fridge in a month that was going to
     *         be tight is still the right purchase. It may **not** lift a hard fail: telling someone
     *         an unaffordable thing is merely a stretch because they called it urgent is how an
     *         advisor becomes a rubber stamp.
     * Result: the verdict. Input: [gates]; [input]; [hardFail]. Output: [Verdict].
     */
    private fun verdict(
        gates: List<GateResult>,
        input: PurchaseInput,
        hardFail: Boolean,
    ): Verdict {
        val worst =
            when {
                gates.any { it.outcome == GateOutcome.FAIL } -> Verdict.NOT_NOW
                gates.any { it.outcome == GateOutcome.WARN } -> Verdict.STRETCH
                else -> Verdict.COMFORTABLE
            }
        val maySoften =
            input.request.urgency == Urgency.URGENT &&
                input.rules.urgencySoftensOneStep &&
                !(hardFail && input.rules.softenBlockedOnHardFail)
        return if (maySoften) softened(worst) else worst
    }

    /** Result: one step kinder. Input: [verdict]. Output: [Verdict]. */
    private fun softened(verdict: Verdict): Verdict =
        when (verdict) {
            Verdict.NOT_NOW -> Verdict.STRETCH
            Verdict.STRETCH -> Verdict.COMFORTABLE
            Verdict.COMFORTABLE -> Verdict.COMFORTABLE
        }

    /** Result: §13.2's before-and-after strip. Input: [input]. Output: [ImpactStrip]. */
    private fun impact(input: PurchaseInput): ImpactStrip {
        val signals = input.signals
        val after = signals.liquidFunds - PurchaseMath.cashOutflow(input)
        return ImpactStrip(
            liquidBefore = signals.liquidFunds,
            liquidAfter = after,
            runwayMonthsBeforeTenths = PurchaseMath.runwayTenths(signals.liquidFunds, signals.monthlyEssentials),
            runwayMonthsAfterTenths = PurchaseMath.runwayTenths(after, signals.monthlyEssentials),
            goalDelayDays = PurchaseMath.goalDelayDays(input),
        )
    }

    /**
     * §13.2's alternatives.
     * Why:    two honest questions — at what price would nothing object, and when would today's
     *         price stop crossing the emergency floor. The second assumes only that saving continues
     *         at the current rate; it deliberately does not model the budget resetting or the
     *         forecast moving, because a date that pretended to know those would be a guess dressed
     *         as arithmetic (ADR-0049).
     * Result: the alternatives. Input: [input]. Output: [Alternatives].
     */
    private fun alternatives(input: PurchaseInput): Alternatives {
        val signals = input.signals
        val monthly = signals.goalContributionsMonthly
        val spare = signals.liquidFunds - signals.emergencyFloor
        val shortfall = input.request.price - spare
        val months =
            if (shortfall > Money.ZERO && monthly > Money.ZERO) {
                PurchaseMath.monthsToSave(
                    shortfall,
                    monthly,
                )
            } else {
                null
            }
        return Alternatives(
            comfortablePrice = comfortablePrice(input),
            comfortableFrom = months?.let { input.today.plusMonths(it) },
            coolOffSuggested = PurchaseMath.coolOffSuggested(input),
        )
    }

    /**
     * The highest price at which every gate would pass.
     * Why:    the binding gate is whichever objects first, so the answer is the smallest of the
     *         caps — and it is often the category budget rather than the bank balance, which is the
     *         honest thing to say.
     *
     *         **Some gates object whatever the price is**: days already under the buffer, a cheaper
     *         month ahead, obligations already past the lender's line. Naming a "comfortable price"
     *         then would be a promise the advisor cannot keep — a property test caught exactly that,
     *         offering a price that still came back a stretch. There is no such price, and the card
     *         says so by leaving it empty.
     * Result: that price, or `null` when no price would do. Input: [input]. Output: `Money?`.
     */
    private fun comfortablePrice(input: PurchaseInput): Money? {
        val signals = input.signals
        if (objectsAtAnyPrice(input)) return null
        val caps =
            listOfNotNull(
                (signals.liquidFunds - signals.emergencyFloor).takeIf { input.request.method != PaymentMethod.EMI },
                signals.forecastLowest?.minus(signals.forecastBuffer),
                signals.goalContributionsMonthly.takeIf { it > Money.ZERO },
                signals.categoryRemaining,
            )
        // A negative cap is a gate that is already unhappy — the forecast is under its buffer, or
        // the balance is under the emergency floor. Spending nothing would not fix it, so there is
        // no comfortable price rather than a comfortable price of zero.
        return caps.minOrNull()?.takeIf { it >= Money.ZERO }
    }

    /**
     * Whether a gate would object however little were spent.
     * Result: `true` when there is no comfortable price. Input: [input]. Output: [Boolean].
     */
    private fun objectsAtAnyPrice(input: PurchaseInput): Boolean {
        val signals = input.signals
        val alreadyObliged = PurchaseMath.ratioBps(signals.monthlyObligations, signals.monthlyIncome) ?: 0
        return signals.forecastCrunchDays > 0 ||
            signals.cheaperMonth != null ||
            alreadyObliged >= input.rules.obligationWarnBps
    }

    // --- arithmetic ----------------------------------------------------------------------------

    /**
     * The requests no verdict can be reached from.
     * Result: the first refusal by field, or `null`. Input: [input]. Output: `AppError.Validation?`.
     */
    private fun validate(input: PurchaseInput): AppError.Validation? {
        val request = input.request
        return when {
            request.item.isBlank() -> AppError.Validation(FIELD_ITEM)
            request.price < Money.ZERO -> AppError.Validation(FIELD_PRICE)
            request.method == PaymentMethod.EMI && request.monthlyEmi == null -> AppError.Validation(FIELD_EMI)
            else -> null
        }
    }

    /**
     * Provenance (AI-ARC-003): every rule any gate cited, and when the card was written. **No
     * confidence** — a gate is arithmetic on published figures, not an estimate.
     * Result: the provenance. Input: [input]; [gates]. Output: [EngineProvenance].
     */
    private fun provenance(
        input: PurchaseInput,
        gates: List<GateResult>,
    ) = EngineProvenance(
        engineId = ENGINE_ID,
        engineVersion = ENGINE_VERSION,
        computedAtUtcMillis = input.nowUtcMillis,
        evidence = (gates.flatMap { it.citations } + PurchaseRules.COOL_OFF).distinct(),
        inputWindow = input.today.toString(),
    )

    private companion object {
        const val ENGINE_ID = "AI-PA"
        const val ENGINE_VERSION = "1.0"
        const val FIELD_ITEM = "purchase.item"
        const val FIELD_PRICE = "purchase.price"
        const val FIELD_EMI = "purchase.monthlyEmi"
    }
}
