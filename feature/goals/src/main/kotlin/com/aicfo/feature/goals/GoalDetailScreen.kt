package com.aicfo.feature.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.designsystem.theme.CfoTheme
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.data.repository.GoalContribution
import com.aicfo.data.repository.GoalFundingAccount
import com.aicfo.domain.engines.goals.GoalProjection

/**
 * What one goal's progress is actually made of (issue 7.4; §15, FR-GOAL-002, FR-GOAL-004).
 *
 * Why:  §15 asks for progress that is transaction-evidenced, and for manual claims to be *visually
 *       distinct*. Both need a surface, and the goals list is not it — that card already carries
 *       three different "monthly" figures, and the 7.3 session recorded what adding a fourth
 *       measurement to a crowded card does to the sentences already on it.
 * What: the split figure, the ghost warning, the linked movements, the dedicated accounts, and the
 *       two pickers that add to either.
 * Result: the composition.
 * Changelog: 2026-09-06 — Created for issue 7.4.
 */
@Composable
fun GoalDetailScreen(
    onDone: () -> Unit,
    viewModel: GoalDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    GoalDetailContent(uiState = uiState, onEvent = viewModel::onEvent, onDone = onDone)
}

/**
 * The screen's body, with no ViewModel in sight.
 * Why:    separated so a test can drive every state directly (ARC-004).
 * Result: the composition. Input: [uiState]; [onEvent]; [onDone]. Output: none.
 */
@Composable
internal fun GoalDetailContent(
    uiState: GoalDetailUiState,
    onEvent: (GoalDetailEvent) -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(CfoDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd),
    ) {
        val goal = uiState.goal
        Text(
            text = goal?.name ?: stringResource(R.string.goal_detail_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        uiState.errorCode?.let {
            Text(text = stringResource(R.string.goals_error), color = CfoTheme.extendedColors.negative)
            CfoSecondaryButton(
                text = stringResource(R.string.goals_dismiss_error),
                onClick = { onEvent(GoalDetailEvent.DismissError) },
            )
        }

        if (goal == null) {
            if (!uiState.isLoading) Text(text = stringResource(R.string.goal_detail_missing))
        } else {
            ProgressCard(goal = goal, uiState = uiState, onEvent = onEvent)
            ContributionsSection(uiState = uiState, onEvent = onEvent)
            FundingAccountsSection(uiState = uiState, onEvent = onEvent)
            AnchorLine(goal)
        }

        CfoSecondaryButton(text = stringResource(R.string.goal_detail_back), onClick = onDone)
    }
}

/**
 * The split figure, and the ghost half marked as such.
 *
 * Why:    §15's "visually distinct" is the whole requirement here. It is met with **words and a
 *         separate line**, not with colour alone — the same decision issue 6.5 recorded for the
 *         staleness label, so the distinction survives greyscale and TalkBack.
 * Result: the composition. Input: [goal]; [uiState]; [onEvent]. Output: none.
 */
@Composable
private fun ProgressCard(
    goal: GoalProjection,
    uiState: GoalDetailUiState,
    onEvent: (GoalDetailEvent) -> Unit,
) {
    CfoCard {
        Text(
            text =
                stringResource(
                    R.string.goals_saved_of_target,
                    MoneyFormatter.format(goal.saved),
                    MoneyFormatter.format(goal.target),
                ),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.goal_detail_evidenced, MoneyFormatter.format(goal.savedEvidenced)),
        )
        if (uiState.hasGhostProgress) {
            Text(
                text = stringResource(R.string.goal_detail_ghost, MoneyFormatter.format(goal.savedDeclared)),
                color = CfoTheme.extendedColors.negative,
            )
            CfoSecondaryButton(
                text = stringResource(R.string.goal_detail_clear_ghost),
                onClick = { onEvent(GoalDetailEvent.ClearGhostProgress) },
            )
        }
    }
}

/**
 * The linked movements, and the picker that adds one.
 * Why:    split out to keep [GoalDetailContent] inside the 40-line limit (§21.6).
 * Result: the composition. Input: [uiState]; [onEvent]. Output: none.
 */
@Composable
private fun ContributionsSection(
    uiState: GoalDetailUiState,
    onEvent: (GoalDetailEvent) -> Unit,
) {
    Text(text = stringResource(R.string.goal_detail_contributions), style = MaterialTheme.typography.titleMedium)
    if (uiState.hasNoEvidence) {
        Text(text = stringResource(R.string.goal_detail_no_evidence), style = MaterialTheme.typography.bodyMedium)
    }
    uiState.contributions.forEach { contribution -> ContributionRow(contribution, onEvent) }

    if (uiState.isPickerOpen) {
        Text(text = stringResource(R.string.goal_detail_picker_title), style = MaterialTheme.typography.titleSmall)
        if (uiState.linkable.isEmpty()) {
            Text(text = stringResource(R.string.goal_detail_picker_empty))
        }
        uiState.linkable.forEach { transaction ->
            CfoSecondaryButton(
                text =
                    stringResource(
                        R.string.goal_detail_picker_row,
                        transaction.merchant ?: transaction.note ?: transaction.bookedOn,
                        MoneyFormatter.format(transaction.amount),
                        transaction.bookedOn,
                    ),
                onClick = { onEvent(GoalDetailEvent.Link(transaction.id)) },
            )
        }
        CfoSecondaryButton(
            text = stringResource(R.string.goal_detail_picker_close),
            onClick = { onEvent(GoalDetailEvent.ClosePicker) },
        )
    } else {
        CfoSecondaryButton(
            text = stringResource(R.string.goal_detail_link),
            onClick = { onEvent(GoalDetailEvent.OpenPicker) },
        )
    }
}

