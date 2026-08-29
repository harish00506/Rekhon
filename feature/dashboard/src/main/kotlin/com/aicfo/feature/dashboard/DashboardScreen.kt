package com.aicfo.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicfo.core.designsystem.chart.CfoProportionBar
import com.aicfo.core.designsystem.chart.CfoProportionSegment
import com.aicfo.core.designsystem.component.CfoAmountText
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoListRow
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.component.maskedAmount
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.DateFormatter
import com.aicfo.core.model.Money
import com.aicfo.data.repository.CategoryBudgetAlert
import com.aicfo.domain.engines.safetospend.SafeToSpend
import com.aicfo.domain.engines.safetospend.SafeToSpendComponent

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
 * Input:  [actions] — where each navigation action goes; [modifier]; [viewModel] — supplied by
 *         Hilt, overridable in tests.
 * Output: the rendered screen.
 */
@Composable
fun DashboardScreen(
    actions: DashboardActions,
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    // collectAsStateWithLifecycle, not collectAsState: the screen must stop collecting when it is
    // not visible, or a backgrounded app keeps doing work the user cannot see.
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Issue 5.4: the file pickers need an Activity, so they are owned here — in the stateful half
    // — and the body below receives one plain lambda. See ArchiveHost for what a test found.
    ArchiveHost(state = uiState.archive, onEvent = viewModel::onEvent) { onPickArchive ->
        DashboardContent(
            uiState = uiState,
            onEvent = viewModel::onEvent,
            actions = actions,
            modifier = modifier,
            onPickArchive = onPickArchive,
        )
    }
}

/**
 * The dashboard's body, with no dependencies of its own.
 * Why:    stateless, so a preview or a screenshot test can render any state — including loading and
 *         error — without constructing a ViewModel.
 * Result: the rendered content.
 * Input:  [uiState]; [onEvent] — events up (ARC-004); [actions] — where each button goes;
 *         [modifier].
 * Output: the composition.
 * Changelog: 2026-07-25 — Created for issue 1.10.
 *            2026-08-11 — Issue 4.4: the three navigation lambdas became [DashboardActions].
 */
@Composable
fun DashboardContent(
    uiState: DashboardUiState,
    onEvent: (DashboardEvent) -> Unit,
    actions: DashboardActions,
    modifier: Modifier = Modifier,
    // Defaulted so a preview or a screenshot test can render the whole screen without an Activity,
    // which is a property this composable is documented to have (issue 5.4).
    onPickArchive: () -> Unit = {},
) {
    Column(
        // Issue 5.1: the reference implementation (1.10) never scrolled — it had four rows. This
        // issue's three added sections are what first pushed the bottom of the screen, including
        // every navigation button, past the fold; without this the buttons render but are
        // unreachable on any device shorter than the content, found by running the app, not by a
        // test (no Compose UI test measures a real screen height against real content).
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(CfoDimens.spaceMd),
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
        // Issue 5.1: three more figures, each already real from its own repository — none computed
        // on this screen (P-03).
        CashFlowSection(uiState)
        BudgetStatusSection(uiState)
        RecentActivitySection(uiState)

        // Directly under the split bar rather than with the other actions: the bar is the plan at
        // the nature level and the budgets screen is the plan at the category level, so the way to
        // change what the bar shows sits next to the bar (issue 4.4).
        CfoSecondaryButton(
            text = stringResource(R.string.dashboard_budgets_action),
            onClick = actions.onNavigateToBudgets,
        )
        DestinationButtons(actions = actions)
        // Issue 5.4: §5.10's portability, below the navigation actions — a thing the user does
        // occasionally, not a destination. Owns the file pickers, so the Uris stay in one file.
        ArchiveSection(state = uiState.archive, onEvent = onEvent, onPickArchive = onPickArchive)
        CfoSecondaryButton(
            text = stringResource(R.string.dashboard_refresh_action),
            onClick = { onEvent(DashboardEvent.Refresh) },
        )
    }
}

/**
 * The two headline figures.
 * Why:    split out of [DashboardContent] to stay within the 40-line function limit (§21.6).
 * Result: the summary cards. Input: [uiState]. Output: the composition.
 * Changelog: 2026-07-25 — Created for issue 1.10.
 *            2026-08-16 — Issue 5.2: Safe-to-Spend became a real figure with a breakdown.
 */
