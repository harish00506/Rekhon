package com.aicfo.domain.engines.budget

import com.aicfo.core.model.RuleCitation

/**
 * The thresholds FR-BUD-002 and FR-BUD-003 apply, copied from `ai/rules/rules-kb.json`.
 *
 * Why:  CLAUDE.md §6 says a financial threshold is a **data row in `ai/`, never a hardcoded
 *       number** — and this file is five hardcoded numbers. The same deliberate, recorded deferral
 *       ADR-0005 made for `QuickSetupRules` and ADR-0017 restates for this engine: nothing in the
 *       app loads `ai/` at runtime, so honouring §6 literally would mean building an asset pipeline
 *       and a JSON parser into a module that has no serialisation dependency by design (ARC-002).
 *       `RulebookDriftTest` closes the gap that matters — edit a threshold in the rulebook and the
 *       build goes red until this file agrees.
 * What: one property per `params_json` key of `RULE-BUD-SUGGEST`, `RULE-BUD-PACE`,
 *       `RULE-BUD-ALERT` and `RULE-BUD-REVIEW`, plus the citations all four rules are cited by.
 * Result: every budget this app proposes is attributable to a row a reviewer can open, and every
 *       threshold that shaped it is one they can change (P-02, AI-ARC-006).
 * Changelog:
 *   2026-08-11 — Created for issue 4.4 from rules-kb.json v1.9.0.
 *   2026-08-13 — Added FR-BUD-004's alert bands from rules-kb.json v1.10.0 (issue 4.5).
 *   2026-08-14 — Added §5.5's review variance threshold from rules-kb.json v1.11.0 (issue 4.6).
 *
 * **Injected rather than read, so a test can move a threshold** and assert the engine moves with it
 * — which is also the seam the real loader will use when it lands, with no change to the engine.
 *
 * Input:  [lookbackMonths] — `RULE-BUD-SUGGEST.lookback_months`, how many months of history the
 *         median is taken over; [minMonthsRequired] — below this there is no suggestion at all,
 *         because a "median" of one month is that month; [seasonalityEnabled] — whether the
 *         calendar prior is applied; [shrinkageDenominatorMonths] — the `24` in the seasonality
 *         KB's own `k = months_observed / 24`; [roundToMinor] — the paise a suggestion is rounded
 *         to (MNY-001, so no `Double` reaches the engine); [minElapsedDaysForProjection] —
 *         `RULE-BUD-PACE.min_elapsed_days_for_projection`, below which no end-of-month figure is
 *         offered; [warnPct] and [exceededPct] — `RULE-BUD-ALERT`'s two bands, whole percents;
 *         [minVariancePct] — `RULE-BUD-REVIEW.min_variance_pct`, the gap between plan and actual
 *         below which the month-end review says nothing about a category.
 * Output: an immutable value.
 */
