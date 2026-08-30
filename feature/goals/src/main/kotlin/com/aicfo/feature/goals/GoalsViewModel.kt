package com.aicfo.feature.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.data.repository.GoalDraft
import com.aicfo.data.repository.GoalRepository
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
 * What: observes the projected goals, opens and closes the editor, and saves or deletes.
 * Result: one immutable [GoalsUiState] as a `StateFlow`.
 * Changelog: 2026-08-30 — Created for issue 7.1.
 */
@HiltViewModel
class GoalsViewModel
    @Inject
    constructor(
        private val repository: GoalRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(GoalsUiState())
        val uiState: StateFlow<GoalsUiState> = _uiState.asStateFlow()

        init {
            observeGoals()
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
