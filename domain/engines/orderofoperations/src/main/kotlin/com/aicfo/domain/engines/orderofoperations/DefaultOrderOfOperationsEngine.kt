package com.aicfo.domain.engines.orderofoperations

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.sum

/**
 * §36's eight-stage waterfall (issue 7.5; AI-FOO).
 *
 * Why:  `internal` so nothing outside this module can name it (ARC-003); reached through
 *       [OrderOfOperationsEngineFactory].
 * What: evaluates the stages **one after another, in §36's order**, each taking what it needs from
 *       what the stages above it left, then picks the top action.
 * Result: an [OrderOfOperations]. Every figure is exact `Long` paise; nothing here constructs a
 *       `Double` (MNY-001), and every rate is compared in basis points (MNY-002).
 * Changelog: 2026-09-17 — Created for issue 7.5.
 *
 * **Strict priority, not pro rata, so no rounding rule appears in this file** — the argument
 * `DefaultGoalWaterfallEngine` makes. `minOf(remaining, need)` in sequence can neither create nor
 * lose a paise, and [OrderOfOperations]' `init` asserts exactly that.
 *
 * **The stages are written out in order rather than looped over.** Each has its own inputs and its
 * own reasons, so a loop would need a per-stage strategy table to say what a plain sequence says
 * directly — and the sequence is the ranking, which is the one thing a reader must be able to see.
 */
internal class DefaultOrderOfOperationsEngine : OrderOfOperationsEngine {
    /**
     * Input:  [input] — the surplus, the emergency-fund position, the debts and the goals' need.
     * Output: `Result<OrderOfOperations, AppError>`.
     * Result: `Ok` for any input whose sums fit in a `Long`; `Err` only when `Money` refuses to wrap
     *         an overflow, which `runCatchingToResult` converts rather than letting it cross a layer.
     */
    override fun rank(input: OrderOfOperationsInput): Result<OrderOfOperations, AppError> =
        runCatchingToResult {
            val rules = input.rules
            val gate = gateHolds(input)
            // A negative surplus is reported as it is but cannot be poured: there is nothing there.
            val pour = Pour(maxOf(Money.ZERO, input.monthlySurplus ?: Money.ZERO))
            val debts = DebtBands.of(input.debts, rules)

            // §36's order, and the order these calls run in, is the ranking. Each call takes from
            // [pour] before the next one sees it.
            val stages =
                listOf(
                    starterBuffer(input, pour),
                    skipped(FooStage.CAPTURE_EPF_VPF, StageReason.NO_EPF_DATA),
                    fireDebt(debts.fire, pour),
                    fullEmergency(input, pour, gate),
                    skipped(FooStage.TAX_ADVANTAGED, StageReason.NO_REGIME_COMPARATOR),
                    goalInvesting(input, pour, gate),
                    greyZoneDebt(debts.grey, pour, gate, rules),
                    lowRateDebt(debts.low, gate),
                )

            OrderOfOperations(
                monthlySurplus = input.monthlySurplus,
                surplusBasis = input.surplusBasis,
                stages = stages,
                topAction = topActionOf(stages),
                unallocated = pour.remaining,
                provenance = provenanceFor(input, stages),
            )
        }

    /**
     * Stage 0 — `min(₹50,000, 1 month essentials)` of instant access.
     *
     * Why:    "a tiny instant-access buffer stops the first shock from becoming card debt" — which is
     *         why it outranks even a 42% card.
     * What:   the smaller of the cap and a month of essentials, less what is already liquid.
     * Result: `ACTION` while liquid funds fall short, else `SATISFIED`. **Unknown essentials size the
     *         buffer by its cap alone**: the cap is the rule's own ceiling, so the ask can be too high
     *         for a frugal household but never above what §36 names — and the reason says so.
     * Input:  [input]; [pour]. Output: the stage.
     */
    private fun starterBuffer(
        input: OrderOfOperationsInput,
        pour: Pour,
    ): StageOutcome {
        val rules = input.rules
        val target =
            input.monthlyEssentials
                ?.let { minOf(rules.starterCap, it * rules.starterEssentialsMonths) }
                ?: rules.starterCap
        val need = maxOf(Money.ZERO, target - input.liquidFunds)
        val reason =
            when {
                need == Money.ZERO -> StageReason.BUFFER_HELD
                input.monthlyEssentials == null -> StageReason.BUFFER_SHORT_ESSENTIALS_UNKNOWN
                else -> StageReason.BUFFER_SHORT
            }
        return StageOutcome(
            stage = FooStage.STARTER_BUFFER,
            status = if (need == Money.ZERO) StageStatus.SATISFIED else StageStatus.ACTION,
            need = need,
            amountMonthly = pour.take(need),
            reason = reason,
            citations = listOf(OrderOfOperationsRules.citationFor(FooStage.STARTER_BUFFER)),
        )
    }

