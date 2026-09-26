package com.aicfo.domain.engines.insight

import com.aicfo.core.model.RuleCitation

/**
 * `RULE-INS-RANK` and `RULE-INS-DEDUP` from `ai/rules/rules-kb.json`, as a typed mirror (issue 9.5;
 * §7.2, CLAUDE.md §6).
 *
 * Why:  the feed's order and its deduplication window are decisions a reviewer should be able to
 *       change in one file. There is no runtime loader (ADR-0017), so this is the copy the engine
 *       and the repository read, held honest by `InsightRulebookDriftTest`.
 * What: how many insights the dashboard shows, and how long a dismissal suppresses a fingerprint.
 * Result: everything [InsightEngine] and the orchestrator repository read besides their input.
 * Changelog: 2026-09-20 — Created for issue 9.5 from rules-kb.json 1.18.0.
 *
 * The severity order and the tie-breaks are not parameters: they are the order of [Severity]'s
 * constants and the fields of [Insight], which the drift test checks against the row's strings.
 *
 * Input:  [dashboardMax] — FR-HOME-001's "top 3", at least 1; [snoozeDays] — how long a dismissed
 *         fingerprint stays suppressed, at least 1. Output: an immutable value.
 */
data class InsightRules(
    val dashboardMax: Int = DEFAULT_DASHBOARD_MAX,
    val snoozeDays: Int = DEFAULT_SNOOZE_DAYS,
) {
    init {
        require(dashboardMax >= 1) { "a feed that shows nothing is not a feed" }
        require(snoozeDays >= 1) { "a snooze shorter than a day would return the same card the same day" }
    }

    companion object {
        private const val DEFAULT_DASHBOARD_MAX = 3
        private const val DEFAULT_SNOOZE_DAYS = 7

        /** The rulebook file these values were copied from, as `_meta.version`. */
        const val RULEBOOK_VERSION = "1.23.0" // Issue 10.3 restated it; no row mirrored here changed.

        /** `RULE-INS-RANK` — the feed's order and its size on the dashboard. */
        val RANK = RuleCitation("RULE-INS-RANK", "1.0")

        /** `RULE-INS-DEDUP` — the fingerprint and the snooze window. */
        val DEDUP = RuleCitation("RULE-INS-DEDUP", "1.0")
    }
}
