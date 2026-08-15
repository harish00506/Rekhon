package com.aicfo.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.Clock
import com.aicfo.core.common.toAppError
import com.aicfo.core.model.Money
import com.aicfo.data.repository.BudgetRepository
import com.aicfo.data.repository.NetWorthRepository
import com.aicfo.data.repository.QuickSetupRepository
import com.aicfo.data.repository.TransactionRepository
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
 *         budget envelopes; [netWorthRepository] — the stored daily snapshot (issue 2.6);
 *         [budgetRepository] — per-category status and alerts (issue 5.1).
 *         Output: an observable screen state.
 */
@HiltViewModel
class DashboardViewModel
    @Inject
    constructor(
        private val clock: Clock,
        private val quickSetupRepository: QuickSetupRepository,
        private val netWorthRepository: NetWorthRepository,
        private val transactionRepository: TransactionRepository,
        private val budgetRepository: BudgetRepository,
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
            observeNature()
            observeCashFlow()
            observeBudgetStatus()
            observeBudgetAlerts()
            observeRecentActivity()
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
         * Observes what this month's money actually became (issue 4.3; SRS §8.3).
         *
         * Why:    a **second** stream beside [observeBudget], not a replacement for it. The budget is
         *         the plan and this is the outcome, and putting them on one screen is the only way
         *         the user finds out they disagree — which is the entire reason §8.3 separates
         *         "spent" from "converted".
         *
         *         A failure clears the section rather than raising a banner. The dashboard's other
         *         figures are still true, and a month's classification is the newest and least
         *         load-bearing thing on the screen.
         * Result: [uiState] carries the breakdown, or `null`.
         * Input:  none. Output: none (launches a collector).
         * Changelog: 2026-08-10 — Created for issue 4.3.
         */
        private fun observeNature() {
            transactionRepository.observeNatureBreakdown()
                .onEach { breakdown -> _uiState.update { it.copy(natureBreakdown = breakdown) } }
                .catch { _uiState.update { it.copy(natureBreakdown = null) } }
                .launchIn(viewModelScope)
        }

        /**
         * Keeps this month's cash flow in step with the ledger (issue 5.1; FR-DASH-*).
         *
         * Why:    a raw ledger aggregation, not an engine result — see
         *         [TransactionRepository.observeMonthCashFlow]'s own doc comment for why it carries
         *         no provenance to show.
         * Result: [uiState] carries the figure, or `null`, following [natureBreakdown]'s rule: a
         *         failed read clears the section rather than raising a banner, because the newest
         *         card on the screen must not be able to obscure the others.
         * Input:  none. Output: none (launches a collector).
         * Changelog: 2026-08-15 — Created for issue 5.1.
         */
        private fun observeCashFlow() {
            transactionRepository.observeMonthCashFlow()
                .onEach { summary -> _uiState.update { it.copy(cashFlow = summary) } }
                .catch { _uiState.update { it.copy(cashFlow = null) } }
                .launchIn(viewModelScope)
        }

        /**
         * Keeps the budget summary in step with the stored budgets (issue 5.1; FR-DASH-*).
         *
         * Why:    a **separate** collector from [observeBudgetAlerts], even though both read
         *         `BudgetRepository`, for the reason [observeNetWorth] and [observeBudget] already
         *         are separate: one failing must not blank the other.
         * Result: [uiState] carries the rows [DashboardUiState.budgetTotals] folds, or a read failure
         *         sets [DashboardUiState.errorCode] — unlike [observeCashFlow], a broken read of the
         *         plan itself is surfaced, matching how `:feature:budgets` treats its own budgets
         *         stream.
         * Input:  none. Output: none (launches a collector).
         * Changelog: 2026-08-15 — Created for issue 5.1.
         */
        private fun observeBudgetStatus() {
            budgetRepository.observeBudgets()
                .onEach { rows -> _uiState.update { it.copy(budgets = rows) } }
                .catch { failure -> _uiState.update { it.copy(errorCode = failure.toAppError().code) } }
                .launchIn(viewModelScope)
        }

        /**
         * Keeps the "needs attention" line in step with the alert bands crossed this month (issue
         * 5.1; FR-DASH-*, FR-BUD-004).
         *
         * Why:    a failure clears the list rather than raising a banner — the same choice
         *         [observeCashFlow] and [observeNature] make, and for the same reason: an alert line
         *         is advisory, and the plan it is advising about is what [observeBudgetStatus]
         *         already surfaces a real error for.
         * Result: [uiState] carries the alerts, each with the rule that fired (P-02) — rendered by
         *         the screen the same way `:feature:budgets`' own alert banner does.
         * Input:  none. Output: none (launches a collector).
         * Changelog: 2026-08-15 — Created for issue 5.1.
         */
        private fun observeBudgetAlerts() {
            budgetRepository.observeAlerts()
                .onEach { rows -> _uiState.update { it.copy(budgetAlerts = rows) } }
                .catch { _uiState.update { it.copy(budgetAlerts = emptyList()) } }
                .launchIn(viewModelScope)
        }

        /**
         * Keeps the recent-activity preview in step with the ledger (issue 5.1; FR-DASH-*).
         *
         * Why:    bounded to [RECENT_ACTIVITY_LIMIT] rows — a preview, not the list. The full ledger
         *         is one tap away through `DashboardActions.onNavigateToTransactions`, which is the
         *         same "view all" the reason [TransactionRepository.observeRecent]'s own doc comment
         *         gives for why this is not a re-run of the window issue 3.6 removed.
         * Result: [uiState] carries the rows, or a failure clears the section — the same choice
         *         [observeCashFlow] makes, since this too is advisory beside the figures above it.
         * Input:  none. Output: none (launches a collector).
         * Changelog: 2026-08-15 — Created for issue 5.1.
         */
        private fun observeRecentActivity() {
            transactionRepository.observeRecent(RECENT_ACTIVITY_LIMIT)
                .onEach { rows -> _uiState.update { it.copy(recentActivity = rows) } }
                .catch { _uiState.update { it.copy(recentActivity = null) } }
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

            /**
             * How many rows [observeRecentActivity] shows (issue 5.1).
             *
             * Not a financial threshold, so §29's data-not-code rule does not reach it — it is the
             * size of a preview. Small enough to read at a glance on the screen the app opens to;
             * the Transactions screen is where "how many" stops being a question.
             */
            const val RECENT_ACTIVITY_LIMIT = 5
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
