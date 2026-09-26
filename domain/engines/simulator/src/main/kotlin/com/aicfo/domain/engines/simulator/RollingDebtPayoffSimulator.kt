package com.aicfo.domain.engines.simulator

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money

/**
 * Avalanche or snowball (issue 10.3; SRS §40.2 CRD-005, RULE-PAYOFF-ORDER).
 *
 * Why:  §40.2 asks for both, because the cheaper strategy and the one people finish are not always
 *       the same: avalanche clears the dearest debt first and costs less; snowball clears the
 *       smallest first and pays in motivation. The simulator runs both over the same money and
 *       reports the difference, so the choice is informed rather than argued.
 * What: month by month — interest on every debt, minimums everywhere, and everything spare against
 *       one target. **A cleared debt's minimum rolls into the next**, which is what makes either
 *       strategy accelerate.
 * Result: a [PayoffComparison]. Nothing is paid; this is arithmetic (P-07).
 * Changelog: 2026-09-26 — Created for issue 10.3.
 *
 * `internal` per ARC-003; pure, with no clock and no I/O (P-08).
 */
internal class RollingDebtPayoffSimulator : DebtPayoffSimulator {
    override fun simulate(input: PayoffInput): Result<PayoffComparison, AppError> {
        validate(input)?.let { return Err(it) }
        val avalanche = run(input) { debts -> debts.sortedWith(dearestFirst) }
        val snowball = run(input) { debts -> debts.sortedWith(smallestFirst) }
        return if (avalanche == null || snowball == null) {
            // A minimum that cannot cover its own interest never clears the debt, so neither plan
            // exists — saying so beats reporting a number of months that means nothing.
            Err(AppError.Validation(FIELD_MINIMUM))
        } else {
            Ok(comparison(input, avalanche, snowball))
        }
    }

    /**
     * Runs one strategy to the last rupee.
     * Why:    the strategy is **only** the choice of target, so both plans share this loop — two
     *         loops would be two chances to disagree about a month's interest.
     * Result: the plan, or `null` when the minimums cannot keep up with the interest.
     * Input:  [input]; [order] — how the remaining debts are ranked each month. Output: `PayoffPlan?`.
     */
    private fun run(
        input: PayoffInput,
        order: (List<Balance>) -> List<Balance>,
    ): PayoffPlan? {
        var debts = input.debts.map { Balance(it.name, it.balance, it.annualRateBps, it.minimumPayment) }
        val cleared = mutableListOf<String>()
        var interestPaid = Money.ZERO
        var months = 0
        while (debts.isNotEmpty() && months < CAP_MONTHS) {
            val month = payOneMonth(debts, order, input.extraMonthly) ?: return null
            interestPaid += month.interest
            months += 1
            cleared += month.cleared
            debts = month.remaining
        }
        return if (debts.isEmpty()) PayoffPlan(cleared, months, interestPaid) else null
    }

    /**
     * One month: interest everywhere, minimums everywhere, the spare money on the target.
     * Why:    **the freed minimums are spare money too** — a debt that is gone still contributes
     *         what it used to cost, which is the rollover that makes a payoff plan accelerate.
     * Result: what happened, or `null` when a minimum cannot cover its own interest.
     * Input:  [debts]; [order]; [extraMonthly]. Output: `Month?`.
     */
    private fun payOneMonth(
        debts: List<Balance>,
        order: (List<Balance>) -> List<Balance>,
        extraMonthly: Money,
    ): Month? {
        var interest = Money.ZERO
        val charged =
            debts.map { debt ->
                val due = SimulatorMath.monthlyInterest(debt.outstanding, debt.rateBps)
                if (debt.minimum <= due) return null
                interest += due
                debt.copy(outstanding = debt.outstanding + due)
            }
        val ranked = order(charged)
        val target = ranked.first()
        var spare = extraMonthly
        val paid =
            ranked.map { debt ->
                val payment = if (debt.name == target.name) debt.minimum + spare else debt.minimum
                val settled = minOf(payment, debt.outstanding)
                if (debt.name == target.name) spare = Money.ZERO
                // The same rounding rule the loan path uses: a residue under 1% of the payment is
                // rounding, not debt, and a lender folds it into the final instalment.
                debt.copy(outstanding = SimulatorMath.settle(debt.outstanding - settled, payment))
            }
        return Month(
            interest = interest,
            cleared = paid.filter { it.outstanding <= Money.ZERO }.map { it.name },
            remaining = paid.filter { it.outstanding > Money.ZERO },
        )
    }

    /** Result: the two plans and the deltas CRD-005 asks for. Input: [input]; [avalanche]; [snowball]. */
    private fun comparison(
        input: PayoffInput,
        avalanche: PayoffPlan,
        snowball: PayoffPlan,
    ) = PayoffComparison(
        debts = input.debts,
        extraMonthly = input.extraMonthly,
        avalanche = avalanche,
        snowball = snowball,
        interestSavedByAvalanche =
            if (input.rules.showInterestDelta) snowball.totalInterest - avalanche.totalInterest else Money.ZERO,
        monthsSavedByAvalanche = snowball.months - avalanche.months,
        provenance =
            EngineProvenance(
                engineId = ENGINE_ID,
                engineVersion = ENGINE_VERSION,
                computedAtUtcMillis = input.nowUtcMillis,
                evidence = listOf(SimulatorRules.PAYOFF_ORDER),
                inputWindow = "${avalanche.months}m",
            ),
    )

    /**
     * The inputs no plan can be made from.
     * Result: the first refusal by field, or `null`. Input: [input]. Output: `AppError.Validation?`.
     */
    private fun validate(input: PayoffInput): AppError.Validation? =
        when {
            input.debts.isEmpty() -> AppError.Validation(FIELD_DEBTS)
            input.extraMonthly < Money.ZERO -> AppError.Validation(FIELD_EXTRA)
            input.debts.any { it.balance < Money.ZERO } -> AppError.Validation(FIELD_BALANCE)
            input.debts.any { it.annualRateBps < 0 } -> AppError.Validation(FIELD_RATE)
            input.debts.any { it.minimumPayment <= Money.ZERO } -> AppError.Validation(FIELD_MINIMUM)
            else -> null
        }

    /** One debt as the loop carries it. */
    private data class Balance(
        val name: String,
        val outstanding: Money,
        val rateBps: Int,
        val minimum: Money,
    )

    /** What one month did. */
    private data class Month(
        val interest: Money,
        val cleared: List<String>,
        val remaining: List<Balance>,
    )

    private companion object {
        /** Avalanche's target: the dearest debt, with the name breaking a tie so runs are stable. */
        val dearestFirst = compareByDescending<Balance> { it.rateBps }.thenBy { it.name }

        /** Snowball's target: the smallest balance, same tie-break. */
        val smallestFirst = compareBy<Balance> { it.outstanding.minor }.thenBy { it.name }

        const val ENGINE_ID = "AI-SIM"
        const val ENGINE_VERSION = "1.0"
        const val FIELD_DEBTS = "payoff.debts"
        const val FIELD_EXTRA = "payoff.extraMonthly"
        const val FIELD_BALANCE = "payoff.balance"
        const val FIELD_RATE = "payoff.annualRateBps"
        const val FIELD_MINIMUM = "payoff.minimum"

        /** A thousand years of months — a guard, not a policy. */
        const val CAP_MONTHS = 12_000
    }
}
