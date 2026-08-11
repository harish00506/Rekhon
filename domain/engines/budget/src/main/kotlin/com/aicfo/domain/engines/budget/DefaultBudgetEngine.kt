package com.aicfo.domain.engines.budget

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation

/**
 * The production [BudgetEngine] — FR-BUD-002's suggestion and FR-BUD-003's status (issue 4.4).
 *
 * Why:  both operations are arithmetic that must not drift, and both feed a screen the user makes
 *       money decisions on. Putting them in a pure module means every figure is reproducible from
 *       its inputs alone (P-08) and no LLM ever computes one (P-03).
 *
 *       **The median, not the mean, and that is the whole design.** A mean over three months hands
 *       a single ₹40,000 hospital bill to next month's health budget as if it were normal. The
 *       median asks what a *typical* month costs, which is what a budget is. The cost of that
 *       choice is honest and stated: a genuine, sustained step change takes two months to move a
 *       three-month median, so a user who moves house sees a stale rent suggestion once.
 * What: `suggest` takes a median over the lookback window and applies the shrunk seasonal prior;
 *       `status` computes the four FR-BUD-003 figures against a rollover-inclusive budget.
 * Result: numbers, each carrying the rulebook row that shaped it (AI-ARC-003). Nothing here writes,
 *       and nothing here decides — a suggestion is offered, never applied (P-07).
 * Changelog: 2026-08-11 — Created for issue 4.4.
 *
 * `internal` per ARC-003 — constructed only by [BudgetEngineFactory].
 *
 * **There is not a `Double` in this file** (MNY-001/MNY-002): the median of an even-length window
 * is an exact `Money.allocate` split, the seasonal index is integer basis points, and both pace
 * figures go through `Money.percentOf`, which rounds HALF_EVEN.
 *
 * **Nothing here reads a clock** (TIM-001); the instant in provenance is the caller's, and the
 * month's shape arrives as two integers the repository resolved in the profile time zone.
 */
internal class DefaultBudgetEngine : BudgetEngine {
    override fun suggest(input: BudgetSuggestionInput): Result<BudgetSuggestion?, AppError> =
        runCatchingToResult {
            val window = input.monthlySpends.takeLast(input.rules.lookbackMonths)
            if (window.size < input.rules.minMonthsRequired) return@runCatchingToResult null

            val median = medianOf(window.map { it.amount })
            val event = seasonalEvent(input)
            val indexBps = seasonalIndexBps(input, event)
            val adjusted = if (indexBps == BPS_FULL) median else median.percentOf(indexBps)

            BudgetSuggestion(
                amount = roundToNearest(adjusted, input.rules.roundToMinor),
                medianAmount = median,
                seasonalEventId = event?.id,
                seasonalIndexBps = indexBps,
                provenance = suggestionProvenance(input, window, event),
            )
        }

    override fun status(input: BudgetStatusInput): Result<BudgetStatus, AppError> =
        runCatchingToResult {
            val budgeted = input.plannedAmount + input.carriedOver
            BudgetStatus(
                budgeted = budgeted,
                carriedOver = input.carriedOver,
                spent = input.spent,
                remaining = budgeted - input.spent,
                safePaceToDate = budgeted.percentOf(elapsedShareBps(input)),
                projectedEndOfMonth = projection(input),
                provenance = statusProvenance(input),
            )
        }

    // --- FR-BUD-002: the suggestion -----------------------------------------------------------

