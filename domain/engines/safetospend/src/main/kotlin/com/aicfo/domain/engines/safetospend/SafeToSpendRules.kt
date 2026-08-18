package com.aicfo.domain.engines.safetospend

import com.aicfo.core.model.RuleCitation

/**
 * The thresholds `RULE-STS` applies, copied from `ai/rules/rules-kb.json`.
 *
 * Why:  CLAUDE.md §6 says a financial threshold is a **data row in `ai/`, never a hardcoded
 *       number** — and this file is a hardcoded number. The same deliberate, recorded deferral
 *       ADR-0005 made for `QuickSetupRules` and ADR-0017 restated for `BudgetRules`: nothing in the
 *       app loads `ai/` at runtime, so honouring §6 literally would mean building an asset pipeline
 *       and a JSON parser into a module that has no serialisation dependency by design (ARC-002).
 *       `RulebookDriftTest` closes the gap that matters — edit `RULE-STS` in the rulebook and the
 *       build goes red until this file agrees.
 * What: one property per `params_json` key of `RULE-STS`, plus the citation every figure carries.
 * Result: the number on the app's home screen is attributable to a row a reviewer can open, and
 *       every threshold that shaped it is one they can change (P-02, AI-ARC-006).
 * Changelog: 2026-08-16 — Created for issue 5.2 from rules-kb.json v1.12.0.
 *
 * **Injected rather than read, so a test can move a threshold** and assert the engine moves with it
 * — which is also the seam the real loader will use when it lands, with no change to the engine.
 *
 * `income_basis` and `horizon` are on the rulebook row but deliberately **not** here: neither is a
 * number this engine applies. Both describe how the *caller* resolves a term — which is why
 * `RulebookDriftTest` asserts their values as string literals instead, so changing either still
 * fails the build even though no field mirrors them.
 *
 * Input:  [bufferPct] — `RULE-STS.buffer_pct`, the whole percent of income held back for the
 *         commitments the app does not know about; [includeGoalContributions] —
 *         `RULE-STS.include_goal_contributions`, whether the unmet savings envelope is subtracted;
 *         [floorAtZero] — `RULE-STS.floor_at_zero`, whether an overcommitted month reports its
 *         shortfall or a clamped zero.
 * Output: an immutable value.
 */
data class SafeToSpendRules(
    /** `RULE-STS.buffer_pct` — one twentieth of income, held back for the unknown unknowns. */
    val bufferPct: Int = 5,
    /** `RULE-STS.include_goal_contributions` — §5.2 names goal contributions as a term. */
    val includeGoalContributions: Boolean = true,
    /** `RULE-STS.floor_at_zero` — false: a user past the plan is told by how much. */
    val floorAtZero: Boolean = false,
) {
    init {
        // Negative would *add* to Safe-to-Spend, turning a safety margin into free money. 100 or
        // more would hold back everything the user earned, so the figure could never be positive and
        // the card would read as permanently broke — both are configuration mistakes that leave
        // every test asserting an *amount* passing while the advice is nonsense.
        require(bufferPct in 0..<FULL_PERCENT) {
            "bufferPct is a whole percent of income withheld and must be in 0..${FULL_PERCENT - 1}, " +
                "was $bufferPct: at or above 100 the whole month's income is held back and " +
                "Safe-to-Spend can never be positive"
        }
    }

    companion object {
        /** §5.2 / §14 — the whole formula, income basis to buffer. One row, one citation. */
        val SAFE_TO_SPEND = RuleCitation("RULE-STS", "1.0")

        /**
         * The rulebook file these thresholds were copied from, as `_meta.version`.
         *
         * Restated to 1.13.0 by issue 6.1, which added `RULE-CC-DUE` — **`RULE-STS` itself did not
         * change** and its own version stayed at 1.0. `_meta.version` describes the file, not this
         * row, so every typed mirror restates it whenever any rule is added.
         */
        const val RULEBOOK_VERSION = "1.13.0"

        /**
         * `RULE-STS.income_basis` — the envelope total, falling back to the ledger's actual income.
         *
         * A string rather than a typed choice because this engine never branches on it: the caller
         * resolves `income` before calling. It is mirrored here only so `RulebookDriftTest` can fail
         * the build if the rulebook ever names a basis the repository does not implement.
         */
        const val INCOME_BASIS = "budget_envelopes_then_actual"

        /** `RULE-STS.horizon` — the window closes at month end. Mirrored for the same reason. */
        const val HORIZON = "month_end"

        /** 100% — the ceiling `bufferPct` must stay under. */
        private const val FULL_PERCENT = 100
    }
}

/** 100 bps = 1%, so a whole-percent rule threshold becomes the bps rate `Money.percentOf` takes (MNY-002). */
internal const val BPS_PER_PERCENT = 100
