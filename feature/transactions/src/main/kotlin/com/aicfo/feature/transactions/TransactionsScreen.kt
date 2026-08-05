package com.aicfo.feature.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.aicfo.core.designsystem.component.CfoAmountText
import com.aicfo.core.designsystem.component.CfoListRow
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.model.Money

/**
 * The recent transactions, grouped by the day they were booked on (issue 3.1; ARC-004).
 *
 * Why:  a capture path whose result the user cannot see is one they cannot trust — FR-TXN-002 puts
 *       the transaction in the database, and this is what shows it landed. It replaces the
 *       placeholder issue 1.10 left here, whose own doc comment said the triad arrives "when there
 *       is something to hold".
 * What: a stateful entry point and a stateless body.
 * Result: a saved transaction appears under its day, with the day's net total beside the header.
 * Changelog: 2026-07-25 — Created for issue 1.10 as a placeholder destination.
 *            2026-08-02 — Issue 3.1: became a real list over `TransactionRepository.observeRecent`.
 *            2026-08-04 — Issue 3.6: FR-TXN-007 and FR-TXN-008 in full — search, the filter sheet,
 *            a paged list with day separators, multi-select and an undo snackbar. The 30-day window
 *            is gone; the rows arrive as `PagingData` rather than on the state.
 *
 * **The screen is split across four files** (`TransactionsFilterUi`, `TransactionsBulkUi`,
 * `TransactionsScheduledUi`), each at the seam detekt's function-per-file ceiling forced and the
 * feature already drew: narrowing what the user sees, changing what they have, and the scheduled
 * rows that are not theirs yet.
 *
 * Input:  [modifier]; [viewModel]. Output: the rendered screen.
 */
@Composable
fun TransactionsScreen(
    modifier: Modifier = Modifier,
    viewModel: TransactionsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Two collections, because the paged rows are a stream and everything else is a value — see
    // `TransactionsViewModel.items`. `collectAsLazyPagingItems` is what drives page loading off the
    // list's scroll position (issue 3.6, FR-TXN-007).
    val items = viewModel.items.collectAsLazyPagingItems()
    TransactionsContent(
        uiState = uiState,
        items = items,
        onEvent = viewModel::onEvent,
        modifier = modifier,
    )
}

/**
 * The list's body, with no dependencies of its own.
 * Why:    stateless so a test can render every state — loading, empty, populated, failed — without
 *         Hilt or a database.
 * Result: the rendered content.
 * Input:  [uiState]; [modifier]. Output: the composition.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 */
@Composable
fun TransactionsContent(
    uiState: TransactionsUiState,
    items: LazyPagingItems<TransactionListItem>,
    onEvent: (TransactionsEvent) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    UndoSnackbar(uiState = uiState, hostState = snackbarHostState, onEvent = onEvent)

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(CfoDimens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
        ) {
            ListHeader(uiState = uiState, items = items, onEvent = onEvent)

            LazyColumn(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
                // Issue 3.7, above the scheduled rows: a question the app is asking is the one thing
                // on this screen a user cannot find by scrolling. It renders nothing when there is
                // nothing to propose, which is the common case.
                recurringSection(uiState = uiState, onEvent = onEvent)
                scheduledSection(uiState = uiState, onEvent = onEvent)

                // `itemKey` off the sealed item's own key, so a page arriving does not re-render or
                // re-order what is above it and does not lose scroll position.
                items(count = items.itemCount, key = items.itemKey { it.key }) { index ->
                    when (val item = items[index]) {
                        // Null while a placeholder-free page is still resolving: Paging can hand back
                        // an index before its row. Rendering nothing is correct — the row arrives.
                        null -> Unit
                        is TransactionListItem.DayHeader -> DayHeader(item = item)
                        is TransactionListItem.Row ->
                            SelectableRow(item = item, uiState = uiState, onEvent = onEvent)
                    }
                }
            }
        }

        // Issue 3.6: the app's first snackbar host, scoped to this screen rather than plumbed
        // through the nav scaffold — undo is the only thing in the app that needs one so far.
        //
        // **Lifted clear of the global FAB**, which lives in `:app`'s scaffold and cannot be moved
        // from here. Flush to the bottom the snackbar rendered *underneath* it and its Undo action
        // was unreachable — the emulator run caught that: "2 deleted" was visible and the only
        // control that could take it back was not.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = CfoDimens.minTouchTarget + CfoDimens.spaceXl),
        )

        // Issue 3.5: renders nothing until a row is tapped.
        TransactionDetailSheet(uiState = uiState, onEvent = onEvent)

        // Issue 3.6: renders nothing until the filter button is tapped (FR-TXN-007).
        TransactionFilterSheet(uiState = uiState, onEvent = onEvent)
    }
}

