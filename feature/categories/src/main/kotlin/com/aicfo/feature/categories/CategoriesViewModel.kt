package com.aicfo.feature.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.getOrNull
import com.aicfo.core.common.toAppError
import com.aicfo.data.repository.CategoryRepository
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
 * Holds the categories editor's state (issue 4.1; ARC-003, ARC-004, FR-SET-001).
 *
 * Why:  the taxonomy changes from underneath this screen for three reasons — the user edits it, the
 *       cold-start seed writes the defaults into it, and the demo is entered or left and the whole
 *       profile switches. A one-off read at construction would be stale after any of them, so the
 *       list is a `Flow` collected on `viewModelScope` (ARC-006, so it dies with the screen).
 * What: exposes [uiState] and handles [CategoriesEvent]s.
 * Result: a screen whose every state is reachable and assertable in a unit test.
 * Changelog: 2026-08-08 — Created for issue 4.1.
 *
 * **The ViewModel sees `Category`, never a Room entity** (ARC-005) — the repository is the only DAO
 * toucher, and this class holds only what `:core:model` defines.
 *
 * **Validation is not duplicated here.** Blankness is checked in [CategoryEditorState.canSave] only
 * to disable the button; uniqueness and the one-level nesting rule are enforced by the repository
 * inside the transaction that writes, and their `AppError.Validation` field is what this class turns
 * into an error code. A second copy of those rules on this side is how the screen and the store come
 * to disagree about what is allowed.
 *
 * Input:  [repository] — the taxonomy store. Output: an observable screen state.
 */
