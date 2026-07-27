package com.aicfo.domain.engines.quicksetup

import com.aicfo.core.model.RuleCitation

/**
 * The thresholds this engine applies, copied from `ai/rules/rules-kb.json` (ADR-0005).
 *
 * Why:  CLAUDE.md §6 says a financial threshold is a **data row in `ai/`, never a hardcoded
 *       number** — and these are hardcoded numbers. That is a deliberate, recorded deferral:
 *       nothing in the app loads `ai/` yet, so honouring §6 literally would mean issue 2.3 first
 *       building an asset pipeline and a rulebook parser, which is a larger change than the
 *       feature it would serve. `RulebookDriftTest` closes the gap that matters — edit a threshold
 *       in the rulebook and the build goes red until this file agrees — so the duplication cannot
 *       silently diverge while the loader is outstanding. ADR-0005 names the trigger to remove it.
 * What: one field per rulebook parameter, each carrying the rule id and version it came from, so
 *       the plan's evidence cites something real rather than a plausible-looking string.
 * Result: every number in the engine is attributable to a rulebook row a reviewer can open.
 * Changelog: 2026-07-27 — Created for issue 2.3 from rules-kb.json v1.7.0.
 *
 * **Injected rather than read, so a test can move a threshold** and assert the engine moves with
 * it — which is also the seam the real loader will use when it lands, with no change to the engine.
 *
 * Input:  [needsPctMax], [wantsPctMax], [savingsPctMin] — the RULE-50-30-20 bands as whole
 *         percents; [metroNeedsPctMax] — how far needs may flex when rent exceeds its band;
 *         [emergencyRunwayMonths] — RULE-EMERG-FIRST's minimum runway; [runwayMinMonths] /
 *         [runwayMaxMonths] — RULE-RUNWAY-M's clamp; [obligationWarnPct] / [obligationFailPct] —
 *         RULE-EMI-40's bands.
 * Output: an immutable value.
 */
data class QuickSetupRules(
    /** RULE-50-30-20 `needs_pct_max`. */
    val needsPctMax: Int = 50,
    /** RULE-50-30-20 `wants_pct_max`. */
    val wantsPctMax: Int = 30,
    /** RULE-50-30-20 `savings_pct_min` — a floor, never traded away by the flex. */
    val savingsPctMin: Int = 20,
    /** RULE-50-30-20 `metro_preset.needs_pct_max` — the ceiling on `auto_flex_to_fixed_load`. */
    val metroNeedsPctMax: Int = 60,
    /** RULE-EMERG-FIRST `min_runway_months`. */
    val emergencyRunwayMonths: Int = 3,
    /** RULE-RUNWAY-M `clamp_months[0]`. */
    val runwayMinMonths: Int = 3,
    /** RULE-RUNWAY-M `clamp_months[1]`. */
    val runwayMaxMonths: Int = 12,
    /** RULE-EMI-40 `warn_pct` — total obligations above this are flagged. */
    val obligationWarnPct: Int = 40,
    /** RULE-EMI-40 `fail_pct` — above this the rule's severity is `fail`. */
    val obligationFailPct: Int = 50,
) {
    init {
        val band = 0..PCT_TOTAL
        require(needsPctMax in band && wantsPctMax in band && savingsPctMin in band) {
            "Budget bands are whole percents in $band"
        }
        // The flex takes from wants and never from savings, so there has to be a wants share left
        // to take from at full flex. Checked here rather than clamped in the engine: a rulebook
        // edit that made the bands unsatisfiable should fail loudly at construction, not quietly
        // produce a budget that breaches the savings floor it was meant to protect.
        require(metroNeedsPctMax + savingsPctMin <= PCT_TOTAL) {
            "metroNeedsPctMax ($metroNeedsPctMax) + savingsPctMin ($savingsPctMin) must leave room for wants"
        }
        require(needsPctMax <= metroNeedsPctMax) { "The flex ceiling must not be below the base needs band" }
        require(runwayMinMonths in 1..runwayMaxMonths) { "The runway clamp must be a positive, ordered range" }
        require(obligationWarnPct <= obligationFailPct) { "The obligation warn band must not exceed the fail band" }
    }

    /** The runway to use, after RULE-RUNWAY-M's clamp. Result: months, 1 or more. */
    val clampedRunwayMonths: Int get() = emergencyRunwayMonths.coerceIn(runwayMinMonths, runwayMaxMonths)

    /** Whether the clamp actually changed the runway — the test of whether RULE-RUNWAY-M *fired*. */
    val runwayWasClamped: Boolean get() = clampedRunwayMonths != emergencyRunwayMonths

    companion object {
        /** The budget frame: needs/wants/savings bands and the metro flex. */
        val BUDGET_SPLIT = RuleCitation("RULE-50-30-20", "1.0")

        /** The minimum emergency runway the target is sized to. */
        val EMERGENCY_RUNWAY = RuleCitation("RULE-EMERG-FIRST", "1.0")

        /** The clamp on that runway; cited only when it changes the answer. */
        val RUNWAY_CLAMP = RuleCitation("RULE-RUNWAY-M", "1.0")

        /** Total obligations (EMIs + rent) as a share of income. */
        val OBLIGATION_LOAD = RuleCitation("RULE-EMI-40", "1.0")
    }
}

/** The whole of an income, as the percent the three bands must sum to. */
private const val PCT_TOTAL = 100
