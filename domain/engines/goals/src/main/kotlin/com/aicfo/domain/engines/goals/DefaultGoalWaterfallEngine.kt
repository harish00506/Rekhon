package com.aicfo.domain.engines.goals

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money

/**
 * §15.1's feasibility check and priority waterfall (issue 7.3).
 *
 * Why:  `internal` so nothing outside this module can name it (ARC-003); reached through
 *       [GoalWaterfallEngineFactory].
 * What: pours the month's surplus down the list — emergency fund first while `RULE-EMERG-FIRST`
 *       holds, then each goal in the user's order — and works out the three levers for whatever the
 *       money did not reach.
 * Result: a [GoalWaterfall]. Every figure is exact `Long` paise; nothing here constructs a `Double`.
 * Changelog: 2026-09-03 — Created for issue 7.3.
 *
 * **Strict priority, not pro rata, and that is why no rounding rule appears in this file.**
 * `Money.allocate` distributes a sum across weights by largest remainder, which is the right tool
 * for a split and the wrong one for a waterfall: a waterfall fills each claim in turn and hands on
 * what is left. `minOf(remaining, required)` in a loop can neither create nor lose a paise, so there
 * is nothing to round and no remainder to redistribute — the [GoalWaterfall] `init` asserts exactly
 * that.
 */
internal class DefaultGoalWaterfallEngine : GoalWaterfallEngine {
    /**
     * Input:  [input] — goals in priority order, the surplus, the emergency-fund position, the day.
     * Output: `Result<GoalWaterfall, AppError>`.
     * Result: `Ok` for any input whose terms fit in a `Long`; `Err(AppError.Unexpected)` only when
     *         `Money` refuses to wrap an overflow (MNY-001), which `runCatchingToResult` converts
     *         rather than letting it cross a layer boundary.
     */
    override fun allocate(input: GoalWaterfallInput): Result<GoalWaterfall, AppError> =
        runCatchingToResult {
            val gateHolds = gateHolds(input)
            // A negative surplus is reported as it is but cannot be poured: there is nothing there.
            val distributable = maxOf(Money.ZERO, input.monthlySurplus ?: Money.ZERO)
            val emergencyAllocated =
                if (gateHolds) minOf(distributable, input.emergencyTopUpMonthly) else Money.ZERO

            val lines = pour(input.goals, distributable - emergencyAllocated, gateHolds)
            val totalRequired = lines.fold(Money.ZERO) { sum, line -> sum + line.requiredMonthly }
            val totalAllocated = lines.fold(Money.ZERO) { sum, line -> sum + line.allocatedMonthly }
            val gap = totalRequired - totalAllocated

            GoalWaterfall(
                monthlySurplus = input.monthlySurplus,
                surplusBasis = input.surplusBasis,
                totalRequiredMonthly = totalRequired,
                totalAllocated = totalAllocated,
                gapMonthly = gap,
                feasibility = verdictFor(input.monthlySurplus, gap),
                emergencyFirstApplied = gateHolds,
                emergencyAllocated = emergencyAllocated,
                lines = lines,
                unallocated = distributable - emergencyAllocated - totalAllocated,
                provenance = provenanceFor(input),
            )
        }

    /**
     * Whether `RULE-EMERG-FIRST` holds goals at zero this month.
     *
     * Why:    the row is `severity: fail` and says "no goal below Emergency Fund in the waterfall
     *         gets funded while runway < 3 months". A buffer is what stops the next shock becoming
     *         card debt, and a holiday fund built in front of it is built on sand.
     * What:   compares the runway against the caller's [GoalWaterfallInput.emergencyGateMonths],
     *         converting whole months to the basis points the runway is measured in (MNY-002).
     * Result: true when the runway is below the gate — **and true when the runway is unknown**. With
     *         no evidence the buffer exists, assuming it does is the expensive direction to be wrong
     *         in; the screen says which of the two it was through `EmergencyFundPlan`'s own status.
     * Input:  [input]. Output: [Boolean].
     */
    private fun gateHolds(input: GoalWaterfallInput): Boolean {
        val runway = input.emergencyRunwayMonthsBps ?: return true
        return runway.toLong() < input.emergencyGateMonths.toLong() * BPS_PER_MONTH
    }

