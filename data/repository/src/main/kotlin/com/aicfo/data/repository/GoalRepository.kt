package com.aicfo.data.repository

import androidx.room.withTransaction
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Err
import com.aicfo.core.common.IdGenerator
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.GoalEntity
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.goals.GoalEngine
import com.aicfo.domain.engines.goals.GoalPlanInput
import com.aicfo.domain.engines.goals.GoalProjection
import com.aicfo.domain.engines.goals.GoalSpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * The `goal` table, and the only thing allowed to read it (issue 7.1; §15, ARC-005).
 *
 * Why:  `GoalEngine` is pure arithmetic over rows somebody has to fetch, and it must never see a
 *       Room type. This is the seam: entities in, domain projections out, every query scoped to the
 *       active profile and every write soft-deleting rather than removing.
 * What: watch the goals with their figures, save one, delete one, and hand the whole plan to
 *       whoever needs the total.
 * Result: a ViewModel sees [GoalProjection]s and nothing else.
 * Changelog: 2026-08-30 — Created for issue 7.1.
 *
 * **Nothing derived is stored.** Every read recomputes through the engine, because a required
 * monthly written to the database would outlive the goal that produced it — and would go stale
 * simply because a day passed, which is the one input the user never edits.
 */
interface GoalRepository {
    /**
     * Watches the active profile's goals, already projected.
     * Why:    the screen renders a required monthly beside every goal, and computing it in the
     *         ViewModel would put engine calls above the repository boundary (ARC-005).
     * Result: soonest target first, re-emitted on every write. Empty when the user has no goals —
     *         which is a state, not an error.
     * Input:  none. Output: `Flow<List<GoalProjection>>`.
     */
    fun observeGoals(): Flow<List<GoalProjection>>

    /**
     * What every goal needs each month, in total.
     * Why:    `RULE-STS` subtracts the goal contributions the month has not yet made, and until this
     *         issue Safe-to-Spend substituted the user's whole quick-setup INVEST envelope for them
     *         (ADR-0021). This is the real term.
     * Result: the sum of the required monthly figures. **`Money.ZERO` when there are no goals**,
     *         which leaves Safe-to-Spend exactly as it was for a user who has set none.
     * Input:  none. Output: `Result<Money, AppError>`.
     */
    suspend fun requiredMonthlyTotal(): Result<Money, AppError>

    /**
     * Creates or updates one goal.
     * Result: `Ok(id)`. `Err(AppError.Validation)` for a blank name or an unparseable date — both
     *         carry a dotted key the UI maps to a string, never a user-visible message.
     * Input:  [draft]; [id] — null to create. Output: `Result<String, AppError>`.
     */
    suspend fun save(
        draft: GoalDraft,
        id: String? = null,
    ): Result<String, AppError>

    /**
     * Soft-deletes one goal.
     * Why:    §21.4 — rows are never removed, so an export taken before the delete still reconciles.
     * Result: `Ok(Unit)` even when the id is already gone; deleting twice is not an error.
     * Input:  [id]. Output: `Result<Unit, AppError>`.
     */
    suspend fun delete(id: String): Result<Unit, AppError>

    /**
     * Puts the goals in the order the user dragged them into (issue 7.3; §15, FR-GOAL-005).
     *
     * Why:    the contribution waterfall pours a limited surplus in list order, so the order **is**
     *         the plan — §15 calls it "a draggable plan" for that reason. It lives here rather than
     *         on `GoalWaterfallRepository` because this is the repository that owns the `goal`
     *         table, and ARC-005 allows exactly one.
     * What:   writes `sort_order = index` for every id given, in one transaction, stamping
     *         `updated_at_utc_millis` from the injected `Clock` (TIM-001).
     * Result: `Ok(Unit)`. **Ids the caller no longer has are ignored rather than refused** — a goal
     *         deleted on another screen mid-drag would otherwise fail a reorder that is still
     *         correct for every goal that remains. Goals absent from the list keep the order they
     *         had, so a partial list cannot silently demote them all to zero.
     * Input:  [goalIdsInOrder] — the ids, first-funded first. May be empty, which is a no-op.
     * Output: `Result<Unit, AppError>`.
     */
    suspend fun reorder(goalIdsInOrder: List<String>): Result<Unit, AppError>

    companion object {
        /** Prefix for minted goal ids, matching the convention every other table uses. */
        const val GOAL_ID_PREFIX = "goal"
    }
}

/**
 * What the user typed, before it has an identity (issue 7.1).
 *
 * Why:    the same reason `HoldingDraft` exists — the ViewModel parses text into money and dates,
 *         and hands over a value the repository can store without re-deciding anything.
 * Result: the argument [GoalRepository.save] takes.
 * Changelog: 2026-08-30 — Created for issue 7.1.
 *
 * @property name the user's label. Blank is refused: a goal nobody can identify is not a goal.
 * @property target what it needs in total, paise. Zero is allowed — "no target yet" is an ordinary
 *   state, and the engine reports it rather than the repository refusing it.
 * @property targetDateIso the day the money is needed, ISO `yyyy-MM-dd` (TIM-002).
 * @property saved what is set aside now. Hand-entered until issue 7.4 derives it.
 * @property plannedMonthly what the user intends to contribute each month. Zero means no plan yet.
 */
