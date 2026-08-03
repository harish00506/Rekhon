package com.aicfo.feature.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.toAppError
import com.aicfo.core.model.Money
import com.aicfo.core.model.Transaction
import com.aicfo.core.model.TransactionSource
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
        // Issue 3.4 note for the reader: this class has no `Clock`, on purpose. "Is this row
        // scheduled?" is answered by which flow it arrived on, and the repository — which has the
        // injected clock — decides that. A second answer computed here could disagree with the one
        // the balances were derived from (TIM-001).
        private val _uiState = MutableStateFlow(TransactionsUiState())

        /**
         * The rows as the repository last emitted them, ungrouped and unfiltered (issue 3.5).
         *
         * Why: the screen's state is now a function of the data **and** of what the user has chosen,
         *      and the two change independently — a chip tap must re-render without waiting for a
         *      database emission, and a database emission must not silently drop the chosen filter.
         *      Keeping the raw lists is what lets [render] be the single place that combines them.
         *      It is also what makes `availableSources` derivable from *unfiltered* rows, which is
         *      the difference between a chip row the user can leave and one they cannot.
         */
        private var snapshot = ListSnapshot()

        /** The source the user has narrowed to, or `null` for all (issue 3.5). */
        private var sourceFilter: TransactionSource? = null

        /** The row whose detail sheet is open, by id, or `null` (issue 3.5). */
        private var selectedId: String? = null

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
                // Issue 3.4: a second flow, not a widened window (FR-TXN-010). Keeping the two
                // apart is what stops a scheduled payment being counted in a day total — the
                // scheduled rows are simply never in `days`, so no filter has to be remembered.
                repository.observeUpcoming(),
                accounts.observeAccounts(includeArchived = true),
            ) { transactions, upcoming, accountList ->
                ListSnapshot(
                    recent = transactions,
                    upcoming = upcoming,
                    accountNames = accountList.associate { it.id to it.name },
                )
            }
                .onEach { latest ->
                    snapshot = latest
                    render()
                }
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
         *            2026-08-03 — Issue 3.5: the source filter and the detail sheet.
         */
        fun onEvent(event: TransactionsEvent) {
            when (event) {
                is TransactionsEvent.Delete -> delete(event.transactionId)
                TransactionsEvent.DismissError -> _uiState.update { it.copy(errorCode = null) }
                is TransactionsEvent.SourceFilterSelected -> {
                    sourceFilter = event.source
                    render()
                }
                is TransactionsEvent.RowTapped -> {
                    selectedId = event.transactionId
                    render()
                }
                TransactionsEvent.DetailDismissed -> {
                    selectedId = null
                    render()
                }
            }
        }

        /**
         * Builds the screen's state from the last data and the user's current choices (issue 3.5).
         *
         * Why:    **the filter is applied before grouping**, which is not a detail — [TransactionDay]
         *         computes its total by folding the rows under it, so filtering first makes the day
         *         totals recompute to the filtered rows for free. Filtering after grouping would
         *         leave every header stating a total for transactions no longer beneath it.
         *
         *         `availableSources` is deliberately read from the **unfiltered** lists: derived from
         *         what is on screen, choosing a chip would remove every other chip and strand the
         *         user with no way back to "All". Ordered by [TransactionSource.entries] rather than
         *         by first appearance, so chips keep their positions as rows arrive.
         *
         *         `errorCode` is carried over rather than cleared. A read that failed is still
         *         failed after a chip tap, and re-rendering is not evidence that it recovered.
         * Result: `_uiState` holds a value consistent with both the data and the choices.
         * Input:  none — reads [snapshot], [sourceFilter] and [selectedId]. Output: none.
         * Changelog: 2026-08-03 — Created for issue 3.5.
         */
        private fun render() {
            val filter = sourceFilter
            val recent = snapshot.recent.filterBySource(filter)
            val upcoming = snapshot.upcoming.filterBySource(filter)
            _uiState.update { previous ->
                previous.copy(
                    isLoading = false,
                    days = recent.groupIntoDays(),
                    upcoming = upcoming.groupIntoDays(soonestFirst = true),
                    accountNames = snapshot.accountNames,
                    sourceFilter = filter,
                    availableSources = snapshot.sourcesPresent(),
                    // Resolved from the unfiltered rows: a sheet opened on a row must not close
                    // itself because the user then narrowed the list to something else.
                    detail = selectedId?.let(snapshot::findById),
                )
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
 * The three streams the list is built from, as one value (issue 3.5).
 *
 * Why:  the ViewModel has to re-render on a chip tap, with no database emission to carry the data
 *       in. Holding the last emission as one immutable value — rather than three mutable fields —
 *       means a re-render cannot mix rows from one emission with account names from another.
 * What: the actuals, the scheduled rows, and the id → name map, all exactly as they arrived.
 * Result: `render()` has one input, and every state it produces is internally consistent.
 * Changelog: 2026-08-03 — Created for issue 3.5.
 *
 * Input:  [recent] — actuals, newest first; [upcoming] — scheduled, soonest first;
 *         [accountNames] — account id → display name.
 * Output: an immutable value.
 */
internal data class ListSnapshot(
    val recent: List<Transaction> = emptyList(),
    val upcoming: List<Transaction> = emptyList(),
    val accountNames: Map<String, String> = emptyMap(),
) {
    /**
     * Every source present across both lists, in enum order (issue 3.5; FR-TXN-009).
     * Why:    the chip row's contents. Both lists, because a profile whose only receipt is a
     *         scheduled one still has receipts to filter by.
     * Result: the distinct sources, ordered by [TransactionSource.entries]; empty for no rows.
     * Input:  none. Output: `List<TransactionSource>`.
     * Changelog: 2026-08-03 — Created for issue 3.5.
     */
    fun sourcesPresent(): List<TransactionSource> {
        val present = (recent + upcoming).mapTo(mutableSetOf()) { it.source }
        return TransactionSource.entries.filter { it in present }
    }

    /**
     * Finds one transaction by id across both lists (issue 3.5).
     * Why:    the detail sheet is opened from a row id, and a row can be an actual or a scheduled
     *         one. For a transfer the id is one leg's, which resolves here like any other.
     * Result: the transaction, or `null` when the id names nothing on screen — a row deleted while
     *         its sheet was open closes the sheet rather than showing a stale copy.
     * Input:  [id]. Output: `Transaction?`.
     * Changelog: 2026-08-03 — Created for issue 3.5.
     */
    fun findById(id: String): Transaction? = recent.firstOrNull { it.id == id } ?: upcoming.firstOrNull { it.id == id }
}

/**
 * Narrows a list to one source, or leaves it alone (issue 3.5; FR-TXN-009).
 * Why:    stated once so the actuals and the scheduled rows cannot drift apart — a filter that
 *         applied to one list and not the other would show a "Scheduled" group contradicting the
 *         chip above it.
 * Result: the rows whose source matches, or the receiver unchanged when [source] is `null`.
 * Input:  the receiver; [source] — the chosen source, or `null` for all.
 * Output: `List<Transaction>`.
 * Changelog: 2026-08-03 — Created for issue 3.5.
 */
internal fun List<Transaction>.filterBySource(source: TransactionSource?): List<Transaction> =
    if (source == null) this else filter { it.source == source }

/**
 * Groups transactions into days, newest day first.
 * Why:    FR-TXN-007 asks for "grouping by day with daily totals", and it is done here rather than
 *         in the composable so a test can assert it without rendering anything. The grouping key is
 *         `bookedOn` — the profile-zone calendar day (TIM-002) — never the instant: deriving the day
 *         from `occurredAtUtcMillis` in the UI layer would need a time zone the UI does not have,
 *         and would put a 23:30 IST spend in the wrong day.
 * Result: one [TransactionDay] per distinct booked date, ordered newest first (or soonest first for
 *         a schedule), each keeping the repository's order within the day. An empty input gives an
 *         empty list.
 * Input:  the receiver — the repository's stream, already ordered; [soonestFirst] — `true` for the
 *         scheduled group (issue 3.4), where the next thing due belongs at the top rather than the
 *         furthest-away one.
 * Output: `List<TransactionDay>`.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 *            2026-08-03 — Issue 3.4: [soonestFirst], so the same grouping serves both halves of the
 *            screen rather than the scheduled group getting a near-copy of this function.
 */
internal fun List<Transaction>.groupIntoDays(soonestFirst: Boolean = false): List<TransactionDay> =
    groupBy { it.bookedOn }
        // ISO `yyyy-MM-dd` sorts lexicographically in date order, which is the whole reason TIM-002
        // stores dates that way rather than as `dd/MM/yyyy` or a midnight timestamp.
        .toSortedMap(if (soonestFirst) naturalOrder() else reverseOrder())
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
