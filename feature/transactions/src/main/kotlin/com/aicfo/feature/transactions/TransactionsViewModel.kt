package com.aicfo.feature.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.toAppError
import com.aicfo.core.model.Transaction
import com.aicfo.data.repository.TransactionRepository
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
 * Holds the recent-transactions list's state (issue 3.1; ARC-003, ARC-004).
 *
 * Why:  the screen this replaces was a placeholder from issue 1.10 with no state to hold — its doc
 *       comment said "issue 3.6 adds the triad when there is something to hold". There is now: a
 *       capture path whose result the user cannot see is one they cannot trust, so the moment
 *       transactions can be created, they have to be visible.
 * What: exposes [uiState], grouping the repository's stream by booked day.
 * Result: a saved transaction appears under today's header, and the day's total is the sum of it.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 *
 * **Deliberately smaller than FR-TXN-007.** Search, filters, infinite scroll and bulk edit are issue
 * 3.6's, and the repository behind this reads a fixed 30-day window rather than everything. This
 * class grows when that issue lands, not before.
 *
 * Input:  [repository] — the transactions store. Output: an observable screen state.
 */
@HiltViewModel
class TransactionsViewModel
    @Inject
    constructor(
        repository: TransactionRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(TransactionsUiState())

        /**
         * The screen's state.
         * Result: emits the current [TransactionsUiState] and every update. Read-only to callers.
         */
        val uiState: StateFlow<TransactionsUiState> = _uiState.asStateFlow()

        init {
            repository.observeRecent()
                .onEach { transactions ->
                    _uiState.update { it.copy(isLoading = false, days = transactions.groupIntoDays()) }
                }
                // `toAppError` rather than the throwable's own message: that message may name a file
                // path, a column or an amount, and P-01 bans all three from anything user-visible.
                .catch { failure ->
                    _uiState.update { it.copy(isLoading = false, errorCode = failure.toAppError().code) }
                }
                .launchIn(viewModelScope)
        }
    }

/**
 * Groups transactions into days, newest day first.
 * Why:    FR-TXN-007 asks for "grouping by day with daily totals", and it is done here rather than
 *         in the composable so a test can assert it without rendering anything. The grouping key is
 *         `bookedOn` — the profile-zone calendar day (TIM-002) — never the instant: deriving the day
 *         from `occurredAtUtcMillis` in the UI layer would need a time zone the UI does not have,
 *         and would put a 23:30 IST spend in the wrong day.
 * Result: one [TransactionDay] per distinct booked date, ordered newest first, each keeping the
 *         repository's newest-first order within the day. An empty input gives an empty list.
 * Input:  the receiver — the repository's stream, already newest first.
 * Output: `List<TransactionDay>`.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 */
internal fun List<Transaction>.groupIntoDays(): List<TransactionDay> =
    groupBy { it.bookedOn }
        // ISO `yyyy-MM-dd` sorts lexicographically in date order, which is the whole reason TIM-002
        // stores dates that way rather than as `dd/MM/yyyy` or a midnight timestamp.
        .toSortedMap(reverseOrder())
        .map { (isoDate, transactions) -> TransactionDay(isoDate = isoDate, transactions = transactions) }