data class GoalDraft(
    val name: String,
    val target: Money,
    val targetDateIso: String,
    val saved: Money = Money.ZERO,
    val plannedMonthly: Money = Money.ZERO,
)

/**
 * [GoalRepository] over Room and [GoalEngine] (issue 7.1).
 *
 * Changelog: 2026-08-30 — Created for issue 7.1.
 */
internal class RoomGoalRepository(
    private val database: CfoDatabase,
    private val engine: GoalEngine,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
) : GoalRepository {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeGoals(): Flow<List<GoalProjection>> =
        activeProfileId
            .flatMapLatest { profileId ->
                database.goalDao().observeForProfile(profileId).map(::project)
            }.flowOn(dispatchers.io)

    override suspend fun requiredMonthlyTotal(): Result<Money, AppError> =
        withContext(dispatchers.io) {
            val profileId = activeProfileId.first()
            val total =
                project(database.goalDao().forProfile(profileId))
                    .fold(Money.ZERO) { running, goal -> running + goal.requiredMonthly }
            Ok(total)
        }

    override suspend fun save(
        draft: GoalDraft,
        id: String?,
    ): Result<String, AppError> =
        withContext(dispatchers.io) {
            val profileId = activeProfileId.first()
            val date = runCatching { LocalDate.parse(draft.targetDateIso) }.getOrNull()
            when {
                draft.name.isBlank() -> Err(AppError.Validation("goal.name"))
                date == null -> Err(AppError.Validation("goal.targetDate"))
                else -> Ok(write(draft, id, profileId))
            }
        }

    override suspend fun delete(id: String): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            database.goalDao().softDelete(id, clock.nowUtcMillis())
            Ok(Unit)
        }

    override suspend fun reorder(goalIdsInOrder: List<String>): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            // One stamp for the whole reorder, not one per row: this is a single user action, and
            // rows that drifted apart by a millisecond would make "what changed together?"
            // unanswerable in the audit trail (TIM-001).
            val now = clock.nowUtcMillis()
            database.withTransaction {
                goalIdsInOrder.forEachIndexed { index, goalId ->
                    database.goalDao().setSortOrder(goalId, index, now)
                }
            }
            Ok(Unit)
        }

    /**
     * Writes one row.
     * Why:    split out of [save] so the validation reads as a `when` rather than being buried under
     *         the entity construction.
     * Result: the row id. Input: [draft]; [id]; [profileId]. Output: [String].
     */
    private suspend fun write(
        draft: GoalDraft,
        id: String?,
        profileId: String,
    ): String {
        val now = clock.nowUtcMillis()
        val rowId = id ?: ids.newId(GoalRepository.GOAL_ID_PREFIX)
        val existing = database.goalDao().find(rowId)
        database.goalDao().upsert(
            GoalEntity(
                id = rowId,
                profileId = profileId,
                name = draft.name.trim(),
                targetMinor = draft.target.minor,
                targetDateIso = draft.targetDateIso,
                savedMinor = draft.saved.minor,
                plannedMonthlyMinor = draft.plannedMonthly.minor,
                // Preserved across an edit, so "when did I start this goal?" stays answerable — as
                // every other row keeps its created stamp.
                createdAtUtcMillis = existing?.createdAtUtcMillis ?: now,
                updatedAtUtcMillis = now,
            ),
        )
        return rowId
    }

    /**
     * Runs the engine over stored rows.
     *
     * Why:    one place, so the observed list and the Safe-to-Spend total cannot disagree about what
     *         a goal needs. `clock.today()` is read **once per projection** rather than per goal, so
     *         every figure in one emission describes the same day (TIM-001) — the same care
     *         `observeAllocation` takes with its `asOfIsoDate`.
     * Result: one projection per row. **A row whose stored date will not parse is dropped**, not
     *         thrown on: it can only arrive from a hand-edited database or a future migration bug,
     *         and losing one goal is a better outcome than a screen that cannot render at all (P-04).
     * Input:  [rows]. Output: the projections, in the DAO's order.
     */
    private fun project(rows: List<GoalEntity>): List<GoalProjection> {
        val specs =
            rows.mapNotNull { row ->
                val date = runCatching { LocalDate.parse(row.targetDateIso) }.getOrNull()
                date?.let {
                    GoalSpec(
                        id = row.id,
                        name = row.name,
                        target = Money(row.targetMinor),
                        targetDate = it,
                        saved = Money(row.savedMinor),
                        plannedMonthly = Money(row.plannedMonthlyMinor),
                    )
                }
            }
        val plan =
            engine.plan(
                GoalPlanInput(goals = specs, today = clock.today(), nowUtcMillis = clock.nowUtcMillis()),
            )
        return (plan as? Ok)?.value?.goals.orEmpty()
    }
}
