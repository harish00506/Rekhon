package com.aicfo.feature.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.data.repository.GoalDraft
import com.aicfo.data.repository.GoalRepository
import com.aicfo.data.repository.GoalWaterfallRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the goals screen (issue 7.1; §15, ARC-004).
 *
 * Why:  the one place text becomes money and a write becomes state. Everything numeric arrives
 *       already computed by `GoalEngine` through `GoalRepository` (P-03) — this class does no
 *       arithmetic, only parsing.
 * What: observes the projected goals and the plan across them, opens and closes the editor, saves,
 *       deletes, and reorders the waterfall.
 * Result: one immutable [GoalsUiState] as a `StateFlow`.
 * Changelog: 2026-08-30 — Created for issue 7.1.
 *            2026-09-03 — Issue 7.3: the waterfall, and the three reorder events.
 *
 * **Eleven functions is detekt's ceiling, and the shape is deliberate.** Nine of them are private
 * and none is longer than a few lines: `onEvent` is one `when` that delegates, and every branch it
 * delegates to is named after what it does. Collapsing them back into `onEvent` would trade eleven
 * short readable functions for one long unreadable one and satisfy a different detekt rule instead.
 * If a twelfth is wanted, the editor is the seam — it is a screen of its own wearing a nullable
 * field.
 *
 * **Two flows rather than one combined flow**, because they fail independently. The goal list is a
 * plain table read; the plan needs six months of ledger, the emergency fund and the onboarding
 * envelopes. Combining them would let a problem resolving the surplus blank a list that is perfectly
 * readable — and the list is the half the user needs in order to fix anything.
 */
