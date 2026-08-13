package com.aicfo.feature.budgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoListRow
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoAmountTextStyle
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.data.repository.CategoryBudget
import com.aicfo.domain.engines.budget.BudgetAlertBand

/**
 * The budgets screen (issue 4.4; FR-BUD-001/002/003, ARC-004).
 *
 * Why:  the first screen in the app that holds spending against a number the user chose. It follows
 *       the categories editor's shape exactly: a stateful entry point that collects the ViewModel, a
 *       stateless body that renders a state, and navigation as lambdas rather than a `NavController`
 *       — so this module never learns another feature exists (ARC-001).
 * What: each planned category with FR-BUD-003's four figures, the categories being spent in with no
 *       plan, the suggestions on offer with their reasoning, the amount sheet and the error banner.
 * Result: FR-BUD-001's CRUD and FR-BUD-002's one-tap accept are reachable, and every figure on the
 *       screen came from the engine (P-03) with a rule attached (P-02).
 * Changelog: 2026-08-11 — Created for issue 4.4.
 *
 * Input:  [modifier]; [viewModel]. Output: the rendered screen.
 */
@Composable
fun BudgetsScreen(
    modifier: Modifier = Modifier,
    viewModel: BudgetsViewModel = hiltViewModel(),
) {
    // collectAsStateWithLifecycle, not collectAsState: a backgrounded screen must stop collecting.
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    NotificationPermissionRequest(
        requested = uiState.requestNotificationPermission,
        onSettled = { viewModel.onEvent(BudgetsEvent.NotificationPermissionSettled) },
    )
    BudgetsContent(uiState = uiState, onEvent = viewModel::onEvent, modifier = modifier)
}

/**
 * The screen's body, with no dependencies of its own.
 * Why:    stateless, so a preview or a test can render any state — loading, empty, on-track,
 *         overspent, sheet open, error — without constructing a ViewModel.
 * Result: the rendered content.
 * Input:  [uiState]; [onEvent] — events up (ARC-004); [modifier]. Output: the composition.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 */