@Composable
private fun MoneySummary(uiState: DashboardUiState) {
    CfoCard {
        Text(text = stringResource(R.string.dashboard_safe_to_spend_label))
        SafeToSpendSection(uiState.safeToSpend)
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
 * The Safe-to-Spend figure with the breakdown that produced it (issue 5.2; §5.2, AI-STS, P-02).
 *
 * Why:    **the breakdown is not optional decoration.** §5.2's acceptance criterion is that the card
 *         shows "the breakdown/rule that produced it; never a black-box number", and a headline of
 *         ₹34,600 with nothing under it is exactly the black box it forbids — the user has no way to
 *         tell an unusually good month from a bill the app has not noticed. Every line comes
 *         straight off [SafeToSpend.lines]; this composable re-derives nothing, so the lines and the
 *         figure above them cannot disagree (P-03).
 *
 *         **Signed**, unlike the ₹-only rendering it replaces. A month that is already overcommitted
 *         reports its shortfall, and hiding the minus would turn "you are ₹8,000 past the plan" into
 *         "you have ₹8,000 to spend" — the single most expensive thing this screen could get wrong.
 * Result: the figure, its lines, and the `RULE-STS` citation — or the pending line when there is no
 *         income basis to work from, which is never rendered as ₹0 (P-03).
 * Input:  [safeToSpend] — the engine's result, `null` before the first emission or when the profile
 *         has declared no income. Output: the composition.
 * Changelog: 2026-08-16 — Created for issue 5.2.
 */
@Composable
private fun SafeToSpendSection(safeToSpend: SafeToSpend?) {
    if (safeToSpend == null) {
        Text(text = stringResource(R.string.dashboard_safe_to_spend_pending))
        return
    }
    CfoAmountText(
        amount = safeToSpend.amount,
        contentDescription = stringResource(R.string.dashboard_safe_to_spend_description),
        showSign = true,
    )
    safeToSpend.lines.forEach { line ->
        Text(
            text =
                stringResource(
                    R.string.dashboard_safe_to_spend_line,
                    stringResource(line.component.labelRes),
                    // signedAmount, not amount: rendered as magnitudes the column reads as six
                    // additions that plainly do not sum to the headline, so a user cannot check the
                    // figure — which is the entire point of showing it (P-02). The minus is what
                    // makes the arithmetic verifiable at a glance.
                    maskedAmount(line.signedAmount),
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val citation = safeToSpend.provenance.evidence.firstOrNull() ?: return
    Text(
        text = stringResource(R.string.dashboard_reason_rule, citation.ruleId, citation.ruleVersion),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The `strings.xml` label for one breakdown term (issue 5.2; §21.6).
 * Why:    the mapping lives here, in the feature module, because `:domain:engines:safetospend` is
 *         pure Kotlin and must hold no user-visible copy (ARC-002) — the engine names a term and
 *         this names it in the user's language. An exhaustive `when` so a component added later
 *         fails to compile rather than rendering as a blank line on the card that exists to explain
 *         the figure.
 * Result: the string resource. Input: the receiver. Output: a resource id.
 * Changelog: 2026-08-16 — Created for issue 5.2.
 */
private val SafeToSpendComponent.labelRes: Int
    get() =
        when (this) {
            SafeToSpendComponent.INCOME -> R.string.dashboard_sts_income
            SafeToSpendComponent.BUFFER -> R.string.dashboard_sts_buffer
            SafeToSpendComponent.SPENT -> R.string.dashboard_sts_spent
            SafeToSpendComponent.SCHEDULED -> R.string.dashboard_sts_scheduled
            SafeToSpendComponent.RECURRING -> R.string.dashboard_sts_recurring
            SafeToSpendComponent.GOALS -> R.string.dashboard_sts_goals
            SafeToSpendComponent.SHORTFALL -> R.string.dashboard_sts_shortfall
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
                maskedAmount(breakdown.needs),
                maskedAmount(breakdown.wants),
                maskedAmount(breakdown.invested),
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

/**
 * This month's income and expense (issue 5.1; FR-DASH-*).
 * Why:    a raw ledger fact, not a judgment — no rule fired deciding it, so unlike
 *         [BudgetAlertsLine] there is nothing to cite (P-02 only asks for a citation where one
 *         exists). Nothing before the first read, the same rule [ActualSpendSection] follows.
 * Result: the line, or nothing. Input: [uiState]. Output: none.
 * Changelog: 2026-08-15 — Created for issue 5.1.
 */
@Composable
private fun CashFlowSection(uiState: DashboardUiState) {
    val flow = uiState.cashFlow ?: return
    Text(text = stringResource(R.string.dashboard_cash_flow_label))
    Text(
        text =
            stringResource(
                R.string.dashboard_cash_flow_values,
                maskedAmount(flow.income),
                maskedAmount(flow.expense),
            ),
        style = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * The budget summary card, or the empty state when nothing is planned yet (issue 5.1; FR-DASH-*).
 * Why:    the totals come from [DashboardUiState.budgetTotals], already folded (P-03) — this only
 *         renders them. Unlike [CashFlowSection] this always shows its label, matching
 *         [SpendSplitSection]'s choice: the empty case here has an action ("set one from Your
 *         budgets"), so it is worth a line rather than silence.
 * Result: the card, or the empty-state line. Input: [uiState]. Output: none.
 * Changelog: 2026-08-15 — Created for issue 5.1.
 */
@Composable
private fun BudgetStatusSection(uiState: DashboardUiState) {
    Text(text = stringResource(R.string.dashboard_budget_status_label))
    val totals = uiState.budgetTotals
    if (totals == null) {
        Text(text = stringResource(R.string.dashboard_budget_status_empty))
        return
    }
    Text(
        text =
            stringResource(
                R.string.dashboard_budget_status_values,
                maskedAmount(totals.spent),
                maskedAmount(totals.budgeted),
            ),
        style = MaterialTheme.typography.bodyMedium,
    )
    BudgetAlertsLine(uiState.budgetAlerts)
}

/**
 * The "needs attention" line, with the rule that fired (issue 5.1; FR-BUD-004, P-02).
 * Why:    split out of [BudgetStatusSection] to stay within the 40-line limit (§21.6), and because
 *         it is the one figure on this card that a rule decided rather than a plain sum — the same
 *         citation `:feature:budgets`' own banner shows for the identical alert (`BudgetReviewCard`
 *         and `BudgetAlertBanner` set the precedent this copies).
 * Result: the count and its citation, or nothing when there is nothing to warn about.
 * Input:  [alerts]. Output: none.
 * Changelog: 2026-08-15 — Created for issue 5.1.
 */
@Composable
private fun BudgetAlertsLine(alerts: List<CategoryBudgetAlert>) {
    if (alerts.isEmpty()) return
    Text(
        text = pluralStringResource(R.plurals.dashboard_budget_alerts, alerts.size, alerts.size),
        style = MaterialTheme.typography.bodySmall,
        color = CfoTheme.extendedColors.warning,
    )
    val citation = alerts.first().alert.provenance.evidence.firstOrNull() ?: return
    Text(
        text = stringResource(R.string.dashboard_reason_rule, citation.ruleId, citation.ruleVersion),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The recent-activity preview, or the empty state (issue 5.1; FR-DASH-*).
 * Why:    a preview only — [TransactionRepository.observeRecent]'s own doc comment is why this does
 *         not reintroduce the fixed-window mistake issue 3.6 fixed. "See all" is
 *         `DashboardActions.onNavigateToTransactions`, already on screen below this section.
 * Result: up to a handful of rows, newest first, or the empty-state line. Input: [uiState].
 * Output: none.
 * Changelog: 2026-08-15 — Created for issue 5.1.
 */
@Composable
private fun RecentActivitySection(uiState: DashboardUiState) {
    val recent = uiState.recentActivity
    Text(text = stringResource(R.string.dashboard_recent_activity_label))
    if (recent.isNullOrEmpty()) {
        Text(text = stringResource(R.string.dashboard_recent_activity_empty))
        return
    }
    recent.forEach { row ->
        val transaction = row.transaction
        CfoListRow(
            title = transaction.merchant ?: stringResource(R.string.dashboard_recent_activity_unnamed),
            // DateFormatter, not the raw field: `bookedOn` is a stored ISO string (TIM-002), and
            // this rendered a literal "2026-08-15" to users until a 2026-08-16 review caught it.
            supporting = DateFormatter.day(transaction.bookedOn),
            trailing = {
                CfoAmountText(
                    amount = transaction.amount,
                    contentDescription = maskedAmount(transaction.amount),
                )
            },
        )
    }
}

/**
 * Where the dashboard's buttons go (ARC-001).
 *
 * Why:  three navigation lambdas as three parameters took [DashboardContent] to detekt's
 *       `LongParameterList` ceiling, and the ceiling was pointing at something real — a call site
 *       passing three same-shaped `() -> Unit` arguments positionally is one transposition away from
 *       sending a user to the wrong screen, and the compiler cannot help. Named fields on a value
 *       make the mistake unrepresentable.
 *
 *       **Still lambdas, not a `NavController`.** ARC-001 keeps routing in `:app`'s nav graph; this
 *       type only groups the callbacks, so the module still does not know another feature exists.
 * What: one callback per destination reachable from the dashboard.
 * Result: adding a fourth destination is a field here rather than a fourth parameter there.
 * Changelog: 2026-08-11 — Created for issue 4.4, when budgets became the third destination.
 *
 * Input:  [onNavigateToTransactions]; [onNavigateToAccounts]; [onNavigateToSettings] — FR-SET-001's
 *         income, consents and app lock; [onNavigateToBudgets] — issue 4.4's
 *         per-category plan, behind the nature-level bar this screen already draws.
 * Output: an immutable value.
 */
@Immutable
data class DashboardActions(
    val onNavigateToTransactions: () -> Unit,
    val onNavigateToAccounts: () -> Unit,
    val onNavigateToBudgets: () -> Unit,
    val onNavigateToSettings: () -> Unit,
)
