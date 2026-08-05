package com.aicfo.feature.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.aicfo.core.designsystem.component.CfoAmountText
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.domain.engines.recurring.RecurringSeries

/*
 * The recurring-detection section of the transactions list (issue 3.7; FR-TXN-006).
 *
 * Why:  its own file, matching the split issue 3.6 left behind — `TransactionsScreen.kt` is already
 *       at detekt's function-per-file ceiling (§21.6). The seam is a real one: everything here
 *       renders a *proposal*, not a transaction, and it renders at all only for a profile whose
 *       ledger already contains a repeating merchant.
 * What: the section, one card per proposal, and its evidence line.
 * Result: FR-TXN-006's confirm/reject surface, beside the very rows that produced it.
 * Changelog: 2026-08-05 — Created for issue 3.7.
 */

/**
 * The proposals, above everything else on the list (issue 3.7; FR-TXN-006).
 *
 * Why:    **it sits at the top and it renders nothing when there is nothing to propose.** A section
 *         that pushed the user's transactions down with an "no subscriptions found" placeholder
 *         would cost every user screen space to tell almost none of them anything. When it does
 *         appear it is above the ledger, because a question the app is asking is the one thing on
 *         this screen the user cannot discover by scrolling.
 * Result: a card per proposal, or nothing at all.
 * Input:  the receiver — the list's scope; [uiState]; [onEvent]. Output: none.
 * Changelog: 2026-08-05 — Created for issue 3.7.
 */
internal fun LazyListScope.recurringSection(
    uiState: TransactionsUiState,
    onEvent: (TransactionsEvent) -> Unit,
) {
    if (!uiState.hasSuggestions) return

    item(key = RECURRING_SECTION_KEY) {
        Text(
            text = stringResource(R.string.transactions_recurring_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().padding(top = CfoDimens.spaceMd),
        )
    }
    uiState.suggestions.forEach { series ->
        // Keyed by merchant, which is what the repository's exclusion is keyed by too: answering one
        // card removes exactly the proposal that key names, with no index to go stale.
        item(key = "$RECURRING_SECTION_KEY:${series.merchant}") {
            SuggestionCard(
                series = series,
                onConfirm = { onEvent(RecurringEvent.Confirm(series)) },
                onDismiss = { onEvent(RecurringEvent.Dismiss(series)) },
            )
        }
    }
}

/**
 * One proposed series, with the evidence behind it (issue 3.7; FR-TXN-006, P-02).
 *
 * Why:    **the card shows its own working.** P-02 says a recommendation shows its inputs and the
 *         rule that fired, and "we think you have a Netflix subscription" is a verdict the user has
 *         to take on trust. So the card names the merchant, the amount, the cadence, *how many*
 *         payments were found and *when they were* — a claim the user can check against their own
 *         memory in a second, and reject in one tap if it is wrong.
 *
 *         **Both buttons are answers, neither is a close.** "Not recurring" writes a decision the
 *         app keeps; there is no way to make the card go away without saying something, because a
 *         proposal that could be swiped into limbo would come straight back (P-07).
 * Result: the composition.
 * Input:  [series] — the proposal; [onConfirm] and [onDismiss] — the user's two answers.
 * Output: none.
 * Changelog: 2026-08-05 — Created for issue 3.7.
 */
@Composable
private fun SuggestionCard(
    series: RecurringSeries,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = CfoDimens.spaceXs)) {
        Column(
            modifier = Modifier.padding(CfoDimens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = series.merchant, style = MaterialTheme.typography.titleSmall)
                // Through CfoAmountText, never interpolated into a string: Indian digit grouping
                // (₹1,23,456.78) lives in MoneyFormatter and nowhere else (P-06).
                CfoAmountText(amount = series.medianAmount)
            }
            EvidenceLine(series = series)
            Row(horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.transactions_recurring_confirm))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.transactions_recurring_reject))
                }
            }
        }
    }
}

/**
 * The two lines under the amount: what was found, and what it predicts (P-02).
 *
 * Why:    split from [SuggestionCard] to keep both under detekt's 40-line limit (§21.6), and the
 *         seam is where the *evidence* ends and the *projection* begins — the dates are things that
 *         happened, the next-due date is a claim about the future that only holds if the user
 *         confirms.
 *
 *         **The dates are joined here rather than in a translated sentence**, because their order
 *         and separator are locale business that `TransactionLabels.dayHeader` already owns; a
 *         format string with three date placeholders would be untranslatable for a series of five.
 * Result: the composition. Input: [series]. Output: none.
 * Changelog: 2026-08-05 — Created for issue 3.7.
 */
@Composable
private fun EvidenceLine(series: RecurringSeries) {
    val cadence = stringResource(TransactionLabels.cadenceName(series.cadence))
    val count =
        pluralStringResource(
            R.plurals.transactions_recurring_occurrences,
            series.occurrences.size,
            series.occurrences.size,
        )
    val dates = series.occurrences.joinToString { TransactionLabels.dayHeader(it.bookedOn) }
    val evidence = "$cadence · $count · $dates"

    // One Text, not three: a screen reader should read "Monthly, 3 payments, 3 Jun…" as one
    // sentence rather than as three fragments it has to reassemble.
    Text(text = evidence, style = MaterialTheme.typography.bodySmall)
    Text(
        text =
            stringResource(
                R.string.transactions_recurring_next_due,
                TransactionLabels.dayHeader(series.nextDueIsoDate),
            ),
        style = MaterialTheme.typography.bodySmall,
    )
}

/** A stable list key for the heading, so it cannot collide with an ISO date or a transaction id. */
private const val RECURRING_SECTION_KEY = "section:recurring"
