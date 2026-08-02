package com.aicfo.feature.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicfo.core.designsystem.component.CfoAmountText
import com.aicfo.core.designsystem.component.CfoListRow
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.model.Transaction

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
 *
 * **Deliberately smaller than FR-TXN-007.** No search, no filters, no infinite scroll, no bulk edit
 * — those are issue 3.6's, and the repository behind this reads a fixed 30-day window rather than
 * everything. Growing this screen is how 3.6 gets built early and badly.
 *
 * Input:  [modifier]; [viewModel]. Output: the rendered screen.
 */
@Composable
fun TransactionsScreen(
    modifier: Modifier = Modifier,
    viewModel: TransactionsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    TransactionsContent(uiState = uiState, modifier = modifier)
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
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(CfoDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
    ) {
        Text(text = stringResource(R.string.transactions_title), style = MaterialTheme.typography.headlineSmall)

        uiState.errorCode?.let { code ->
            Text(
                text = stringResource(TransactionLabels.errorMessage(code)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        if (uiState.isEmpty) {
            Text(text = stringResource(R.string.transactions_empty), style = MaterialTheme.typography.bodyMedium)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            uiState.days.forEach { day ->
                // `key` on both, so a save that prepends a row does not re-render every row below it
                // and does not lose scroll position.
                item(key = day.isoDate) { DayHeader(day = day) }
                items(items = day.transactions, key = { it.id }) { TransactionRow(transaction = it) }
            }
        }
    }
}

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
private fun DayHeader(day: TransactionDay) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = CfoDimens.spaceSm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = TransactionLabels.dayHeader(day.isoDate), style = MaterialTheme.typography.titleSmall)
        CfoAmountText(
            amount = day.total,
            contentDescription = stringResource(R.string.transactions_day_total),
        )
    }
}

/**
 * One transaction.
 * Why:    the title falls back through note → merchant → "Uncategorised" rather than rendering a
 *         blank line: every field but the amount is optional (FR-TXN-001), and a row a user cannot
 *         identify is a row they cannot decide to delete. The amount goes through [CfoAmountText],
 *         which formats it with Indian digit grouping and colours it by sign (P-06).
 * Result: the composition. Input: [transaction]. Output: none.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 */
@Composable
private fun TransactionRow(transaction: Transaction) {
    CfoListRow(
        title = transaction.note ?: transaction.merchant ?: stringResource(R.string.transactions_uncategorised),
        trailing = { CfoAmountText(amount = transaction.amount) },
    )
}