    /**
     * Fills each goal in turn from what is left.
     *
     * Why:    a loop rather than a `map`, because each goal's share depends on what the goals above
     *         it took — the one place in this engine where order is not cosmetic.
     * What:   `minOf(remaining, required)` per goal, in the order given, decrementing as it goes.
     * Result: one [GoalAllocation] per goal, in input order. Every goal gets zero when [gateHolds],
     *         which is `RULE-EMERG-FIRST` doing its job rather than the money running out — the two
     *         are distinguished on each line so a card can explain which happened.
     * Input:  [goals]; [available] — the surplus after the emergency fund took its share;
     *   [gateHolds]. Output: the allocations.
     */
    private fun pour(
        goals: List<GoalProjection>,
        available: Money,
        gateHolds: Boolean,
    ): List<GoalAllocation> {
        var remaining = available
        return goals.map { goal ->
            val allocated = if (gateHolds) Money.ZERO else minOf(remaining, goal.requiredMonthly)
            remaining -= allocated
            lineFor(goal, allocated, gateHolds)
        }
    }

    /**
     * One goal's line, with its levers when it needs them.
     * Why:    the shortfall and the three ways out are the same calculation from two directions, so
     *         they are built together and cannot disagree.
     * Result: a [GoalAllocation]; `levers` is null exactly when the goal is fully funded.
     * Input:  [goal]; [allocated] — what it got; [gateHolds]. Output: [GoalAllocation].
     */
    private fun lineFor(
        goal: GoalProjection,
        allocated: Money,
        gateHolds: Boolean,
    ): GoalAllocation {
        val shortfall = maxOf(Money.ZERO, goal.requiredMonthly - allocated)
        val fullyFunded = shortfall == Money.ZERO
        return GoalAllocation(
            goalId = goal.goalId,
            name = goal.name,
            requiredMonthly = goal.requiredMonthly,
            allocatedMonthly = allocated,
            shortfallMonthly = shortfall,
            fullyFunded = fullyFunded,
            blockedByEmergencyFund = gateHolds && goal.requiredMonthly > Money.ZERO,
            levers = if (fullyFunded) null else leversFor(goal, allocated, shortfall),
        )
    }

    /**
     * FR-GOAL-003's three ways out of a shortfall.
     *
     * Why:    "you are ₹2,400 short" is a complaint; "push the date out four months, or aim at
     *         ₹1,80,000, or find ₹2,400 more" is a decision the user can make (P-07). All three are
     *         computed at the **allocated** rate, because that is what this plan can actually spare
     *         — quoting them against the user's wished-for contribution would answer a question
     *         nobody asked.
     * What:   ceiling division for the date, multiplication for the target, the shortfall itself for
     *         the contribution.
     * Result: a [GoalLevers]. Each may be null where that lever genuinely does not exist — see the
     *         property KDoc; a null is not "we did not bother", it is "this one cannot work".
     * Input:  [goal]; [allocated]; [shortfall]. Output: [GoalLevers].
     */
    private fun leversFor(
        goal: GoalProjection,
        allocated: Money,
        shortfall: Money,
    ): GoalLevers =
        GoalLevers(
            extendByMonths = extensionFor(goal, allocated),
            // What the allocated rate can actually accumulate by the existing date, on top of what
            // is already saved. With no whole month left there is nothing to accumulate over, and
            // the only lever is paying the balance today — so the answer is null, not `saved`.
            reduceTargetTo =
                if (goal.monthsRemaining <= 0) null else goal.saved + allocated * goal.monthsRemaining,
            increaseContributionBy = shortfall,
        )

