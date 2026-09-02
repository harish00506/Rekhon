package com.aicfo.feature.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aicfo.core.designsystem.component.CfoSecondaryButton

/**
 * The three places the dashboard can send the user.
 * Why:    extracted because adding FR-SET-001's settings button took [DashboardContent] past
 *         detekt's 40-line limit (§21.6) — the same pressure that produced [DashboardActions]
 *         itself. Grouping them also puts the navigation in one place rather than interleaved with
 *         the cards.
 * Result: the composition. Input: [actions]. Output: none.
 * Changelog: 2026-08-29 — Created for FR-SET-001.
 */
@Composable
internal fun DestinationButtons(actions: DashboardActions) {
    CfoSecondaryButton(
        text = stringResource(R.string.dashboard_accounts_action),
        onClick = actions.onNavigateToAccounts,
    )
    CfoSecondaryButton(
        text = stringResource(R.string.dashboard_transactions_action),
        onClick = actions.onNavigateToTransactions,
    )
    // Issue 7.1: beside the budgets button, one line up. A budget is the plan for this month and a
    // goal is the plan for the years after it — the same plan at two horizons, so the same place.
    CfoSecondaryButton(
        text = stringResource(R.string.dashboard_goals_action),
        onClick = actions.onNavigateToGoals,
    )
    // Issue 7.2: immediately below goals, because §10.1 gates goals on the runway — no goal
    // below the emergency fund gets funded while cover is under three months (RULE-EMERG-FIRST).
    CfoSecondaryButton(
        text = stringResource(R.string.dashboard_emergency_fund_action),
        onClick = actions.onNavigateToEmergencyFund,
    )
    // Reached from here because this is the screen whose two empty states depend on the monthly
    // income it sets — and because until it existed those states told the user to visit a Settings
    // screen the app did not have.
    CfoSecondaryButton(
        text = stringResource(R.string.dashboard_settings_action),
        onClick = actions.onNavigateToSettings,
    )
}
