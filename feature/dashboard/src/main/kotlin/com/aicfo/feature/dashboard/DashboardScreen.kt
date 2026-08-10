package com.aicfo.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicfo.core.designsystem.chart.CfoProportionBar
import com.aicfo.core.designsystem.chart.CfoProportionSegment
import com.aicfo.core.designsystem.component.CfoAmountText
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter

/**
 * The dashboard (ARC-004) — the app's home screen.
 *
 * Why:  the reference for every screen that follows. Two rules are visible in its shape: the
 *       composable is a **function of state** with no logic of its own, and navigation arrives as
 *       a lambda rather than a `NavController`, so `:feature:dashboard` never learns that
 *       `:feature:transactions` exists (ARC-001).
 * What: a stateful entry point that collects the ViewModel, and a stateless body that renders a
 *       [DashboardUiState].
 * Result: a screen that can be previewed, screenshot-tested and reasoned about without Hilt or
 *       navigation.
 * Changelog: 2026-07-25 — Created for issue 1.10.
 *
 * The figures are placeholders until issues 5.1/5.2; the structure is not.
 *
 * Input:  [onNavigateToTransactions], [onNavigateToAccounts] — where each action goes; [modifier];
 *         [viewModel] — supplied by Hilt, overridable in tests.
 * Output: the rendered screen.
 */
@Composable
fun DashboardScreen(
    onNavigateToTransactions: () -> Unit,
    onNavigateToAccounts: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    // collectAsStateWithLifecycle, not collectAsState: the screen must stop collecting when it is
    // not visible, or a backgrounded app keeps doing work the user cannot see.
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    DashboardContent(
        uiState = uiState,
        onEvent = viewModel::onEvent,
        onNavigateToTransactions = onNavigateToTransactions,
        onNavigateToAccounts = onNavigateToAccounts,
        modifier = modifier,
    )
}

/**
 * The dashboard's body, with no dependencies of its own.
 * Why:    stateless, so a preview or a screenshot test can render any state — including loading and
 *         error — without constructing a ViewModel.
 * Result: the rendered content.
 * Input:  [uiState]; [onEvent] — events up (ARC-004); [onNavigateToTransactions];
 *         [onNavigateToAccounts]; [modifier].
 * Output: the composition.
 * Changelog: 2026-07-25 — Created for issue 1.10.
 */
