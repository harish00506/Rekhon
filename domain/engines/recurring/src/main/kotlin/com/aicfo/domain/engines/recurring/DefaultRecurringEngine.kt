package com.aicfo.domain.engines.recurring

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * The production [RecurringEngine] (issue 3.7; FR-TXN-006, MNY-001, MNY-002, TIM-002, P-02, P-08).
 *
 * Why:  the whole engine turns on one judgement — **a series is rejected unless *every* gap fits
 *       the cadence, not just the average one**. The tempting shortcut is to classify on the mean
 *       gap and stop there, which is cheaper and quietly wrong: three charges on the 1st, the 3rd
 *       and the 60th average out to a 30-day cadence and would be proposed as a monthly bill the
 *       user has never had. Checking every gap costs one extra line and is the difference between
 *       a detector and a coincidence generator.
 * What: group by normalised merchant → sort by date → classify the median gap → verify every gap
 *       and every amount against the rulebook's tolerances → project the next due date.
 * Result: the proposals the transactions list shows. Nothing is written and nothing is created
 *       (P-07); the user confirms.
 * Changelog: 2026-08-05 — Created for issue 3.7.
 *
 * `internal` per ARC-003 — constructed only by [RecurringEngineFactory].
 *
 * **There is not a `Double` in this file** (MNY-001). Amount tolerance is checked by
 * cross-multiplication rather than by dividing into a ratio, so no rounding decision arises at all;
 * the two medians are *lower* medians for the same reason (see [lowerMedian]).
 */
internal class DefaultRecurringEngine : RecurringEngine {
    override fun detect(input: RecurringInput): Result<List<RecurringSeries>, AppError> =
        runCatchingToResult {
            input.candidates
                .filter { it.merchant.isNotBlank() }
                .groupBy { it.merchant.trim().lowercase() }
                // Sorted by the normalised key, so two runs over the same rows propose the same
                // series in the same order — `groupBy` alone preserves only encounter order, which
                // a page boundary or a re-query can change (P-08).
                .toSortedMap()
                .values
                .mapNotNull { group -> seriesFrom(group, input.rules, input.nowUtcMillis) }
        }

    /**
     * Turns one merchant's transactions into a series, or into nothing.
     * Why:    every early return here is a false positive the detector refuses to make, and each
     *         one is a case the acceptance criteria name: too few occurrences, an irregular gap, a
     *         wandering amount. Returning `null` rather than throwing keeps "this is not a series"
     *         an ordinary answer instead of an error — most merchants are not series.
     * What:   sorts, measures the gaps, classifies, and verifies.
     * Result: a [RecurringSeries], or `null` when the group fails any of the rulebook's tests.
     * Input:  [group] — one merchant's candidates, unordered; [rules] — the rulebook thresholds;
     *         [nowUtcMillis] — the caller's clock reading (TIM-001).
     * Output: [RecurringSeries]?. Throws only if a `bookedOn` is not ISO `yyyy-MM-dd`, which
     *         [detect]'s `runCatchingToResult` turns into an `Err`.
     * Changelog: 2026-08-05 — Created for issue 3.7.
     */
    @Suppress("ReturnCount") // One early return per refusal — see the doc; folding them into a
    // single expression would hide which rulebook test a rejected group failed.
    private fun seriesFrom(
        group: List<RecurringCandidate>,
        rules: RecurringRules,
        nowUtcMillis: Long,
    ): RecurringSeries? {
        // Tie-broken by id: two charges booked on the same day would otherwise order arbitrarily,
        // and `occurrences` is shown to the user (P-02) so it must not reshuffle between reads.
        val sorted = group.sortedWith(compareBy({ it.bookedOn }, { it.transactionId }))
        if (sorted.size < rules.minOccurrences) return null

        val dates = sorted.map { LocalDate.parse(it.bookedOn) }
        val gaps = dates.zipWithNext { earlier, later -> ChronoUnit.DAYS.between(earlier, later) }
        val cadence = classify(gaps, rules) ?: return null

        val medianAmount = Money(sorted.map { it.amount.minor }.lowerMedian())
        if (!sorted.all { it.amount.within(medianAmount, rules.amountTolerancePct) }) return null

        return RecurringSeries(
            // The most recent spelling, not the matched key: showing the key would render a payee
            // the user typed as `NETFLIX` back to them as `netflix`.
            merchant = sorted.last().merchant.trim(),
            cadence = cadence,
            medianAmount = medianAmount,
            nextDueIsoDate = cadence.advance(dates.last()).toString(),
            occurrences = sorted.map { RecurringOccurrence(it.transactionId, it.bookedOn) },
            provenance =
                EngineProvenance(
                    engineId = ENGINE_ID,
                    engineVersion = ENGINE_VERSION,
                    computedAtUtcMillis = nowUtcMillis,
                    evidence = listOf(RecurringRules.SERIES_MATCH),
                    inputWindow = "${dates.first()}..${dates.last()}",
                    confidenceBps = confidenceBps(gaps, cadence, rules),
                ),
        )
    }

    /**
     * Decides which cadence a set of gaps is, if any.
     * Why:    classified on the **median** gap and then verified against **every** gap. The median
     *         picks the candidate cadence without one mistyped date dragging the answer, and the
     *         `all` check is what stops a coincidence being promoted (see the class comment). Both
     *         steps are needed: the median alone accepts too much, and matching every gap without
     *         first picking a target would need three passes.
     * Result: the [Cadence] whose nominal period every gap sits within tolerance of, or `null`.
     * Input:  [gaps] — days between consecutive occurrences, in date order, never empty because the
     *         caller has already required at least two occurrences; [rules] — the tolerances.
     * Output: [Cadence]?.
     * Changelog: 2026-08-05 — Created for issue 3.7.
     */
    private fun classify(
        gaps: List<Long>,
        rules: RecurringRules,
    ): Cadence? {
        val median = gaps.lowerMedian()
        val candidate =
            Cadence.entries.firstOrNull { abs(median - it.periodDays) <= rules.toleranceDaysFor(it) }
                ?: return null
        val tolerance = rules.toleranceDaysFor(candidate)
        return candidate.takeIf { gaps.all { gap -> abs(gap - candidate.periodDays) <= tolerance } }
    }

