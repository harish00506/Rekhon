package com.aicfo.domain.engines.stream

import com.aicfo.core.model.RuleCitation

/**
 * `stream_classification` from `ai/knowledge/classification-kb.json`, as a typed mirror (issue 9.1).
 *
 * Why:  CLAUDE.md §6 — thresholds are data rows in `ai/`, never engine constants. There is still no
 *       runtime loader (ADR-0017), so this is the same typed mirror every other engine here uses,
 *       held honest by `StreamKbDriftTest`. The file states the weights and thresholds as decimal
 *       fractions (0.45, 0.75); they are mirrored as **basis points** (4 500, 7 500) because engines
 *       compute rates in integer bps (MNY-002, P-08), and the drift test checks `decimal × 10 000`.
 *       Injectable, so a test can move a threshold and watch the class move.
 * What: the weights, the class thresholds, the §8.2 parameters stated in prose, the confidence of
 *       each step, the cold-start priors (`category_defaults.typical_stream`) and the four rows'
 *       citations.
 * Result: everything [DefaultStreamEngine] reads besides its input.
 * Changelog: 2026-09-19 — Created for issue 9.1 from classification-kb.json v1.4.
 *
 * Input:  every field defaulted to the shipped knowledge base. Output: an immutable value.
 */
data class StreamRules(
    val cvWeightBps: Int = 4_500,
    val cadenceWeightBps: Int = 3_500,
    val dayLockWeightBps: Int = 2_000,
    val fixedMinScoreBps: Int = 7_500,
    val fixedMinMonths: Int = 3,
    val semiFixedMinScoreBps: Int = 4_500,
    val dayLockWindowDays: Int = 3,
    val coldStartMinMonths: Int = 2,
    val pinnedConfidenceBps: Int = 10_000,
    val obligationConfidenceBps: Int = 9_500,
    val priorConfidenceBps: Int = 4_000,
    val noPriorConfidenceBps: Int = 2_000,
    val scoredConfidenceBps: Int = 8_000,
    val priors: Map<String, StreamClass> = DEFAULT_PRIORS,
) {
    init {
        require(cvWeightBps + cadenceWeightBps + dayLockWeightBps == FULL_BPS) {
            "the §8.2 weights must sum to 1 (10 000 bps)"
        }
        require(semiFixedMinScoreBps <= fixedMinScoreBps) { "SEMI_FIXED's floor cannot sit above FIXED's" }
        require(coldStartMinMonths >= 2) { "a coefficient of variation needs at least two months" }
        require(dayLockWindowDays >= 0) { "a day-lock window cannot be negative" }
    }

    companion object {
        /** 10 000 bps = 100% (MNY-002). */
        const val FULL_BPS = 10_000

        /** The knowledge-base file these values were copied from, as `_meta.version`. */
        const val KB_VERSION = "1.4"

        /** CLS-STR-001 — the §8.2 score. */
        val SCORED = RuleCitation("CLS-STR-001", "1.0")

        /** CLS-STR-002 — a known obligation is FIXED. */
        val KNOWN_OBLIGATION = RuleCitation("CLS-STR-002", "1.0")

        /** CLS-STR-003 — the user's pin. */
        val PINNED = RuleCitation("CLS-STR-003", "1.0")

        /** CLS-STR-004 — the cold-start prior. */
        val COLD_START = RuleCitation("CLS-STR-004", "1.0")

        /**
         * `category_defaults[].typical_stream`, keyed by `category_defaults[].key`, in file order.
         * Why:    §8.2's cold start uses "category-level Indian priors", and these are those priors.
         *         Each is cited by its own `CLS-CAT-*` row (see [PRIOR_CITATIONS]).
         */
        val DEFAULT_PRIORS: Map<String, StreamClass> =
            linkedMapOf(
                "rent" to StreamClass.FIXED,
                "groceries" to StreamClass.SEMI_FIXED,
                "utilities" to StreamClass.SEMI_FIXED,
                "fuel" to StreamClass.SEMI_FIXED,
                "transport" to StreamClass.SEMI_FIXED,
                "health" to StreamClass.VARIABLE,
                "insurance" to StreamClass.FIXED,
                "education" to StreamClass.FIXED,
                "dining" to StreamClass.VARIABLE,
                "shopping" to StreamClass.VARIABLE,
                "entertainment" to StreamClass.VARIABLE,
                "travel" to StreamClass.VARIABLE,
                "subscriptions" to StreamClass.FIXED,
                "investment" to StreamClass.FIXED,
                "emi" to StreamClass.FIXED,
            )

        /** The `CLS-CAT-*` row each prior comes from, so a prior verdict cites its source (P-02). */
        val PRIOR_CITATIONS: Map<String, RuleCitation> =
            DEFAULT_PRIORS.keys.withIndex().associate { (index, key) ->
                key to RuleCitation("CLS-CAT-%03d".format(index + 1), "1.0")
            }
    }
}
