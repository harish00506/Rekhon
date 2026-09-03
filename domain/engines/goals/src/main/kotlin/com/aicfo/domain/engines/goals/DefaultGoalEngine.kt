package com.aicfo.domain.engines.goals

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * `RULE-HORIZON` and the arithmetic of §15, applied to each goal (issue 7.1).
 *
 * Why:  `internal` so nothing outside this module can name it (ARC-003); reached through
 *       [GoalEngineFactory].
 * What: per goal — what is left, how many contributions fit, the largest of them, when the user's
 *       own plan gets there, and which funding bucket the horizon falls in.
 * Result: a [GoalPlan]. Every figure is exact `Long` paise; nothing here constructs a `Double`.
 * Changelog: 2026-08-30 — Created for issue 7.1.
 */
internal class DefaultGoalEngine : GoalEngine {
    /**
     * Input:  [input] — goals, the day, the instant, the thresholds.
     * Output: `Result<GoalPlan, AppError>`.
     * Result: `Ok` for any input whose terms fit in a `Long`; `Err(AppError.Unexpected)` only when
     *         `Money` refuses to wrap an overflow (MNY-001), which `runCatchingToResult` converts
     *         rather than letting it cross a layer boundary.
     */
    override fun plan(input: GoalPlanInput): Result<GoalPlan, AppError> =
        runCatchingToResult {
            GoalPlan(
                goals = input.goals.map { goal -> project(goal, input.today, input.rules) },
                provenance =
                    EngineProvenance(
                        engineId = ENGINE_ID,
                        engineVersion = ENGINE_VERSION,
                        computedAtUtcMillis = input.nowUtcMillis,
                        evidence = listOf(GoalRules.HORIZON),
                        inputWindow = input.today.toString(),
                        // No confidenceBps: this is arithmetic, not an inference.
                    ),
            )
        }

    /**
     * Projects one goal.
     *
     * Why:    every goal is independent. Sharing a surplus between them is feasibility, which is
     *         issue 7.3's job and needs a number this engine is not given.
     * What:   the six figures in the order they depend on each other.
     * Result: a [GoalProjection].
     * Input:  [goal]; [today]; [rules]. Output: [GoalProjection].
     */
    private fun project(
        goal: GoalSpec,
        today: LocalDate,
        rules: GoalRules,
    ): GoalProjection {
        val remaining = maxOf(Money.ZERO, goal.target - goal.saved)
        val monthsRemaining = monthsBetween(today, goal.targetDate)
        // `split` refuses a zero part count, and it is also the wrong question: with the date here
        // or gone, the whole remainder is due in one payment, not spread over no months.
        val requiredMonthly = remaining.split(maxOf(1, monthsRemaining)).max()
        val shortfall = maxOf(Money.ZERO, requiredMonthly - goal.plannedMonthly)
        return GoalProjection(
            goalId = goal.id,
            name = goal.name,
            target = goal.target,
            targetDateIso = goal.targetDate.toString(),
            saved = goal.saved,
            remaining = remaining,
            monthsRemaining = monthsRemaining,
            requiredMonthly = requiredMonthly,
            plannedMonthly = goal.plannedMonthly,
            shortfallMonthly = shortfall,
            etaIsoDate = etaFor(remaining, goal.plannedMonthly, today),
            onTrack = shortfall == Money.ZERO,
            horizon = rules.bucketFor(monthsRemaining),
            status = statusFor(goal, remaining, shortfall, today),
        )
    }

    /**
     * How many whole monthly contributions still fit before the date.
     *
     * Why:    the count of *payments the user can still make*, not a duration. A goal sixteen days
     *         away has no whole month left, so the honest answer is zero and the caller treats the
     *         whole remainder as due — being conservative here fails towards "you need more now",
     *         which is the safe direction for a savings target.
     * What:   `ChronoUnit.MONTHS` between two dates, floored at zero.
     * Result: zero when the date is today or past. Whole months otherwise: a target one day short of
     *         a year away counts eleven, not twelve, because the twelfth contribution would land
     *         after the money was needed.
     * Input:  [today]; [targetDate]. Output: whole months, never negative.
     */
    private fun monthsBetween(
        today: LocalDate,
        targetDate: LocalDate,
    ): Int = maxOf(0L, ChronoUnit.MONTHS.between(today, targetDate)).toInt()

    /**
     * When the user's own plan reaches the target.
     *
     * Why:    the second figure §15 asks for. It answers a different question from the required
     *         monthly: not "what would it take", but "where does what I am actually doing get me".
     *         The two together are what make a goal card worth reading.
     * What:   ceiling division of what is left by what is contributed, in exact `Long` paise.
     * Result: today when nothing is left to save; **null** when nothing is being contributed, or
     *         when the answer is further out than [MAX_ETA_MONTHS] — at ₹1 a month against a large
     *         target the true answer is tens of thousands of years, and a date that far out is not
     *         information, it is noise. Null reads as "not at this rate", which is the truth.
     * Input:  [remaining]; [plannedMonthly]; [today]. Output: an ISO date (TIM-002), or null.
     */
    private fun etaFor(
        remaining: Money,
        plannedMonthly: Money,
        today: LocalDate,
    ): String? {
        if (remaining == Money.ZERO) return today.toString()
        if (plannedMonthly <= Money.ZERO) return null
        // Ceiling division: a part-month contribution does not arrive early, so the last instalment
        // still costs a whole month. addExact rather than +, so a huge remainder cannot wrap.
        val months = Math.addExact(remaining.minor, plannedMonthly.minor - 1) / plannedMonthly.minor
        return if (months > MAX_ETA_MONTHS) null else today.plusMonths(months).toString()
    }

    /**
     * The one verdict a card leads with.
     *
     * Why:    the order matters and is not arbitrary. Over-funded is checked before past-due,
     *         because a goal that is fully saved is finished whatever its date said; and no-target
     *         is checked before behind, because "you are short by ₹0 a month" against a target of
     *         zero is not a shortfall, it is a goal nobody has filled in yet.
     * What:   a `when`, in that order.
     * Result: [GoalStatus].
     * Input:  [goal]; [remaining]; [shortfall]; [today]. Output: [GoalStatus].
     */
    private fun statusFor(
        goal: GoalSpec,
        remaining: Money,
        shortfall: Money,
        today: LocalDate,
    ): GoalStatus =
        when {
            goal.target <= Money.ZERO -> GoalStatus.NO_TARGET
            remaining == Money.ZERO -> GoalStatus.OVER_FUNDED
            !goal.targetDate.isAfter(today) -> GoalStatus.PAST_DUE
            shortfall == Money.ZERO -> GoalStatus.ON_TRACK
            else -> GoalStatus.BEHIND
        }

    private companion object {
        /** Matches the `AI-GOAL` row in `ai/orchestrator/engine-registry.yaml`. */
        const val ENGINE_ID = "AI-GOAL"

        /** Bumped whenever the formula changes, so a stored result stays reproducible (AI-ARC-006). */
        const val ENGINE_VERSION = "1.0"

        /**
         * A hundred years of monthly contributions.
         *
         * Not a financial threshold — nothing about the advice changes at the boundary. It is the
         * point past which a projected date stops being information: the difference between "in
         * 400 years" and "in 40,000 years" is not something a user acts on, and computing it risks
         * `LocalDate.plusMonths` throwing on a year outside its supported range.
         */
        const val MAX_ETA_MONTHS = 1_200L
    }
}
