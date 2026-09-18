package com.aicfo.data.repository

import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.emergencyfund.EmergencyFundPlan
import com.aicfo.domain.engines.goals.GoalProjection
import com.aicfo.domain.engines.goals.GoalWaterfall
import com.aicfo.domain.engines.goals.GoalWaterfallEngine
import com.aicfo.domain.engines.goals.GoalWaterfallInput
import com.aicfo.domain.engines.goals.SurplusBasis
import com.aicfo.domain.engines.orderofoperations.FooStage
import com.aicfo.domain.engines.orderofoperations.OrderOfOperations
import com.aicfo.domain.engines.quicksetup.QuickSetupRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn

/**
 * Resolves what `GoalWaterfallEngine` needs, and hands back its answer (issue 7.3; §15.1, ARC-005).
 *
 * Why:  the engine is a fold over figures somebody has to decide — what the month has spare **for
 *       goals**, and how deep a runway `RULE-EMERG-FIRST` calls sufficient. Both are storage
 *       questions, so they belong on this side of the boundary, not in the engine and certainly not
 *       in a ViewModel.
 * What: watch the plan, recomputed on every change to the goals, the ledger, the debts or the
 *       emergency fund.
 * Result: a ViewModel sees a [GoalWaterfall] and nothing else — no Room types, no DAOs.
 * Changelog: 2026-09-03 — Created for issue 7.3.
 *            2026-09-18 — The surplus it pours is what AI-FOO leaves after §36's earlier stages,
 *            not the month's whole surplus (ADR-0038).
 *
 * **Nothing derived is stored**, for the reason `GoalRepository` and `EmergencyFundRepository` both
 * give: an allocation written to the database would outlive the surplus that produced it, and would
 * go stale simply because a month closed — the one input the user never edits. The single thing that
 * *is* stored is the order, because that is the user's own decision rather than a derived figure,
 * and it lives on the `goal` row (`sort_order`, schema 21) rather than here.
 */
interface GoalWaterfallRepository {
    /**
     * Watches the active profile's contribution plan.
     * Why:    every term of it moves on its own schedule — a transaction changes the surplus, a
     *         reconciliation changes the runway, a drag changes the order — so a one-shot read would
     *         be stale before the user finished reading it.
     * Result: re-emitted on every relevant change. **Never null and never an error**: a profile the
     *         app has never watched spend yields an `UNKNOWN` plan, which the screen
     *         renders as "we cannot size this yet" rather than an error state (P-04).
     * Input:  none — the active profile. Output: `Flow<GoalWaterfall>`.
     */
    fun observeWaterfall(): Flow<GoalWaterfall>
}

/**
 * The Room-backed [GoalWaterfallRepository] (issue 7.3).
 *
 * Why:  `internal`, reached through [RepositoryFactory], like every other repository here.
 * What: combines three sources, takes what §36 leaves for goals, and calls the engine once per
 *       emission.
 * Result: a [GoalWaterfall] per emission.
 * Changelog: 2026-09-03 — Created for issue 7.3.
 *            2026-09-18 — Pours AI-FOO's remainder; the surplus derivation moved to
 *            [SurplusRepository], which AI-FOO now owns the reading of (ADR-0038).
 *
 * **It pours what the ranking leaves, and that is the whole point of the change.** Until 2026-09-18
 * this repository derived the month's surplus itself and handed all of it to the goals, while AI-FOO
 * filled the starter buffer, high-interest debt and the emergency fund first. Both were right about
 * their own rule and they disagreed about the goals' share — the divergence ADR-0037 recorded as its
 * first follow-up. §36 supersedes §15's simple waterfall, so the ranking is the base and this is the
 * per-goal split of what it did not claim.
 *
 * **It composes three repositories rather than reaching for their DAOs.** `GoalRepository` already
 * projects the goals through `GoalEngine`, `OrderOfOperationsRepository` already ranked the month,
 * and `EmergencyFundRepository` already resolved the runway; duplicating any of them here would give
 * the app two answers to one question, which is the failure `GoalRepository.project`'s own KDoc warns
 * about one level down.
 */
