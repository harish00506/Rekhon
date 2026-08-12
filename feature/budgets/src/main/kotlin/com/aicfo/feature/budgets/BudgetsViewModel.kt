package com.aicfo.feature.budgets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.toAppError
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.data.repository.BudgetRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Holds the budgets screen's state (issue 4.4; ARC-003, ARC-004, FR-BUD-001/002/003).
 *
 * Why:  a budget's status changes from underneath this screen every time a transaction lands, a
 *       split is edited, a category is renamed or the day rolls over — so both lists are `Flow`s
 *       collected on `viewModelScope` (ARC-006, so they die with the screen) rather than one-off
 *       reads that would be stale before the user finished reading them.
 * What: exposes [uiState] and handles [BudgetsEvent]s.
 * Result: a screen whose every state is reachable and assertable in a unit test.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 *
 * **This class computes no money** (P-03). Spent, remaining, safe pace and the projection all arrive
 * from `BudgetEngine` through the repository, each carrying the rule that produced it; the only
 * arithmetic-shaped thing here is [MoneyFormatter.parse] turning the user's typing into paise, which
 * is a parse rather than a calculation.
 *
 * **Two collectors, not one `combine`.** Budgets and suggestions fail independently — a suggestion
 * query that throws must not blank out the budgets the user came to read — and combining them would
 * also hold the screen at `isLoading` until both had answered.
 *
 * Input:  [repository] — the budget store. Output: an observable screen state.
 */