    /**
     * A stage the app cannot assess yet — Stage 1 (no EPF data) and Stage 4 (no regime comparator).
     *
     * Why:    §36 says "every skipped stage shows why". Leaving the stage out would hide that the
     *         ranking is incomplete; reporting it with a reason tells the user what is missing.
     * Result: `SKIPPED`, no need, no amount. Input: [stage]; [reason]. Output: the stage.
     */
    private fun skipped(
        stage: FooStage,
        reason: StageReason,
    ): StageOutcome =
        StageOutcome(
            stage = stage,
            status = StageStatus.SKIPPED,
            need = null,
            amountMonthly = Money.ZERO,
            reason = reason,
            citations = listOf(OrderOfOperationsRules.citationFor(stage)),
        )

    /**
     * Stage 2 — every debt at or above the fire threshold.
     *
     * Why:    "paying a 42% card = a guaranteed 42% return — nothing else competes".
     * What:   the whole outstanding across [fire], which [DebtBands] has already sorted highest
     *         rate first.
     * Result: `ACTION` when any exists, else `NOT_APPLICABLE`. The reason flags a card counted with
     *         no rate entered, so the screen can ask for it. Input: [fire]; [pour]. Output: the stage.
     */
    private fun fireDebt(
        fire: List<DebtPosition>,
        pour: Pour,
    ): StageOutcome {
        val need = fire.map { it.outstanding }.sum()
        val reason =
            when {
                fire.isEmpty() -> StageReason.NO_FIRE_DEBT
                fire.any { it.aprBps == null } -> StageReason.FIRE_DEBT_CARD_RATE_UNKNOWN
                else -> StageReason.FIRE_DEBT_OUTSTANDING
            }
        return StageOutcome(
            stage = FooStage.KILL_FIRE_DEBT,
            status = if (fire.isEmpty()) StageStatus.NOT_APPLICABLE else StageStatus.ACTION,
            need = need,
            amountMonthly = pour.take(need),
            reason = reason,
            debts = fire,
            citations = listOf(OrderOfOperationsRules.citationFor(FooStage.KILL_FIRE_DEBT)),
        )
    }

    /**
     * Stage 3 — the full emergency fund, and `RULE-EMERG-FIRST` as its gate.
     *
     * Why:    "the buffer that protects every later goal from being liquidated in a crisis".
     * What:   past the gate, the fund takes AI-EMF's monthly pace — the plan the emergency-fund screen
     *         already shows. **While the gate holds it may take up to its whole shortfall**, because
     *         the gate means nothing below it may be funded: money the fund could use would otherwise
     *         sit idle behind a rule that exists to build the fund (ADR-0037).
     * Result: `ACTION` while short, `SATISFIED` when funded, and `ACTION` with no need when the fund
     *         cannot be sized — the next step then is data, not money. `RULE-EMERG-FIRST` is cited
     *         either way, as in 7.3: both outcomes of a gate decide what is funded.
     * Input:  [input]; [pour]; [gate]. Output: the stage.
     */
    private fun fullEmergency(
        input: OrderOfOperationsInput,
        pour: Pour,
        gate: Boolean,
    ): StageOutcome {
        val shortfall = input.emergencyShortfall
        val claim =
            when {
                shortfall == null -> Money.ZERO
                gate -> shortfall
                else -> minOf(input.emergencyTopUpMonthly, shortfall)
            }
        val reason =
            when {
                shortfall == null -> StageReason.EMERGENCY_UNSIZED
                shortfall == Money.ZERO -> StageReason.EMERGENCY_FUNDED
                gate -> StageReason.EMERGENCY_BELOW_GATE
                else -> StageReason.EMERGENCY_BUILDING
            }
        return StageOutcome(
            stage = FooStage.FULL_EMERGENCY,
            status = if (shortfall == Money.ZERO) StageStatus.SATISFIED else StageStatus.ACTION,
            need = shortfall,
            amountMonthly = pour.take(claim),
            reason = reason,
            citations =
                listOf(
                    OrderOfOperationsRules.citationFor(FooStage.FULL_EMERGENCY),
                    OrderOfOperationsRules.EMERGENCY_FIRST,
                ),
        )
    }

