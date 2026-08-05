package com.aicfo.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.Clock
import com.aicfo.core.common.toAppError
import com.aicfo.core.model.Money
import com.aicfo.data.repository.NetWorthRepository
import com.aicfo.data.repository.QuickSetupRepository
import com.aicfo.domain.engines.quicksetup.BudgetEnvelope
import com.aicfo.domain.engines.quicksetup.BudgetNature
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
 * Changelog: 2026-07-27 — Issue 2.3: the spending split is real, read from the persisted budget.
 *            2026-08-01 — Issue 2.6: net worth is real, read from the daily snapshot (FR-ACC-005).
 *
 * **Safe-to-Spend is the last placeholder** — it needs the engine issue 5.2 owns. The other two are
 * real: the spending split since issue 2.3 (FR-ONB-002), and net worth since issue 2.6 (FR-ACC-005),
 * both observed as Flows so they update the moment the underlying data changes. What was *never*
 * placeholder is the shape: state in one immutable value, events in through one function, `Clock`
 * injected rather than read from the wall (TIM-001), and work on `viewModelScope` so it is
 * cancelled with the screen (ARC-006).
 *
 * Input:  [clock] — injected time, so a test can fix "now"; [quickSetupRepository] — the persisted
 *         budget envelopes; [netWorthRepository] — the stored daily snapshot (issue 2.6).
 *         Output: an observable screen state.
 */
@HiltViewModel
class DashboardViewModel
    @Inject
    constructor(
        private val clock: Clock,
        private val quickSetupRepository: QuickSetupRepository,
        private val netWorthRepository: NetWorthRepository,
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
            observeBudget()
            observeNetWorth()
        }

        /**
         * Keeps net worth in step with the stored snapshot (issue 2.6; FR-ACC-005).
         *
         * Why:    **`observeCurrent`, not `observeLatest`.** The stored daily snapshot is the
         *         historical record issue 6.6 charts; showing it here would leave the headline
         *         figure a day stale, so a user who deleted an account would watch the total not
         *         move. That is what happened the first time this was driven on a device.
         * Result: [uiState] carries the live figure. It stays `null` only until the first emission
         *         arrives, which the screen renders as "not worked out yet" rather than as ₹0 —
         *         a zero the app made up is what P-03 forbids, and it is indistinguishable from a
         *         real net worth of nothing.
         * Input:  none. Output: none (launches a collector).
         * Changelog: 2026-08-01 — Created for issue 2.6.
         */
        private fun observeNetWorth() {
            netWorthRepository.observeCurrent()
                .onEach { current -> _uiState.update { it.copy(netWorth = current.netWorth) } }
                .catch { failure -> _uiState.update { it.copy(errorCode = failure.toAppError().code) } }
                .launchIn(viewModelScope)
        }

        /**
         * Keeps the spending split in step with the stored budget (issue 2.3; FR-ONB-002).
         *
         * Why:    a Flow rather than a one-off read inside [load], because the budget can change
         *         while this screen is on top — the user finishes onboarding and lands straight
         *         here, and later the budgets editor (issue 4.4) will edit it behind them. A
         *         snapshot taken at construction would show the old figures until a manual refresh.
         * What:   collects the persisted envelopes on `viewModelScope` (ARC-006, so it dies with
         *         the screen) and folds them into the state.
         * Result: [uiState] carries the split, or `null` when the user has no budget — the screen
         *         then shows an empty state rather than a bar of zeroes (P-03). A read failure
         *         sets `errorCode` and leaves the split absent, because a dashboard that cannot
         *         read the budget must not imply there is none.
         * Input:  none. Output: none (launches a collector).
         * Changelog: 2026-07-27 — Created for issue 2.3.
         */
        private fun observeBudget() {
            quickSetupRepository.observeLatestEnvelopes()
                .onEach { envelopes -> _uiState.update { it.copy(spendSplit = envelopes.toSpendSplit()) } }
                .catch { failure -> _uiState.update { it.copy(errorCode = failure.toAppError().code) } }
                .launchIn(viewModelScope)
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
                // One placeholder left. Issue 5.2 replaces Safe-to-Spend with its engine; the state
                // shape stays the same. Net worth is no longer here: issue 2.6 made it real, and it
                // arrives through observeNetWorth(). The spending split went the same way in 2.3.
                // `clock.today()` is still read so the injected Clock stays genuinely on this path
                // rather than decorative (TIM-001).
                val today = clock.today()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        safeToSpend = Money(SAMPLE_SAFE_TO_SPEND_MINOR + today.dayOfMonth),
                    )
                }
            }
        }

        private companion object {
            const val SAMPLE_SAFE_TO_SPEND_MINOR = 12_500_00L
        }
    }

/**
 * Folds the persisted envelopes into the bar's three weights.
 * Why:    the chart takes three figures in a fixed order, and the stored rows are a list that may
 *         be missing a nature — a per-category budget from issue 4.4 carries none, and an old row
 *         may name one this build does not know. Missing means zero **weight in the bar**, which is
 *         not the same as the whole split being absent: that case is the `null` returned here.
 * Result: a [SpendSplit], or `null` when there are no envelopes at all — the empty state.
 * Input:  the receiver — the persisted envelopes. Output: `SpendSplit?`.
 * Changelog: 2026-07-27 — Created for issue 2.3.
 */
private fun List<BudgetEnvelope>.toSpendSplit(): SpendSplit? {
    if (isEmpty()) return null

    fun minorOf(nature: BudgetNature) = firstOrNull { it.nature == nature }?.amount?.minor ?: 0L

    return SpendSplit(
        needsMinor = minorOf(BudgetNature.NEED),
        wantsMinor = minorOf(BudgetNature.WANT),
        savingsMinor = minorOf(BudgetNature.INVEST),
    )
}
