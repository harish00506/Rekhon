package com.aicfo.domain.engines.budget

import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money

/**
 * FR-BUD-003's pace math, lifted out of [DefaultBudgetEngine] (issue 4.5).
 *
 * Why:  when 4.5 added the third question this engine answers, the class holding all three grew past
 *       what one file should carry (detekt's `TooManyFunctions`). Rather than raise the threshold,
 *       each requirement now reads as its own unit and the engine composes them — so a reviewer
 *       looking for "how is safe pace computed?" opens one short file instead of scrolling past two
 *       unrelated requirements. **Pure move: no arithmetic changed**, and the 4.4 golden file is what
 *       proves that.
 * What: the elapsed-share multiplier, the run-rate projection, and the provenance naming the row.
 * Result: the four FR-BUD-003 figures, reproducible from their inputs alone (P-08).
 * Changelog: 2026-08-13 — Extracted from DefaultBudgetEngine for issue 4.5; behaviour unchanged.
 *
 * `internal` — the public seam is [BudgetEngine.status] (ARC-003). **Nothing here reads a clock**
 * (TIM-001): the month arrives as two integers the repository resolved in the profile time zone.
 */
internal object BudgetPace {
    /** The engine that owns this math; repeated here so provenance reads the same either way. */
    private const val ENGINE_ID = "budget-planner"

    /** Bump whenever the pace arithmetic changes (AI-ARC-006). */
    private const val ENGINE_VERSION = "1.0"

    /**
     * The share of the month that has elapsed, in basis points.
     * Why:    "safe pace" is the budget spread evenly, so the elapsed share is the multiplier. In
     *         bps rather than a fraction because `Money.percentOf` is the only sanctioned way to
     *         take a share of an amount (MNY-002).
     * Result: 0..10 000; exactly 10 000 on the last day of the month.
     * Input:  [input]. Output: [Int].
     */
    fun elapsedShareBps(input: BudgetStatusInput): Int = (input.daysElapsed * BPS_FULL) / input.daysInPeriod

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
    fun projection(input: BudgetStatusInput): Money? {
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
    fun provenance(input: BudgetStatusInput): EngineProvenance =
        EngineProvenance(
            engineId = ENGINE_ID,
            engineVersion = ENGINE_VERSION,
            computedAtUtcMillis = input.nowUtcMillis,
            evidence = listOf(BudgetRules.PACE),
        )
}
