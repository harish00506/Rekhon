package com.aicfo.domain.engines.goals

import com.aicfo.core.model.RuleCitation

/**
 * The thresholds `RULE-HORIZON` applies, copied from `ai/rules/rules-kb.json` (issue 7.1; §15).
 *
 * Why:  CLAUDE.md §6 says a financial threshold is a **data row in `ai/`, never a hardcoded
 *       number** — and this file is a hardcoded number. The same deliberate, recorded deferral
 *       ADR-0005 made for `QuickSetupRules` and ADR-0017 restated for `BudgetRules`: nothing in the
 *       app loads `ai/` at runtime, so honouring §6 literally would mean building an asset pipeline
 *       and a JSON parser into a module that has no serialisation dependency by design (ARC-002).
 *       `RulebookDriftTest` closes the gap that matters — edit `RULE-HORIZON` in the rulebook and
 *       the build goes red until this file agrees.
 * What: one property per numeric `params_json` key of `RULE-HORIZON`, plus the citation every
 *       projection carries.
 * Result: the horizon this engine reports is attributable to a row a reviewer can open and change
 *       (P-02, AI-ARC-006).
 * Changelog: 2026-08-30 — Created for issue 7.1 from rules-kb.json v1.14.0.
 *
 * **`RULE-HORIZON` already named this engine before it existed**: its `consumed_by` is
 * `["AI-GOAL.funding_buckets"]`. Mirroring the row it already points at is why issue 7.1 mints no
 * new rulebook row at all — see `ENGINE.md` and ADR-0033.
 *
 * **There is no threshold here for "on track", and that is deliberate.** Whether a goal is on track
 * is an *exact* comparison of two figures the user typed — the monthly they plan against the monthly
 * they need — so there is nothing to store. The engine reports `shortfallMonthly` beside the verdict
 * so the user sees the gap rather than a bare boolean, which is better "show the work" (P-02) than a
 * tolerance band would be. Minting a row for a slack percentage would also bump the rulebook's
 * `_meta.version`, forcing every other engine's mirror to restate [RULEBOOK_VERSION] — a lot of
 * churn to buy a band nobody asked for.
 *
 * **There is no assumed rate of return either**, and that is an absence rather than a zero: no
 * return rate exists anywhere in `ai/rules/`, and inventing one would be exactly the hardcoded
 * financial number §6 forbids. The horizon below is reported *as advice* — "this is a long-horizon
 * goal, equity is eligible" — never compounded into the projection behind the user's back (P-03).
 *
 * Input:  [shortYearsMax] — `RULE-HORIZON.short_years_max`, under which money is needed too soon to
 *         take market risk; [hybridYearsMax] — `RULE-HORIZON.hybrid_years_max`, the top of the
 *         middle band.
 * Output: an immutable value.
 */
data class GoalRules(
    /** `RULE-HORIZON.short_years_max` — under three years: savings, FD or paying down debt. */
    val shortYearsMax: Int = 3,
    /** `RULE-HORIZON.hybrid_years_max` — three to five years: hybrid. Above it, equity-eligible. */
    val hybridYearsMax: Int = 5,
) {
    init {
        // Inverted or negative bands would silently swallow a whole bucket: with hybrid below short,
        // `bucketFor` could never return HYBRID, and every test asserting an *amount* would still
        // pass while the advice beside it was nonsense.
        require(shortYearsMax > 0) {
            "shortYearsMax is a horizon in whole years and must be positive, was $shortYearsMax"
        }
        require(hybridYearsMax >= shortYearsMax) {
            "hybridYearsMax ($hybridYearsMax) must not be below shortYearsMax ($shortYearsMax): the " +
                "bands are ordered, and inverting them makes the middle bucket unreachable"
        }
    }

    /**
     * Which funding bucket a horizon falls in.
     *
     * Why:    `RULE-HORIZON` is written in years and this engine counts in months, because a goal
     *         eleven months out and one thirteen months out are not the same advice and whole-year
     *         truncation would call them both "1 year".
     * What:   the row's two bands, applied to a month count.
     * Result: [Horizon]. The lower band is **exclusive** and the upper **inclusive**, matching the
     *         row's own wording — "money needed < 3y" is short, "3-5y" is hybrid, "> 5y" is
     *         equity-eligible. A goal exactly five years out is therefore hybrid, not long.
     * Input:  [monthsRemaining] — whole months from today to the target date; never negative.
     * Output: [Horizon].
     */
    fun bucketFor(monthsRemaining: Int): Horizon =
        when {
            monthsRemaining < shortYearsMax * MONTHS_IN_YEAR -> Horizon.SHORT
            monthsRemaining <= hybridYearsMax * MONTHS_IN_YEAR -> Horizon.HYBRID
            else -> Horizon.LONG
        }

    companion object {
        /** §15 — the funding-bucket bands, the one row this engine applies. */
        val HORIZON = RuleCitation("RULE-HORIZON", "1.0")

        /**
         * The rulebook file these thresholds were copied from, as `_meta.version`.
         *
         * `_meta.version` describes the **file**, not this row, so every typed mirror restates it
         * whenever any rule anywhere is added. `RULE-HORIZON`'s own version is on [HORIZON] and is
         * still 1.0 — issue 7.1 added no rulebook row, so neither number moved.
         */
        const val RULEBOOK_VERSION = "1.15.0"

        /** Twelve. The rulebook states horizons in years; this engine counts months. */
        internal const val MONTHS_IN_YEAR = 12
    }
}

/**
 * Which funding bucket a goal's horizon puts it in (`RULE-HORIZON`, §15).
 *
 * Why:  an enum rather than a user-visible string, so the domain never decides wording and a
 *       feature module can translate it (the same reason `SafeToSpendComponent` is an enum).
 * What: the three bands `RULE-HORIZON` defines.
 * Result: advice about *where* the money should sit, reported alongside the arithmetic and never
 *       folded into it.
 * Changelog: 2026-08-30 — Created for issue 7.1.
 */
enum class Horizon {
    /** Under `short_years_max`: savings, FD, or paying down debt. Too soon for market risk. */
    SHORT,

    /** Between the two bands: hybrid. */
    HYBRID,

    /** Above `hybrid_years_max`: equity-eligible. */
    LONG,
}
