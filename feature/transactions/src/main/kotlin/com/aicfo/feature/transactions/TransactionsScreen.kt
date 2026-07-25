package com.aicfo.feature.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aicfo.core.designsystem.theme.CfoDimens

/**
 * The transaction list — a placeholder destination (issue 3.6 builds the real one).
 *
 * Why:  a navigation graph with one destination proves nothing. This second screen exists so that
 *       typed cross-feature navigation is genuinely exercised: the dashboard reaches it without
 *       either feature importing the other (ARC-001).
 * What: a title and an empty-state line, both from this module's own `strings.xml` (§21.6).
 * Result: a real destination to navigate to.
 * Changelog: 2026-07-25 — Created for issue 1.10.
 *
 * Deliberately without a ViewModel: it has no state yet, and adding an empty one would be
 * scaffolding for its own sake. Issue 3.6 adds the triad when there is something to hold.
 *
 * Input:  [modifier]. Output: the rendered screen.
 */
@Composable
fun TransactionsScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(CfoDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
    ) {
        Text(text = stringResource(R.string.transactions_title), style = MaterialTheme.typography.headlineSmall)
        Text(text = stringResource(R.string.transactions_empty), style = MaterialTheme.typography.bodyMedium)
    }
}