    /**
     * How many further months the date would have to move.
     *
     * What:   ceiling division of what is left by what this plan can spare each month, less the
     *         months already available.
     * Result: at least one month, or **null** when the lever does not exist: at a zero allocation no
     *         date reaches the target, and past [MAX_EXTENSION_MONTHS] the true answer stops being
     *         information — the same hundred-year horizon `DefaultGoalEngine` uses for an ETA.
     * Input:  [goal]; [allocated]. Output: whole months, or null.
     */
    private fun extensionFor(
        goal: GoalProjection,
        allocated: Money,
    ): Int? {
        if (allocated <= Money.ZERO) return null
        // Ceiling division: a part-month contribution does not arrive early, so the last instalment
        // still costs a whole month. addExact rather than +, so a huge remainder cannot wrap.
        val monthsNeeded =
            Math.addExact(goal.remaining.minor, allocated.minor - 1) / allocated.minor
        val extension = monthsNeeded - goal.monthsRemaining
        return if (extension > MAX_EXTENSION_MONTHS) null else maxOf(1L, extension).toInt()
    }

    /**
     * §15.1's verdict.
     * Why:    the comparison the section states, expressed through the gap so that the emergency
     *         fund's claim on the surplus counts too — a plan whose goals are starved because the
     *         buffer took everything is not feasible, however the arithmetic is grouped.
     * Result: [Feasibility]. **Unknown when the surplus is unknown**, which is not the same as zero:
     *         a month with no room is a finding, a month with no data is not.
     * Input:  [surplus]; [gap]. Output: [Feasibility].
     */
    private fun verdictFor(
        surplus: Money?,
        gap: Money,
    ): Feasibility =
        when {
            surplus == null -> Feasibility.UNKNOWN
            gap == Money.ZERO -> Feasibility.FEASIBLE
            else -> Feasibility.INFEASIBLE
        }

    /**
     * Who computed this, when, and under which rule.
     *
     * Why:    **`RULE-EMERG-FIRST` is cited on every plan, including the ones where it let the goals
     *         through** — and that is a deliberate departure from issue 7.2's rule about citing only
     *         a rule that fired. A clamp that does not bind changed nothing, so citing it would
     *         mislead. A *gate* is different: it is evaluated every time, and both of its outcomes
     *         decide whether goals are funded at all. `emergencyFirstApplied` on the result says
     *         which way it went, which is clearer than inferring it from whether a citation is
     *         present.
     *
     *         `RULE-HORIZON` is **not** cited here. This engine applies no horizon; `GoalEngine`
     *         cites it on the projections that do.
     * Result: an [EngineProvenance] with no `confidenceBps` — this is arithmetic over amounts the
     *         caller resolved, not an inference.
     * Input:  [input]. Output: [EngineProvenance].
     */
    private fun provenanceFor(input: GoalWaterfallInput): EngineProvenance =
        EngineProvenance(
            engineId = ENGINE_ID,
            engineVersion = ENGINE_VERSION,
            computedAtUtcMillis = input.nowUtcMillis,
            evidence = listOf(GoalRules.EMERGENCY_FIRST),
            inputWindow = input.today.toString(),
        )

    private companion object {
        /**
         * A sub-id of the `AI-GOAL` row in `ai/orchestrator/engine-registry.yaml`, whose contract
         * has said "feasibility in 7.3" since before this file existed. Dotted sub-ids are the
         * house convention for a facet of an engine — `AI-GOAL.funding_buckets`,
         * `AI-MKT.capacity_gate` — and keeping the facet distinct means a stored waterfall and a
         * stored projection can be told apart when their versions diverge (AI-ARC-006).
         */
        const val ENGINE_ID = "AI-GOAL.waterfall"

        /** Bumped whenever the allocation changes, so a stored plan stays reproducible. */
        const val ENGINE_VERSION = "1.0"

        /** MNY-002: a runway is carried in basis points of a month, so one month is 10 000. */
        const val BPS_PER_MONTH = 10_000L

        /**
         * A hundred years of monthly contributions, matching `DefaultGoalEngine.MAX_ETA_MONTHS`.
         *
         * Not a financial threshold — no advice changes at the boundary. It is the point past which
         * "extend the date" stops being a lever a human could take.
         */
        const val MAX_EXTENSION_MONTHS = 1_200L
    }
}