    /**
     * The median of a window of monthly totals.
     * Why:    the median is the reason this engine resists one-off tickets, so it has to be the real
     *         one — for an even-length window that means the **midpoint of the two middle values**,
     *         not the lower of them. Taking the lower would bias every even-length suggestion
     *         downward, which on a budget reads as a cut the user never asked for.
     * What:   sorts, then either takes the middle value or splits the sum of the two middle ones in
     *         half with `Money.split`, whose remainder handling means the result can neither create
     *         nor lose a paise (MNY-001).
     * Result: the typical month, in paise.
     * Input:  [amounts] — non-empty. Output: [Money].
     */
    private fun medianOf(amounts: List<Money>): Money {
        require(amounts.isNotEmpty()) { "a median needs at least one month" }
        val sorted = amounts.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]).split(2).first()
        }
    }

    /**
     * The seasonal event that applies to this category and month, if any.
     * Result: the strongest matching event, or `null` when seasonality is switched off in the rules
     *         or the month is ordinary for this category.
     * Input:  [input]. Output: [SeasonalEvent]?.
     */
    private fun seasonalEvent(input: BudgetSuggestionInput): SeasonalEvent? =
        if (!input.rules.seasonalityEnabled) {
            null
        } else {
            SeasonalityPriors.strongestFor(input.categoryName, input.targetMonth)
        }

    /**
     * How much the seasonal prior actually moves this suggestion.
     * Why:    the prior is what the calendar expects of *anyone*; the shrinkage is what this app has
     *         earned the right to claim about *this* profile. A one-month-old install applying
     *         Diwali's full +38% would be asserting a pattern it has never seen.
     * Result: basis points, 10 000 when there is no event.
     * Input:  [input]; [event] — the winning prior, or `null`. Output: [Int].
     */
    private fun seasonalIndexBps(
        input: BudgetSuggestionInput,
        event: SeasonalEvent?,
    ): Int =
        if (event == null) {
            BPS_FULL
        } else {
            SeasonalityPriors.seasonalIndexBps(
                rawMultiplierBps = event.priorMultiplierBps,
                monthsObserved = input.monthsObserved,
                denominatorMonths = input.rules.shrinkageDenominatorMonths,
            )
        }

    /**
     * Rounds a suggestion to the nearest [step] paise.
     * Why:    `RULE-BUD-SUGGEST.round_to_minor` exists because a budget is a number a person has to
     *         hold in their head and decide against; `₹4,283.51` invites editing, `₹4,300` invites
     *         agreement. Ties round **up**, deliberately: for a spending cap, the generous direction
     *         is the one that does not set the user up to fail by a rupee.
     * What:   integer arithmetic on paise — no `BigDecimal`, no `Double`.
     * Result: the rounded amount, never negative.
     * Input:  [amount]; [step] — the rounding increment in paise, positive. Output: [Money].
     */
    private fun roundToNearest(
        amount: Money,
        step: Long,
    ): Money {
        val remainder = amount.minor % step
        val rounded =
            if (remainder * 2 >= step) {
                amount.minor - remainder + step
            } else {
                amount.minor - remainder
            }
        return Money(rounded)
    }

    /**
     * Provenance for a suggestion (AI-ARC-003).
     * Why:    this is the one engine in the app whose result states the window it read, because the
     *         window is the argument — "median of three months" is only checkable if the three
     *         months are named.
     * Result: engine id/version, the caller's instant, the cited rows and the ISO window.
     * Input:  [input]; [window] — the months actually read; [event] — the cited seasonal prior.
     * Output: [EngineProvenance].
     */
    private fun suggestionProvenance(
        input: BudgetSuggestionInput,
        window: List<MonthlySpend>,
        event: SeasonalEvent?,
    ): EngineProvenance {
        // The event's own `id` from calendar-seasonality.json, cited exactly as the nature engine
        // cites `CLS-NAT-*` from classification-kb.json — a knowledge-base row is as citable as a
        // rulebook row, and inventing a new id here would point evidence at a row nobody can open.
        val evidence =
            listOfNotNull(
                BudgetRules.SUGGESTION,
                event?.let { RuleCitation(it.id, SeasonalityPriors.KB_VERSION) },
            )
        return EngineProvenance(
            engineId = ENGINE_ID,
            engineVersion = ENGINE_VERSION,
            computedAtUtcMillis = input.nowUtcMillis,
            evidence = evidence,
            inputWindow = "${window.first().monthStartIsoDate}..${window.last().monthStartIsoDate}",
            // Confidence tracks how much history stands behind the median: a full window is worth
            // full confidence, the bare minimum proportionally less. Integer bps, rounded down.
            confidenceBps = (window.size * BPS_FULL) / input.rules.lookbackMonths,
        )
    }

    // --- FR-BUD-003: the status ---------------------------------------------------------------

    /**
     * The share of the month that has elapsed, in basis points.
     * Why:    "safe pace" is the budget spread evenly, so the elapsed share is the multiplier. In
     *         bps rather than a fraction because `Money.percentOf` is the only sanctioned way to
     *         take a share of an amount (MNY-002).
     * Result: 0..10 000; exactly 10 000 on the last day of the month.
     * Input:  [input]. Output: [Int].
     */
    private fun elapsedShareBps(input: BudgetStatusInput): Int = (input.daysElapsed * BPS_FULL) / input.daysInPeriod

    /**
     * The end-of-month figure the current run rate points at.
     * Why:    spent-versus-remaining cannot distinguish a month that is merely early from one that
     *         will overshoot, which is the question a user has mid-month. Withheld below
     *         `min_elapsed_days_for_projection` because a run rate taken on day 1 turns one grocery
     *         run into an implausible month — **`null` rather than a hedge**, so the screen shows
     *         nothing instead of showing a number it would have to apologise for.
     * What:   `spent x daysInPeriod / daysElapsed`, via `percentOf` so the rounding is HALF_EVEN and
     *         the arithmetic stays in paise.
     * Result: the projection, or `null` while it is still withheld.
     * Input:  [input]. Output: [Money]?.
     */
    private fun projection(input: BudgetStatusInput): Money? {
        if (input.daysElapsed < input.rules.minElapsedDaysForProjection) return null
        val runRateBps = (input.daysInPeriod * BPS_FULL) / input.daysElapsed
        return input.spent.percentOf(runRateBps)
    }

    /**
     * Provenance for a status (AI-ARC-003).
     * Why:    `inputWindow` is null here on purpose — a status reads one month, the one it was
     *         handed, so there is no window to state that the caller did not already choose.
     * Result: engine id/version, the caller's instant and the cited row.
     * Input:  [input]. Output: [EngineProvenance].
     */
    private fun statusProvenance(input: BudgetStatusInput): EngineProvenance =
        EngineProvenance(
            engineId = ENGINE_ID,
            engineVersion = ENGINE_VERSION,
            computedAtUtcMillis = input.nowUtcMillis,
            evidence = listOf(BudgetRules.PACE),
        )

    private companion object {
        const val ENGINE_ID = "budget-planner"

        /** Bump whenever the suggestion or pace arithmetic changes (AI-ARC-006). */
        const val ENGINE_VERSION = "1.0"
    }
}
