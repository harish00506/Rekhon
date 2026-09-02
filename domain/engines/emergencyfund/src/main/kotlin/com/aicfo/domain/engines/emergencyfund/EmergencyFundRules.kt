package com.aicfo.domain.engines.emergencyfund

import com.aicfo.core.model.RuleCitation

/**
 * The thresholds `RULE-EMF-MULT` and `RULE-EMF-COACH` apply, copied from `ai/rules/rules-kb.json`
 * (issue 7.2; §10.1).
 *
 * Why:  CLAUDE.md §6 says a financial threshold is a **data row in `ai/`, never a hardcoded
 *       number** — and this file is hardcoded numbers. The same deliberate, recorded deferral
 *       ADR-0005 made for `QuickSetupRules`, ADR-0017 restated for `BudgetRules` and ADR-0033 for
 *       `GoalRules`: nothing in the app loads `ai/` at runtime, so honouring §6 literally would
 *       mean building an asset pipeline and a JSON parser into a module that has no serialisation
 *       dependency by design (ARC-002). `RulebookDriftTest` closes the gap that matters — edit
 *       either row in the rulebook and the build goes red until this file agrees.
 * What: one property per numeric `params_json` key of the two rows this engine applies, plus
 *       `RULE-RUNWAY-M`'s clamp and the three citations an assessment carries.
 * Result: every number this engine reports is attributable to a row a reviewer can open and change
 *       (P-02, AI-ARC-006).
 * Changelog: 2026-09-02 — Created for issue 7.2 from rules-kb.json v1.15.0.
 *
 * **Three of §10.1's five multiplier terms are missing on purpose.** The spec also adds +1 for a
 * single-earner household with dependents, +1 for no health cover, and ±1 for a job-stability
 * self-assessment. **No field anywhere in this app holds any of the three** — not in
 * `cfo_settings.proto`, not on `profile`, nowhere. Minting params for them would ship three numbers
 * that nothing reads and that no test could distinguish from working. `RulebookDriftTest` asserts
 * they are absent from the rulebook too, so the issue that adds the fields has to add the params in
 * the same breath. See ADR-0034.
 *
 * **There is no assumed return on the fund**, for the reason `GoalRules` gives about goals: no rate
 * exists anywhere in `ai/rules/`, and inventing one would be exactly the hardcoded financial number
 * §6 forbids. An emergency fund is held liquid; compounding it would be advice this app made up.
 *
 * Input:  [baseMonths] — `RULE-EMF-MULT.base_months`; [cvLowBps] / [cvHighBps] — the volatility band
 *         edges in basis points (MNY-002); [cvMidBump] / [cvHighBump] — months added inside and
 *         above the band; [essentialsLookbackMonths] / [minMonthsObserved] — how much history the
 *         caller gathers and how little it may act on; [runwayMinMonths] / [runwayMaxMonths] —
 *         `RULE-RUNWAY-M.clamp_months`; [urgentBelowMonths] / [surplusAboveTargetMonths] —
 *         `RULE-EMF-COACH`'s two bands.
 * Output: an immutable value.
 */
