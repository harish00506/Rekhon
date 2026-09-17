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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.component.maskedAmount
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.goals.SurplusBasis
import com.aicfo.domain.engines.orderofoperations.DebtPosition
import com.aicfo.domain.engines.orderofoperations.FooStage
import com.aicfo.domain.engines.orderofoperations.OrderOfOperations
import com.aicfo.domain.engines.orderofoperations.StageOutcome
import com.aicfo.domain.engines.orderofoperations.StageStatus

/**
 * The full Financial Order of Operations (issue 7.5; §36, FOO-002, P-02, P-07).
 *
 * Why:  the dashboard card names one action; this screen shows **all eight stages** — the ones that
 *       ask for money, the ones already done, and the ones skipped or held — each with its reason and
 *       the rule that placed it. §36 is explicit that "every skipped stage shows why", and a list that
 *       only showed the stages with work to do would hide exactly the ones the user most needs
 *       explained.
 * What: a stateful entry point over [OrderOfOperationsViewModel] and a stateless body.
 * Result: the ranking, with a way to the screen that acts on each stage it can (goals and the
 *       emergency fund).
 * Changelog: 2026-09-17 — Created for issue 7.5.
 *
 * Lives in `:feature:dashboard` because the card that opens it does, and feature modules may not
 * depend on each other (ARC-001). When Epic 10 builds the Advisor hub §36 names, this list moves
 * there (ADR-0037).
 *
 * Input:  [actions] — where each button goes; [viewModel] — supplied by Hilt. Output: the screen.
 */
@Composable
fun OrderOfOperationsScreen(
    actions: OrderOfOperationsActions,
    viewModel: OrderOfOperationsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    OrderOfOperationsContent(uiState = uiState, onEvent = viewModel::onEvent, actions = actions)
}

/**
 * The screen's body, with no dependencies of its own.
 * Why:    stateless, so a test can render any state without Hilt or navigation.
 * Result: the composition. Input: [uiState]; [onEvent]; [actions]. Output: none.
 */
@Composable
internal fun OrderOfOperationsContent(
    uiState: OrderOfOperationsUiState,
    onEvent: (OrderOfOperationsEvent) -> Unit,
    actions: OrderOfOperationsActions,
) {
    // A plain scrolling Column, the emergency-fund screen's reasoning: eight cards and a disclaimer
    // that must stay reachable — nothing here needs virtualising.
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(CfoDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd),
    ) {
        Text(text = stringResource(R.string.foo_title), style = MaterialTheme.typography.headlineSmall)
        Text(text = stringResource(R.string.foo_intro), style = MaterialTheme.typography.bodyMedium)
        uiState.errorCode?.let {
            Text(text = stringResource(R.string.foo_error), color = CfoTheme.extendedColors.negative)
            CfoSecondaryButton(
                text = stringResource(R.string.foo_dismiss_error),
                onClick = { onEvent(OrderOfOperationsEvent.DismissError) },
            )
        }
        if (uiState.isLoading) Text(text = stringResource(R.string.foo_loading))
        uiState.ranking?.let { ranking -> Ranking(ranking, actions) }
        CfoSecondaryButton(text = stringResource(R.string.foo_back), onClick = actions.onDone)
        Text(text = stringResource(R.string.foo_disclaimer), style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * The ranking: where the surplus came from, each stage, and what is left.
 * Result: the composition. Input: [ranking]; [actions]. Output: none.
 */
@Composable
private fun Ranking(
    ranking: OrderOfOperations,
    actions: OrderOfOperationsActions,
) {
    SurplusBasisLine(ranking)
    ranking.stages.forEach { stage -> StageCard(stage, actions) }
    if (ranking.unallocated > Money.ZERO) {
        Text(text = stringResource(R.string.foo_left_over, maskedAmount(ranking.unallocated)))
    }
}

/**
 * Says where the surplus figure came from (P-02; ADR-0035).
 * Why:    §36 asks for the *forecast* surplus and there is no forecast yet, so the figure every amount
 *         below is poured from is a stand-in — and the user should know which one.
 * Result: one line. Input: [ranking]. Output: none.
 */
@Composable
private fun SurplusBasisLine(ranking: OrderOfOperations) {
    val surplus = ranking.monthlySurplus
    val text =
        when {
            surplus == null || ranking.surplusBasis == SurplusBasis.NONE -> stringResource(R.string.foo_basis_none)
            ranking.surplusBasis == SurplusBasis.OBSERVED_MEDIAN ->
                stringResource(R.string.foo_basis_observed, maskedAmount(surplus))
            else -> stringResource(R.string.foo_basis_declared, maskedAmount(surplus))
        }
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}

/**
 * One stage: its name and step number, status, reason, figures, debts, the way to act, the rules.
 * Result: the card. Input: [stage]; [actions]. Output: none.
 */
@Composable
private fun StageCard(
    stage: StageOutcome,
    actions: OrderOfOperationsActions,
) {
    CfoCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs)) {
            Text(
                text =
                    stringResource(
                        R.string.foo_step,
                        // §36 numbers the stages from 0; a person counts from 1. The citation below
                        // still names the stage by its file id, so nothing traceable is lost.
                        stage.stage.ordinal + 1,
                        stringResource(OrderOfOperationsLabels.stage(stage.stage)),
                    ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(OrderOfOperationsLabels.status(stage.status)),
                style = MaterialTheme.typography.labelLarge,
                color = statusColor(stage.status),
            )
            Text(
                text = stringResource(OrderOfOperationsLabels.reason(stage.reason)),
                style = MaterialTheme.typography.bodyMedium,
            )
            StageFigures(stage)
            StageAction(stage, actions)
            RulesLine(stage)
        }
    }
}

