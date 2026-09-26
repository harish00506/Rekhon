package com.aicfo.domain.engines.simulator

import com.aicfo.core.model.RuleCitation

/**
 * The rulebook rows AI-SIM applies (issue 10.3; CLAUDE.md §6).
 *
 * Why:  unusually for this project, **no new row was minted here**. `RULE-PREPAY-VS-INVEST` and
 *       `RULE-PAYOFF-ORDER` shipped with the rulebook's first version and say exactly what these
 *       simulators do — compare the loan's rate against an after-tax expected return and show the
 *       breakeven; offer avalanche and snowball with the interest delta computed. Both are cited
 *       rather than restated, and the drift test checks the flags that decide what is shown.
 * What: the citations, and the two switches the rows carry.
 * Result: everything the simulators read besides their input.
 * Changelog: 2026-09-26 — Created for issue 10.3 from rules-kb.json 1.22.0.
 *
 * Input:  [showBreakeven] — RULE-PREPAY-VS-INVEST's `show_breakeven`; [showInterestDelta] —
 *         RULE-PAYOFF-ORDER's `show_interest_delta`; [breakevenSearchSteps] — how many bisection
 *         steps the breakeven search takes, fixed so the answer is reproducible (P-08).
 * Output: an immutable value.
 */
data class SimulatorRules(
    val showBreakeven: Boolean = true,
    val showInterestDelta: Boolean = true,
    val breakevenSearchSteps: Int = DEFAULT_SEARCH_STEPS,
) {
    init {
        require(breakevenSearchSteps in 1..MAX_SEARCH_STEPS) {
            "the search has to run, and a search that never stops is not a simulation"
        }
    }

    companion object {
        private const val DEFAULT_SEARCH_STEPS = 40
        private const val MAX_SEARCH_STEPS = 200

        /** The rulebook file these values were copied from, as `_meta.version`. */
        const val RULEBOOK_VERSION = "1.23.0"

        /** `RULE-PREPAY-VS-INVEST` — the loan's rate against an after-tax return, with the breakeven. */
        val PREPAY_VS_INVEST = RuleCitation("RULE-PREPAY-VS-INVEST", "1.0")

        /** `RULE-PAYOFF-ORDER` — avalanche and snowball, with the interest delta. */
        val PAYOFF_ORDER = RuleCitation("RULE-PAYOFF-ORDER", "1.0")
    }
}