    /**
     * Stage 5 — the user's goals.
     *
     * Why:    long-horizon money compounds, short-horizon money must not risk the goal; which bucket
     *         each goal belongs in is `RULE-HORIZON`'s, already reported by AI-GOAL on each goal.
     * What:   one aggregate line — what every goal needs this month. The per-goal split stays on the
     *         goals screen, which 7.5 does not change (ADR-0037).
     * Result: `NOT_APPLICABLE` with no goals, `SATISFIED` when they need nothing, `BLOCKED` behind the
     *         gate, else `ACTION`. Input: [input]; [pour]; [gate]. Output: the stage.
     */
    private fun goalInvesting(
        input: OrderOfOperationsInput,
        pour: Pour,
        gate: Boolean,
    ): StageOutcome {
        val need = input.goalsRequiredMonthly
        val (status, reason) =
            when {
                input.goalCount == 0 -> StageStatus.NOT_APPLICABLE to StageReason.NO_GOALS
                need == Money.ZERO -> StageStatus.SATISFIED to StageReason.GOALS_ON_TRACK
                gate -> StageStatus.BLOCKED to StageReason.EMERGENCY_GATE
                else -> StageStatus.ACTION to StageReason.GOALS_NEED_FUNDING
            }
        return StageOutcome(
            stage = FooStage.GOAL_INVESTING,
            status = status,
            need = need,
            amountMonthly = if (status == StageStatus.ACTION) pour.take(need) else Money.ZERO,
            reason = reason,
            citations =
                listOf(OrderOfOperationsRules.citationFor(FooStage.GOAL_INVESTING), OrderOfOperationsRules.HORIZON),
        )
    }

    /**
     * Stage 6 — debt in the grey band.
     *
     * Why:    "a close call — user chooses with the math shown". So the payoff is offered, not
     *         pushed, and the expected equity return is carried beside the rates to be compared.
     * Result: `NOT_APPLICABLE` with no such debt, `BLOCKED` behind the gate, else `CHOICE` taking what
     *         is left up to the balance. Input: [grey]; [pour]; [gate]; [rules]. Output: the stage.
     */
    private fun greyZoneDebt(
        grey: List<DebtPosition>,
        pour: Pour,
        gate: Boolean,
        rules: OrderOfOperationsRules,
    ): StageOutcome {
        val need = grey.map { it.outstanding }.sum()
        val (status, reason) =
            when {
                grey.isEmpty() -> StageStatus.NOT_APPLICABLE to StageReason.NO_GREY_DEBT
                gate -> StageStatus.BLOCKED to StageReason.EMERGENCY_GATE
                else -> StageStatus.CHOICE to StageReason.GREY_DEBT_OUTSTANDING
            }
        return StageOutcome(
            stage = FooStage.GREY_ZONE_DEBT,
            status = status,
            need = need,
            amountMonthly = if (status == StageStatus.CHOICE) pour.take(need) else Money.ZERO,
            reason = reason,
            debts = grey,
            comparisonBps = rules.equityNominalBps,
            citations = listOf(OrderOfOperationsRules.citationFor(FooStage.GREY_ZONE_DEBT)),
        )
    }

    /**
     * Stage 7 — low-rate debt.
     *
     * Why:    "at low rates, invest-vs-prepay is a genuine toss-up; defer to the user's simulator".
     *         That simulator is issue 10.3 and not built, so the stage proposes **no amount** — any
     *         figure here would be a verdict §36 explicitly declines to give.
     * Result: `NOT_APPLICABLE` with no such debt, `BLOCKED` behind the gate, else
     *         `DEFER_TO_SIMULATOR` with the balance shown and nothing taken.
     * Input:  [low]; [gate]. Output: the stage.
     */
    private fun lowRateDebt(
        low: List<DebtPosition>,
        gate: Boolean,
    ): StageOutcome {
        val (status, reason) =
            when {
                low.isEmpty() -> StageStatus.NOT_APPLICABLE to StageReason.NO_LOW_RATE_DEBT
                gate -> StageStatus.BLOCKED to StageReason.EMERGENCY_GATE
                else -> StageStatus.DEFER_TO_SIMULATOR to StageReason.LOW_RATE_DEBT_SIMULATOR_NOT_BUILT
            }
        return StageOutcome(
            stage = FooStage.LOW_RATE_DEBT,
            status = status,
            need = low.map { it.outstanding }.sum(),
            amountMonthly = Money.ZERO,
            reason = reason,
            debts = low,
            citations =
                listOf(
                    OrderOfOperationsRules.citationFor(FooStage.LOW_RATE_DEBT),
                    OrderOfOperationsRules.PREPAY_VS_INVEST,
                ),
        )
    }

