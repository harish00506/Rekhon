package com.aicfo.domain.engines.safetospend

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money

/**
 * The production [SafeToSpendEngine] — `RULE-STS`'s subtraction and nothing else (issue 5.2).
 *
 * Why:  `internal`, so `:app` cannot name it and every caller goes through the interface (ARC-003).
 *       Stateless, so [SafeToSpendEngineFactory] can hand one instance to the whole app.
 * What: builds the breakdown, folds it, and stamps provenance.
 * Result: the figure on the home screen, with the lines that produced it.
 * Changelog: 2026-08-16 — Created for issue 5.2.
 *
 * **The breakdown is built first and the figure is folded from it**, rather than the figure being
 * computed and the lines assembled to look like it. That ordering is the whole design: it makes the
 * card's arithmetic the *same* arithmetic as the headline's, so [SafeToSpend]'s adds-up invariant
 * cannot fail for any input the engine itself produces — only for one a future caller constructs by
 * hand, which is exactly who that check is for.
 */
internal class DefaultSafeToSpendEngine : SafeToSpendEngine {
    override fun compute(input: SafeToSpendInput): Result<SafeToSpend, AppError> =
        // Every `Money` operator is overflow-checked and throws rather than wrapping (MNY-001). A
        // throw crossing this boundary would crash the home screen, so it becomes an Err here
        // (§21.6) — the one failure path this engine has.
        runCatchingToResult {
            val lines = breakdown(input)
            val raw = lines.fold(Money.ZERO) { running, line -> running + line.signedAmount }
            val floored = input.rules.floorAtZero && raw < Money.ZERO

            // The clamp does not drop the shortfall, it names it — see SafeToSpendComponent.SHORTFALL.
            // Built only when it applies: `Money.ZERO - raw` is negative for a solvent month, and a
            // breakdown line carries a magnitude.
            val shortfall =
                if (floored) listOf(SafeToSpendLine(SafeToSpendComponent.SHORTFALL, Money.ZERO - raw)) else emptyList()

            SafeToSpend(
                amount = if (floored) Money.ZERO else raw,
                lines = lines + shortfall,
                provenance =
                    EngineProvenance(
                        engineId = ENGINE_ID,
                        engineVersion = ENGINE_VERSION,
                        computedAtUtcMillis = input.nowUtcMillis,
                        evidence = listOf(SafeToSpendRules.SAFE_TO_SPEND),
                        inputWindow = input.inputWindow,
                        // No confidenceBps: this is arithmetic over amounts the caller resolved, not
                        // an inference. A confidence here would be a number with nothing behind it.
                    ),
            )
        }

    /**
     * Builds the lines `RULE-STS` subtracts, in [SafeToSpendComponent] order.
     *
     * Why:    **a zero term is left out entirely.** A card reading "Bills due ₹0 · Scheduled ₹0 ·
     *         Savings ₹0" is three lines of noise around the one that matters, and a breakdown the
     *         user stops reading is a breakdown that has stopped satisfying P-02. Income is the one
     *         exception — it is always shown, because a card that opened with a deduction would be
     *         explaining a subtraction from nothing.
     * Result: the income line followed by one line per non-zero deduction.
     * Input:  [input] — the resolved terms and the thresholds. Output: `List<SafeToSpendLine>`.
     * Changelog: 2026-08-16 — Created for issue 5.2.
     */
    private fun breakdown(input: SafeToSpendInput): List<SafeToSpendLine> {
        val goals =
            if (input.rules.includeGoalContributions) input.goalContributionsRemaining else Money.ZERO
        val deductions =
            listOf(
                SafeToSpendComponent.BUFFER to input.income.percentOf(input.rules.bufferPct * BPS_PER_PERCENT),
                SafeToSpendComponent.SPENT to input.spentToDate,
                SafeToSpendComponent.SCHEDULED to input.scheduled,
                SafeToSpendComponent.RECURRING to input.recurringDue,
                SafeToSpendComponent.GOALS to goals,
            )

        return listOf(SafeToSpendLine(SafeToSpendComponent.INCOME, input.income)) +
            deductions
                .filter { (_, amount) -> amount != Money.ZERO }
                .map { (component, amount) -> SafeToSpendLine(component, amount) }
    }

    private companion object {
        /** AI-ARC-003's engine identity; the registry row in `ai/orchestrator/engine-registry.yaml`. */
        const val ENGINE_ID = "safe-to-spend"

        /** Bumped whenever the formula changes, so old figures stay reproducible (AI-ARC-006). */
        const val ENGINE_VERSION = "1.0"
    }
}
