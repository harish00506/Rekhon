package com.aicfo.domain.engines.healthscore

import com.aicfo.core.model.RuleCitation

/**
 * `RULE-FHS-PILLARS`, `RULE-FHS-BANDS`, `RULE-FHS-SIGNALS` — and the two rows they borrow a number
 * from — as a typed mirror of `ai/rules/rules-kb.json` (issue 9.4; §14, CLAUDE.md §6).
 *
 * Why:  every number in the score is a rulebook number; there is no runtime loader (ADR-0017), so
 *       this is the copy the engine reads, held honest by `HealthRulebookDriftTest`. Injectable, so a
 *       test can move a weight and watch the score move.
 * What: the five pillar weights and the scale; the lookback and the minimum signal; the four band
 *       edges; the signal curves' anchors — including `RULE-CC-UTIL.max_utilisation_pct` and
 *       `RULE-SAVE-RATE.excellent_pct`, which are read, not restated in the FHS rows.
 * Result: everything [HealthScoreEngine] reads besides its input.
 * Changelog: 2026-09-19 — Created for issue 9.4 from rules-kb.json 1.17.0.
 *
 * Input:  weights in bps summing to 10 000; [scoreMax] — the top of the scale; [lookbackMonths] —
 *         closed months the caller reads income and saving over; [minMonthsOfSignal] — fewer and a
 *         signal is absent, never guessed; band edges descending; percentages whole-number.
 * Output: an immutable value.
 */
data class HealthRules(
    val liquidityWeightBps: Int = 2_500,
    val debtWeightBps: Int = 2_000,
    val disciplineWeightBps: Int = 2_000,
    val goalsWeightBps: Int = 2_000,
    val protectionWeightBps: Int = 1_500,
    val scoreMax: Int = 1_000,
    val lookbackMonths: Int = 3,
    val minMonthsOfSignal: Int = 1,
    val excellentMin: Int = 800,
    val goodMin: Int = 650,
    val fairMin: Int = 500,
    val attentionMin: Int = 350,
    val runwayFloorPoints: Int = 25,
    val obligationFullPct: Int = 30,
    val obligationZeroPct: Int = 55,
    val utilisationFullPct: Int = 30,
    val utilisationZeroPct: Int = 100,
    val savingsFullPct: Int = 30,
) {
    init {
        require(Pillar.entries.sumOf { weightOf(it) } == BPS) { "pillar weights must sum to 10 000 bps" }
        require(Pillar.entries.all { weightOf(it) >= 0 }) { "a pillar weight cannot be negative" }
        require(scoreMax > 0 && lookbackMonths > 0 && minMonthsOfSignal > 0) { "scale and windows must be positive" }
        require(excellentMin > goodMin && goodMin > fairMin && fairMin > attentionMin && attentionMin > 0) {
            "band edges must descend"
        }
        require(runwayFloorPoints in 0..PERCENT) { "the runway floor is a 0–100 score" }
        require(obligationFullPct < obligationZeroPct) { "obligations must reach zero after they stop being full" }
        require(utilisationFullPct < utilisationZeroPct) { "utilisation must reach zero after it stops being full" }
        require(savingsFullPct > 0) { "the savings-rate top must be above zero" }
    }

    /** Result: [pillar]'s weight in bps. Input: [pillar]. Output: [Int]. */
    fun weightOf(pillar: Pillar): Int =
        when (pillar) {
            Pillar.LIQUIDITY -> liquidityWeightBps
            Pillar.DEBT -> debtWeightBps
            Pillar.DISCIPLINE -> disciplineWeightBps
            Pillar.GOALS -> goalsWeightBps
            Pillar.PROTECTION -> protectionWeightBps
        }

    companion object {
        private const val BPS = 10_000
        private const val PERCENT = 100

        /** The rulebook file these values were copied from, as `_meta.version`. */
        const val RULEBOOK_VERSION = "1.20.0" // Issue 9.7 restated it for RULE-GRD-*; no row mirrored here changed.

        /** `RULE-FHS-PILLARS` — the weights, the scale and the insufficient-data rule. */
        val PILLARS = RuleCitation("RULE-FHS-PILLARS", "1.0")

        /** `RULE-FHS-BANDS` — the five bands. */
        val BANDS = RuleCitation("RULE-FHS-BANDS", "1.0")

        /** `RULE-FHS-SIGNALS` — the signal curves. */
        val SIGNALS = RuleCitation("RULE-FHS-SIGNALS", "1.0")

        /** `RULE-CC-UTIL` — cited when card utilisation is scored. */
        val CARD_UTILISATION = RuleCitation("RULE-CC-UTIL", "1.0")

        /** `RULE-SAVE-RATE` — cited when the savings rate is scored. */
        val SAVINGS_RATE = RuleCitation("RULE-SAVE-RATE", "1.0")
    }
}
