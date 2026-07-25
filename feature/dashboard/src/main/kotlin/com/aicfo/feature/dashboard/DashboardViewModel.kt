package com.aicfo.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.Clock
import com.aicfo.core.model.Money
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Holds the dashboard's state (ARC-004, ARC-003).
 *
 * Why:  the reference implementation of the pattern every screen in this app will copy — one
 *       `StateFlow<UiState>` out, one `onEvent(Event)` in, and no other public surface. Keeping it
 *       to exactly that shape is what stops screens from growing ad-hoc callbacks and mutable
 *       state that nothing can test.
 * What: exposes [uiState] and handles [DashboardEvent]s.
 * Result: a screen whose every state is reachable and assertable in a unit test.
 * Changelog: 2026-07-25 — Created for issue 1.10 as the ARC-004 reference implementation.
 *
 * The figures are placeholders until issues 5.1/5.2 compute them from real engines. What is *not*
 * placeholder is the shape: state in one immutable value, events in through one function, `Clock`
 * injected rather than read from the wall (TIM-001), and work on `viewModelScope` so it is
 * cancelled with the screen (ARC-006).
 *
 * Input:  [clock] — injected time, so a test can fix "now". Output: an observable screen state.
 */
@HiltViewModel
class DashboardViewModel
    @Inject
    constructor(
        private val clock: Clock,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(DashboardUiState())

        /**
         * The screen's state.
         * Result: emits the current [DashboardUiState] and every update. Read-only to callers —
         *         `asStateFlow()` prevents a composable from writing to it (ARC-004).
         */
        val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

        init {
            load()
        }

        /**
         * Handles something the user did.
         * Why:    one entry point, so the sealed interface's exhaustiveness guarantees no
         *         interaction is silently unhandled.
         * Result: applies the event. Input: [event]. Output: none.
         */
        fun onEvent(event: DashboardEvent) {
            when (event) {
                DashboardEvent.Refresh -> load()
                DashboardEvent.DismissError -> _uiState.update { it.copy(errorCode = null) }
            }
        }

        /**
         * Loads the dashboard figures.
         * Why:    emits the loading state first so the UI has something honest to show while work
         *         is in flight — a screen that jumps straight to numbers cannot show a spinner, and
         *         the loading state is part of what §21.5 asks tests to assert.
         * Result: updates [uiState]. Input: none. Output: none (launches on `viewModelScope`).
         */
        private fun load() {
            _uiState.update { it.copy(isLoading = true, errorCode = null) }
            viewModelScope.launch {
                // Placeholder figures. Issues 5.1/5.2 replace this with the Safe-to-Spend and
                // net-worth engines; the state shape stays the same. `clock.today()` is read here
                // so the injected Clock is genuinely on this path rather than decorative.
                val today = clock.today()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        safeToSpend = Money(SAMPLE_SAFE_TO_SPEND_MINOR),
                        netWorth = Money(SAMPLE_NET_WORTH_MINOR),
                        spendSplit =
                            SpendSplit(
                                needsMinor = SAMPLE_NEEDS_MINOR,
                                wantsMinor = SAMPLE_WANTS_MINOR,
                                // Varies with the day only so the placeholder is visibly live
                                // rather than a frozen constant during manual testing.
                                savingsMinor = SAMPLE_SAVINGS_MINOR + today.dayOfMonth,
                            ),
                    )
                }
            }
        }

        private companion object {
            const val SAMPLE_SAFE_TO_SPEND_MINOR = 12_500_00L
            const val SAMPLE_NET_WORTH_MINOR = 4_82_350_00L
            const val SAMPLE_NEEDS_MINOR = 32_000_00L
            const val SAMPLE_WANTS_MINOR = 18_500_00L
            const val SAMPLE_SAVINGS_MINOR = 25_000_00L
        }
    }