/**
 * One linked movement, with the way to take it back off.
 * Result: the composition. Input: [contribution]; [onEvent]. Output: none.
 */
@Composable
private fun ContributionRow(
    contribution: GoalContribution,
    onEvent: (GoalDetailEvent) -> Unit,
) {
    val transaction = contribution.transaction
    CfoCard {
        Text(
            text =
                stringResource(
                    R.string.goal_detail_contribution_row,
                    transaction.merchant ?: transaction.note ?: transaction.bookedOn,
                    MoneyFormatter.format(contribution.amount),
                    transaction.bookedOn,
                ),
        )
        CfoSecondaryButton(
            text = stringResource(R.string.goal_detail_unlink),
            onClick = { onEvent(GoalDetailEvent.Unlink(transaction.id)) },
        )
    }
}

/**
 * The dedicated accounts, and the chooser that adds one (FR-GOAL-002).
 *
 * Why:    the chooser asks the history question outright rather than assuming an answer. Dedicating
 *         an account that has held money for two years is a different claim from dedicating one
 *         from today, and the app must not decide which the user meant.
 * Result: the composition. Input: [uiState]; [onEvent]. Output: none.
 */
@Composable
private fun FundingAccountsSection(
    uiState: GoalDetailUiState,
    onEvent: (GoalDetailEvent) -> Unit,
) {
    Text(text = stringResource(R.string.goal_detail_funding), style = MaterialTheme.typography.titleMedium)
    uiState.fundingAccounts.forEach { funding -> FundingAccountRow(funding, onEvent) }

    if (uiState.isAccountPickerOpen) {
        if (uiState.dedicatableAccounts.isEmpty()) {
            Text(text = stringResource(R.string.goal_detail_funding_empty))
        }
        uiState.dedicatableAccounts.forEach { account ->
            CfoSecondaryButton(
                text = stringResource(R.string.goal_detail_funding_from_today, account.name),
                onClick = { onEvent(GoalDetailEvent.LinkAccount(account.id, countHistory = false)) },
            )
            CfoSecondaryButton(
                text = stringResource(R.string.goal_detail_funding_all_history, account.name),
                onClick = { onEvent(GoalDetailEvent.LinkAccount(account.id, countHistory = true)) },
            )
        }
        CfoSecondaryButton(
            text = stringResource(R.string.goal_detail_picker_close),
            onClick = { onEvent(GoalDetailEvent.CloseAccountPicker) },
        )
    } else {
        CfoSecondaryButton(
            text = stringResource(R.string.goal_detail_dedicate),
            onClick = { onEvent(GoalDetailEvent.OpenAccountPicker) },
        )
    }
}

/**
 * One dedicated account, with the day it started counting.
 * Result: the composition. Input: [funding]; [onEvent]. Output: none.
 */
@Composable
private fun FundingAccountRow(
    funding: GoalFundingAccount,
    onEvent: (GoalDetailEvent) -> Unit,
) {
    CfoCard {
        // The sentinel date is never shown: "counting from 0001-01-01" is a true statement about the
        // database and a meaningless one about the user's money. Found by running it.
        Text(
            text =
                if (funding.countsWholeHistory) {
                    stringResource(R.string.goal_detail_funding_row_all, funding.account.name)
                } else {
                    stringResource(
                        R.string.goal_detail_funding_row,
                        funding.account.name,
                        funding.linkedFromIsoDate,
                    )
                },
        )
        CfoSecondaryButton(
            text = stringResource(R.string.goal_detail_release),
            onClick = { onEvent(GoalDetailEvent.UnlinkAccount(funding.account.id)) },
        )
    }
}

/**
 * `RULE-PAY-FIRST`, said out loud (issue 7.4; §29.2).
 *
 * Why:    P-02 — advice is shown with the rule that produced it. The line is drawn only when the
 *         app actually knows the day; a profile that never onboarded is told nothing rather than a
 *         guessed payday (P-03).
 * Result: the composition, or nothing. Input: [goal]. Output: none.
 */
@Composable
private fun AnchorLine(goal: GoalProjection) {
    val day = goal.contributionAnchorDay ?: return
    Text(
        text = stringResource(R.string.goal_detail_pay_first, day),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(text = stringResource(R.string.goal_detail_pay_first_rule), style = MaterialTheme.typography.bodySmall)
}
