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
 * What: one property per `params_json` key of `RULE-BUD-SUGGEST`, `RULE-BUD-PACE` and
 *       `RULE-BUD-ALERT`, plus the citations all three rules are cited by.
 * Result: every budget this app proposes is attributable to a row a reviewer can open, and every
 *       threshold that shaped it is one they can change (P-02, AI-ARC-006).
 * Changelog:
 *   2026-08-11 — Created for issue 4.4 from rules-kb.json v1.9.0.
 *   2026-08-13 — Added FR-BUD-004's alert bands from rules-kb.json v1.10.0 (issue 4.5).
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
 *         offered; [warnPct] and [exceededPct] — `RULE-BUD-ALERT`'s two bands, whole percents.
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
    }

    companion object {
        /** FR-BUD-002 — the median-plus-seasonality suggestion. */
        val SUGGESTION = RuleCitation("RULE-BUD-SUGGEST", "1.0")

        /** FR-BUD-003 — safe pace and the projected end-of-month figure. */
        val PACE = RuleCitation("RULE-BUD-PACE", "1.0")

        /** FR-BUD-004 — the 80%/100% alert bands. A separate row on purpose; see ADR-0019. */
        val ALERT = RuleCitation("RULE-BUD-ALERT", "1.0")

        /** The rulebook file these thresholds were copied from, as `_meta.version`. */
        const val RULEBOOK_VERSION = "1.10.0"
    }
}

/** 10 000 bps = 100% (MNY-002). */
internal const val BPS_FULL = 10_000

/** 100 bps = 1%, so a whole-percent rule threshold becomes a bps comparison (MNY-002). */
internal const val BPS_PER_PERCENT = 100