@Composable
fun DashboardContent(
    uiState: DashboardUiState,
    onEvent: (DashboardEvent) -> Unit,
    onNavigateToTransactions: () -> Unit,
    onNavigateToAccounts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(CfoDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd),
    ) {
        Text(text = stringResource(R.string.dashboard_title), style = MaterialTheme.typography.headlineSmall)

        if (uiState.isLoading) {
            Text(text = stringResource(R.string.dashboard_loading), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        if (uiState.errorCode != null) {
            Text(text = stringResource(R.string.dashboard_error), color = CfoTheme.extendedColors.negative)
        }

        MoneySummary(uiState)
        SpendSplitSection(uiState)
        // Issue 4.3: the plan is above, the outcome is here. Adjacent on purpose.
        ActualSpendSection(uiState)

        CfoSecondaryButton(
            text = stringResource(R.string.dashboard_accounts_action),
            onClick = onNavigateToAccounts,
        )
        CfoSecondaryButton(
            text = stringResource(R.string.dashboard_transactions_action),
            onClick = onNavigateToTransactions,
        )
        CfoSecondaryButton(
            text = stringResource(R.string.dashboard_refresh_action),
            onClick = { onEvent(DashboardEvent.Refresh) },
        )
    }
}

/**
 * The two headline figures.
 * Why:    split out of [DashboardContent] to stay within the 40-line function limit (§21.6), and
 *         because the summary is the part a future issue (5.2) will replace wholesale.
 * Result: the summary cards. Input: [uiState]. Output: the composition.
 * Changelog: 2026-07-25 — Created for issue 1.10.
 */
@Composable
private fun MoneySummary(uiState: DashboardUiState) {
    CfoCard {
        Text(text = stringResource(R.string.dashboard_safe_to_spend_label))
        CfoAmountText(
            amount = uiState.safeToSpend,
            contentDescription = stringResource(R.string.dashboard_safe_to_spend_description),
            showSign = false,
        )
    }
    CfoCard {
        Text(text = stringResource(R.string.dashboard_net_worth_label))
        // The absence is rendered, not zeroed: no snapshot has been taken yet, and ₹0 would be a
        // figure the app invented (P-03, issue 2.6).
        val netWorth = uiState.netWorth
        if (netWorth == null) {
            Text(text = stringResource(R.string.dashboard_net_worth_pending))
        } else {
            CfoAmountText(
                amount = netWorth,
                contentDescription = stringResource(R.string.dashboard_net_worth_description),
                // Signed: a user who owes more than they hold has a negative net worth, and hiding
                // the minus would turn a debt into savings.
                showSign = true,
            )
        }
    }
}

/**
 * The needs/wants/savings bar, or the empty state when there is no budget.
 * Why:    since issue 2.3 these are the user's real envelopes, so the absent case is now reachable:
 *         someone who skipped quick setup has no budget at all. Rendering a bar of three zeroes for
 *         them would be a chart of numbers nobody supplied (P-03), and it looks identical to a
 *         genuine budget of ₹0 — so the two are drawn differently.
 * Result: the labelled chart, or a line explaining how to get one. Input: [uiState].
 * Output: the composition.
 * Changelog: 2026-07-25 — Created for issue 1.10.
 *            2026-07-27 — Issue 2.3: real figures plus the empty state.
 */
@Composable
private fun SpendSplitSection(uiState: DashboardUiState) {
    Text(text = stringResource(R.string.dashboard_spend_split_label))
    val split = uiState.spendSplit
    if (split == null) {
        Text(text = stringResource(R.string.dashboard_spend_split_empty))
        return
    }
    CfoProportionBar(
        segments =
            listOf(
                CfoProportionSegment(split.needsMinor, CfoTheme.extendedColors.negative),
                CfoProportionSegment(split.wantsMinor, CfoTheme.extendedColors.warning),
                CfoProportionSegment(split.savingsMinor, CfoTheme.extendedColors.positive),
            ),
        contentDescription = stringResource(R.string.dashboard_spend_split_description),
    )
}

/**
 * What this month's money actually became (issue 4.3; SRS §8.3).
 *
 * Why:    the counterpart to [SpendSplitSection], and the two are deliberately adjacent. That one is
 *         the **plan** — the envelopes quick setup persisted. This is the **outcome**, classified
 *         from the ledger by §8.3.1. A dashboard showing only the plan is a dashboard that never
 *         disagrees with the user.
 *
 *         **The caveat is on screen, not only in ENGINE.md.** §8.3's true spend is
 *         `NEED + WANT + interest/fees`, and this build cannot split an EMI into principal and
 *         interest — so the figure is short by exactly that interest. An understated number the user
 *         is told about is a different thing from one they are not (P-02), and a footnote costs one
 *         line where a silently wrong total costs their trust.
 *
 *         Nothing at all before the first emission or for a month with no transactions, the same
 *         rule the split above follows: a row of zeroes is a figure the app made up (P-03).
 * Result: the composition, or nothing. Input: [uiState]. Output: none.
 * Changelog: 2026-08-10 — Created for issue 4.3.
 */
@Composable
private fun ActualSpendSection(uiState: DashboardUiState) {
    val breakdown = uiState.natureBreakdown
    if (breakdown == null || breakdown.isEmpty) return

    Text(text = stringResource(R.string.dashboard_actual_label))
    Text(
        text =
            stringResource(
                R.string.dashboard_actual_values,
                MoneyFormatter.format(breakdown.needs),
                MoneyFormatter.format(breakdown.wants),
                MoneyFormatter.format(breakdown.invested),
            ),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (breakdown.liabilities != Money.ZERO) {
        Text(
            text = stringResource(R.string.dashboard_actual_debt_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
