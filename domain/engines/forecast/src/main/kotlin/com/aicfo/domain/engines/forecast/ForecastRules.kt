package com.aicfo.domain.engines.forecast

import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation

/**
 * `RULE-FCT-METHOD` and `RULE-FCT-CRUNCH` from `ai/rules/rules-kb.json`, as a typed mirror (issue 9.2).
 *
 * Why:  CLAUDE.md §6 — §9.2's numbers are rulebook rows, not engine constants. There is no runtime
 *       loader (ADR-0017), so this is the typed mirror every engine here uses, held honest by
 *       `ForecastRulebookDriftTest`. Injectable, so a test can move a parameter and watch the
 *       forecast move.
 * What: horizon, lookback, trim, simulations, the three band percentiles, the pay-cycle bucket
 *       edges, and the crunch buffer.
 * Result: everything [HeuristicForecastEngine] reads besides its input.
 * Changelog: 2026-09-19 — Created for issue 9.2 from rules-kb.json 1.16.0.
 *
 * Input:  every field defaulted to the shipped rulebook. Output: an immutable value.
 */
data class ForecastRules(
    val horizonDays: Int = 90,
    val lookbackDays: Int = 90,
    val trimBps: Int = 1_000,
    val simulations: Int = 500,
    val bandLowPct: Int = 10,
    val bandMidPct: Int = 50,
    val bandHighPct: Int = 90,
    val monthStartSpikeLastDay: Int = 5,
    val monthEndTroughFirstDay: Int = 25,
    val buffer: Money = Money(DEFAULT_BUFFER_MINOR),
) {
    init {
        require(
            horizonDays > 0 && lookbackDays > 0 && simulations > 0,
        ) { "horizon, lookback and simulations must be positive" }
        require(trimBps in 0 until HALF_BPS) { "a trim must leave something in the middle" }
        require(bandLowPct in 1..bandMidPct && bandMidPct <= bandHighPct && bandHighPct <= FULL_PCT) {
            "bands must be ordered percentiles"
        }
        require(monthStartSpikeLastDay < monthEndTroughFirstDay) { "the pay-cycle buckets must not overlap" }
    }

    companion object {
        private const val HALF_BPS = 5_000
        private const val FULL_PCT = 100
        private const val DEFAULT_BUFFER_MINOR = 500_000L // ₹5,000, RULE-FCT-CRUNCH buffer_minor

        /** The rulebook file these values were copied from, as `_meta.version`. */
        const val RULEBOOK_VERSION = "1.16.0"

        /** `RULE-FCT-METHOD` — the §9.2 method and its parameters. */
        val METHOD = RuleCitation("RULE-FCT-METHOD", "1.0")

        /** `RULE-FCT-CRUNCH` — the buffer a crunch day falls below. */
        val CRUNCH = RuleCitation("RULE-FCT-CRUNCH", "1.0")
    }
}
