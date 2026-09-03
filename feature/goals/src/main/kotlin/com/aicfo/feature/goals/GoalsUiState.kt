package com.aicfo.feature.goals

import androidx.compose.runtime.Immutable
import com.aicfo.domain.engines.goals.GoalProjection

/**
 * Everything the goals screen draws, as one immutable value (issue 7.1; ARC-004).
 *
 * Why:  one state class per screen as a `StateFlow`, events up via a sealed interface — the shape
 *       every screen in this app keeps, so a composable holds nothing and a test can drive any
 *       state directly.
 * What: the projected goals, the editor when it is open, and the two transient flags.
 * Result: what `GoalsScreen` renders.
 * Changelog: 2026-08-30 — Created for issue 7.1.
 *
 * @property goals the goals with their figures, already computed by the engine (P-03). This screen
 *   does no arithmetic of its own.
 * @property editor the open editor, or null when the list is showing.
 * @property isLoading true until the first emission, so an empty list and an unread one are not
 *   drawn the same way.
 * @property errorCode a dotted key from `AppError.Validation`, or null. A key rather than a message,
 *   because the domain must not decide wording.
 */
@Immutable
data class GoalsUiState(
    val goals: List<GoalProjection> = emptyList(),
    val editor: GoalEditorState? = null,
    val isLoading: Boolean = true,
    val errorCode: String? = null,
) {
    /** Whether to draw the "nothing here yet" copy rather than an empty list under a heading. */
    val isEmpty: Boolean get() = goals.isEmpty() && !isLoading && errorCode == null
}

/**
 * The editor, as typed (issue 7.1).
 *
 * Why:  text, not `Money` and not `LocalDate`. Parsing happens in the ViewModel on save, so a
 *       half-typed amount is a legitimate state rather than something the type system refuses while
 *       the user is still typing.
 * What: the five fields, plus the id when editing an existing goal.
 * Result: what the editor renders, and what `toDraft` turns into a write.
 * Changelog: 2026-08-30 — Created for issue 7.1.
 *
 * @property goalId the goal being edited, or null when new.
 * @property name the user's label.
 * @property target the target amount, as typed, in rupees.
 * @property targetDate the ISO day the money is needed, as typed.
 * @property saved what is set aside, as typed. Blank means zero — an untouched goal is not an error.
 * @property plannedMonthly the monthly plan, as typed. Blank means "not decided", and the engine
 *   then reports no ETA rather than inventing one.
 * @property fieldError set when a save was refused, so the editor stays open and says why.
 */
@Immutable
data class GoalEditorState(
    val goalId: String? = null,
    val name: String = "",
    val target: String = "",
    val targetDate: String = "",
    val saved: String = "",
    val plannedMonthly: String = "",
    val fieldError: String? = null,
)

/**
 * What the goals screen can be asked to do (issue 7.1; ARC-004).
 *
 * Why:  a sealed interface so the `when` in the ViewModel is exhaustive — a new event cannot be
 *       added without somewhere to handle it.
 * Result: events travel up, state comes down, and the composable holds nothing.
 * Changelog: 2026-08-30 — Created for issue 7.1.
 */
sealed interface GoalsEvent {
    /** Open the editor on a new goal. */
    data object AddGoal : GoalsEvent

    /** Open the editor on an existing goal. */
    data class EditGoal(val goalId: String) : GoalsEvent

    /** Close the editor without writing. */
    data object CancelEditor : GoalsEvent

    /** Write what the editor holds. */
    data object SaveEditor : GoalsEvent

    /** Soft-delete a goal. */
    data class DeleteGoal(val goalId: String) : GoalsEvent

    /** The name field changed. */
    data class NameChanged(val value: String) : GoalsEvent

    /** The target-amount field changed. */
    data class TargetChanged(val value: String) : GoalsEvent

    /** The target-date field changed. */
    data class TargetDateChanged(val value: String) : GoalsEvent

    /** The saved-so-far field changed. */
    data class SavedChanged(val value: String) : GoalsEvent

    /** The monthly-plan field changed. */
    data class PlannedMonthlyChanged(val value: String) : GoalsEvent

    /** Clear the error banner. */
    data object DismissError : GoalsEvent
}
