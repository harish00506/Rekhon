package com.aicfo.feature.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.toAppError
import com.aicfo.data.repository.AccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Holds the accounts list's state (issue 2.5; ARC-003, ARC-004, FR-ACC-001, FR-ACC-007).
 *
 * Why:  the list is the one screen in the app whose contents change from underneath it for three
 *       different reasons — the user edits an account, a transaction lands and moves a balance
 *       (DB-001), or the demo is entered or left and the whole profile switches. A one-off read at
 *       construction would be stale after any of them, so everything here is a `Flow` collected on
 *       `viewModelScope` (ARC-006, so it dies with the screen).
 * What: exposes [uiState] and handles [AccountsEvent]s.
 * Result: a screen whose every state is reachable and assertable in a unit test.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 *
 * **The ViewModel sees `Account`, never a Room entity** (ARC-005) — the repository is the only DAO
 * toucher, and this class holds only what `:core:model` defines.
 *
 * Input:  [repository] — the accounts store. Output: an observable screen state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AccountsViewModel
    @Inject
    constructor(
        private val repository: AccountRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(AccountsUiState())

        /**
         * The screen's state.
         * Result: emits the current [AccountsUiState] and every update. Read-only to callers —
         *         `asStateFlow()` prevents a composable from writing to it (ARC-004).
         */
        val uiState: StateFlow<AccountsUiState> = _uiState.asStateFlow()

        init {
            observeAccounts()
        }

        /**
         * Keeps the list in step with the store.
         *
         * Why:    **`flatMapLatest` over the archived toggle**, not two separate collectors: turning
         *         archived accounts on must *switch* which query is observed and cancel the previous
         *         one, or the screen would keep receiving emissions from the list it is no longer
         *         showing. The repository does the same thing one layer down for the demo switch.
         * Result: [uiState] carries the accounts, or an empty list, which the screen renders as an
         *         empty state. A read failure sets `errorCode` and clears loading, because a list
         *         that cannot be read must not look like a user with no accounts.
         * Input:  none. Output: none (launches a collector).
         */
        private fun observeAccounts() {
            _uiState.map { it.showArchived }
                .flatMapLatest { showArchived -> repository.observeAccounts(includeArchived = showArchived) }
                .onEach { accounts -> _uiState.update { it.copy(isLoading = false, accounts = accounts) } }
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
         */
        fun onEvent(event: AccountsEvent) {
            when (event) {
                is AccountsEvent.ToggleArchived -> _uiState.update { it.copy(showArchived = event.show) }
                is AccountsEvent.SetArchived -> write { repository.setArchived(event.id, event.archived) }
                is AccountsEvent.Delete -> write { repository.delete(event.id) }
                is AccountsEvent.OpenReconcile -> openReconcile(event.id)
                is AccountsEvent.StatementChanged ->
                    _uiState.update { state ->
                        state.copy(reconciling = state.reconciling?.copy(statementText = event.value))
                    }
                AccountsEvent.ConfirmReconcile -> confirmReconcile()
                AccountsEvent.CancelReconcile -> _uiState.update { it.copy(reconciling = null) }
                AccountsEvent.DismissError -> _uiState.update { it.copy(errorCode = null) }
            }
        }

        /**
         * Opens FR-ACC-006's panel on one account (issue 2.7).
         * Why:    the account is taken from the list already in state rather than re-read, because
         *         the list *is* the live read — it re-emits on every change to an account or a
         *         transaction. Fetching again would only add a second source of truth for the same
         *         row, and the figure the panel shows is a preview either way: the repository
         *         re-derives it inside its own transaction before writing anything.
         * Result: sets `reconciling`, or does nothing when the id names no row on screen.
         * Input:  [id] — the account. Output: none.
         * Changelog: 2026-08-02 — Created for issue 2.7.
         */
        private fun openReconcile(id: String) {
            val account = _uiState.value.accounts.firstOrNull { it.id == id } ?: return
            _uiState.update { it.copy(reconciling = ReconcileState(account = account), errorCode = null) }
        }

        /**
         * Posts the adjustment (issue 2.7; FR-ACC-006).
         * Why:    hands the repository the **statement balance**, never the delta the panel
         *         previewed — the subtraction is the store's to do against a freshly derived
         *         balance, so a transaction landing while the panel was open cannot be silently
         *         absorbed into the correction (P-03: the number comes from one place).
         * Result: closes the panel on success and lets the list's `Flow` re-emit the new balance;
         *         on failure keeps it open with `errorCode` set, so the user's typing survives.
         * Input:  none. Output: none (launches on `viewModelScope`).
         * Changelog: 2026-08-02 — Created for issue 2.7.
         */
        private fun confirmReconcile() {
            val reconciling = _uiState.value.reconciling ?: return
            val statement = reconciling.statement ?: return
            _uiState.update { it.copy(reconciling = reconciling.copy(isSaving = true), errorCode = null) }
            viewModelScope.launch {
                when (val outcome = repository.reconcile(reconciling.account.id, statement)) {
                    is Ok -> _uiState.update { it.copy(reconciling = null) }
                    is Err ->
                        _uiState.update { state ->
                            state.copy(
                                reconciling = state.reconciling?.copy(isSaving = false),
                                errorCode = outcome.error.code,
                            )
                        }
                }
            }
        }

        /**
         * Runs a write and surfaces its failure.
         * Why:    archive and delete differ only in which repository call they make, and both must
         *         report a failure rather than swallow it — a delete that silently did nothing is
         *         the bug where the row reappears on the next launch. The list itself needs no
         *         refresh: it is a `Flow`, so a successful write re-emits on its own.
         * Result: sets `errorCode` on failure, nothing on success.
         * Input:  [block] — the repository call. Output: none (launches on `viewModelScope`).
         */
        private fun write(block: suspend () -> Result<Unit, AppError>) {
            viewModelScope.launch {
                val outcome = block()
                if (outcome is Err) {
                    _uiState.update { it.copy(errorCode = outcome.error.code) }
                }
            }
        }
    }
