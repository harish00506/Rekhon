package com.aicfo.feature.transactions

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aicfo.core.designsystem.theme.CfoDimens

/*
 * The scheduled half of the transactions list (issue 3.4; FR-TXN-010).
 *
 * Why:  split from `TransactionsScreen.kt` when that file reached detekt's function-per-file ceiling
 *       (§21.6) during issue 3.6. The seam is the one FR-TXN-010 already draws: everything here
 *       renders money that has **not** moved, read from a different repository flow and carrying no
 *       day total, and it renders at all for almost nobody — most users schedule nothing.
 * What: the section, its two headings, and its date header.
 * Result: the actuals/scheduled split is legible in one file rather than interleaved with the list.
 * Changelog: 2026-08-04 — Split out of `TransactionsScreen.kt` for issue 3.6.
 */

/**
 * The scheduled group, above the actuals and labelled (issue 3.4; FR-TXN-010).
 *
 * Why:    extracted from [TransactionsContent] to stay within detekt's 40-line function limit
 *         (§21.6), and the seam is a real one: everything here renders only for a user who has
 *         scheduled something, which is almost nobody.
 *
 *         **It sits above the actuals** because the one thing a user must never wonder is whether a
 *         row has already left their account — the section they have to scroll to find is the wrong
 *         place for the answer. Its rows carry the same delete action as any other, so a payment
 *         scheduled by mistake can be removed before it ever counts.
 * Result: the two headings and the scheduled days, or nothing at all.
 * Input:  the receiver — the list's scope; [uiState]; [onEvent]. Output: none.
 * Changelog: 2026-08-03 — Created for issue 3.4.
 */
internal fun LazyListScope.scheduledSection(
    uiState: TransactionsUiState,
    onEvent: (TransactionsEvent) -> Unit,
) {
    if (!uiState.hasUpcoming) return

    item(key = SCHEDULED_SECTION_KEY) { SectionHeader(textId = R.string.transactions_scheduled) }
    uiState.upcoming.forEach { day ->
        item(key = "$SCHEDULED_SECTION_KEY:${day.isoDate}") { ScheduledHeader(day = day) }
        items(items = day.rows, key = { it.id }) { row ->
            ListRow(
                row = row,
                accountNames = uiState.accountNames,
                onDelete = { onEvent(TransactionsEvent.Delete(row.id)) },
                // No long press here: a scheduled payment is not part of the actuals a bulk edit
                // operates on, and offering selection on rows the action bar's count would not
                // include is the kind of near-miss that ships as a wrong figure.
                modifier = Modifier.clickable { onEvent(TransactionsEvent.RowTapped(row.transaction)) },
            )
        }
    }
    item(key = ACTUALS_SECTION_KEY) { SectionHeader(textId = R.string.transactions_posted) }
}

/**
 * A section heading — "Scheduled" or "Posted" (issue 3.4; FR-TXN-010).
 * Why:    the two halves of this screen mean different things about the user's money, and a date
 *         header alone does not say which is which: "5 Aug" reads identically whether the money has
 *         gone or is going to. The heading is what makes the split legible (P-02).
 * Result: the composition. Input: [textId] — the heading's string resource. Output: none.
 * Changelog: 2026-08-03 — Created for issue 3.4.
 */
@Composable
private fun SectionHeader(
    @StringRes textId: Int,
) {
    Text(
        text = stringResource(textId),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth().padding(top = CfoDimens.spaceMd),
    )
}

/**
 * One scheduled day's header (issue 3.4; FR-TXN-010).
 * Why:    the same date header as [DayHeader] and deliberately **without the total**. A day total is
 *         a statement about money that has moved; printing one over rows that have not moved yet
 *         would invite exactly the reading FR-TXN-010 exists to prevent, and it would not reconcile
 *         with any balance on any other screen. What the user needs here is when, not how much in
 *         aggregate — each row still shows its own amount.
 * Result: the composition. Input: [day]. Output: none.
 * Changelog: 2026-08-03 — Created for issue 3.4.
 */
@Composable
private fun ScheduledHeader(day: TransactionDay) {
    Text(
        text = TransactionLabels.dayHeader(day.isoDate),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth().padding(top = CfoDimens.spaceSm),
    )
}

/** Stable list keys for the two section headings, so neither collides with an ISO date. */
private const val SCHEDULED_SECTION_KEY = "section:scheduled"

private const val ACTUALS_SECTION_KEY = "section:posted"
