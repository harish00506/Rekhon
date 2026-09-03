package com.aicfo.feature.goals

import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import com.aicfo.data.repository.GoalWaterfallRepository
import com.aicfo.domain.engines.goals.GoalProjection
import com.aicfo.domain.engines.goals.GoalWaterfall
import com.aicfo.domain.engines.goals.GoalWaterfallEngineFactory
import com.aicfo.domain.engines.goals.GoalWaterfallInput
import com.aicfo.domain.engines.goals.SurplusBasis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * An in-memory [GoalWaterfallRepository] for the feature tests (issue 7.3).
 *
 * Why:  a fake rather than a mock, the convention this repo keeps. **It runs the real engine**, for
 *       [FakeGoalRepository]'s reason and one sharper: a fake returning hand-written allocations
 *       would let the screen render a split the engine would never produce, and the reorder test —
 *       the one that has to prove dragging changes who goes short — would be asserting the fixture's
 *       arithmetic rather than the app's.
 * What: watches [goals]'s projections and allocates a fixed surplus across them.
 * Result: the feature's tests exercise the real waterfall without a database.
 * Changelog: 2026-09-03 — Created for issue 7.3.
 *
 * @property goals the same fake the ViewModel is given, so a reorder there shows up here — which is
 *   what makes "dragging changes the plan" testable end to end inside the feature module.
 * @property surplus what the month has spare, or null to exercise the `UNKNOWN` branch.
 * @property runwayBps the emergency runway in basis points of a month; the default clears the gate.
 * @property topUp what the emergency fund would claim if the gate held.
 */
internal class FakeGoalWaterfallRepository(
    private val goals: FakeGoalRepository,
    private val surplus: Money? = Money(30_000_00),
    private val runwayBps: Int? = CLEAR_RUNWAY_BPS,
    private val topUp: Money = Money.ZERO,
    private val today: LocalDate = LocalDate.parse("2026-08-30"),
) : GoalWaterfallRepository {
    private val engine = GoalWaterfallEngineFactory.create()

    override fun observeWaterfall(): Flow<GoalWaterfall> = goals.observeGoals().map(::allocate)

    /** Result: the plan for these projections. Input: [projections]. Output: [GoalWaterfall]. */
    private fun allocate(projections: List<GoalProjection>): GoalWaterfall =
        (
            engine.allocate(
                GoalWaterfallInput(
                    goals = projections,
                    monthlySurplus = surplus,
                    surplusBasis =
                        if (surplus == null) SurplusBasis.NONE else SurplusBasis.OBSERVED_MEDIAN,
                    emergencyTopUpMonthly = topUp,
                    emergencyRunwayMonthsBps = runwayBps,
                    today = today,
                ),
            ) as Ok
        ).value

    private companion object {
        /** Nine months — comfortably clear of `RULE-EMERG-FIRST`'s three. */
        const val CLEAR_RUNWAY_BPS = 90_000
    }
}