/**
 * Everything above the list: the title or the action bar, the error, search, chips, empty state.
 *
 * Why:    extracted from [TransactionsContent] at detekt's 40-line ceiling (§21.6), and the seam is
 *         real — everything here is fixed-height chrome, while what follows scrolls.
 *
 *         **In selection mode the bar replaces the title.** The screen is in a different mode, and
 *         saying so is more useful than repeating a heading the user is already looking at.
 * Result: the composition. Input: [uiState], [items], [onEvent]. Output: none.
 * Changelog: 2026-08-04 — Created for issue 3.6.
 */
@Composable
private fun ListHeader(
    uiState: TransactionsUiState,
    items: LazyPagingItems<TransactionListItem>,
    onEvent: (TransactionsEvent) -> Unit,
) {
    if (uiState.isSelecting) {
        BulkActionBar(uiState = uiState, onEvent = onEvent)
    } else {
        Text(
            text = stringResource(R.string.transactions_title),
            style = MaterialTheme.typography.headlineSmall,
        )
    }

    uiState.errorCode?.let { code ->
        Text(
            text = stringResource(TransactionLabels.errorMessage(code)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }

    // Issue 3.6: FR-TXN-007's search half.
    SearchField(uiState = uiState, onEvent = onEvent)

    // Issue 3.5: renders nothing unless the ledger holds two or more distinct sources, which a real
    // profile today does not (FR-TXN-009).
    SourceFilterRow(uiState = uiState, onEvent = onEvent)

    EmptyState(uiState = uiState, items = items)
}

/**
 * The search field and the filter button (issue 3.6; FR-TXN-007).
 *
 * Why:    FR-TXN-007's search covers "payee, note, amount, tag" — four things behind one field,
 *         because a user looking for a transaction knows *something* about it and should not have to
 *         say which kind of something. The filter button sits beside it rather than in a menu: the
 *         two are the same job at different precisions, and burying one makes it undiscoverable.
 *
 *         **No debounce.** The query is local SQLite behind `flatMapLatest`, so each keystroke
 *         cancels the previous query rather than queueing behind it. A debounce would add latency to
 *         the common case to save work in a case that costs nothing.
 * Result: the composition. Input: [uiState], [onEvent]. Output: none.
 * Changelog: 2026-08-04 — Created for issue 3.6 (FR-TXN-007).
 */
@Composable
private fun SearchField(
    uiState: TransactionsUiState,
    onEvent: (TransactionsEvent) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = { onEvent(TransactionsEvent.SearchChanged(it)) },
            label = { Text(stringResource(R.string.transactions_search)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = { onEvent(FilterEvent.Opened) },
            modifier = Modifier.defaultMinSize(CfoDimens.minTouchTarget),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.List,
                // Icon-only, so this is the whole of what a screen reader announces (§21.6).
                contentDescription = stringResource(R.string.transactions_filter),
                // Tinted when something is narrowing the list, so an active filter is visible
                // without opening the sheet — otherwise a user wonders where their rows went.
                tint =
                    if (uiState.filter.isActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }
}

/**
 * The "nothing here" line, whichever kind of nothing it is (issue 3.5).
 *
 * Why:    extracted from [TransactionsContent] at detekt's 40-line limit (§21.6), and the seam is a
 *         real one: this is the whole of the screen's four-way distinction in one place.
 *
 *         **Four kinds of nothing, and only two of them say "nothing".** Still loading is not empty.
 *         A failed read is not empty — rendering a database that would not open as a cheerful "add
 *         your first one" hides the failure from the user who most needs to see it, which is why the
 *         error banner above handles it. A profile with no transactions gets the invitation. A
 *         **filter matching nothing** gets its own line, because telling a user who has plenty and
 *         has merely narrowed the view that they have none reads as the app having lost them.
 * Result: the composition, or nothing. Input: [uiState]. Output: none.
 * Changelog: 2026-08-03 — Created for issue 3.5.
 */
@Composable
private fun EmptyState(
    uiState: TransactionsUiState,
    items: LazyPagingItems<TransactionListItem>,
) {
    // Issue 3.6: emptiness is now the pager's to report, not a list's `isEmpty`. `refresh` being
    // `NotLoading` is what separates "there is nothing" from "the first page has not arrived", which
    // is the same distinction `isLoading` made before paging — and getting it wrong would flash the
    // "add your first one" invitation at a user who has hundreds.
    val hasLoaded = items.loadState.refresh is LoadState.NotLoading
    val message =
        when {
            !hasLoaded || uiState.errorCode != null || items.itemCount > 0 -> return
            // A profile with only scheduled rows is not empty (issue 3.4): the user has entered
            // something, and telling them they have not would read as the app having lost it.
            uiState.hasUpcoming -> return
            // A filtered-to-nothing list is not empty either (issue 3.5) — "no transactions yet, tap
            // + to add your first one" is a lie told to a user who has plenty and has merely
            // narrowed the view.
            uiState.filter.isActive -> R.string.transactions_filter_empty
            else -> R.string.transactions_empty
        }
    Text(text = stringResource(message), style = MaterialTheme.typography.bodyMedium)
}

/**
 * What joins the two halves of a row's supporting line (issue 3.5).
 *
 * A middle dot rather than a comma or a dash: it reads as "and also" without implying a list or a
 * range, and it is the separator the transfer row already uses.
 */
private const val SUPPORTING_SEPARATOR = " · "

/**
 * One day's header and net total (FR-TXN-007's grouping half).
 * Why:    the total is read from [TransactionDay.total], which computes it from the rows underneath
 *         it with `Money`'s overflow-checked arithmetic (MNY-001) — the screen never adds money.
 *         It is a **net** figure, not a spend figure: an income booked on the same day offsets the
 *         outflows, which is what makes it consistent with the account balance the user will check
 *         it against.
 * Result: the composition. Input: [day]. Output: none.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 */
@Composable
private fun DayHeader(item: TransactionListItem.DayHeader) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = CfoDimens.spaceSm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = TransactionLabels.dayHeader(item.isoDate), style = MaterialTheme.typography.titleSmall)
        CfoAmountText(
            amount = item.total,
            contentDescription = stringResource(R.string.transactions_day_total),
        )
    }
}

/**
 * One line: either a plain transaction or a whole transfer (issue 3.2; FR-TXN-003).
 * Why:    the `when` is exhaustive over [TransactionRow], so a future row kind cannot be added
 *         without deciding how it renders. Both kinds carry the same delete action, and neither
 *         passes a transfer id to it — the row hands over the transaction it was built from and the
 *         repository works out whether a sibling goes too.
 * Result: the composition. Input: [row], [accountNames], [onDelete], [onClick]. Output: none.
 * Changelog: 2026-08-02 — Created for issue 3.2, replacing issue 3.1's single-row composable.
 *            2026-08-03 — Issue 3.5: the source label, and [onClick] to open the detail sheet.
 *            2026-08-04 — Issue 3.6: [isSelected] and [showDelete], and the click handling moved out
 *            to the caller — the list needs long-press while the scheduled section does not.
 */
@Composable
internal fun ListRow(
    row: TransactionRow,
    accountNames: Map<String, String>,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    selection: RowSelection = RowSelection(),
) {
    // A tinted background rather than a checkbox on every row: selection is a mode, and a permanent
    // control would cost a column of the row's width at 200% font for a feature most taps never use.
    val rowModifier =
        if (selection.isSelected) {
            modifier.background(MaterialTheme.colorScheme.secondaryContainer)
        } else {
            modifier
        }
    when (row) {
        is TransactionRow.Single ->
            CfoListRow(
                // Falls back through note → merchant → "Uncategorised" rather than rendering a blank
                // line: every field but the amount is optional (FR-TXN-001), and a row a user cannot
                // identify is a row they cannot decide to delete.
                title =
                    row.transaction.note
                        ?: row.transaction.merchant
                        ?: stringResource(R.string.transactions_uncategorised),
                supporting = supportingLine(row),
                trailing = {
                    RowTrailing(row.transaction.amount, onDelete, selection = selection)
                },
                modifier = rowModifier,
            )

        is TransactionRow.TransferPair ->
            CfoListRow(
                // The two account names carry the direction, because a collapsed pair has no single
                // sign to colour. An id that no longer resolves to a name falls back to the id
                // rather than rendering an empty arrow.
                title = transferTitle(row = row, accountNames = accountNames),
                // `showSign = false`: the amount is the size of the movement, and a leading "+" would
                // claim the user gained money they only moved.
                trailing = {
                    RowTrailing(row.amount, onDelete, showSign = false, selection = selection)
                },
                modifier = rowModifier,
            )
    }
}

/**
 * A transfer row's title: "Transfer · HDFC Savings → Cash Wallet" (issue 3.2; FR-TXN-003).
 * Why:    extracted so [ListRow] stays under detekt's 40-line ceiling (§21.6). An id that no longer
 *         resolves to a name falls back to the id rather than rendering an empty arrow — an account
 *         archived after a transfer is a real case (FR-ACC-007).
 * Result: the title string. Input: [row], [accountNames]. Output: [String].
 * Changelog: 2026-08-04 — Extracted from `ListRow` for issue 3.6.
 */
@Composable
private fun transferTitle(
    row: TransactionRow.TransferPair,
    accountNames: Map<String, String>,
): String =
    stringResource(
        R.string.transactions_transfer,
        accountNames[row.outAccountId] ?: row.outAccountId,
        accountNames[row.inAccountId] ?: row.inAccountId,
    )

/**
 * The second line of an ordinary row: where it came from, and how it is split (issue 3.5).
 *
 * Why:    `CfoListRow` has one supporting slot and two things now want it. Combining them here
 *         rather than adding a second slot keeps the row one line tall at 200% font, and the order
 *         is deliberate: **provenance first**, because "Balance adjustment" explains what a row *is*
 *         while "3 lines" only describes how it was categorised.
 *
 *         **Manual rows contribute nothing** (`TransactionLabels.sourceLabel` returns null for
 *         them), so the overwhelmingly common row is unchanged from before this issue — the label
 *         exists to explain a row the user did not type.
 * Result: `"Balance adjustment"`, `"3 lines"`, `"From a receipt · 3 lines"`, or `null` for a plain
 *         hand-typed transaction.
 * Input:  [row] — the single-transaction row. Output: `String?`.
 * Changelog: 2026-08-03 — Created for issue 3.5.
 */
@Composable
private fun supportingLine(row: TransactionRow.Single): String? {
    val source = TransactionLabels.sourceLabel(row.transaction.source)?.let { stringResource(it) }
    // Issue 3.3: a split says how many categories its one amount is spread across. The amount itself
    // is unchanged — the parent still holds all of it — so listing the lines here would show the
    // same money twice.
    val split =
        row.splitLineCount?.let { count ->
            pluralStringResource(R.plurals.transactions_split_lines, count, count)
        }
    return listOfNotNull(source, split).takeIf { it.isNotEmpty() }?.joinToString(SUPPORTING_SEPARATOR)
}

/**
 * A row's amount and its delete action.
 * Why:    shared by both row kinds so the delete affordance cannot end up on one and not the other.
 *         The icon is icon-only, so the content description is what a screen reader announces —
 *         without it the app's only destructive control reads as "button" (§21.6).
 * Result: the composition. Input: [amount]; [showSign]; [onDelete]. Output: none.
 * Changelog: 2026-08-02 — Created for issue 3.2.
 */
@Composable
private fun RowTrailing(
    amount: Money,
    onDelete: () -> Unit,
    showSign: Boolean = true,
    selection: RowSelection = RowSelection(),
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
    ) {
        CfoAmountText(amount = amount, showSign = showSign)
        // Hidden in selection mode (issue 3.6): a per-row bin beside a bulk Delete is two
        // destructive controls on one screen, which is how a user deletes the wrong thing.
        if (selection.showDelete) {
            IconButton(onClick = onDelete, modifier = Modifier.defaultMinSize(CfoDimens.minTouchTarget)) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.transactions_delete),
                )
            }
        }
    }
}