@Suppress("LongParameterList") // Six, and every one is a source the plan genuinely needs.
internal class RoomGoalWaterfallRepository(
    private val goals: GoalRepository,
    private val ranking: OrderOfOperationsRepository,
    private val emergencyFund: EmergencyFundRepository,
    private val engine: GoalWaterfallEngine,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
) : GoalWaterfallRepository {
    override fun observeWaterfall(): Flow<GoalWaterfall> =
        combine(
            goals.observeGoals(),
            ranking.observe(),
            emergencyFund.observeEmergencyFund(),
        ) { projections, order, fund ->
            // Read once per emission (TIM-001). The engine reads no clock of its own.
            val today = clock.today()
            val forGoals = order.availableForGoals()
            val result =
                engine.allocate(
                    GoalWaterfallInput(
                        goals = projections,
                        monthlySurplus = forGoals.amount,
                        surplusBasis = forGoals.basis,
                        // ZERO, not the fund's top-up: §36 already claimed it in Stage 3, and
                        // claiming it twice would hide a month's pace from the goals.
                        emergencyTopUpMonthly = Money.ZERO,
                        emergencyRunwayMonthsBps = fund.runwayMonthsBps,
                        emergencyGateMonths = GATE_RULES.emergencyRunwayMonths,
                        claimedBeforeGoals = forGoals.claimed,
                        grossSurplus = order.monthlySurplus,
                        today = today,
                        nowUtcMillis = clock.nowUtcMillis(),
                    ),
                )
            // An `Err` here means an amount that will not fit in a `Long` (MNY-001) — absurd data,
            // not a broken app. Reporting UNKNOWN keeps the screen renderable, which is what the
            // engine's own null-surplus branch exists to say.
            (result as? Ok)?.value ?: unknownFor(projections, fund)
        }.flowOn(dispatchers.io)

    /**
     * What §36 leaves for the goals, and what it took first.
     *
     * Why:    AI-FOO fills the starter buffer, high-interest debt and the emergency fund before it
     *         reaches goals. Pouring the *whole* surplus here instead is what made the goals screen
     *         and the dashboard disagree past the emergency gate (ADR-0038).
     * What:   the distributable surplus less every stage above `GOAL_INVESTING`. Below the gate those
     *         stages have already absorbed or blocked everything, so this is zero and the engine's own
     *         gate marks each goal `blockedByEmergencyFund` — the same sentence the card always showed.
     * Result: the amount, its basis and what was claimed first. **Null stays null**: an unknown
     *         surplus is not a zero one, and feasibility must stay UNKNOWN rather than become
     *         INFEASIBLE.
     * Input:  the receiver — the ranking. Output: [ForGoals].
     */
    private fun OrderOfOperations.availableForGoals(): ForGoals {
        val claimed =
            stages.takeWhile { it.stage != FooStage.GOAL_INVESTING }
                .fold(Money.ZERO) { sum, stage -> sum + stage.amountMonthly }
        val available = monthlySurplus?.let { maxOf(Money.ZERO, it) - claimed }
        return ForGoals(available, surplusBasis, claimed)
    }

    /** What the goals may have this month, and what §36's earlier stages took before them. */
    private data class ForGoals(
        val amount: Money?,
        val basis: SurplusBasis,
        val claimed: Money,
    )

    /**
     * The plan for a ranking that could not be turned into one.
     * Result: an UNKNOWN-surplus waterfall, so the screen renders with every goal unallocated rather
     *         than showing nothing at all. Input: [projections]; [fund]. Output: [GoalWaterfall].
     */
    private fun unknownFor(
        projections: List<GoalProjection>,
        fund: EmergencyFundPlan,
    ): GoalWaterfall =
        (
            engine.allocate(
                GoalWaterfallInput(
                    goals = projections,
                    monthlySurplus = null,
                    surplusBasis = SurplusBasis.NONE,
                    emergencyRunwayMonthsBps = fund.runwayMonthsBps,
                    emergencyGateMonths = GATE_RULES.emergencyRunwayMonths,
                    today = clock.today(),
                    nowUtcMillis = clock.nowUtcMillis(),
                ),
            ) as Ok
        ).value

    private companion object {
        /** `RULE-EMERG-FIRST`'s one mirror in this repository, as it has been since 7.3. */
        val GATE_RULES = QuickSetupRules()
    }
}
