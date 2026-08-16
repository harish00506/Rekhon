package com.aicfo.feature.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import com.aicfo.core.designsystem.component.CfoAmountText
import com.aicfo.core.designsystem.component.CfoButton
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoListRow
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.component.maskedAmount
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.model.Account

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
 * Input:  [onAddAccount], [onEditAccount] — where the editor lives; [modifier]; [viewModel].
 * Output: the rendered screen.
 */
@Composable
fun AccountsScreen(
    onAddAccount: () -> Unit,
    onEditAccount: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AccountsViewModel = hiltViewModel(),
) {
    // collectAsStateWithLifecycle, not collectAsState: a backgrounded screen must stop collecting.
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    AccountsContent(
        uiState = uiState,
        onEvent = viewModel::onEvent,
        onAddAccount = onAddAccount,
        onEditAccount = onEditAccount,
        modifier = modifier,
    )
}

/**
 * The accounts list's body, with no dependencies of its own.
 * Why:    stateless, so a preview or a test can render any state — loading, empty, populated,
 *         error — without constructing a ViewModel.
 * Result: the rendered content.
 * Input:  [uiState]; [onEvent] — events up (ARC-004); [onAddAccount]; [onEditAccount]; [modifier].
 * Output: the composition.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 */
@Composable
fun AccountsContent(
    uiState: AccountsUiState,
    onEvent: (AccountsEvent) -> Unit,
    onAddAccount: () -> Unit,
    onEditAccount: (String) -> Unit,
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

        CfoButton(text = stringResource(R.string.accounts_add), onClick = onAddAccount)

        ArchivedToggle(
            checked = uiState.showArchived,
            onCheckedChange = { onEvent(AccountsEvent.ToggleArchived(it)) },
        )

        // Above the list rather than over it: the panel is where the user's attention has to be,
        // and the row it came from stays visible and stays live underneath (issue 2.7).
        uiState.reconciling?.let { reconciling ->
            ReconcilePanel(state = reconciling, onEvent = onEvent)
        }

        if (uiState.isEmpty) {
            EmptyState()
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
                items(uiState.accounts, key = { it.id }) { account ->
                    AccountRow(
                        account = account,
                        onEdit = { onEditAccount(account.id) },
                        onEvent = onEvent,
                    )
                }
            }
        }
    }
}

/**
 * One account in the list.
 * Why:    the balance shown is the **derived** one (DB-001), and the opening balance is relegated to
 *         the supporting line — those are two different facts and a row that showed only one would
 *         make the other unverifiable (P-02).
 * Result: a tappable row with its archive and delete actions.
 * Input:  [account]; [onEdit]; [onEvent]. Output: the composition.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountRow(
    account: Account,
    onEdit: () -> Unit,
    onEvent: (AccountsEvent) -> Unit,
) {
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            CfoListRow(
                title = account.rowTitle(),
                supporting = account.rowSupporting(),
                trailing = { CfoAmountText(amount = account.balance) },
            )
            // FlowRow, not Row: three buttons do not fit one line once the archive action reads
            // "Reopen account" rather than "Close account", and a plain Row pushes Delete off the
            // right edge with nothing to scroll. Found on a device, not in a test — at 200% font
            // even the shorter labels wrap. Issue 2.7's Reconcile makes it four, so the wrapping
            // this already did is now load-bearing rather than a precaution.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
                verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs),
            ) {
                CfoSecondaryButton(text = stringResource(R.string.account_editor_title_edit), onClick = onEdit)
                CfoSecondaryButton(
                    // Offered on a closed account too: recording its final statement balance is
                    // exactly when a user reaches for this (FR-ACC-006 with FR-ACC-007).
                    text = stringResource(R.string.accounts_reconcile),
                    onClick = { onEvent(AccountsEvent.OpenReconcile(account.id)) },
                )
                CfoSecondaryButton(
                    text =
                        stringResource(
                            if (account.isArchived) R.string.accounts_unarchive else R.string.accounts_archive,
                        ),
                    onClick = { onEvent(AccountsEvent.SetArchived(account.id, !account.isArchived)) },
                )
                CfoSecondaryButton(
                    text = stringResource(R.string.accounts_delete),
                    onClick = { onEvent(AccountsEvent.Delete(account.id)) },
                )
            }
        }
    }
}

/** Result: the row's title — the name, marked when closed (FR-ACC-007). Output: a [String]. */
@Composable
private fun Account.rowTitle(): String =
    if (isArchived) {
        "$name · ${stringResource(R.string.accounts_archived_label)}"
    } else {
        name
    }

/**
 * Result: the row's second line — the type, the institution when there is one, and what the account
 *         opened with. Output: a [String].
 */
@Composable
private fun Account.rowSupporting(): String {
    val typeLabel = stringResource(AccountLabels.typeLabel(type))
    val opened = maskedAmount(openingBalance)
    return if (institution.isNullOrBlank()) {
        "$typeLabel · ${stringResource(R.string.accounts_row_supporting_no_institution, opened)}"
    } else {
        "$typeLabel · ${stringResource(R.string.accounts_row_supporting, institution!!, opened)}"
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
private fun ErrorBanner(
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