data class EmergencyFundRules(
    /** `RULE-EMF-MULT.base_months` — the consensus floor before anything personal is added. */
    val baseMonths: Int = 6,
    /** `RULE-EMF-MULT.cv_low_bps` — below this the income counts as steady and adds nothing. */
    val cvLowBps: Int = 1_000,
    /** `RULE-EMF-MULT.cv_high_bps` — above this the income counts as genuinely lumpy. */
    val cvHighBps: Int = 3_000,
    /** `RULE-EMF-MULT.cv_mid_bump` — months added inside the band. */
    val cvMidBump: Int = 1,
    /** `RULE-EMF-MULT.cv_high_bump` — months added above it. */
    val cvHighBump: Int = 3,
    /** `RULE-EMF-MULT.essentials_lookback_months` — whole closed months the caller should gather. */
    val essentialsLookbackMonths: Int = 6,
    /** `RULE-EMF-MULT.min_months_observed` — fewer than this and the volatility term is unknown. */
    val minMonthsObserved: Int = 3,
    /** `RULE-RUNWAY-M.clamp_months[0]`. */
    val runwayMinMonths: Int = 3,
    /** `RULE-RUNWAY-M.clamp_months[1]`. */
    val runwayMaxMonths: Int = 12,
    /** `RULE-EMF-COACH.urgent_below_months` — under this much runway the framing changes. */
    val urgentBelowMonths: Int = 1,
    /** `RULE-EMF-COACH.surplus_above_target_months` — the margin past the target that reads as idle. */
    val surplusAboveTargetMonths: Int = 2,
) {
    init {
        require(baseMonths > 0) { "baseMonths is a count of months and must be positive, was $baseMonths" }
        // Inverted band edges would make the middle bump unreachable while every *amount* the engine
        // reports stayed arithmetically correct — the failure mode GoalRules guards against too.
        require(cvLowBps in 0..cvHighBps) {
            "The volatility band is ordered basis points: cvLowBps ($cvLowBps) must be in 0..$cvHighBps"
        }
        require(cvMidBump in 0..cvHighBump) {
            "The bumps are ordered: a merely variable income cannot need more months than a lumpy one"
        }
        require(minMonthsObserved > 0) {
            "A volatility reading needs at least one month, was $minMonthsObserved"
        }
        require(essentialsLookbackMonths >= minMonthsObserved) {
            "The lookback ($essentialsLookbackMonths) must be able to supply minMonthsObserved " +
                "($minMonthsObserved): a window narrower than the minimum can never satisfy it"
        }
        require(runwayMinMonths in 1..runwayMaxMonths) {
            "The runway clamp must be a positive, ordered range"
        }
        require(urgentBelowMonths >= 0) { "urgentBelowMonths is a months floor, was $urgentBelowMonths" }
        require(surplusAboveTargetMonths >= 0) {
            "surplusAboveTargetMonths is a margin above the target, was $surplusAboveTargetMonths"
        }
    }

    /**
     * The months `RULE-EMF-MULT` adds for income volatility.
     *
     * Why:    §10.1's whole claim to a *personal* multiplier rests on this one term — everything
     *         else it names is a flag this app cannot yet read. A lumpy income has to bridge a
     *         longer gap between paydays, so it needs a deeper fund.
     * What:   the row's two band edges, applied to a coefficient of variation in basis points.
     * Result: the months to add. **`null` gives 0, not [cvHighBump]** — too little history is an
     *         absence of evidence, and inflating a stranger's target on no data would be the app
     *         inventing a number about them (P-03).
     * Input:  [incomeCvBps] — cv × 10 000, or null when it could not be measured.
     * Output: whole months, 0 or more.
     */
    fun volatilityBumpFor(incomeCvBps: Int?): Int =
        when {
            incomeCvBps == null -> 0
            incomeCvBps < cvLowBps -> 0
            incomeCvBps <= cvHighBps -> cvMidBump
            else -> cvHighBump
        }

    /**
     * The personal multiplier M, after `RULE-RUNWAY-M`'s clamp.
     *
     * Why:    §10.1 states the clamp separately from the sum because it is a different rule, owned
     *         by a different row. Keeping the two steps distinct is what lets the caller say whether
     *         the clamp actually fired — and cite `RULE-RUNWAY-M` only when it did, the discipline
     *         `QuickSetupRules.runwayWasClamped` already keeps.
     * What:   base + the volatility bump, coerced into `clamp_months`.
     * Result: whole months in `[runwayMinMonths, runwayMaxMonths]`.
     * Input:  [incomeCvBps]. Output: months.
     */
    fun multiplierFor(incomeCvBps: Int?): Int =
        unclampedMultiplierFor(incomeCvBps).coerceIn(runwayMinMonths, runwayMaxMonths)

    /**
     * The multiplier **before** the clamp, so a caller can tell whether the clamp changed anything.
     * Result: base + bump, uncoerced. Input: [incomeCvBps]. Output: months.
     */
    fun unclampedMultiplierFor(incomeCvBps: Int?): Int = baseMonths + volatilityBumpFor(incomeCvBps)

    companion object {
        /** §10.1 — how deep the fund should be. The row this engine exists to apply. */
        val MULTIPLIER = RuleCitation("RULE-EMF-MULT", "1.0")

        /** §10.1's coach behaviour — how to frame the runway the user actually has. */
        val COACH = RuleCitation("RULE-EMF-COACH", "1.0")

        /** The clamp on M; cited **only when it changed the answer**, as `QuickSetupRules` does. */
        val RUNWAY_CLAMP = RuleCitation("RULE-RUNWAY-M", "1.0")

        /**
         * The rulebook file these thresholds were copied from, as `_meta.version`.
         *
         * `_meta.version` describes the **file**, not these rows, so every typed mirror restates it
         * whenever any rule anywhere is added. Issue 7.2 added two rows, which is why 1.14.0 became
         * 1.15.0 in six places at once.
         */
        const val RULEBOOK_VERSION = "1.15.0"

        /** 10 000 bps = 100% (MNY-002). Also the scale [EmergencyFundPlan.runwayMonthsBps] uses. */
        internal const val BPS_FULL = 10_000
    }
}
