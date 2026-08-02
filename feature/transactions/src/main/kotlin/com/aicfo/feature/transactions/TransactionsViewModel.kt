package com.aicfo.feature.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.toAppError
import com.aicfo.core.model.Money
import com.aicfo.core.model.Transaction
import com.aicfo.data.repository.AccountRepository
import com.aicfo.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
        private val repository: TransactionRepository,
        accounts: AccountRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(TransactionsUiState())

        /**
         * The screen's state.
         * Result: emits the current [TransactionsUiState] and every update. Read-only to callers.
         */
        val uiState: StateFlow<TransactionsUiState> = _uiState.asStateFlow()

        init {
            // Accounts are read for their **names** only: a transfer row says "HDFC Savings → Cash
            // Wallet", and a transaction carries account *ids*. Archived accounts are included
            // (`includeArchived = true`) because a transfer into an account closed afterwards must
            // still name it rather than falling back to a raw id (FR-ACC-007).
            combine(
                repository.observeRecent(),
                accounts.observeAccounts(includeArchived = true),
            ) { transactions, accountList ->
                TransactionsUiState(
                    isLoading = false,
                    days = transactions.groupIntoDays(),
                    accountNames = accountList.associate { it.id to it.name },
                )
            }
                .onEach { state -> _uiState.update { state } }
                // `toAppError` rather than the throwable's own message: that message may name a file
                // path, a column or an amount, and P-01 bans all three from anything user-visible.
                .catch { failure ->
                    _uiState.update { it.copy(isLoading = false, errorCode = failure.toAppError().code) }
                }
                .launchIn(viewModelScope)
        }

        /**
         * Handles something the user did.
         * Why:    one entry point, so the sealed interface's exhaustiveness guarantees no interaction
         *         is silently unhandled.
         * Result: applies the event. Input: [event]. Output: none.
         * Changelog: 2026-08-02 — Created for issue 3.2, which gave this screen its first action.
         */
        fun onEvent(event: TransactionsEvent) {
            when (event) {
                is TransactionsEvent.Delete -> delete(event.transactionId)
                TransactionsEvent.DismissError -> _uiState.update { it.copy(errorCode = null) }
            }
        }

        /**
         * Removes a row, and its sibling leg when it is half of a transfer (FR-TXN-003).
         *
         * Why:    the id goes to the store as-is. **This is deliberately not "if it is a transfer,
         *         delete the transfer"** — the screen does not branch on the row's kind, because the
         *         atomicity guarantee belongs in one place and that place is the repository. No
         *         refresh follows: `observeRecent` is a live query, so the list updates itself.
         * Result: the row leaves the list, or `errorCode` is set and it stays.
         * Input:  [transactionId]. Output: none (launches on `viewModelScope`).
         */
        private fun delete(transactionId: String) {
            viewModelScope.launch {
                when (val outcome = repository.delete(transactionId)) {
                    is Ok -> Unit
                    is Err -> _uiState.update { it.copy(errorCode = outcome.error.code) }
                }
            }
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
        .map { (isoDate, transactions) -> TransactionDay(isoDate = isoDate, rows = transactions.toRows()) }

/**
 * Collapses each transfer's two legs into one row (issue 3.2; FR-TXN-003).
 *
 * Why:    FR-TXN-003 calls a transfer "a single logical record ... not two unlinked transactions".
 *         Rendering the rows as they are stored would show a ₹5,000 transfer twice, reading as
 *         ₹10,000 of activity, and would offer two delete buttons for one thing. The pairing key is
 *         `transferId`, never a match on amount and date — two genuinely separate transactions of
 *         the same size on one day are not a transfer and must not be merged into one.
 *
 *         **A lone leg stays a row of its own.** It should not happen — the legs are written and
 *         deleted together — but it can be *observed*: the recent window is 30 days, so a transfer
 *         straddling the boundary yields one leg here. Hiding it would make money vanish from the
 *         list; showing it is the honest failure mode.
 * Result: ordinary rows in their original order, each transfer once, positioned where its first leg
 *         appeared. An empty input gives an empty list.
 * Input:  the receiver — one day's transactions, newest first.
 * Output: `List<TransactionRow>`.
 * Changelog: 2026-08-02 — Created for issue 3.2.
 */
internal fun List<Transaction>.toRows(): List<TransactionRow> {
    val legsByTransfer = filter { it.transferId != null }.groupBy { it.transferId }
    val emitted = mutableSetOf<String>()
    return mapNotNull { transaction ->
        val transferId = transaction.transferId
        val legs = legsByTransfer[transferId]
        when {
            transferId == null || legs == null || legs.size < 2 -> TransactionRow.Single(transaction)
            // The pair renders at the position of whichever leg the repository returned first, so the
            // row keeps its place in the day's ordering rather than jumping to the top.
            !emitted.add(transferId) -> null
            else -> legs.toTransferRow(transferId)
        }
    }
}

/**
 * Builds the collapsed row for one transfer's legs.
 * Why:    the direction is read from the **amount's sign**, not from `type`, because the sign is what
 *         the balances were actually derived from — if the two ever disagreed, this row would still
 *         match the figures the accounts screen shows. `TransferTest` is what stops them disagreeing.
 * Result: a [TransactionRow.TransferPair] with a positive amount.
 * Input:  the receiver — the legs, at least two; [transferId]. Output: the row.
 * Changelog: 2026-08-02 — Created for issue 3.2.
 */
private fun List<Transaction>.toTransferRow(transferId: String): TransactionRow.TransferPair {
    val out = first { it.amount < Money.ZERO }
    val into = first { it.amount > Money.ZERO }
    return TransactionRow.TransferPair(
        transferId = transferId,
        // Either leg identifies the transfer to the store; the outgoing one is chosen so the id is
        // deterministic rather than depending on the order the query returned.
        id = out.id,
        outAccountId = out.accountId,
        inAccountId = into.accountId,
        amount = into.amount,
    )
}