@HiltViewModel
class BudgetsViewModel
    @Inject
    constructor(
        private val repository: BudgetRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(BudgetsUiState())

        /**
         * The screen's state.
         * Result: emits the current [BudgetsUiState] and every update. Read-only to callers —
         *         `asStateFlow()` prevents a composable from writing to it (ARC-004).
         */
        val uiState: StateFlow<BudgetsUiState> = _uiState.asStateFlow()

        init {
            observeBudgets()
            observeSuggestions()
        }

        /**
         * Keeps the budget list in step with the store.
         * Result: [uiState] carries every category with its live status. A read failure sets
         *         `errorCode` and clears loading, because a list that cannot be read must not look
         *         like a user who has planned nothing — the distinction [BudgetsUiState.isEmpty]
         *         exists to make.
         * Input:  none. Output: none (launches a collector).
         * Changelog: 2026-08-11 — Created for issue 4.4.
         */
        private fun observeBudgets() {
            repository.observeBudgets()
                .onEach { budgets -> _uiState.update { it.copy(isLoading = false, budgets = budgets) } }
                .catch { failure ->
                    _uiState.update { it.copy(isLoading = false, errorCode = failure.toAppError().code) }
                }
                .launchIn(viewModelScope)
        }

        /**
         * Keeps the suggestions in step with the store (FR-BUD-002).
         * Result: [uiState] carries the proposals. **A failure here is swallowed to an empty list, not
         *         raised to the banner**: suggestions are an offer, and a screen that reported an
         *         error because it could not think of one would be alarming about nothing. The
         *         budgets the user came to read are unaffected either way.
         * Input:  none. Output: none (launches a collector).
         * Changelog: 2026-08-11 — Created for issue 4.4.
         */
        private fun observeSuggestions() {
            repository.observeSuggestions()
                .onEach { suggestions -> _uiState.update { it.copy(suggestions = suggestions) } }
                .catch { _uiState.update { it.copy(suggestions = emptyList()) } }
                .launchIn(viewModelScope)
        }

        /**
         * Handles something the user did.
         * Why:    one entry point, so the sealed interface's exhaustiveness guarantees no
         *         interaction is silently unhandled.
         * Result: applies the event. Input: [event]. Output: none.
         * Changelog: 2026-08-11 — Created for issue 4.4.
         */
        fun onEvent(event: BudgetsEvent) {
            when (event) {
                is BudgetsEvent.EditClicked -> openEditor(event.categoryId)
                is BudgetsEvent.AmountChanged ->
                    _uiState.update { state -> state.copy(editing = state.editing?.copy(amountText = event.value)) }
                is BudgetsEvent.RolloverChanged -> setRollover(event.value)
                BudgetsEvent.Save -> save()
                BudgetsEvent.CancelEdit -> _uiState.update { it.copy(editing = null) }
                is BudgetsEvent.DeleteClicked -> delete(event.id)
                is BudgetsEvent.AcceptSuggestion -> accept(event.categoryId)
                BudgetsEvent.DismissError -> _uiState.update { it.copy(errorCode = null) }
            }
        }

        /**
         * Flips the carry-over switch in the open sheet (FR-BUD-001).
         * Result: updates `editing`, or does nothing when no sheet is open.
         * Input:  [enabled]. Output: none.
         * Changelog: 2026-08-11 — Created for issue 4.4.
         */
        private fun setRollover(enabled: Boolean) {
            _uiState.update { state -> state.copy(editing = state.editing?.copy(rolloverEnabled = enabled)) }
        }

        /**
         * Opens the sheet on a category, pre-filled with whatever it already has.
         * Why:    the row is taken from the list already in state rather than re-read, for the reason
         *         `CategoriesViewModel.openEditor` gives: the list *is* the live read, and fetching
         *         again would add a second source of truth for the same row. An unbudgeted category
         *         opens with a blank field rather than "₹0.00" — a zero the user did not choose reads
         *         as a decision they made.
         * Result: sets `editing`, or does nothing when the id names no row on screen.
         * Input:  [categoryId]. Output: none.
         * Changelog: 2026-08-11 — Created for issue 4.4.
         */
        private fun openEditor(categoryId: String) {
            val row = _uiState.value.budgets.firstOrNull { it.category.id == categoryId } ?: return
            _uiState.update {
                it.copy(
                    editing =
                        BudgetEditorState(
                            categoryId = row.category.id,
                            categoryName = row.category.name,
                            amountText =
                                if (row.isUnbudgeted) "" else MoneyFormatter.format(row.status.budgeted),
                            rolloverEnabled = row.rolloverEnabled,
                        ),
                    errorCode = null,
                )
            }
        }

        /**
         * Saves the open sheet (FR-BUD-001).
         * Result: closes the sheet on success and lets the list's `Flow` re-emit; on failure keeps it
         *         open with `errorCode` set, **so the user's typing survives** — a rejected amount
         *         that cleared the field would make the user retype it to find out what was wrong.
         * Input:  none. Output: none (launches on `viewModelScope`).
         * Changelog: 2026-08-11 — Created for issue 4.4.
         */
        private fun save() {
            val editing = _uiState.value.editing ?: return
            val amount = editing.amount ?: return
            if (!editing.canSave) return
            _uiState.update { it.copy(editing = editing.copy(isSaving = true), errorCode = null) }
            viewModelScope.launch {
                val outcome = repository.setBudget(editing.categoryId, amount, editing.rolloverEnabled)
                applyWriteOutcome(outcome)
            }
        }

        /**
         * Accepts a suggestion as this month's budget (FR-BUD-002, P-07).
         * Why:    the amount is **not** passed from the screen — the repository re-reads the
         *         suggestion it published, so the number written is provably the one the engine
         *         produced rather than one that travelled through a UI event and could be altered on
         *         the way. The screen's job is to say *which* category the user tapped.
         * Result: the suggestion becomes a budget with `source = 'suggested'` and its rule recorded;
         *         on failure the banner explains.
         * Input:  [categoryId]. Output: none (launches on `viewModelScope`).
         * Changelog: 2026-08-11 — Created for issue 4.4.
         */
        private fun accept(categoryId: String) {
            _uiState.update { it.copy(errorCode = null) }
            viewModelScope.launch { applyWriteOutcome(repository.acceptSuggestion(categoryId)) }
        }

        /**
         * Removes a budget, leaving the category unplanned (FR-BUD-001).
         * Why:    **no confirmation dialog**, unlike deleting a category. Deleting a category changes
         *         how past transactions read; deleting a budget destroys nothing — the spending stays,
         *         the row simply moves to the unplanned section, and setting it again is two taps. A
         *         confirmation on a reversible action trains the user to dismiss confirmations.
         * Result: the row becomes unplanned; on failure the banner explains.
         * Input:  [id] — the budget row. Output: none (launches on `viewModelScope`).
         * Changelog: 2026-08-11 — Created for issue 4.4.
         */
        private fun delete(id: String) {
            viewModelScope.launch {
                val outcome = repository.deleteBudget(id)
                _uiState.update { it.copy(errorCode = (outcome as? Err)?.error?.displayCode()) }
            }
        }

        /**
         * Applies the result of a write that the sheet may be open behind.
         * Why:    `setBudget` and `acceptSuggestion` differ in what they write and not at all in what
         *         the screen does afterwards, so they share this rather than each keeping a copy that
         *         could drift.
         * Result: closes the sheet on success; leaves it open with the error on failure.
         * Input:  [outcome]. Output: none.
         * Changelog: 2026-08-11 — Created for issue 4.4.
         */
        private fun applyWriteOutcome(outcome: Result<String, AppError>) {
            when (outcome) {
                is Ok -> _uiState.update { it.copy(editing = null) }
                is Err ->
                    _uiState.update { state ->
                        state.copy(
                            editing = state.editing?.copy(isSaving = false),
                            errorCode = outcome.error.displayCode(),
                        )
                    }
            }
        }

        /**
         * Turns an error into the code the screen looks wording up by.
         *
         * Why:    **every `AppError.Validation` carries the same `code` — "validation" — and the
         *         field that failed lives in a separate property.** Passing `code` straight through
         *         would render one sentence for two different refusals: an amount below zero and a
         *         category that has been deleted since the sheet opened. The second is not something
         *         the user can fix by re-reading what they typed. So the field is folded into the
         *         code here, where the mapping to a message is made ([BudgetLabels.errorMessage]).
         * Result: `validation:amount`, `validation:categoryId`, or the error's own code otherwise.
         * Input:  the receiver. Output: [String].
         * Changelog: 2026-08-11 — Created for issue 4.4.
         */
        private fun AppError.displayCode(): String =
            when (this) {
                is AppError.Validation -> "$code:$field"
                else -> code
            }
    }
