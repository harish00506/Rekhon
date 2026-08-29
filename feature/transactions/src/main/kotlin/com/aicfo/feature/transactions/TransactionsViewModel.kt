package com.aicfo.feature.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.getOrNull
import com.aicfo.core.common.toAppError
import com.aicfo.core.model.CategoryNature
import com.aicfo.core.model.Money
import com.aicfo.core.model.Transaction
import com.aicfo.core.model.TransactionType
import com.aicfo.data.repository.AccountRepository
import com.aicfo.data.repository.FilteredTransaction
import com.aicfo.data.repository.ReceiptRepository
import com.aicfo.data.repository.RecurringRepository
import com.aicfo.data.repository.TransactionFilter
import com.aicfo.data.repository.TransactionRepository
import com.aicfo.domain.engines.nature.NatureVerdict
import com.aicfo.domain.engines.recurring.RecurringSeries
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
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
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // One private handler per event group (filter, bulk, recurring):
// the count tracks FR-TXN-006/007/008, and merging them would push `onEvent` past its own ceiling.
@HiltViewModel
class TransactionsViewModel
    @Inject
    constructor(
        private val repository: TransactionRepository,
        private val recurring: RecurringRepository,
        private val receipts: ReceiptRepository,
        accounts: AccountRepository,
    ) : ViewModel() {
        // Issue 3.4 note for the reader: this class has no `Clock`, on purpose. "Is this row
        // scheduled?" is answered by which flow it arrived on, and the repository — which has the
        // injected clock — decides that. A second answer computed here could disagree with the one
        // the balances were derived from (TIM-001).
        private val _uiState = MutableStateFlow(TransactionsUiState())

        /**
         * What the list is querying with (issue 3.6; FR-TXN-007).
         *
         * Why: a `StateFlow` rather than a plain field, because [items] has to *re-query* when it
         *      changes — `flatMapLatest` needs something to observe. Declared before [items] so it
         *      exists when that property initialises; Kotlin evaluates them in source order.
         */
        private val filterFlow = MutableStateFlow(TransactionFilter())

        /**
         * The ids the user has selected (issue 3.6; FR-TXN-008).
         *
         * A flow for the same reason [filterFlow] is: a row renders its own selected state, so a tap
         * must reach the paged stream. Combining it there rather than re-querying means selecting a
         * row costs no database read.
         */
        private val selectionFlow = MutableStateFlow(emptySet<String>())

        /**
         * The screen's state — everything except the paged rows (issue 3.6).
         * Result: emits the current [TransactionsUiState] and every update. Read-only to callers.
         */
        val uiState: StateFlow<TransactionsUiState> = _uiState.asStateFlow()

        /**
         * The pager's own stream, cached (issue 3.6; FR-TXN-007).
         *
         * Why:    **`cachedIn` belongs here, before [items]'s `combine`, and putting it after was a
         *         crash.** `combine` re-emits its latest values whenever *any* source moves, so a
         *         changed selection would hand the list the same `PagingData` a second time — and an
         *         uncached one may be collected exactly once
         *         (`IllegalStateException: Attempt to collect twice from pageEventFlow`). Caching
         *         first makes the stream multicast, which is what lets it be re-emitted safely; it
         *         is also what keeps the loaded pages across a rotation.
         *
         *         **No unit test caught this**, and none could have: the fakes hand back
         *         `PagingData.from(…)`, a static value that *is* safely re-collectable, while a real
         *         `Pager` is not. The emulator run found it on the second visit to the screen — the
         *         case §9's "a green build does not close the issue" exists for.
         *
         *         `flatMapLatest`, not `map`: a changed facet must *switch* which query is observed
         *         and cancel the previous one, or the old query's pages race into the new list.
         */
        private val pagedRows: Flow<PagingData<FilteredTransaction>> =
            filterFlow
                .flatMapLatest { filter -> repository.observeFiltered(filter) }
                .cachedIn(viewModelScope)

        /**
         * Each day's total, re-queried on every filter change (issue 3.6; FR-TXN-007).
         *
         * Read from the database rather than folded from the loaded page, because a page boundary
         * can fall inside a day — see [TransactionListItem.DayHeader].
         */
        private val dayTotals: Flow<Map<String, Money>> =
            filterFlow.flatMapLatest { filter -> repository.observeDayTotals(filter) }

        /**
         * The paged rows, with their day headers inserted (issue 3.6; FR-TXN-007).
         *
         * Why:    a second flow rather than a field on [uiState], because `PagingData` is a stream of
         *         load events and not a value: every `copy` of an immutable state class holding one
         *         would hand the list a fresh stream and restart it from page one, losing the user's
         *         scroll position on a keystroke.
         *
         *         The three sources are combined **after** [pagedRows] has been cached — see the
         *         note there for why the other order crashes.
         * Result: emits day headers and rows, newest first. The header's total is the database's,
         *         never a fold over the loaded page — see [TransactionListItem.DayHeader].
         */
        val items: Flow<PagingData<TransactionListItem>> =
            // `combine`, so the list re-renders as soon as the rows, the totals **or** the selection
            // move. The totals are a small keyed map and the selection is a set of ids, so
            // recombining costs nothing next to a re-query.
            combine(pagedRows, dayTotals, selectionFlow) { page, totals, selection ->
                page.toListItems(totals, selection)
            }

        init {
            // Accounts are read for their **names** only: a transfer row says "HDFC Savings → Cash
            // Wallet", and a transaction carries account *ids*. Archived accounts are included
            // (`includeArchived = true`) because a transfer into an account closed afterwards must
            // still name it rather than falling back to a raw id (FR-ACC-007).
            combine(
                // Issue 3.4: a separate flow from the paged list (FR-TXN-010). Keeping the two apart
                // is what stops a scheduled payment being counted in a day total — the scheduled
                // rows are simply never in the paged stream, so no filter has to be remembered.
                repository.observeUpcoming(),
                accounts.observeAccounts(includeArchived = true),
                repository.observeSources(),
                repository.observeTags(),
                repository.observeCategories(),
            ) { upcoming, accountList, sources, tags, categories ->
                { previous: TransactionsUiState ->
                    previous.copy(
                        isLoading = false,
                        upcoming = upcoming.groupIntoDays(soonestFirst = true),
                        accountNames = accountList.associate { it.id to it.name },
                        availableSources = sources,
                        availableTags = tags,
                        categories = categories,
                    )
                }
            }
                .onEach { apply -> _uiState.update(apply) }
                // `toAppError` rather than the throwable's own message: that message may name a file
                // path, a column or an amount, and P-01 bans all three from anything user-visible.
                .catch { failure ->
                    _uiState.update { it.copy(isLoading = false, errorCode = failure.toAppError().code) }
                }
                .launchIn(viewModelScope)

            // Issue 3.7: collected separately rather than folded into the `combine` above, and not
            // only because five is the typed overload's limit. A proposal is *optional* — if the
            // detector or its query fails, the section renders nothing and the ledger above it is
            // untouched, where a sixth source in that combine would take the whole screen down with
            // it (P-04). It is also why the catch sets no `errorCode`: there is nothing the user
            // could do about a suggestion that did not arrive, and a banner over their transactions
            // for a feature they never asked for is worse than its absence.
            recurring.observeSuggestions()
                .onEach { series -> _uiState.update { it.copy(suggestions = series) } }
                .catch { _uiState.update { it.copy(suggestions = emptyList()) } }
                .launchIn(viewModelScope)
        }

        /**
         * Handles something the user did.
         * Why:    one entry point, so the sealed interface's exhaustiveness guarantees no interaction
         *         is silently unhandled. The two nested groups are handled as groups, which is what
         *         keeps this function under detekt's complexity ceiling (§21.6).
         * Result: applies the event. Input: [event]. Output: none.
         * Changelog: 2026-08-02 — Created for issue 3.2, which gave this screen its first action.
         *            2026-08-03 — Issue 3.5: the source filter and the detail sheet.
         *            2026-08-04 — Issue 3.6: search, the filter sheet and bulk selection.
         */
        fun onEvent(event: TransactionsEvent) {
            when (event) {
                is FilterEvent -> applyFilter(event)
                is BulkEvent -> applyBulk(event)
                is RecurringEvent -> applyRecurring(event)
                is TransactionsEvent.Delete -> delete(event.transactionId)
                TransactionsEvent.DismissError -> _uiState.update { it.copy(errorCode = null) }
                is TransactionsEvent.SearchChanged -> setFilter(currentFilter.copy(query = event.query))
                is TransactionsEvent.SourceFilterSelected ->
                    setFilter(currentFilter.copy(source = event.source))
                is TransactionsEvent.RowTapped -> openDetail(event.transaction)
                TransactionsEvent.DetailDismissed ->
                    _uiState.update { it.copy(detail = null, detailReceipt = null, detailNature = null) }

                is TransactionsEvent.ReceiptDeleted -> deleteReceipt(event.attachmentId)
                is TransactionsEvent.NatureOverridden -> overrideNature(event.nature)
            }
        }

        /**
         * Works out what this row's money became, for the open sheet (issue 4.3; §8.3, P-02).
         *
         * Why:    fetched **when the sheet opens**, not carried on every row of the list, for the
         *         same reason the receipt is: §8.3.1 branches on five joins, and doing them per row
         *         of a paged ledger would be a query storm for a label most rows never show.
         *
         *         Guarded on the sheet still showing the same transaction, like the receipt above —
         *         the read is off the main thread, and a user who closed one sheet and opened
         *         another must not be handed the previous row's nature.
         *
         *         **A failure clears the section rather than raising a banner.** The sheet's other
         *         fields are still true and still useful; an error over a label the user did not ask
         *         for would teach them to dismiss banners.
         * Result: sets `detailNature`, or leaves it null. Input: [transactionId].
         * Output: none (launches on `viewModelScope`).
         * Changelog: 2026-08-10 — Created for issue 4.3.
         */
        private fun loadNature(transactionId: String) {
            viewModelScope.launch {
                val verdict = (repository.natureOf(transactionId) as? Ok<NatureVerdict>)?.value
                _uiState.update { state ->
                    if (state.detail?.id != transactionId) state else state.copy(detailNature = verdict)
                }
            }
        }

        /**
         * Records or withdraws the user's nature correction (issue 4.3; §8.3, P-07).
         * Why:    re-reads afterwards rather than assuming the answer, because withdrawing an
         *         override (`null`) hands the transaction back to §8.3.1 and **this class does not
         *         know what the rules will say** — only the repository and the engine do. Assuming
         *         would put a second opinion about nature in the UI layer, which is the one place
         *         P-03 says numbers must never be decided.
         * Result: the sheet shows the new verdict. Input: [nature] — what the user chose, or `null`
         *         to withdraw. Output: none (launches on `viewModelScope`).
         * Changelog: 2026-08-10 — Created for issue 4.3.
         */
        private fun overrideNature(nature: CategoryNature?) {
            val transactionId = _uiState.value.detail?.id ?: return
            viewModelScope.launch {
                when (val outcome = repository.setNature(transactionId, nature)) {
                    is Ok -> loadNature(transactionId)
                    is Err -> _uiState.update { it.copy(errorCode = outcome.error.code) }
                }
            }
        }

        /**
         * Opens the detail sheet and looks for the row's receipt (issue 3.8; FR-OCR-005).
         * Why:    the image is fetched **when the sheet opens**, not carried on every row of the
         *         list: a paged ledger would otherwise decrypt a photograph for each visible
         *         transaction, and P-01 says the plaintext should exist for as long as it is being
         *         looked at and no longer.
         *
         *         `first()` rather than a collected flow: the sheet is short-lived and an attachment
         *         does not change while it is open — the one thing that *can* change it is the
         *         delete button, which updates the state itself.
         * Result: the sheet opens immediately; the receipt appears when it has been decrypted, or
         *         never, which is what a transaction with no receipt looks like.
         * Input:  [transaction] — the tapped row. Output: none (launches on `viewModelScope`).
         * Changelog: 2026-08-06 — Created for issue 3.8.
         */
        private fun openDetail(transaction: Transaction) {
            _uiState.update { it.copy(detail = transaction, detailReceipt = null, detailNature = null) }
            loadNature(transaction.id)
            viewModelScope.launch {
                val attachment = receipts.observeAttachment(transaction.id).firstOrNull()
                val image = attachment?.let { receipts.readImage(it).getOrNull() }
                if (attachment != null && image != null) {
                    // Guarded on the sheet still showing the same row: decryption is off the main
                    // thread, and a user who closed one sheet and opened another must not be handed
                    // the previous receipt.
                    _uiState.update { state ->
                        if (state.detail?.id != transaction.id) {
                            state
                        } else {
                            state.copy(detailReceipt = ReceiptImage(attachment.id, image))
                        }
                    }
                }
            }
        }

        /**
         * FR-OCR-005: deletes the image and keeps the transaction (issue 3.8).
         * Why:    the sheet stays open on success, showing the transaction without its receipt —
         *         which is the requirement's whole point, and closing the sheet would leave the user
         *         unable to see that the row survived.
         * Result: `detailReceipt` cleared, or `errorCode` set with the image untouched.
         * Input:  [attachmentId]. Output: none (launches on `viewModelScope`).
         * Changelog: 2026-08-06 — Created for issue 3.8.
         */
        private fun deleteReceipt(attachmentId: String) {
            viewModelScope.launch {
                when (val outcome = receipts.deleteImage(attachmentId)) {
                    is Ok -> _uiState.update { it.copy(detailReceipt = null) }
                    is Err -> _uiState.update { it.copy(errorCode = outcome.error.code) }
                }
            }
        }

        /**
         * Applies a filter-sheet event (issue 3.6; FR-TXN-007).
         * Why:    split from [onEvent] so the main handler stays one line per group, the shape the
         *         add screen's `applySplit` already established.
         * Result: the filter or the sheet's visibility changes; the paged flow re-queries.
         * Input:  [event]. Output: none.
         * Changelog: 2026-08-04 — Created for issue 3.6.
         */
        private fun applyFilter(event: FilterEvent) {
            when (event) {
                FilterEvent.Opened -> _uiState.update { it.copy(isFilterSheetOpen = true) }
                FilterEvent.Dismissed -> _uiState.update { it.copy(isFilterSheetOpen = false) }
                // The search text survives Clear: it is typed in a field the sheet does not own, and
                // wiping it from under the user's cursor would read as the app losing what they said.
                FilterEvent.Cleared -> setFilter(TransactionFilter(query = currentFilter.query))
                is FilterEvent.Changed -> setFilter(event.filter)
            }
        }

        /**
         * Applies a bulk-selection event (issue 3.6; FR-TXN-008).
         * Why:    split from [onEvent] for the same reason [applyFilter] is. Selection is a mode.
         * Result: the selection changes, or a bulk write is launched.
         * Input:  [event]. Output: none.
         * Changelog: 2026-08-04 — Created for issue 3.6.
         */
        private fun applyBulk(event: BulkEvent) {
            when (event) {
                is BulkEvent.Toggled -> toggleSelection(event.transactionId)
                BulkEvent.Cleared -> setSelection(emptySet())
                is BulkEvent.Recategorise -> runBulk { repository.recategoriseAll(it, event.categoryId) }
                is BulkEvent.Retag -> runBulk { repository.retagAll(it, event.tagNames) }
                BulkEvent.Delete -> deleteSelection()
                BulkEvent.Undo -> undo()
                BulkEvent.UndoDismissed -> _uiState.update { it.copy(undo = null) }
            }
        }

        /**
         * Applies the user's answer to a proposed series (issue 3.7; FR-TXN-006).
         * Why:    split from [onEvent] for the same reason [applyFilter] is. Both answers are the
         *         same write with a different flag, so both go through [decide] — what must not
         *         differ between them is the merchant name recorded, which is the only thing
         *         stopping the proposal coming back.
         * Result: the decision is stored and the card leaves the list.
         * Input:  [event]. Output: none.
         * Changelog: 2026-08-05 — Created for issue 3.7.
         */
        private fun applyRecurring(event: RecurringEvent) {
            when (event) {
                is RecurringEvent.Confirm -> decide(event.series) { recurring.confirm(it) }
                is RecurringEvent.Dismiss -> decide(event.series) { recurring.dismiss(it) }
            }
        }

        /**
         * Records one decision and lets the repository re-emit (issue 3.7; FR-TXN-006).
         * Why:    **the card is not removed here.** Dropping it from the state optimistically would
         *         make it vanish on a write that failed, and the section would then disagree with
         *         the database until the next emission. `observeSuggestions` re-runs on the write,
         *         so the card leaves because the decision is stored, not because the screen hid it.
         * Result: `Err` surfaces as an `errorCode` and the card stays, which is the honest outcome —
         *         the user's answer was not recorded, so they should be able to give it again.
         * Input:  [series] — the proposal; [write] — confirm or dismiss. Output: none.
         * Changelog: 2026-08-05 — Created for issue 3.7.
         */
        private fun decide(
            series: RecurringSeries,
            write: suspend (RecurringSeries) -> Result<Unit, AppError>,
        ) {
            viewModelScope.launch {
                val outcome = write(series)
                if (outcome is Err) _uiState.update { it.copy(errorCode = outcome.error.code) }
            }
        }

        /**
         * Adds or removes one row from the selection (issue 3.6; FR-TXN-008).
         * Result: the selection changes; emptying it leaves selection mode.
         * Input:  [transactionId]. Output: none.
         * Changelog: 2026-08-04 — Created for issue 3.6.
         */
        private fun toggleSelection(transactionId: String) {
            val current = selectionFlow.value
            setSelection(if (transactionId in current) current - transactionId else current + transactionId)
        }

        /**
         * Runs a bulk write over the current selection and reports the outcome (issue 3.6).
         * Why:    recategorise and retag differ only in which repository call they make, so the
         *         guard rails around them — refuse an empty selection, block a second write while
         *         one is in flight, clear the selection on success, surface the error code on
         *         failure — are written once here rather than twice.
         *
         *         **The selection is cleared on success.** Leaving twenty rows selected after they
         *         have been changed invites the user to change them again by reflex.
         * Result: the write runs; `isBulkRunning` brackets it either way.
         * Input:  [write] — the repository call, given the selected ids.
         * Output: none (launches on `viewModelScope`).
         * Changelog: 2026-08-04 — Created for issue 3.6 (FR-TXN-008).
         */
        private fun runBulk(write: suspend (List<String>) -> Result<Int, AppError>) {
            val ids = selectionFlow.value.toList()
            if (ids.isEmpty() || _uiState.value.isBulkRunning) return
            viewModelScope.launch {
                _uiState.update { it.copy(isBulkRunning = true) }
                when (val outcome = write(ids)) {
                    is Ok -> setSelection(emptySet())
                    is Err -> _uiState.update { it.copy(errorCode = outcome.error.code) }
                }
                _uiState.update { it.copy(isBulkRunning = false) }
            }
        }

        /**
         * Deletes the selection and arms the undo snackbar (issue 3.6; FR-TXN-008).
         * Why:    not routed through [runBulk], because this is the one bulk write whose *result* is
         *         needed afterwards: the repository returns the ids it actually removed, which for a
         *         transfer is more than was selected, and undo has to restore exactly those.
         * Result: the rows leave every read; `undo` holds what can bring them back.
         * Input:  none — reads the selection. Output: none (launches on `viewModelScope`).
         * Changelog: 2026-08-04 — Created for issue 3.6 (FR-TXN-008).
         */
        private fun deleteSelection() {
            val ids = selectionFlow.value.toList()
            if (ids.isEmpty() || _uiState.value.isBulkRunning) return
            viewModelScope.launch {
                _uiState.update { it.copy(isBulkRunning = true) }
                when (val outcome = repository.deleteAll(ids)) {
                    is Ok -> {
                        setSelection(emptySet())
                        _uiState.update {
                            it.copy(undo = UndoBatch(ids = outcome.value, selectedCount = ids.size))
                        }
                    }

                    is Err -> _uiState.update { it.copy(errorCode = outcome.error.code) }
                }
                _uiState.update { it.copy(isBulkRunning = false) }
            }
        }

        /**
         * Puts back what the last bulk delete removed (issue 3.6; FR-TXN-008).
         * Why:    restores [UndoBatch.ids] — what the repository *removed* — rather than what the
         *         user selected. For a transfer those differ, and restoring only the selection would
         *         bring back one leg and leave the money it moved in one account with no counterpart.
         * Result: the rows return to every read and the balances revert; the snackbar disarms.
         * Input:  none — reads `undo`. Output: none (launches on `viewModelScope`).
         * Changelog: 2026-08-04 — Created for issue 3.6 (FR-TXN-008).
         */
        private fun undo() {
            val batch = _uiState.value.undo ?: return
            // Disarmed first, so a second tap on a snackbar that has not yet gone cannot restore
            // twice — the second call would be a NotFound and would surface as an error banner over
            // an operation that had in fact succeeded.
            _uiState.update { it.copy(undo = null) }
            viewModelScope.launch {
                when (val outcome = repository.restoreAll(batch.ids)) {
                    is Ok -> Unit
                    is Err -> _uiState.update { it.copy(errorCode = outcome.error.code) }
                }
            }
        }

        /** The filter the list is currently querying with. */
        private val currentFilter: TransactionFilter get() = filterFlow.value

        /**
         * Applies a new filter and mirrors it into the state (issue 3.6).
         * Why:    two places need it — [filterFlow] drives the query, [uiState] drives the controls —
         *         and setting one without the other is how a chip ends up showing a facet the query
         *         never saw. One function, so they cannot part company.
         * Result: the paged flow re-queries and the sheet re-renders.
         * Input:  [filter]. Output: none.
         * Changelog: 2026-08-04 — Created for issue 3.6.
         */
        private fun setFilter(filter: TransactionFilter) {
            filterFlow.value = filter
            _uiState.update { it.copy(filter = filter) }
        }

        /**
         * Applies a new selection and mirrors it into the state (issue 3.6).
         * Why:    the same argument [setFilter] makes. [selectionFlow] feeds the paged stream so a
         *         row can render its own checkbox; [uiState] feeds the action bar's count.
         * Result: both move together. Input: [selection]. Output: none.
         * Changelog: 2026-08-04 — Created for issue 3.6.
         */
        private fun setSelection(selection: Set<String>) {
            selectionFlow.value = selection
            _uiState.update { it.copy(selection = selection) }
        }

        /**
         * Removes a row, and its sibling leg when it is half of a transfer (FR-TXN-003).
         *
         * Why:    the id goes to the store as-is. **This is deliberately not "if it is a transfer,
         *         delete the transfer"** — the screen does not branch on the row's kind, because the
         *         atomicity guarantee belongs in one place and that place is the repository. No
         *         refresh follows: the paged query is invalidated by the write, so the list
         *         updates itself.
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
 * Turns one page row into the row the list renders (issue 3.6; FR-TXN-003).
 *
 * Why:    the collapse of a transfer's two legs moved into SQL in issue 3.6 — [toRows] pairs legs
 *         within a loaded day, which paging breaks the moment they land in different pages. The
 *         query now returns one leg with its counterpart account projected beside it, and this is
 *         where that becomes a [TransactionRow.TransferPair].
 *
 *         **The direction comes from the leg's type, not from the amount's sign.** Either would
 *         work — `TransactionType.matches` is asserted over every write path — but the type is what
 *         the query filtered on, so reading it here keeps one answer rather than two that agree.
 * Result: a [TransactionRow.TransferPair] for a transfer leg whose sibling is still live, otherwise
 *         a [TransactionRow.Single]. **A leg whose sibling has gone stays a Single**, which is the
 *         honest failure mode: hiding it would make money vanish from the list.
 * Input:  the receiver. Output: [TransactionRow].
 * Changelog: 2026-08-04 — Created for issue 3.6.
 */
internal fun FilteredTransaction.toRow(): TransactionRow {
    val transferId = transaction.transferId
    val counterpart = counterpartAccountId
    if (transferId == null || counterpart == null) return TransactionRow.Single(transaction)
    val isOutgoing = transaction.type == TransactionType.TRANSFER_OUT
    return TransactionRow.TransferPair(
        transferId = transferId,
        // This leg: either identifies the transfer to the store, and using the one the query
        // returned keeps the delete action pointing at a row that is actually on screen.
        transaction = transaction,
        outAccountId = if (isOutgoing) transaction.accountId else counterpart,
        inAccountId = if (isOutgoing) counterpart else transaction.accountId,
        // Positive: the size of the movement, matching `Transfer`. The stored leg is signed.
        amount = if (isOutgoing) Money.ZERO - transaction.amount else transaction.amount,
    )
}

/**
 * Turns a page of transactions into the list's items, day headers included (issue 3.6; FR-TXN-007).
 *
 * Why:    `insertSeparators` is Paging's answer to "group by day" over a stream that arrives in
 *         fixed-size pages: it is called with each adjacent pair, so a header is emitted exactly
 *         where the booked day changes — including at a page boundary, where a naive grouping
 *         would emit a second header for a day already headed, or none at all.
 *
 *         **`before == null` is the top of the list, not the top of a page.** Paging guarantees the
 *         nulls only bracket the whole loaded stream, which is what makes the first day get a header.
 *
 *         **The total is looked up, never folded.** A day split across two pages would otherwise be
 *         summed from whichever half is loaded — see [TransactionListItem.DayHeader].
 * Result: `[DayHeader, Row, Row, DayHeader, Row, …]`, newest first.
 * Input:  the receiver — one page of rows; [totals] — day → net total, from the database;
 *         [selection] — the ids the user has selected. Output: `PagingData<TransactionListItem>`.
 * Changelog: 2026-08-04 — Created for issue 3.6 (FR-TXN-007).
 */
internal fun PagingData<FilteredTransaction>.toListItems(
    totals: Map<String, Money>,
    selection: Set<String>,
): PagingData<TransactionListItem> =
    map { row ->
        TransactionListItem.Row(row = row.toRow(), isSelected = row.transaction.id in selection)
    }.insertSeparators { before, after ->
        // Nothing after: the end of the list, where a trailing header would head nothing.
        val day = (after as? TransactionListItem.Row)?.row?.transaction?.bookedOn ?: return@insertSeparators null
        val previousDay = (before as? TransactionListItem.Row)?.row?.transaction?.bookedOn
        if (day == previousDay) {
            null
        } else {
            // ZERO when the day is absent from the map, which happens when its only activity was a
            // transfer — the totals query excludes both legs, and zero is the arithmetic truth.
            TransactionListItem.DayHeader(isoDate = day, total = totals[day] ?: Money.ZERO)
        }
    }

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
        // Either leg identifies the transfer to the store; the outgoing one is chosen so the row is
        // deterministic rather than depending on the order the query returned.
        transaction = out,
        outAccountId = out.accountId,
        inAccountId = into.accountId,
        amount = into.amount,
    )
}
