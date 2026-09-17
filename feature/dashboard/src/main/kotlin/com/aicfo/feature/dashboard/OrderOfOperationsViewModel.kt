package com.aicfo.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.data.repository.OrderOfOperationsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * Holds the full-order screen's state (issue 7.5; §36, FOO-002, ARC-004).
 *
 * Why:  the Advisor hub that §36 names as the home of the full ranked list is Epic 10's and does not
 *       exist yet, so the list gets its own small screen behind the dashboard card until it does.
 * What: observes [OrderOfOperationsRepository] and handles [OrderOfOperationsEvent]s.
 * Result: one `StateFlow` out, one `onEvent` in, and nothing else — the dashboard's pattern.
 * Changelog: 2026-09-17 — Created for issue 7.5.
 *
 * Input:  [repository] — the ranking. Output: an observable screen state.
 */
@HiltViewModel
class OrderOfOperationsViewModel
    @Inject
    constructor(
        private val repository: OrderOfOperationsRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(OrderOfOperationsUiState())

        /** The screen's state. Result: read-only to callers (ARC-004). */
        val uiState: StateFlow<OrderOfOperationsUiState> = _uiState.asStateFlow()

        init {
            observeRanking()
        }

        /**
         * Handles one event from the screen.
         * Result: [uiState] updated. Input: [event]. Output: none.
         */
        fun onEvent(event: OrderOfOperationsEvent) {
            when (event) {
                OrderOfOperationsEvent.DismissError -> _uiState.update { it.copy(errorCode = null) }
            }
        }

        /**
         * Keeps the ranking live.
         * Why:    §36 recomputes "on any surplus change", and a card APR edited in Accounts should move
         *         a debt between stages the moment the user comes back.
         * Result: each emission replaces the ranking and clears any earlier error; a failure stops
         *         the spinner and raises [ERROR_STORAGE] while keeping the last ranking on screen.
         * Input:  none. Output: none (launches a collector).
         */
        private fun observeRanking() {
            repository.observe()
                .onEach { ranking ->
                    _uiState.update { it.copy(ranking = ranking, isLoading = false, errorCode = null) }
                }
                .catch { _uiState.update { it.copy(isLoading = false, errorCode = ERROR_STORAGE) } }
                .launchIn(viewModelScope)
        }

        private companion object {
            /** The one failure this screen can report: the ranking could not be read. */
            const val ERROR_STORAGE = "order_of_operations.storage"
        }
    }
