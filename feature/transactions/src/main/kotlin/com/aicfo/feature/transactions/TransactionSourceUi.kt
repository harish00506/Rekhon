package com.aicfo.feature.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.aicfo.core.designsystem.component.CfoAmountText
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.model.Transaction

/*
 * Provenance on screen: the source filter and the detail sheet (issue 3.5; FR-TXN-009, P-02).
 *
 * A separate file from `TransactionsScreen.kt` for the reason `SplitEditor.kt` and `ScheduleField.kt`
 * are separate from the add screen — that file sits near detekt's eleven-function ceiling, which both
 * of those issues hit. The seam is real: everything here is about **where a row came from**, and none
 * of it renders for the profile the app has today, which is entirely hand-typed.
 */

/**
 * The source filter chips (issue 3.5; FR-TXN-009).
 *
 * Why:    FR-TXN-009 requires the source to be recorded and this issue's criteria require it to be
 *         filterable. **It renders nothing at all unless the window holds two or more distinct
 *         sources** — a real profile today is entirely manual, so a chip row offering "All · Manual"
 *         would be a choice between a thing and the same thing, on the screen the user looks at most.
 *
 *         The chips come from [TransactionsUiState.availableSources], which the repository reads
 *         straight from the ledger (issue 3.6): derived from what is on screen, choosing one would
 *         delete the others and strand the user — and with paging, "on screen" is one page.
 *
 *         `FilterChip` in a `FlowRow`, the same shape the category pickers use, so the two read as
 *         one idiom and wrap rather than clip at 200% font.
 * Result: the composition, or nothing. Input: [uiState], [onEvent]. Output: none.
 * Changelog: 2026-08-03 — Created for issue 3.5.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourceFilterRow(
    uiState: TransactionsUiState,
    onEvent: (TransactionsEvent) -> Unit,
) {
    if (!uiState.hasSourceFilter) return

    val label = stringResource(R.string.transactions_source_filter)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
        // The chips are individually labelled; this says what the group of them is for, which a
        // screen reader would otherwise have to infer from a row of bare nouns.
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
    ) {
        FilterChip(
            selected = uiState.filter.source == null,
            onClick = { onEvent(TransactionsEvent.SourceFilterSelected(null)) },
            label = { Text(stringResource(R.string.transactions_source_all)) },
        )
        uiState.availableSources.forEach { source ->
            FilterChip(
                selected = uiState.filter.source == source,
                // Tapping the selected chip clears the filter rather than doing nothing — the same
                // behaviour the category chips have, and it saves a trip to "All".
                onClick = {
                    val next = source.takeIf { it != uiState.filter.source }
                    onEvent(TransactionsEvent.SourceFilterSelected(next))
                },
                label = { Text(stringResource(TransactionLabels.sourceName(source))) },
            )
        }
    }
}

/**
 * The detail sheet for one transaction (issue 3.5; FR-TXN-001, P-02).
 *
 * Why:    the issue's criteria ask for the source "in the detail view", and there is no detail view
 *         — nothing in the app has ever opened a single transaction. **A sheet rather than a
 *         route**: issue 3.6 owns editing and will want a real screen for it, and this codebase has
 *         twice recorded that growing this surface early is how 3.6 gets built badly. A sheet costs
 *         no `CfoRoute`, no back-stack handling and no navigation test.
 *
 *         **The app's first `ModalBottomSheet`.** The stateful wrapper is deliberately thin and the
 *         content ([TransactionDetailContent]) is stateless, so the Compose tests render the content
 *         directly rather than fighting Robolectric over sheet animation — the same stateful/
 *         stateless split every screen in this module already uses.
 * Result: the composition, or nothing when no row is selected.
 * Input:  [uiState], [onEvent]. Output: none.
 * Changelog: 2026-08-03 — Created for issue 3.5.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TransactionDetailSheet(
    uiState: TransactionsUiState,
    onEvent: (TransactionsEvent) -> Unit,
) {
    val transaction = uiState.detail ?: return

    ModalBottomSheet(
        onDismissRequest = { onEvent(TransactionsEvent.DetailDismissed) },
        sheetState = rememberModalBottomSheetState(),
    ) {
        TransactionDetailContent(
            transaction = transaction,
            accountNames = uiState.accountNames,
            onClose = { onEvent(TransactionsEvent.DetailDismissed) },
        )
    }
}

/**
 * Everything known about one transaction, as fields (issue 3.5; FR-TXN-001, FR-TXN-009).
 *
 * Why:    stateless, so a test renders it without a sheet. **The source is spelled out here even
 *         when it is "Manual"** — unlike the row label, which stays blank for manual. In a list a
 *         missing label reads as "ordinary"; in a field list a missing value reads as missing data,
 *         and P-02 is about the user being able to see what produced a row rather than guess.
 *
 *         Optional fields are omitted rather than rendered empty (FR-TXN-001 makes every field but
 *         the amount optional), so the sheet is as short as the row is simple.
 * Result: the composition. Input: [transaction]; [accountNames] — id → display name; [onClose].
 * Output: none.
 * Changelog: 2026-08-03 — Created for issue 3.5.
 */
