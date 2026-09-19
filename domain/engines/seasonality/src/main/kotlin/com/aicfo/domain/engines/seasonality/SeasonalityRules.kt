package com.aicfo.domain.engines.seasonality

import com.aicfo.core.model.RuleCitation

/**
 * `SEAS-INDEX` from `ai/knowledge/calendar-seasonality.json`'s `method` block, as a typed mirror
 * (issue 9.3; §9.3, CLAUDE.md §6).
 *
 * Why:  §9.3's index has three numbers — the shrinkage denominator in `k = months_observed / 24`,
 *       how many closed months of history the per-category median reads, and the smallest effect on a
 *       month that counts (ADR-0044). They are data, so they
 *       live in the knowledge base; there is no runtime loader (ADR-0017), so this is the copy the
 *       engine and the repository use, held honest by `SeasonalityKbDriftTest`. Injectable, so a
 *       test can move a parameter and watch the index move.
 * What: the denominator, the history window, and the smallest effect that counts.
 * Result: everything [SeasonalityEngine] reads besides its input and [SeasonalityPriors].
 * Changelog: 2026-09-19 — Created for issue 9.3 from calendar-seasonality.json 1.1.
 *
 * Input:  [shrinkageDenominatorMonths] — the `24`, at least 1; [historyMonths] — closed months of
 *         per-category history the caller should supply, at least 12 (a calendar month has to be
 *         able to recur once); [minEffectBps] — the smallest move, either way, that counts: a month
 *         whose factor moves less is ×1, and a category whose effect is smaller is not named (P-02
 *         without noise: ten June days of "summer" in a lookback should not label every later month
 *         "summer easing", and a young install should not see "0.0% more — ₹0.39"). Output: an
 *         immutable value.
 */
data class SeasonalityRules(
    val shrinkageDenominatorMonths: Int = DEFAULT_DENOMINATOR,
    val historyMonths: Int = DEFAULT_HISTORY,
    val minEffectBps: Int = DEFAULT_MIN_EFFECT,
) {
    init {
        require(shrinkageDenominatorMonths >= 1) { "the shrinkage denominator must be at least 1" }
        require(historyMonths >= MONTHS_IN_YEAR) { "history must span at least a year for a month to recur" }
        require(minEffectBps >= 0) { "a minimum effect cannot be negative" }
    }

    companion object {
        private const val DEFAULT_DENOMINATOR = 24 // SEAS-INDEX shrinkage_denominator_months
        private const val DEFAULT_HISTORY = 36 // SEAS-INDEX history_months
        private const val DEFAULT_MIN_EFFECT = 100 // SEAS-INDEX min_effect_bps

        /** `SEAS-INDEX` — §9.3's median index with shrinkage, cited in every result's evidence. */
        val INDEX = RuleCitation("SEAS-INDEX", "1.0")
    }
}
