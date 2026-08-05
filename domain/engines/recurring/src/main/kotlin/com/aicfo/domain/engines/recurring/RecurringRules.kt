package com.aicfo.domain.engines.recurring

import com.aicfo.core.model.RuleCitation

/**
 * The thresholds this engine applies, copied from `ai/rules/rules-kb.json` (ADR-0005).
 *
 * Why:  CLAUDE.md §6 says a financial threshold is a **data row in `ai/`, never a hardcoded
 *       number** — and these are hardcoded numbers. That is the same deliberate, recorded deferral
 *       ADR-0005 made for `QuickSetupRules`: nothing in the app loads `ai/` yet, so honouring §6
 *       literally would mean this issue first building an asset pipeline and a rulebook parser.
 *       `RulebookDriftTest` closes the gap that matters — edit a threshold in the rulebook and the
 *       build goes red until this file agrees.
 * What: one field per `RULE-RECUR-DETECT` parameter, each carrying the rule id it came from.
 * Result: every number in the detector is attributable to a rulebook row a reviewer can open.
 * Changelog: 2026-08-05 — Created for issue 3.7 from rules-kb.json v1.7.0.
 *
 * **Injected rather than read, so a test can move a threshold** and assert the engine moves with
 * it — which is also the seam the real loader will use when it lands, with no change to the engine.
 *
 * Input:  [minOccurrences] — FR-TXN-006's "≥ 2"; [amountTolerancePct] — how far one occurrence's
 *         amount may sit from the series median, as a whole percent (MNY-002 keeps it integer, so
 *         there is no floating-point comparison anywhere in the detector);
 *         [weeklyToleranceDays] / [monthlyToleranceDays] / [yearlyToleranceDays] — how far a gap
 *         between consecutive occurrences may sit from the nominal period, in whole days.
 * Output: an immutable value.
 */
data class RecurringRules(
    /** RULE-RECUR-DETECT `min_occurrences`. */
    val minOccurrences: Int = 2,
    /** RULE-RECUR-DETECT `amount_tolerance_pct`. */
    val amountTolerancePct: Int = 5,
    /** RULE-RECUR-DETECT `cadence_tolerance_days.weekly`. */
    val weeklyToleranceDays: Int = 2,
    /** RULE-RECUR-DETECT `cadence_tolerance_days.monthly`. */
    val monthlyToleranceDays: Int = 4,
    /** RULE-RECUR-DETECT `cadence_tolerance_days.yearly`. */
    val yearlyToleranceDays: Int = 10,
) {
    init {
        // FR-TXN-006 says "≥ 2 similar transactions", and one transaction is not a series under any
        // reading — a rulebook edit to 1 would make the detector propose every purchase ever made.
        require(minOccurrences >= MIN_SERIES_LENGTH) {
            "A series needs at least $MIN_SERIES_LENGTH occurrences (FR-TXN-006), was $minOccurrences"
        }
        require(amountTolerancePct in 0..PCT_TOTAL) {
            "amountTolerancePct is a whole percent in 0..$PCT_TOTAL, was $amountTolerancePct"
        }
        // Checked as a group: a tolerance wide enough to swallow its own period would classify a
        // gap of zero days as that cadence, which is how a detector starts proposing nonsense.
        Cadence.entries.forEach { cadence ->
            val tolerance = toleranceDaysFor(cadence)
            require(tolerance in 0 until cadence.periodDays) {
                "${cadence.name} tolerance must be under its ${cadence.periodDays}-day period, was $tolerance"
            }
        }
    }

    /**
     * How far a gap may sit from one cadence's nominal period.
     * Why:    the three tolerances are separate fields because the rulebook holds them separately,
     *         but every caller wants them keyed by cadence — so the mapping lives here once rather
     *         than as a `when` in the engine and a second `when` in the tests.
     * Result: whole days. Input: [cadence]. Output: [Int].
     * Changelog: 2026-08-05 — Created for issue 3.7.
     */
    fun toleranceDaysFor(cadence: Cadence): Int =
        when (cadence) {
            Cadence.WEEKLY -> weeklyToleranceDays
            Cadence.MONTHLY -> monthlyToleranceDays
            Cadence.YEARLY -> yearlyToleranceDays
        }

    companion object {
        /** The rule every proposal cites as its evidence (P-02, AI-ARC-006). */
        val SERIES_MATCH = RuleCitation("RULE-RECUR-DETECT", "1.0")
    }
}

/**
 * How often a series repeats (issue 3.7; FR-TXN-006).
 *
 * Why:    an enum, not a string, so the engine cannot emit a cadence no screen knows how to render
 *         and no screen can invent one the engine never produces. [periodDays] lives here because
 *         it is the cadence's definition, not the engine's opinion of it.
 * What:   the three cadences this issue detects, with the nominal gap each one means.
 * Result: carried on every [RecurringSeries] and mapped to copy by the UI — **never to copy here**,
 *         because `:domain:*` holds no user-visible strings (§21.6).
 * Changelog: 2026-08-05 — Created for issue 3.7.
 *
 * Input:  [periodDays] — the nominal gap in days. Monthly is 30 and yearly 365 on purpose: these
 *         are *match* targets compared against a tolerance, not calendar arithmetic. The actual
 *         next-due date is computed with `LocalDate.plusMonths`/`plusYears`, which does understand
 *         February.
 * Output: an enum constant.
 */
enum class Cadence(val periodDays: Int) {
    WEEKLY(DAYS_IN_WEEK),
    MONTHLY(DAYS_IN_NOMINAL_MONTH),
    YEARLY(DAYS_IN_NOMINAL_YEAR),
}

/** The whole of an amount, as the percent a tolerance is bounded by. */
private const val PCT_TOTAL = 100

/** The three nominal periods. Match targets for classifying a gap, never used to project a date
 * — `DefaultRecurringEngine.advance` uses `java.time`, which knows about February. */
private const val DAYS_IN_WEEK = 7

private const val DAYS_IN_NOMINAL_MONTH = 30

private const val DAYS_IN_NOMINAL_YEAR = 365

/** Two occurrences is the shortest thing FR-TXN-006 calls a series. */
private const val MIN_SERIES_LENGTH = 2