@Composable
fun BudgetsContent(
    uiState: BudgetsUiState,
    onEvent: (BudgetsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // One scrolling Column, not a LazyColumn, for the reason the categories editor records: with the
    // list lazy and the sheet above it the sheet itself could not scroll, and at 200% font Save was
    // unreachable. A taxonomy is tens of rows, so composing them all costs less than the machinery to
    // avoid it.
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(CfoDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd),
    ) {
        Text(
            text = stringResource(R.string.budgets_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        uiState.errorCode?.let { code ->
            ErrorBanner(code = code, onDismiss = { onEvent(BudgetsEvent.DismissError) })
        }

        // Above the plan, because it is the answer to the question the user opened this screen with
        // (issue 4.5, FR-BUD-004). It renders whether or not a notification was ever posted.
        BudgetAlertBanner(uiState = uiState)

        // Above the list rather than over it, for the reason `ReconcilePanel` states: the row the
        // sheet refers to stays visible and stays live underneath.
        uiState.editing?.let { editing -> BudgetEditorSheet(state = editing, onEvent = onEvent) }

        if (uiState.isEmpty) {
            EmptyState()
            return@Column
        }

        PlannedSection(uiState = uiState, onEvent = onEvent)
        SuggestionsSection(uiState = uiState, onEvent = onEvent)
        UnplannedSection(uiState = uiState, onEvent = onEvent)
    }
}

/**
 * The categories with a budget, worst first.
 * Why:    split out of [BudgetsContent] to stay within the 40-line function limit (§21.6), the same
 *         seam `DashboardContent` and `AccountsScreen` were split along.
 * Result: one card per planned category, or nothing when none is planned.
 * Input:  [uiState]; [onEvent]. Output: the composition.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 */
@Composable
private fun PlannedSection(
    uiState: BudgetsUiState,
    onEvent: (BudgetsEvent) -> Unit,
) {
    if (uiState.planned.isEmpty()) return
    Text(
        text = stringResource(R.string.budgets_section_planned),
        style = MaterialTheme.typography.titleMedium,
    )
    uiState.planned.forEach { budget ->
        BudgetCard(
            budget = budget,
            band = uiState.bandByCategoryId[budget.category.id],
            onEvent = onEvent,
        )
    }
}

/**
 * The categories being spent in with no budget at all.
 * Why:    a separate section rather than mixed in, because these rows answer a different question —
 *         not "how is this going?" but "did you mean to be spending here?". Each offers Set rather
 *         than Edit, and shows no pace figures: there is no plan to be ahead of.
 * Result: one row per unplanned category with spending, or nothing.
 * Input:  [uiState]; [onEvent]. Output: the composition.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 */
@Composable
private fun UnplannedSection(
    uiState: BudgetsUiState,
    onEvent: (BudgetsEvent) -> Unit,
) {
    if (uiState.unplanned.isEmpty()) return
    Text(
        text = stringResource(R.string.budgets_section_unplanned),
        style = MaterialTheme.typography.titleMedium,
    )
    uiState.unplanned.forEach { row ->
        CfoCard {
            Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
                CfoListRow(
                    title = row.category.name,
                    supporting = stringResource(R.string.budgets_unplanned_supporting),
                    trailing = {
                        BudgetAmountText(
                            amount = row.status.spent,
                            contentDescription = stringResource(R.string.budgets_spent_description),
                        )
                    },
                )
                CfoSecondaryButton(
                    text = stringResource(R.string.budgets_set),
                    onClick = { onEvent(BudgetsEvent.EditClicked(row.category.id)) },
                )
            }
        }
    }
}

/**
 * One planned category: FR-BUD-003's four figures and its actions.
 * Why:    the pace line is stated as a *comparison* rather than as a bare number, because "₹4,838"
 *         means nothing on its own — the user's question is whether they are ahead of it. The
 *         projection is shown only when the engine supplied one; below its elapsed-days floor the
 *         card says the month is too young to project rather than inventing a figure (P-03).
 * Result: a card. Input: [budget]; [band] — FR-BUD-004's crossed band, `null` when none; [onEvent].
 *         Output: the composition.
 * Changelog:
 *   2026-08-11 — Created for issue 4.4.
 *   2026-08-13 — Added the band chip for issue 4.5.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BudgetCard(
    budget: CategoryBudget,
    band: BudgetAlertBand?,
    onEvent: (BudgetsEvent) -> Unit,
) {
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            CfoListRow(
                title = budget.category.name,
                supporting = budget.plannedSupporting(),
                trailing = {
                    BudgetAmountText(
                        amount = budget.status.spent,
                        contentDescription = stringResource(R.string.budgets_spent_description),
                    )
                },
            )
            BudgetBandChip(band = band)
            BudgetFigures(budget = budget)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
                verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs),
            ) {
                CfoSecondaryButton(
                    text = stringResource(R.string.budgets_edit),
                    onClick = { onEvent(BudgetsEvent.EditClicked(budget.category.id)) },
                )
                budget.id?.let { id ->
                    CfoSecondaryButton(
                        text = stringResource(R.string.budgets_delete),
                        onClick = { onEvent(BudgetsEvent.DeleteClicked(id)) },
                    )
                }
            }
        }
    }
}

/**
 * FR-BUD-003's figures, as sentences.
 * Why:    split out of [BudgetCard] for the 40-line limit. Written as full lines rather than a
 *         two-column table because a table of six numbers at 200% font is unreadable on a phone, and
 *         because each of these needs a word to mean anything.
 * Result: remaining, pace and projection. Input: [budget]. Output: the composition.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 */
@Composable
private fun BudgetFigures(budget: CategoryBudget) {
    val status = budget.status
    Text(
        text =
            stringResource(
                R.string.budgets_remaining,
                MoneyFormatter.format(status.remaining),
                MoneyFormatter.format(status.budgeted),
            ),
        style = MaterialTheme.typography.bodyMedium,
        color = if (status.isOverspent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text =
            stringResource(
                if (status.isAheadOfPace) R.string.budgets_pace_ahead else R.string.budgets_pace_within,
                MoneyFormatter.format(status.safePaceToDate),
            ),
        style = MaterialTheme.typography.bodySmall,
    )
    val projected = status.projectedEndOfMonth
    Text(
        text =
            when {
                // Withheld, not zero: below the engine's elapsed-days floor a run rate would say more
                // about one coffee than about the month (RULE-BUD-PACE).
                projected == null -> stringResource(R.string.budgets_projection_pending)
                status.isProjectedToOverspend ->
                    stringResource(R.string.budgets_projection_over, MoneyFormatter.format(projected))
                else -> stringResource(R.string.budgets_projection_within, MoneyFormatter.format(projected))
            },
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * Result: the row's second line — what was planned, and what carried over if anything did.
 *         Rollover is named on the row rather than only in the sheet, because a budget that is
 *         larger than the number the user typed must say why on the face of it (P-02).
 *         Output: a [String].
 */
@Composable
private fun CategoryBudget.plannedSupporting(): String =
    if (rolloverEnabled && status.carriedOver > Money.ZERO) {
        stringResource(R.string.budgets_planned_with_rollover, MoneyFormatter.format(status.carriedOver))
    } else {
        stringResource(R.string.budgets_planned_supporting)
    }

/**
 * What a user with nothing planned and nothing to suggest sees.
 * Why:    an empty plan and a failed read must look different — one invites an action, the other
 *         reports a problem. See [BudgetsUiState.isEmpty] for the three states that all empty this
 *         list and why only one of them is an invitation.
 * Result: the composition. Input: none.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 */
@Composable
private fun EmptyState() {
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            Text(
                text = stringResource(R.string.budgets_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.budgets_empty_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * The error banner.
 * Why:    a polite live region, so a screen reader announces a rejected save without the user having
 *         to go looking for why nothing happened.
 * Result: the composition. Input: [code] — a code from `BudgetsViewModel`; [onDismiss].
 * Changelog: 2026-08-11 — Created for issue 4.4.
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
                text = stringResource(BudgetLabels.errorMessage(code)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            CfoSecondaryButton(text = stringResource(R.string.budgets_error_dismiss), onClick = onDismiss)
        }
    }
}

/**
 * A spend or budget figure — a **magnitude**, drawn without a sign's colour.
 *
 * Why:  `CfoAmountText` colours by sign, and every figure on this screen is a positive magnitude, so
 *       it rendered all of them in the "money in" green. Spending shown in the colour of income is
 *       actively misleading, and it survived every unit and Compose test because colour is not what
 *       any of them assert — it was caught by looking at the screen on a device.
 *
 *       The typography and the no-wrap rule still come from the design system: an amount that wraps
 *       reads as a different number, which is the lesson `CfoAmountText` records from a screenshot
 *       test. Only the colour is neutral here.
 * Result: the formatted amount, announced with the caller's wording.
 * Input:  [amount] — paise (MNY-001); [contentDescription] — localised, from the caller.
 * Output: the composition.
 * Changelog: 2026-08-11 — Created for issue 4.4, after the emulator run.
 */
@Composable
internal fun BudgetAmountText(
    amount: Money,
    contentDescription: String,
) {
    Text(
        text = MoneyFormatter.format(amount),
        style = CfoAmountTextStyle,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.semantics { this.contentDescription = contentDescription },
    )
}