    /**
     * Who computed this, when, and under which rules.
     * Result: every citation any stage carries, in rank order, once each. No confidence — this is
     *         arithmetic over figures the caller resolved, not an inference.
     * Input:  [input]; [stages]. Output: [EngineProvenance].
     */
    private fun provenanceFor(
        input: OrderOfOperationsInput,
        stages: List<StageOutcome>,
    ): EngineProvenance =
        EngineProvenance(
            engineId = ENGINE_ID,
            engineVersion = ENGINE_VERSION,
            computedAtUtcMillis = input.nowUtcMillis,
            evidence = stages.flatMap(StageOutcome::citations).distinct(),
            inputWindow = input.today.toString(),
        )

    /**
     * What is left of the month's surplus as the stages take their shares.
     * Why:    one small mutable holder, used strictly in rank order, instead of threading a running
     *         total through eight calls by hand — where a single missed subtraction would hand the
     *         same rupee to two stages.
     * Result: [take] never gives more than is left and never goes below zero.
     */
    private class Pour(
        var remaining: Money,
    ) {
        /** Result: `minOf(remaining, need)`, which is subtracted. Input: [need]. Output: the share. */
        fun take(need: Money): Money = minOf(remaining, need).also { remaining -= it }
    }

    /**
     * The debts sorted into §36's three rate bands.
     * Why:    each debt belongs to exactly one band, so they are split once, up front, and no stage
     *         can count a debt another stage already counted.
     * Result: three lists, each highest rate first. A card with no rate entered is fire debt — §36
     *         names credit cards as 36–42% — and sorts ahead of every known rate. Paid-off debts are
     *         dropped: a zero balance is not a debt to rank.
     */
    private data class DebtBands(
        val fire: List<DebtPosition>,
        val grey: List<DebtPosition>,
        val low: List<DebtPosition>,
    ) {
        companion object {
            /** Result: the bands. Input: [debts]; [rules]. Output: [DebtBands]. */
            fun of(
                debts: List<DebtPosition>,
                rules: OrderOfOperationsRules,
            ): DebtBands {
                val rate = { debt: DebtPosition -> debt.aprBps ?: Int.MAX_VALUE }
                val owed =
                    debts
                        .filter { it.outstanding > Money.ZERO }
                        .sortedWith(compareByDescending(rate).thenBy { it.accountId })
                return DebtBands(
                    fire = owed.filter { rate(it) >= rules.fireAprThresholdBps },
                    grey = owed.filter { rate(it) in rules.greyAprMinBps until rules.fireAprThresholdBps },
                    low = owed.filter { rate(it) < rules.greyAprMinBps },
                )
            }
        }
    }

    /**
     * The two decisions that read no stage of their own — kept here, as pure functions, rather than
     * beside the eight stage builders, so the class reads as the ranking and nothing else.
     */
    private companion object {
        /**
         * Whether `RULE-EMERG-FIRST` holds every stage below the fund.
         * Result: true when the runway is below the gate, **and true when it is unknown** — with no
         *         evidence a buffer exists, assuming one does is the expensive way to be wrong. The
         *         same reading `DefaultGoalWaterfallEngine.gateHolds` takes.
         * Input:  [input]. Output: [Boolean].
         */
        fun gateHolds(input: OrderOfOperationsInput): Boolean {
            val runway = input.emergencyRunwayMonthsBps ?: return true
            return runway.toLong() < input.emergencyGateMonths.toLong() * BPS_PER_MONTH
        }

        /**
         * FOO-002's single top action.
         * Result: the first stage that asks for money; failing that, the first that offers a choice;
         *         failing that, null. A deferral to a simulator is not an action — it proposes nothing.
         * Input:  [stages], in rank order. Output: one of them, or null.
         */
        fun topActionOf(stages: List<StageOutcome>): StageOutcome? =
            stages.firstOrNull { it.status == StageStatus.ACTION }
                ?: stages.firstOrNull { it.status == StageStatus.CHOICE }

        /** AI-FOO's row in `ai/orchestrator/engine-registry.yaml`. */
        const val ENGINE_ID = "AI-FOO"

        /** Bumped whenever a stage's decision changes, so a stored ranking stays reproducible. */
        const val ENGINE_VERSION = "1.0"

        /** MNY-002: a runway is carried in basis points of a month, so one month is 10 000. */
        const val BPS_PER_MONTH = 10_000L
    }
}