    /**
     * Scores how regular a series is, as integer basis points (MNY-002).
     * Why:    `EngineProvenance.confidenceBps` exists so a proposal can say how sure it is, and the
     *         only evidence this detector has is how tightly the gaps cluster — a bill paid on the
     *         3rd every month is a stronger claim than one that wanders four days either way. The
     *         scale is deliberately relative to the *tolerance*: a gap at the edge of what the
     *         rulebook allows should score near the bottom, not near the middle, because it is the
     *         proposal most likely to be wrong.
     * Result: 10 000 bps (100%) for a series whose gaps are exactly the period, falling towards
     *         2 000 bps at the tolerance edge for the monthly band. Never outside 0..10 000, which
     *         `EngineProvenance` requires.
     * Input:  [gaps] — days between occurrences; [cadence] — the classified cadence; [rules] — the
     *         tolerances. Output: [Int] basis points.
     * Changelog: 2026-08-05 — Created for issue 3.7.
     */
    private fun confidenceBps(
        gaps: List<Long>,
        cadence: Cadence,
        rules: RecurringRules,
    ): Int {
        val worst = gaps.maxOf { abs(it - cadence.periodDays) }
        // +1 on the divisor so a zero tolerance is not a division by zero, and so the worst
        // permitted deviation still scores above zero rather than reading as "no confidence".
        val penalty = worst * BPS_FULL / (rules.toleranceDaysFor(cadence) + 1)
        return (BPS_FULL - penalty).coerceIn(0L, BPS_FULL.toLong()).toInt()
    }

    private companion object {
        /** Stable identifier stored with every proposal (AI-ARC-003). */
        const val ENGINE_ID = "recurring-detector"

        /**
         * Bump whenever the formula changes (AI-ARC-006).
         *
         * Stored with every proposal, so a rule the user confirmed today can still be explained
         * after the detector is rewritten — which is the whole reason the field exists.
         */
        const val ENGINE_VERSION = "1.0"

        /** 10 000 bps = 100% (MNY-002). */
        const val BPS_FULL = 10_000L
    }
}

/**
 * The lower of the two middle values, for an odd or even count alike.
 * Why:    a true median of an even-sized list averages the middle pair, and averaging paise
 *         introduces a division and therefore a rounding decision (MNY-001) for no benefit — the
 *         figure is a *representative* amount shown for confirmation, not a total that has to
 *         reconcile. Picking one real observed value keeps the detector free of rounding entirely
 *         and means the amount the user is asked to confirm is one they actually paid.
 *
 *         **It sorts by the signed value**, so for an even count of outflows it picks the *more
 *         negative* — the larger expense. Which of the two middle values is "the" median is
 *         arbitrary for an even count whichever way it is defined; what matters under P-08 is that
 *         it is fixed, so `RecurringEngineTest` pins it rather than leaving it to be discovered.
 * Result: an element of the list. Input: the receiver — never empty; the caller has already
 *         required at least two occurrences. Output: [Long].
 * Changelog: 2026-08-05 — Created for issue 3.7.
 */
private fun List<Long>.lowerMedian(): Long = sorted()[(size - 1) / 2]

/**
 * Whether one amount is close enough to the series median.
 * Why:    checked by cross-multiplication rather than by dividing into a percentage, so there is no
 *         rounding and no floating point anywhere on a money path (MNY-001). `multiplyExact` makes
 *         an amount too large to compare fail loudly instead of wrapping into a false match.
 *
 *         **A zero median means every occurrence must be exactly zero**, which falls out of the
 *         arithmetic rather than needing a special case: 0 × any percent is 0.
 * Result: `true` when `|this − median| × 100 ≤ |median| × tolerancePct`.
 * Input:  the receiver — one occurrence's amount; [median] — the series median; [tolerancePct] — a
 *         whole percent from the rulebook. Output: [Boolean].
 * Changelog: 2026-08-05 — Created for issue 3.7.
 */
private fun Money.within(
    median: Money,
    tolerancePct: Int,
): Boolean {
    val deviation = Math.multiplyExact(abs(minor - median.minor), PCT_TOTAL)
    return deviation <= Math.multiplyExact(abs(median.minor), tolerancePct.toLong())
}

/**
 * Moves a date on by one period of this cadence.
 * Why:    calendar arithmetic, not `plusDays(periodDays)`. [Cadence.periodDays] is a *match* target
 *         for classifying gaps; using it to project a date would put the next monthly bill 30 days
 *         after 31 January — the 2nd of March — when the user expects the 28th of February.
 *         `java.time` knows about short months and leap years, and this is the one place that
 *         knowledge is needed.
 * Result: the projected next occurrence. Input: the receiver — the cadence; [from] — the last
 *         observed occurrence. Output: [LocalDate].
 * Changelog: 2026-08-05 — Created for issue 3.7.
 */
private fun Cadence.advance(from: LocalDate): LocalDate =
    when (this) {
        Cadence.WEEKLY -> from.plusWeeks(1)
        Cadence.MONTHLY -> from.plusMonths(1)
        Cadence.YEARLY -> from.plusYears(1)
    }

/** The whole of an amount, as the multiplier that turns a percent band into a comparison. */
private const val PCT_TOTAL = 100L