/**
 * What the stage needs, what this month suggests for it, and the debts behind it.
 * Why:    split from [StageCard] for the 40-line limit (§21.6). A need of zero is not shown — a
 *         satisfied stage already says so in words, and "Needed: ₹0" adds nothing.
 * Result: the lines that apply. Input: [stage]. Output: none.
 */
@Composable
private fun StageFigures(stage: StageOutcome) {
    val need = stage.need
    if (need != null && need > Money.ZERO) {
        Text(text = stringResource(R.string.foo_needs, maskedAmount(need)))
    }
    if (stage.amountMonthly > Money.ZERO) {
        Text(text = stringResource(R.string.foo_this_month, maskedAmount(stage.amountMonthly)))
    }
    stage.debts.forEach { debt -> DebtLine(debt) }
    val comparison = stage.comparisonBps
    if (comparison != null && stage.debts.isNotEmpty()) {
        Text(
            text = stringResource(R.string.foo_compare, ratePercent(comparison)),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * One debt: its name, what is owed, and its rate — or that the rate was never entered.
 * Result: one line. Input: [debt]. Output: none.
 */
@Composable
private fun DebtLine(debt: DebtPosition) {
    val rate = debt.aprBps
    val text =
        if (rate == null) {
            stringResource(R.string.foo_debt_unrated, debt.name, maskedAmount(debt.outstanding))
        } else {
            stringResource(R.string.foo_debt_rated, debt.name, maskedAmount(debt.outstanding), ratePercent(rate))
        }
    Text(text = text, style = MaterialTheme.typography.bodySmall)
}

/**
 * The one screen that acts on this stage, when there is one.
 * Why:    advice the user cannot act on from here is a dead end (P-07 keeps the acting to the user,
 *         not the looking). Two stages have a screen that moves them forward: the emergency fund and
 *         the goals.
 *
 *         **No button for a card with no rate**, though one was built. Running the app found that
 *         the card editor (issue 6.1) has no rate field at all — `credit_card.apr_bps` is in the
 *         schema and nothing in the UI writes it — so "add the rate in Accounts" sent the user to a
 *         screen where they could not. A button whose promise the app cannot keep is worse than none
 *         (ADR-0037).
 * Result: a button, or nothing. Input: [stage]; [actions]. Output: none.
 */
@Composable
private fun StageAction(
    stage: StageOutcome,
    actions: OrderOfOperationsActions,
) {
    val (label, onClick) =
        when {
            stage.stage == FooStage.FULL_EMERGENCY && stage.status != StageStatus.SATISFIED ->
                R.string.foo_open_emergency_fund to actions.onOpenEmergencyFund
            stage.stage == FooStage.GOAL_INVESTING && stage.status != StageStatus.NOT_APPLICABLE ->
                R.string.foo_open_goals to actions.onOpenGoals
            else -> return
        }
    CfoSecondaryButton(text = stringResource(label), onClick = onClick)
}

/**
 * Every rule that placed this stage, by id and version (P-02, AI-ARC-006).
 * Result: one line. Input: [stage]. Output: none.
 */
@Composable
private fun RulesLine(stage: StageOutcome) {
    val citations =
        stage.citations.map { stringResource(R.string.foo_rule_citation, it.ruleId, it.ruleVersion) }
    Text(
        text = stringResource(R.string.foo_rules, citations.joinToString()),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The colour a status label is drawn in — theme tokens only (§21.6).
 * Result: warning for a held stage, positive for a done one, the primary colour for one that asks
 *         for money, and the muted variant otherwise. Input: [status]. Output: [Color].
 */
@Composable
private fun statusColor(status: StageStatus): Color =
    when (status) {
        StageStatus.ACTION -> MaterialTheme.colorScheme.primary
        StageStatus.SATISFIED -> CfoTheme.extendedColors.positive
        StageStatus.BLOCKED -> CfoTheme.extendedColors.warning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

/**
 * Where the full-order screen can send the user (ARC-001).
 * Why:  named lambdas on a value rather than four same-shaped parameters, [DashboardActions]' reason.
 *       Still lambdas, never a `NavController`: routing stays in `:app`.
 * Changelog: 2026-09-17 — Created for issue 7.5.
 *
 * Input:  [onDone] — back; [onOpenGoals]; [onOpenEmergencyFund].
 * Output: an immutable value.
 */
@Immutable
data class OrderOfOperationsActions(
    val onDone: () -> Unit,
    val onOpenGoals: () -> Unit,
    val onOpenEmergencyFund: () -> Unit,
)
