package com.aicfo.feature.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
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
                AccountsEvent.DismissError -> _uiState.update { it.copy(errorCode = null) }
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
