package com.aicfo.feature.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicfo.core.designsystem.component.CfoButton
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens

/**
 * The accounts list (issue 2.5; FR-ACC-001, FR-ACC-007, ARC-004).
 *
 * Why:  the screen that finally makes the `account` table the user's rather than the demo's. It
 *       follows the dashboard's shape exactly: a stateful entry point that collects the ViewModel,
 *       a stateless body that renders a state, and navigation as lambdas rather than a
 *       `NavController` — so this module never learns another feature exists (ARC-001).
 * What: the list, the archived toggle, per-row actions, and the empty state.
 * Result: create, read, archive and delete are reachable in one screen.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 *
 * Input:  [onAddAccount], [onEditAccount] — where the editor lives; [onOpenHoldings] — the
 *         holdings screen for one account (issue 6.3); [modifier]; [viewModel].
 * Output: the rendered screen.
 */
@Composable
fun AccountsScreen(
    actions: AccountsActions,
    modifier: Modifier = Modifier,
    viewModel: AccountsViewModel = hiltViewModel(),
) {
    // collectAsStateWithLifecycle, not collectAsState: a backgrounded screen must stop collecting.
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    AccountsContent(
        uiState = uiState,
        onEvent = viewModel::onEvent,
        actions = actions,
        modifier = modifier,
    )
}

/**
 * The accounts list's body, with no dependencies of its own.
 * Why:    stateless, so a preview or a test can render any state — loading, empty, populated,
 *         error — without constructing a ViewModel.
 * Result: the rendered content.
 * Input:  [uiState]; [onEvent] — events up (ARC-004); [onAddAccount]; [onEditAccount];
 *         [onOpenHoldings]; [modifier].
 * Output: the composition.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 */
@Composable
fun AccountsContent(
    uiState: AccountsUiState,
    onEvent: (AccountsEvent) -> Unit,
    actions: AccountsActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(CfoDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd),
    ) {
        Text(
            text = stringResource(R.string.accounts_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        uiState.errorCode?.let { code ->
            ErrorBanner(code = code, onDismiss = { onEvent(AccountsEvent.DismissError) })
        }

        CfoButton(text = stringResource(R.string.accounts_add), onClick = actions.onAddAccount)

        // Only once something investable exists. Offering "see allocation" to a user whose only
        // account is a savings account is offering a screen that can only say "nothing invested
        // yet" — the button would be the question and the screen the shrug.
        if (uiState.accounts.any { it.type in INVESTABLE_TYPES }) {
            CfoSecondaryButton(
                text = stringResource(R.string.allocation_open),
                onClick = actions.onOpenAllocation,
            )
        }

        ArchivedToggle(
            checked = uiState.showArchived,
            onCheckedChange = { onEvent(AccountsEvent.ToggleArchived(it)) },
        )

        // Above the list rather than over it: the panel is where the user's attention has to be,
        // and the row it came from stays visible and stays live underneath (issue 2.7).
        uiState.reconciling?.let { reconciling ->
            ReconcilePanel(state = reconciling, onEvent = onEvent)
        }

        AccountsList(uiState = uiState, actions = actions, onEvent = onEvent)
    }
}

/**
 * The accounts themselves, or the invitation when there are none.
 * Why:    split from [AccountsContent] when issue 6.3's holdings lookup took that function past the
 *         40-line limit (§21.6). The seam is the one the screen already had: everything above is
 *         chrome, this is the list.
 * Result: the list. Input: [uiState]; [actions]; [onEvent]. Output: none.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 */
@Composable
private fun AccountsList(
    uiState: AccountsUiState,
    actions: AccountsActions,
    onEvent: (AccountsEvent) -> Unit,
) {
    if (uiState.isEmpty) {
        EmptyState()
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
        items(uiState.accounts, key = { it.id }) { account ->
            AccountRow(
                account = account,
                figures =
                    AccountFigures(
                        card = uiState.cards[account.id],
                        instalment = uiState.loans[account.id],
                        holdings = uiState.investments[account.id],
                    ),
                actions = actions,
                onEvent = onEvent,
            )
        }
    }
}

/**
 * FR-ACC-007's toggle.
 *
 * Why:    `toggleable` sits on the **Row**, not the `Switch`, so the label is part of the control.
 *         A 20dp switch beside an inert piece of text is both under the 48dp minimum and announced
 *         to a screen reader as two unrelated things — the same reasoning the onboarding flow's
 *         consent row already uses. The `Switch` therefore takes `onCheckedChange = null` and the
 *         row owns the interaction, so it is announced once with its label and state.
 * Result: the composition. Input: [checked], [onCheckedChange]. Output: none.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 */
@Composable
private fun ArchivedToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .defaultMinSize(minHeight = CfoDimens.minTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
    ) {
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics {})
        Text(
            text = stringResource(R.string.accounts_show_archived),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * What a user with no accounts sees.
 * Why:    an empty list and a failed read must look different — one invites an action, the other
 *         reports a problem. Rendering a blank screen for both is how a storage failure gets
 *         mistaken for "I have no accounts".
 * Result: the composition. Input: none.
 */
@Composable
private fun EmptyState() {
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            Text(
                text = stringResource(R.string.accounts_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.accounts_empty_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * The error banner.
 * Why:    `liveRegion` so a screen reader announces a failure the user did not look for — the same
 *         reasoning `CfoDemoBanner` uses (issue 2.4).
 * Result: the composition. Input: [code] — an `AppError.code`; [onDismiss].
 * Changelog: 2026-07-28 — Created for issue 2.5.
 */
@Composable
internal fun ErrorBanner(
    code: String,
    onDismiss: () -> Unit,
) {
    CfoCard {
        Column(
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
        ) {
            Text(
                text = stringResource(AccountLabels.errorMessage(code)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            CfoSecondaryButton(text = stringResource(R.string.accounts_error_dismiss), onClick = onDismiss)
        }
    }
}