@Composable
internal fun TransactionDetailContent(
    transaction: Transaction,
    accountNames: Map<String, String>,
    onClose: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                // The sheet draws over the gesture bar; without this the close button sits under it.
                .navigationBarsPadding()
                .padding(CfoDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
    ) {
        Text(
            text = stringResource(R.string.transactions_detail_title),
            style = MaterialTheme.typography.titleMedium,
        )

        DetailRow(labelId = R.string.transactions_detail_amount) {
            CfoAmountText(amount = transaction.amount)
        }
        DetailField(
            labelId = R.string.transactions_detail_account,
            // Falls back to the id rather than rendering blank, the same choice the transfer row
            // makes: an account deleted afterwards must still name something.
            value = accountNames[transaction.accountId] ?: transaction.accountId,
        )
        DetailField(
            labelId = R.string.transactions_detail_date,
            value = TransactionLabels.dayHeader(transaction.bookedOn),
        )
        OptionalDetailFields(transaction = transaction)
        // FR-TXN-009, and the reason this sheet exists at all.
        DetailField(
            labelId = R.string.transactions_detail_source,
            value = stringResource(TransactionLabels.sourceName(transaction.source)),
        )

        CfoSecondaryButton(text = stringResource(R.string.transactions_detail_close), onClick = onClose)
    }
}

/**
 * The fields a transaction may or may not have (issue 3.5; FR-TXN-001).
 *
 * Why:    extracted from [TransactionDetailContent] at detekt's 40-line limit (§21.6), and the seam
 *         is the honest one: everything here **renders nothing at all** for the plain hand-typed
 *         expense that most rows are, while everything above it renders always.
 *
 *         Omitted rather than shown empty. FR-TXN-001 makes every field but the amount optional, and
 *         a blank "Merchant" line reads as data the app lost rather than as data that was never
 *         entered.
 * Result: the composition, or nothing. Input: [transaction]. Output: none.
 * Changelog: 2026-08-03 — Created for issue 3.5.
 */
@Composable
private fun OptionalDetailFields(transaction: Transaction) {
    transaction.merchant?.let { DetailField(labelId = R.string.transactions_detail_merchant, value = it) }
    transaction.note?.let { DetailField(labelId = R.string.transactions_detail_note, value = it) }
    if (transaction.isSplit) {
        DetailField(
            labelId = R.string.transactions_detail_split,
            // The line *count*, not the lines: the parent holds the whole amount, so listing them
            // here would show the same money twice (issue 3.3).
            value =
                pluralStringResource(
                    R.plurals.transactions_split_lines,
                    transaction.splits.size,
                    transaction.splits.size,
                ),
        )
    }
}

/**
 * One labelled field of the detail sheet.
 * Why:    one place that decides how a label and its value sit together, so eight fields cannot
 *         drift into eight layouts.
 * Result: the composition. Input: [labelId]; [value]. Output: none.
 * Changelog: 2026-08-03 — Created for issue 3.5.
 */
@Composable
private fun DetailField(
    labelId: Int,
    value: String,
) {
    DetailRow(labelId = labelId) {
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * A field whose value is a composable rather than a string.
 * Why:    the amount is rendered by `CfoAmountText` so Indian digit grouping and the sign colour
 *         come from one place (MNY-001, P-06) — it cannot be passed as a `String`.
 * Result: the composition. Input: [labelId]; [value] — the value slot. Output: none.
 * Changelog: 2026-08-03 — Created for issue 3.5.
 */
@Composable
private fun DetailRow(
    labelId: Int,
    value: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(labelId),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        value()
    }
}