data class BudgetRules(
    /** `RULE-BUD-SUGGEST.lookback_months` — FR-BUD-002's "3 months of history". */
    val lookbackMonths: Int = 3,
    /** `RULE-BUD-SUGGEST.min_months_required` — fewer than this and no suggestion is offered. */
    val minMonthsRequired: Int = 2,
    /** `RULE-BUD-SUGGEST.seasonality_enabled` — FR-BUD-002's "seasonality adjustment". */
    val seasonalityEnabled: Boolean = true,
    /** `RULE-BUD-SUGGEST.shrinkage_denominator_months` — the `24` in the KB's `k = observed / 24`. */
    val shrinkageDenominatorMonths: Int = 24,
    /** `RULE-BUD-SUGGEST.round_to_minor` — 10 000 paise = ₹100, so a suggestion reads as a number. */
    val roundToMinor: Long = 10_000L,
    /** `RULE-BUD-PACE.min_elapsed_days_for_projection` — a run rate needs a run. */
    val minElapsedDaysForProjection: Int = 3,
    /** `RULE-BUD-ALERT.warn_pct` — FR-BUD-004's first band, in whole percent of the budget. */
    val warnPct: Int = 80,
    /** `RULE-BUD-ALERT.exceeded_pct` — FR-BUD-004's second band; the plan has been spent. */
    val exceededPct: Int = 100,
    /** `RULE-BUD-REVIEW.min_variance_pct` — below this, a month's gap is timing, not a habit. */
    val minVariancePct: Int = 15,
) {
    init {
        require(lookbackMonths >= 1) { "lookbackMonths must be at least 1, was $lookbackMonths" }
        require(minMonthsRequired >= 1) { "minMonthsRequired must be at least 1, was $minMonthsRequired" }
        // A window shorter than the floor could never satisfy it, so every category would be
        // permanently unsuggestable while every test asserting a *number* kept passing.
        require(minMonthsRequired <= lookbackMonths) {
            "minMonthsRequired ($minMonthsRequired) cannot exceed lookbackMonths ($lookbackMonths): " +
                "no category could ever collect enough history to be suggested"
        }
        require(shrinkageDenominatorMonths >= 1) {
            "shrinkageDenominatorMonths must be at least 1, was $shrinkageDenominatorMonths"
        }
        require(roundToMinor >= 1L) { "roundToMinor is in paise and must be at least 1, was $roundToMinor" }
        // Zero would mean projecting a month from an empty day, which is what the floor exists to
        // prevent; the run rate divides by elapsed days, so zero is also a division by zero.
        require(minElapsedDaysForProjection >= 1) {
            "minElapsedDaysForProjection must be at least 1, was $minElapsedDaysForProjection: a run " +
                "rate taken across zero days is both meaningless and undefined"
        }
        require(warnPct >= 1) { "warnPct must be at least 1, was $warnPct" }
        // Equal is allowed — a profile that only wants the overspend message sets both to 100 and
        // gets one alert, not two. Inverted is not: the warn band would be unreachable, so the
        // earlier warning would silently never fire while every test asserting a *band* still passed.
        require(warnPct <= exceededPct) {
            "warnPct ($warnPct) cannot exceed exceededPct ($exceededPct): the earlier band would " +
                "never be reachable, so the user would only ever hear about an overspend after it happened"
        }
        // Zero would propose an adjustment for every category every month, including ones that came
        // in a rupee off plan — which is the failure this threshold exists to prevent, and the one
        // that teaches a user to stop reading the review at all.
        require(minVariancePct >= 1) {
            "minVariancePct must be at least 1, was $minVariancePct: at zero every category is " +
                "'material' every month, and a review that always has something to say has nothing to say"
        }
    }

    companion object {
        /** FR-BUD-002 — the median-plus-seasonality suggestion. */
        val SUGGESTION = RuleCitation("RULE-BUD-SUGGEST", "1.0")

        /** FR-BUD-003 — safe pace and the projected end-of-month figure. */
        val PACE = RuleCitation("RULE-BUD-PACE", "1.0")

        /** FR-BUD-004 — the 80%/100% alert bands. A separate row on purpose; see ADR-0019. */
        val ALERT = RuleCitation("RULE-BUD-ALERT", "1.0")

        /**
         * §5.5 — the month-end review's materiality threshold. A separate row again (ADR-0020),
         * and the only citation here that never travels alone: a review's proposal is
         * [SUGGESTION]'s median, so a proposed adjustment cites both rows.
         */
        val REVIEW = RuleCitation("RULE-BUD-REVIEW", "1.0")

        /**
         * The rulebook file these thresholds were copied from, as `_meta.version`.
         *
         * Restated to 1.13.0 by issue 6.1, which added `RULE-CC-DUE` — as issue 5.2 restated it to
         * 1.12.0 for `RULE-STS`. **None of the four `RULE-BUD-*` rows changed** either time, and
         * none of their own versions moved. `_meta.version` describes the file, so every typed
         * mirror in the repo restates it whenever any rule is added; this constant says "copied from
         * that revision", not "these thresholds changed then".
         */
        const val RULEBOOK_VERSION = "1.15.0"
    }
}

/** 10 000 bps = 100% (MNY-002). */
internal const val BPS_FULL = 10_000

/** 100 bps = 1%, so a whole-percent rule threshold becomes a bps comparison (MNY-002). */
internal const val BPS_PER_PERCENT = 100
