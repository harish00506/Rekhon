package com.aicfo.feature.goals

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import com.aicfo.data.repository.GoalDraft
import com.aicfo.data.repository.GoalRepository
import com.aicfo.domain.engines.goals.GoalEngineFactory
import com.aicfo.domain.engines.goals.GoalPlanInput
import com.aicfo.domain.engines.goals.GoalProjection
import com.aicfo.domain.engines.goals.GoalSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * An in-memory [GoalRepository] for the feature tests (issue 7.1).
 *
 * Why:  a fake rather than a mock, the convention this repo keeps — a hand-written double reads
 *       better and fails loudly on an unimplemented method.
 *
 *       **It runs the real engine.** A fake that returned hand-written projections would let the
 *       ViewModel and the screen agree on a required monthly the engine would never produce, and
 *       every test would pass. It also means the drafts this fake stores are projected exactly as
 *       the app projects them — which is what makes "saving a goal shows its required monthly" a
 *       claim about the app rather than about the fixture.
 *
 *       This is the same trap `FakeInvestmentRepository` fell into in issue 6.5: it silently dropped
 *       `priceKey`, so no test noticed the editor never set one.
 * What: a `MutableStateFlow` of stored drafts, projected on read.
 * Result: the feature's tests exercise real arithmetic without a database.
 * Changelog: 2026-08-30 — Created for issue 7.1.
 *
 * @property today the day every projection is reckoned from. Fixed, so the suite is reproducible.
 */
internal class FakeGoalRepository(
    private val today: LocalDate = LocalDate.parse("2026-08-30"),
) : GoalRepository {
    private val engine = GoalEngineFactory.create()
    private val stored = MutableStateFlow<List<Pair<String, GoalDraft>>>(emptyList())

    /** Set to fail the next write, so the refusal path is reachable. */
    var failOnSave: AppError? = null

    /** Every draft written, in order, so a test can assert what actually reached the repository. */
    val saved: List<GoalDraft> get() = stored.value.map { it.second }

    /** Every reorder asked for, so a test can assert the order the ViewModel computed (issue 7.3). */
    val reorders: MutableList<List<String>> = mutableListOf()

    override fun observeGoals(): Flow<List<GoalProjection>> = stored.map(::project)

    override suspend fun requiredMonthlyTotal(): Result<Money, AppError> =
        Ok(project(stored.value).fold(Money.ZERO) { running, goal -> running + goal.requiredMonthly })

    override suspend fun save(
        draft: GoalDraft,
        id: String?,
    ): Result<String, AppError> {
        failOnSave?.let { return Err(it) }
        val rowId = id ?: "goal:${stored.value.size + 1}"
        stored.value = stored.value.filterNot { it.first == rowId } + (rowId to draft)
        return Ok(rowId)
    }

    override suspend fun delete(id: String): Result<Unit, AppError> {
        stored.value = stored.value.filterNot { it.first == id }
        return Ok(Unit)
    }

    /**
     * Reorders the stored goals (issue 7.3).
     *
     * Why:    the fake keeps insertion order and `observeGoals` reads it back, which is exactly what
     *         `sort_order` does in the real DAO — so applying the order here rather than recording
     *         the call means a test asserts what the *screen* would show, not what the ViewModel
     *         said. Ids the fake does not hold are skipped, matching the repository's own promise
     *         about a goal deleted mid-drag.
     * Result: `Ok(Unit)`. Input: [goalIdsInOrder]. Output: the result.
     */
    override suspend fun reorder(goalIdsInOrder: List<String>): Result<Unit, AppError> {
        reorders += goalIdsInOrder
        val byId = stored.value.associateBy { it.first }
        val moved = goalIdsInOrder.mapNotNull(byId::get)
        stored.value = moved + stored.value.filterNot { it.first in goalIdsInOrder }
        return Ok(Unit)
    }

    /** Result: the stored drafts, projected by the real engine. Input: [rows]. Output: projections. */
    private fun project(rows: List<Pair<String, GoalDraft>>): List<GoalProjection> {
        val specs =
            rows.mapNotNull { (id, draft) ->
                runCatching { LocalDate.parse(draft.targetDateIso) }.getOrNull()?.let { date ->
                    GoalSpec(
                        id = id,
                        name = draft.name,
                        target = draft.target,
                        targetDate = date,
                        saved = draft.saved,
                        plannedMonthly = draft.plannedMonthly,
                    )
                }
            }
        val plan = engine.plan(GoalPlanInput(goals = specs, today = today))
        return (plan as? Ok)?.value?.goals.orEmpty()
    }
}
