package com.aicfo.domain.engines.purchase

import com.aicfo.core.model.RuleCitation

/**
 * `RULE-PAI-LADDER` and `RULE-PAI-SCORE` from `ai/rules/rules-kb.json`, as a typed mirror
 * (issue 10.2; §13.3, CLAUDE.md §6).
 *
 * Why:  how many questions a wish is worth, and what each answer is worth, are the two settings a
 *       reviewer would most want to tune — and the two that would otherwise be constants scattered
 *       through a scoring function. There is no runtime loader (ADR-0017), so this is the copy the
 *       engine reads, held honest by `InterviewRulebookDriftTest`.
 * What: §13.3.1's bands and question counts, and §13.3.2's starting score, outcome bands and
 *       per-answer deltas.
 * Result: everything [PurchaseInterviewEngine] reads besides its input.
 * Changelog: 2026-09-26 — Created for issue 10.2 from rules-kb.json 1.22.0.
 *
 * Input:  [bandCeilingsBps] — the top of each band in basis points of monthly income (MNY-002);
 *         [questionsPerBand] — how many questions each band asks, merged over the defaults;
 *         [heavyCoolOffHours]; [emiIsAlwaysHeavy]; [startScore]; [keepMin] / [parkMin] — §13.3.2's
 *         outcome lines; [reinterviewDays]; [deltas] — points per named answer, merged over the
 *         defaults.
 * Output: an immutable value.
 */
data class InterviewRules(
    val bandCeilingsBps: Map<PurchaseWeight, Int> = DEFAULT_BANDS,
    private val questionsPerBand: Map<PurchaseWeight, Int> = emptyMap(),
    val heavyCoolOffHours: Int = DEFAULT_COOL_OFF_HOURS,
    val emiIsAlwaysHeavy: Boolean = true,
    val startScore: Int = DEFAULT_START,
    val keepMin: Int = DEFAULT_KEEP_MIN,
    val parkMin: Int = DEFAULT_PARK_MIN,
    val reinterviewDays: Int = DEFAULT_REINTERVIEW_DAYS,
    private val deltas: Map<String, Int> = emptyMap(),
) {
    init {
        require(startScore >= 0) { "a wish cannot start below nothing" }
        require(parkMin <= keepMin) { "the park line must sit at or below the keep line, or PARK is unreachable" }
        require(reinterviewDays >= 1) { "asking again on the same day is the nagging §13.3 forbids" }
        require(heavyCoolOffHours >= 0) { "a negative pause is not a pause" }
    }

    /** How many questions a band asks — the rulebook's number, or §13.3.1's default. */
    fun questionsFor(weight: PurchaseWeight): Int = questionsPerBand[weight] ?: DEFAULT_QUESTIONS.getValue(weight)

    /** What one named answer is worth — the rulebook's number, or the minted default (ADR-0050). */
    fun delta(reason: String): Int = deltas[reason] ?: DEFAULT_DELTAS[reason] ?: 0

    /** Every delta this mirror knows, for the drift test to compare against the rulebook. */
    fun knownDeltas(): Map<String, Int> = DEFAULT_DELTAS + deltas

    companion object {
        private const val DEFAULT_COOL_OFF_HOURS = 24
        private const val DEFAULT_START = 50
        private const val DEFAULT_KEEP_MIN = 70
        private const val DEFAULT_PARK_MIN = 40
        private const val DEFAULT_REINTERVIEW_DAYS = 30

        /** §13.3.1's ladder, in basis points of monthly income: 0.5%, 2%, 10%, 25%. */
        val DEFAULT_BANDS: Map<PurchaseWeight, Int> =
            mapOf(
                PurchaseWeight.CASUAL to 50,
                PurchaseWeight.SMALL to 200,
                PurchaseWeight.SIGNIFICANT to 1_000,
                PurchaseWeight.MAJOR to 2_500,
            )

        /** §13.3.1's question counts, band by band. */
        val DEFAULT_QUESTIONS: Map<PurchaseWeight, Int> =
            mapOf(
                PurchaseWeight.CASUAL to 1,
                PurchaseWeight.SMALL to 3,
                PurchaseWeight.SIGNIFICANT to 5,
                PurchaseWeight.MAJOR to 7,
                PurchaseWeight.HEAVY to 8,
            )

        /**
         * The per-answer points (RULE-PAI-SCORE).
         *
         * §13.3.2 names the factors and gives no numbers, so these are minted — sized so that no
         * single answer can carry a wish from 50 to either outcome on its own. `PurchaseInterviewEngineTest`
         * asserts exactly that, and the first draft of these numbers failed it: a single "need"
         * reached KEEP and a single "I own one already" reached SUGGEST_REMOVE (ADR-0050).
         */
        val DEFAULT_DELTAS: Map<String, Int> =
            mapOf(
                "need" to 15,
                "want" to -5,
                "uses_high" to 15,
                "uses_medium" to 5,
                "uses_low" to -10,
                "owns_similar" to -10,
                "owns_nothing_similar" to 5,
                "waiting_breaks_something" to 10,
                "waiting_breaks_nothing" to -10,
                "goal_delay_accepted" to 5,
                "goal_delay_refused" to -10,
                "still_wanted_after_30_days" to 10,
            )

        /** The rulebook file these values were copied from, as `_meta.version`. */
        const val RULEBOOK_VERSION = "1.23.0"

        /** `RULE-PAI-LADDER` — how many questions a wish is worth. */
        val LADDER = RuleCitation("RULE-PAI-LADDER", "1.0")

        /** `RULE-PAI-SCORE` — the starting score, the deltas and the outcome bands. */
        val SCORE = RuleCitation("RULE-PAI-SCORE", "1.0")
    }
}