@HiltViewModel
class CategoriesViewModel
    @Inject
    constructor(
        private val repository: CategoryRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(CategoriesUiState())

        /**
         * The screen's state.
         * Result: emits the current [CategoriesUiState] and every update. Read-only to callers —
         *         `asStateFlow()` prevents a composable from writing to it (ARC-004).
         */
        val uiState: StateFlow<CategoriesUiState> = _uiState.asStateFlow()

        init {
            observeCategories()
        }

        /**
         * Keeps the list in step with the store.
         * Result: [uiState] carries the taxonomy, or an empty list, which the screen renders as an
         *         empty state. A read failure sets `errorCode` and clears loading, because a list
         *         that cannot be read must not look like a profile with no categories — the
         *         distinction [CategoriesUiState.isEmpty] exists to make.
         * Input:  none. Output: none (launches a collector).
         * Changelog: 2026-08-08 — Created for issue 4.1.
         */
        private fun observeCategories() {
            repository.observeCategories()
                .onEach { categories -> _uiState.update { it.copy(isLoading = false, categories = categories) } }
                .catch { failure ->
                    _uiState.update { it.copy(isLoading = false, errorCode = failure.toAppError().code) }
                }
                .launchIn(viewModelScope)
        }

        /**
         * Handles something the user did.
         * Why:    one entry point, so the sealed interface's exhaustiveness guarantees no
         *         interaction is silently unhandled.
         * Result: applies the event. Input: [event]. Output: none.
         * Changelog: 2026-08-08 — Created for issue 4.1.
         */
        fun onEvent(event: CategoriesEvent) {
            when (event) {
                CategoriesEvent.AddClicked ->
                    _uiState.update { it.copy(editing = CategoryEditorState(), errorCode = null) }
                is CategoriesEvent.EditClicked -> openEditor(event.id)
                is CategoriesEvent.NameChanged ->
                    _uiState.update { state -> state.copy(editing = state.editing?.copy(name = event.value)) }
                is CategoriesEvent.NatureChanged ->
                    _uiState.update { state -> state.copy(editing = state.editing?.copy(nature = event.value)) }
                is CategoriesEvent.ParentChanged ->
                    _uiState.update { state -> state.copy(editing = state.editing?.copy(parentId = event.value)) }
                CategoriesEvent.Save -> save()
                CategoriesEvent.CancelEdit -> _uiState.update { it.copy(editing = null) }
                is CategoriesEvent.DeleteClicked -> openDeleteConfirmation(event.id)
                CategoriesEvent.ConfirmDelete -> confirmDelete()
                CategoriesEvent.CancelDelete -> _uiState.update { it.copy(confirmingDelete = null) }
                CategoriesEvent.DismissError -> _uiState.update { it.copy(errorCode = null) }
            }
        }

        /**
         * Opens the sheet on an existing category.
         * Why:    the row is taken from the list already in state rather than re-read, for the reason
         *         `AccountsViewModel.openReconcile` gives: the list *is* the live read, and fetching
         *         again would add a second source of truth for the same row.
         * Result: sets `editing`, or does nothing when the id names no row on screen.
         * Input:  [id] — the category. Output: none.
         * Changelog: 2026-08-08 — Created for issue 4.1.
         */
        private fun openEditor(id: String) {
            val category = _uiState.value.categories.firstOrNull { it.id == id } ?: return
            _uiState.update {
                it.copy(
                    editing =
                        CategoryEditorState(
                            id = category.id,
                            name = category.name,
                            nature = category.nature,
                            parentId = category.parentId,
                        ),
                    errorCode = null,
                )
            }
        }

        /**
         * Saves the open sheet, creating or updating according to its [CategoryEditorState.id].
         * Result: closes the sheet on success and lets the list's `Flow` re-emit; on failure keeps it
         *         open with `errorCode` set, **so the user's typing survives** — a rejected name that
         *         cleared the form would make the user retype it to find out what was wrong with it.
         * Input:  none. Output: none (launches on `viewModelScope`).
         * Changelog: 2026-08-08 — Created for issue 4.1.
         */
        private fun save() {
            val editing = _uiState.value.editing ?: return
            if (!editing.canSave) return
            _uiState.update { it.copy(editing = editing.copy(isSaving = true), errorCode = null) }
            viewModelScope.launch {
                val outcome =
                    if (editing.id == null) {
                        repository.create(editing.name, editing.nature, editing.parentId)
                    } else {
                        repository.update(editing.id, editing.name, editing.nature, editing.parentId)
                    }
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
        }

        /**
         * Opens the delete confirmation and counts what the category is attached to (P-02).
         * Why:    the dialog opens **immediately** with a `null` count rather than waiting for the
         *         query, so a tap never appears to do nothing; the number arrives into the already
         *         open dialog. A failed count leaves it `null`, which the dialog renders as "this
         *         cannot be counted right now" rather than as zero — claiming nothing is affected
         *         when the app does not know would be the one wrong thing to say here.
         * Result: sets `confirmingDelete`, then fills in its count.
         * Input:  [id] — the category. Output: none (launches on `viewModelScope`).
         * Changelog: 2026-08-08 — Created for issue 4.1.
         */
        private fun openDeleteConfirmation(id: String) {
            val category = _uiState.value.categories.firstOrNull { it.id == id } ?: return
            _uiState.update {
                it.copy(confirmingDelete = DeleteConfirmation(id = category.id, name = category.name))
            }
            viewModelScope.launch {
                val count = repository.countUsage(id).getOrNull()
                _uiState.update { state ->
                    // Guarded on the id: the user may have cancelled and opened another confirmation
                    // while this query was in flight, and writing the old count into the new dialog
                    // would state a number about the wrong category.
                    if (state.confirmingDelete?.id != id) {
                        state
                    } else {
                        state.copy(confirmingDelete = state.confirmingDelete.copy(usageCount = count))
                    }
                }
            }
        }

        /**
         * Deletes the confirmed category (DB-002, soft).
         * Result: closes the dialog on success and lets the list's `Flow` re-emit; on failure closes
         *         it too and sets `errorCode` — the banner is where a refusal belongs, because the
         *         reason a delete is refused ("it still has children") is not something re-confirming
         *         would fix.
         * Input:  none. Output: none (launches on `viewModelScope`).
         * Changelog: 2026-08-08 — Created for issue 4.1.
         */
        private fun confirmDelete() {
            val confirming = _uiState.value.confirmingDelete ?: return
            viewModelScope.launch {
                val outcome = repository.delete(confirming.id)
                _uiState.update { state ->
                    state.copy(
                        confirmingDelete = null,
                        errorCode = (outcome as? Err)?.error?.displayCode(),
                    )
                }
            }
        }

        /**
         * Turns an error into the code the screen looks wording up by.
         *
         * Why:    **every `AppError.Validation` carries the same `code` — "validation" — and the
         *         field that failed lives in a separate property.** Passing `code` straight through
         *         would render one sentence for three different refusals: a duplicate name, an
         *         illegal parent, and a category that still has children. The user would be told
         *         "check what you entered" for a delete they never typed anything into. So the field
         *         is folded into the code here, where the mapping to a message is made
         *         ([CategoryLabels.errorMessage]).
         * Result: `validation:name`, `validation:parentId`, `validation:children`, or the error's own
         *         code for everything else.
         * Input:  the receiver. Output: [String].
         * Changelog: 2026-08-08 — Created for issue 4.1.
         */
        private fun AppError.displayCode(): String =
            when (this) {
                is AppError.Validation -> "$code:$field"
                else -> code
            }
    }
