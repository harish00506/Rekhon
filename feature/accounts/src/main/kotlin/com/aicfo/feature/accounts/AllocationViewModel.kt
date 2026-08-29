package com.aicfo.feature.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.toAppError
import com.aicfo.data.repository.InvestmentRepository
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
 * Holds the allocation screen's state (issue 6.4; FR-INV-002, ARC-003, ARC-004).
 *
 * Why:  the screen changes from underneath itself whenever an account, a holding or a lot changes —
 *       and a price edit on one holding moves every other holding's share, because they all divide
 *       by the same total. So this collects a `Flow` on `viewModelScope` (ARC-006, so it dies with
 *       the screen) rather than reading once.
 *
 *       **There are no events.** This screen is read-only: §11.1 binds the module to analysing and
 *       flagging rather than recommending, and there is nothing here for the user to act on in the
 *       app — the action, if any, happens at a broker (P-07). A `HoldingsEvent`-style sealed
 *       interface with no members would be ceremony.
 * What: exposes [uiState].
 * Result: a screen whose every state is reachable and assertable without a device.
 * Changelog: 2026-08-28 — Created for issue 6.4.
 *
 * **The ViewModel sees engine results, never a Room entity** (ARC-005). It does no arithmetic at
 * all: every share and every flag on screen was computed by the engine (P-03).
 *
 * Input:  [repository] — the holdings store, which decides what counts as the portfolio (ADR-0029).
 * Output: an observable screen state.
 */
@HiltViewModel
class AllocationViewModel
    @Inject
    constructor(
        repository: InvestmentRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(AllocationUiState())

        /** The screen's state; every field a `val`, replaced wholesale on each emission (ARC-004). */
        val uiState: StateFlow<AllocationUiState> = _uiState.asStateFlow()

        init {
            repository.observeAllocation()
                .onEach { allocation ->
                    _uiState.update { it.copy(allocation = allocation, isLoading = false, errorCode = null) }
                }
                // A read failure clears the chart and raises the banner rather than leaving a stale
                // split on screen. An out-of-date allocation is worse than none: nothing on a pie
                // chart says it is old, and the flags beside it would be accusing the user on the
                // strength of numbers that have since changed.
                .catch { cause ->
                    _uiState.update {
                        it.copy(allocation = null, isLoading = false, errorCode = cause.toAppError().code)
                    }
                }
                .launchIn(viewModelScope)
        }
    }
