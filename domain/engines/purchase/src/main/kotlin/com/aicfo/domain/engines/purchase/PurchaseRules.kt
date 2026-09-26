package com.aicfo.domain.engines.purchase

import com.aicfo.core.model.RuleCitation

/**
 * `RULE-PA-GATES`, `RULE-PA-OPPCOST` and the rows the advisor reuses, as a typed mirror
 * (issue 10.1; §13, CLAUDE.md §6).
 *
 * Why:  every number that decides a verdict belongs in one file a reviewer can read — the
 *       obligation lines a lender would use, the return a rupee is assumed to earn, what urgency is
 *       allowed to do. There is no runtime loader (ADR-0017), so this is the copy the engine reads,
 *       held honest by `PurchaseRulebookDriftTest`.
 * What: the gate policy, the opportunity-cost assumption, and the reused thresholds of RULE-EMI-40
 *       and RULE-COOL-OFF.
 * Result: everything [PurchaseAdvisorEngine] reads besides its input.
 * Changelog: 2026-09-25 — Created for issue 10.1 from rules-kb.json 1.21.0.
 *
 * Input:  [daysPerMonth] — the month a goal delay is counted in; [urgencySoftensOneStep] — whether
 *         URGENT lifts the verdict one step; [softenBlockedOnHardFail] — whether that lift is
 *         refused when a gate failed outright; [expectedReturnBps] — the assumed annual return,
 *         1100 = 11%; [opportunityHorizonYears] — the horizons shown; [obligationWarnBps] /
 *         [obligationFailBps] — RULE-EMI-40's 40% and 50%; [coolOffTriggerBpsOfAnnualIncome] —
 *         RULE-COOL-OFF's 1% of annual income.
 * Output: an immutable value.
 */
data class PurchaseRules(
    val daysPerMonth: Int = DEFAULT_DAYS_PER_MONTH,
    val urgencySoftensOneStep: Boolean = true,
    val softenBlockedOnHardFail: Boolean = true,
    val expectedReturnBps: Int = DEFAULT_RETURN_BPS,
    val opportunityHorizonYears: List<Int> = listOf(FIRST_HORIZON, SECOND_HORIZON),
    val obligationWarnBps: Int = DEFAULT_OBLIGATION_WARN_BPS,
    val obligationFailBps: Int = DEFAULT_OBLIGATION_FAIL_BPS,
    val coolOffTriggerBpsOfAnnualIncome: Int = DEFAULT_COOL_OFF_BPS,
) {
    init {
        require(daysPerMonth >= 1) { "a month has to have days in it" }
        require(expectedReturnBps >= 0) { "a negative expected return is not an assumption this engine models" }
        require(opportunityHorizonYears.isNotEmpty()) { "the opportunity cost needs at least one horizon" }
        require(opportunityHorizonYears.all { it >= 1 }) { "a horizon is whole years, at least one" }
        require(obligationWarnBps in 0..obligationFailBps) {
            "the warning line must sit at or below the failing line, or the warning is unreachable"
        }
        require(coolOffTriggerBpsOfAnnualIncome >= 0) { "a negative trigger would pause every purchase" }
    }

    companion object {
        private const val DEFAULT_DAYS_PER_MONTH = 30
        private const val DEFAULT_RETURN_BPS = 1_100
        private const val FIRST_HORIZON = 5
        private const val SECOND_HORIZON = 10
        private const val DEFAULT_OBLIGATION_WARN_BPS = 4_000
        private const val DEFAULT_OBLIGATION_FAIL_BPS = 5_000
        private const val DEFAULT_COOL_OFF_BPS = 100

        /** The rulebook file these values were copied from, as `_meta.version`. */
        const val RULEBOOK_VERSION = "1.23.0"

        /** `RULE-PA-GATES` — the seven gates, the worst-of verdict, and what urgency may do. */
        val GATES = RuleCitation("RULE-PA-GATES", "1.0")

        /** `RULE-PA-OPPCOST` — what the money would have become. */
        val OPPORTUNITY_COST = RuleCitation("RULE-PA-OPPCOST", "1.0")

        /** `RULE-EMI-40` — obligations at 40% warn, 50% fail. Not this engine's number; it is a lender's. */
        val OBLIGATIONS = RuleCitation("RULE-EMI-40", "1.0")

        /** `RULE-COOL-OFF` — a discretionary purchase over 1% of annual income deserves a night. */
        val COOL_OFF = RuleCitation("RULE-COOL-OFF", "1.0")

        /** `RULE-FCT-CRUNCH` — what counts as a day under the buffer. */
        val CRUNCH = RuleCitation("RULE-FCT-CRUNCH", "1.0")

        /** `RULE-STS` — the month's safe-to-spend, which the budget gate reads. */
        val SAFE_TO_SPEND = RuleCitation("RULE-STS", "1.0")
    }
}