@HiltViewModel
@Suppress("TooManyFunctions") // Eleven, each one private and four lines: see the note above.
class GoalsViewModel
    @Inject
    constructor(
        private val repository: GoalRepository,
        private val waterfallRepository: GoalWaterfallRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(GoalsUiState())
        val uiState: StateFlow<GoalsUiState> = _uiState.asStateFlow()

        init {
            observeGoals()
            observeWaterfall()
        }

        /**
         * Handles one event.
         * Why:    a `when` over a sealed interface, so adding an event without handling it will not
         *         compile.
         * Result: the state moves. Input: [event]. Output: none.
         */
        fun onEvent(event: GoalsEvent) {
            when (event) {
                GoalsEvent.AddGoal -> _uiState.update { it.copy(editor = GoalEditorState()) }
                is GoalsEvent.EditGoal -> openEditor(event.goalId)
                GoalsEvent.CancelEditor -> _uiState.update { it.copy(editor = null) }
                GoalsEvent.SaveEditor -> save()
                is GoalsEvent.DeleteGoal -> delete(event.goalId)
                GoalsEvent.DismissError -> _uiState.update { it.copy(errorCode = null) }
                is GoalsEvent.MoveUp -> move(event.goalId, -1)
                is GoalsEvent.MoveDown -> move(event.goalId, 1)
                is GoalsEvent.MoveGoal -> reorder(event.fromIndex, event.toIndex)
                else -> editField(event)
            }
        }

        /**
         * The five text fields.
         * Why:    split out so [onEvent] stays inside the 40-line limit (§21.6) and so every field
         *         edit clears the error — a message about a field the user has since fixed is worse
         *         than none.
         * Result: the editor's text moves. Input: [event]. Output: none.
         */
        private fun editField(event: GoalsEvent) {
            editEditor { state ->
                when (event) {
                    is GoalsEvent.NameChanged -> state.copy(name = event.value)
                    is GoalsEvent.TargetChanged -> state.copy(target = event.value)
                    is GoalsEvent.TargetDateChanged -> state.copy(targetDate = event.value)
                    is GoalsEvent.SavedChanged -> state.copy(saved = event.value)
                    is GoalsEvent.PlannedMonthlyChanged -> state.copy(plannedMonthly = event.value)
                    else -> state
                }.copy(fieldError = null)
            }
        }

        /**
         * Subscribes to the projected goals.
         * Why:    the repository runs the engine, so what arrives is already the figures the screen
         *         draws. `catch` rather than a try: a Flow that throws would take the screen down.
         * Result: the list, and `isLoading` false from the first emission. Input/Output: none.
         */
        private fun observeGoals() {
            repository.observeGoals()
                .onEach { goals -> _uiState.update { it.copy(goals = goals, isLoading = false) } }
                .catch { _uiState.update { it.copy(isLoading = false, errorCode = STORAGE_ERROR) } }
                .launchIn(viewModelScope)
        }

        /**
         * Subscribes to the contribution plan.
         * Why:    separate from [observeGoals] so a failure here cannot blank the list — see the
         *         class doc. `isLoading` is **not** touched: the list owns that flag, and letting
         *         the slower flow clear it would show an empty list as "no goals yet".
         * Result: the plan, re-emitted on every change to the goals, the ledger or the buffer.
         * Input/Output: none.
         */
        private fun observeWaterfall() {
            waterfallRepository.observeWaterfall()
                .onEach { plan -> _uiState.update { it.copy(waterfall = plan) } }
                .catch { _uiState.update { it.copy(errorCode = STORAGE_ERROR) } }
                .launchIn(viewModelScope)
        }

        /**
         * Moves one goal by one place (issue 7.3; FR-GOAL-005).
         * Why:    the accessible half of the drag, and the half a Compose test can drive. Computing
         *         the target index here rather than in the composable keeps the bounds check in one
         *         place — a row that offers "move up" on the first goal is a UI bug, but a *silent*
         *         out-of-range write would be a data one.
         * Result: nothing happens when the goal is absent or already at the end.
         * Input:  [goalId]; [delta] — −1 for up, +1 for down. Output: none.
         */
        private fun move(
            goalId: String,
            delta: Int,
        ) {
            val from = _uiState.value.goals.indexOfFirst { it.goalId == goalId }
            if (from < 0) return
            reorder(from, from + delta)
        }

        /**
         * Writes a new waterfall order.
         * Why:    the whole list is sent, not the moved pair. `sort_order` is positional, so a
         *         partial write would leave the goals it did not name sharing a rank with the ones
         *         it did, and the tie-break would decide the plan instead of the user.
         * Result: the repository re-emits and the plan recomputes. **Out-of-range indices are
         *         ignored**, which is what makes a drag released off the end of the list harmless.
         * Input:  [from]; [to]. Output: none.
         */
        private fun reorder(
            from: Int,
            to: Int,
        ) {
            val ids = _uiState.value.goals.map { it.goalId }
            if (from !in ids.indices || to !in ids.indices || from == to) return
            val reordered = ids.toMutableList().apply { add(to, removeAt(from)) }
            viewModelScope.launch {
                if (repository.reorder(reordered) is Err) {
                    _uiState.update { it.copy(errorCode = STORAGE_ERROR) }
                }
            }
        }

        /**
         * Fills the editor from a stored goal.
         * Why:    from the projection already in state rather than a second read — it carries every
         *         field the editor needs, and re-reading could show something different from the row
         *         the user just tapped.
         * Result: the editor opens populated, or nothing happens if the id is unknown.
         * Input:  [goalId]. Output: none.
         */
        private fun openEditor(goalId: String) {
            val goal = _uiState.value.goals.firstOrNull { it.goalId == goalId } ?: return
            _uiState.update {
                it.copy(
                    editor =
                        GoalEditorState(
                            goalId = goal.goalId,
                            name = goal.name,
                            target = MoneyFormatter.format(goal.target),
                            targetDate = goal.targetDateIso,
                            saved = MoneyFormatter.format(goal.saved),
                            plannedMonthly = MoneyFormatter.format(goal.plannedMonthly),
                        ),
                )
            }
        }

        /**
         * Writes what the editor holds.
         * Why:    parsing lives here, not in the repository (which would then own wording) and not in
         *         the composable (which owns nothing).
         * Result: the editor closes on success; on refusal it stays open carrying the field error, so
         *         the user's typing is never discarded.
         * Input:  none. Output: none.
         */
        private fun save() {
            val editor = _uiState.value.editor ?: return
            val draft = editor.toDraft()
            if (draft == null) {
                editEditor { it.copy(fieldError = FIELD_GOAL) }
                return
            }
            viewModelScope.launch {
                when (repository.save(draft, id = editor.goalId)) {
                    is Ok -> _uiState.update { it.copy(editor = null) }
                    is Err -> editEditor { it.copy(fieldError = FIELD_GOAL) }
                }
            }
        }

        /** Result: the goal is tombstoned and leaves the list. Input: [goalId]. Output: none. */
        private fun delete(goalId: String) {
            viewModelScope.launch {
                if (repository.delete(goalId) is Err) {
                    _uiState.update { it.copy(errorCode = STORAGE_ERROR) }
                }
            }
        }

        /** Result: applies [change] to the open editor, if there is one. */
        private fun editEditor(change: (GoalEditorState) -> GoalEditorState) {
            _uiState.update { state -> state.copy(editor = state.editor?.let(change)) }
        }

        /** `MutableStateFlow.update` without the atomicfu dependency the rest of the app avoids. */
        private inline fun MutableStateFlow<GoalsUiState>.update(change: (GoalsUiState) -> GoalsUiState) {
            value = change(value)
        }

        companion object {
            /** The editor's own field-error code, kept out of `AppError` because it never leaves. */
            const val FIELD_GOAL = "goal"

            /** Shown when the store itself failed, as distinct from something the user typed. */
            const val STORAGE_ERROR = "storage"
        }
    }

/**
 * Turns the editor's text into a [GoalDraft].
 *
 * Why:    the one place text becomes money. A blank target is **not** legitimate here even though
 *         the engine tolerates one: a user who opened the editor is setting a goal, and silently
 *         storing zero would give them a card reading "no target set yet" with no clue why. Blank
 *         *saved* and blank *planned* are legitimate and mean zero.
 * Result: the draft, or `null` when the name is blank, an amount will not parse, or an amount is
 *         negative. Blank and unparseable are deliberately different outcomes (P-03).
 * Input:  the receiver. Output: [GoalDraft]?.
 * Changelog: 2026-08-30 — Created for issue 7.1.
 */
internal fun GoalEditorState.toDraft(): GoalDraft? {
    val parsedTarget = MoneyFormatter.parse(target)
    val parsedSaved = if (saved.isBlank()) Money.ZERO else MoneyFormatter.parse(saved)
    val parsedPlanned = if (plannedMonthly.isBlank()) Money.ZERO else MoneyFormatter.parse(plannedMonthly)
    val amountsUsable =
        parsedTarget != null && parsedTarget > Money.ZERO &&
            parsedSaved != null && parsedSaved >= Money.ZERO &&
            parsedPlanned != null && parsedPlanned >= Money.ZERO
    return if (name.isBlank() || targetDate.isBlank() || !amountsUsable) {
        null
    } else {
        GoalDraft(
            name = name.trim(),
            target = parsedTarget,
            targetDateIso = targetDate.trim(),
            saved = parsedSaved,
            plannedMonthly = parsedPlanned,
        )
    }
}
