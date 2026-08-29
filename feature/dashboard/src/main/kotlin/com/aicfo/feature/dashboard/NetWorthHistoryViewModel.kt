package com.aicfo.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.toAppError
import com.aicfo.data.repository.NetWorthRepository
import com.aicfo.domain.engines.networth.NetWorthRange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * Feeds the net-worth history screen (issue 6.6; FR-ACC-005, ARC-004, ARC-005).
 *
 * Why:  **`flatMapLatest` over the selected range, not four subscriptions.** Picking a chip must
 *       *switch* which window is being observed and cancel the previous one; keeping all four alive
 *       would hold four Room queries open to render one line, and a slow query for a window the user
 *       has already left could still land and overwrite the one they are looking at.
 * What: the range the chips write, flat-mapped into the repository's history.
 * Result: the screen's state, and the only place it is assembled.
 * Changelog: 2026-08-30 — Created for issue 6.6.
 *
 * Sees domain types only — `NetWorthTrend`, never a Room row (ARC-005). It computes nothing: every
 * figure on the screen was measured by the engine and read from a stored snapshot (P-03).
 *
 * Input:  [repository] — the stored series. Output: a ViewModel Hilt can build.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NetWorthHistoryViewModel
    @Inject
    constructor(
        repository: NetWorthRepository,
    ) : ViewModel() {
        private val selected = MutableStateFlow(NetWorthHistoryUiState().range)
        private val _uiState = MutableStateFlow(NetWorthHistoryUiState())

        /** The screen's state; every field a `val`, replaced wholesale on each emission (ARC-004). */
        val uiState: StateFlow<NetWorthHistoryUiState> = _uiState.asStateFlow()

        init {
            selected
                .flatMapLatest { range -> repository.observeHistory(range) }
                .onEach { trend ->
                    _uiState.update { it.copy(trend = trend, isLoading = false, errorCode = null) }
                }
                // A read failure clears the chart and raises the banner rather than leaving the
                // previous window's line on screen under the new window's label. Nothing on a
                // sparkline says which dates it covers, so a stale one is not a lesser answer — it
                // is a wrong one the user cannot detect.
                .catch { cause ->
                    _uiState.update {
                        it.copy(trend = null, isLoading = false, errorCode = cause.toAppError().code)
                    }
                }
                .launchIn(viewModelScope)
        }

        /**
         * Handles a chip.
         * Why:    the range moves into [_uiState] immediately so the chip reads as selected on the
         *         frame it was tapped, while the series it names is still being read. `isLoading`
         *         goes back up with it: the line on screen belongs to the window the user just left,
         *         and showing it under the new label would be a lie the screen tells for one frame.
         * Result: the new window is observed and the state reflects the choice.
         * Input:  [event]. Output: none.
         * Changelog: 2026-08-30 — Created for issue 6.6.
         */
        fun onEvent(event: NetWorthHistoryEvent) {
            when (event) {
                is NetWorthHistoryEvent.RangeSelected -> {
                    if (event.range == selected.value) return
                    _uiState.update { it.copy(range = event.range, trend = null, isLoading = true) }
                    selected.value = event.range
                }
            }
        }

        /** Result: the range currently observed, for the tests that assert the switch. */
        internal val observedRange: NetWorthRange get() = selected.value
    }
