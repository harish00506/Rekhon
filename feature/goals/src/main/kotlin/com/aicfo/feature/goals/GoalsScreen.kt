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
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.domain.engines.goals.GoalProjection

/**
 * The goals screen (issue 7.1; §15, FR-GOAL, ARC-004).
 *
 * Why:  the screen that makes `GoalEngine` reachable. An engine nobody can put a goal into is an
 *       engine nobody can use — the exact shape of the bug issue 6.7 found in 6.5, where a whole
 *       market-data stack shipped with no field to fill in.
 * What: the list, an editor, and the disclaimer §11.1 requires.
 * Result: the composition.
 * Changelog: 2026-08-30 — Created for issue 7.1.
 */
@Composable
fun GoalsScreen(
    onDone: () -> Unit,
    viewModel: GoalsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    GoalsContent(uiState = uiState, onEvent = viewModel::onEvent, onDone = onDone)
}

/**
 * The screen's body, with no ViewModel in sight.
 * Why:    separated so a test can drive every state directly — the reason every screen here splits
 *         this way (ARC-004).
 * Result: the composition. Input: [uiState]; [onEvent]; [onDone]. Output: none.
 * Changelog: 2026-08-30 — Created for issue 7.1.
 */
@Composable
internal fun GoalsContent(
    uiState: GoalsUiState,
    onEvent: (GoalsEvent) -> Unit,
    onDone: () -> Unit,
) {
    // A plain scrolling Column rather than a LazyColumn, for the reason HoldingsScreen gives: the
    // disclaimer sits below the list and must be reachable, so the whole screen scrolls as one, and
    // a LazyColumn inside a scrolling parent is measured with an infinite height constraint. A
    // person's goals number in the handful; there is nothing to virtualise.
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(CfoDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd),
    ) {
        Text(text = stringResource(R.string.goals_title), style = MaterialTheme.typography.headlineSmall)

        uiState.errorCode?.let {
            Text(text = stringResource(R.string.goals_error), color = CfoTheme.extendedColors.negative)
            CfoSecondaryButton(
                text = stringResource(R.string.goals_dismiss_error),
                onClick = { onEvent(GoalsEvent.DismissError) },
            )
        }

        val editor = uiState.editor
        if (editor != null) {
            GoalEditor(state = editor, onEvent = onEvent)
        } else {
            GoalList(uiState = uiState, onEvent = onEvent)
            CfoSecondaryButton(text = stringResource(R.string.goals_add), onClick = { onEvent(GoalsEvent.AddGoal) })
            CfoSecondaryButton(text = stringResource(R.string.goals_editor_cancel), onClick = onDone)
        }

        Text(
            text = stringResource(R.string.goals_disclaimer),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The list, or the reason it is empty.
 * Why:    split out to keep [GoalsContent] within the 40-line limit (§21.6).
 * Result: the composition. Input: [uiState]; [onEvent]. Output: none.
 */
@Composable
private fun GoalList(
    uiState: GoalsUiState,
    onEvent: (GoalsEvent) -> Unit,
) {
    if (uiState.isEmpty) {
        Text(text = stringResource(R.string.goals_empty), style = MaterialTheme.typography.bodyMedium)
        return
    }
    uiState.goals.forEach { goal -> GoalCard(goal = goal, onEvent = onEvent) }
}

/**
 * One goal, and the working behind its figure.
 *
 * Why:    P-02 — the required monthly is shown with the inputs it came from and the rule that
 *         shaped the advice beside it, the way the Safe-to-Spend card names `RULE-STS v1.0`. A bare
 *         "₹20,000 a month" with no explanation is the black-box verdict P-02 exists to forbid.
 * Result: the composition. Input: [goal]; [onEvent]. Output: none.
 */
@Composable
private fun GoalCard(
    goal: GoalProjection,
    onEvent: (GoalsEvent) -> Unit,
) {
    CfoCard {
        Text(text = goal.name, style = MaterialTheme.typography.titleMedium)
        Text(text = stringResource(GoalLabels.status(goal.status)))
        Text(
            text = stringResource(R.string.goals_required_monthly, MoneyFormatter.format(goal.requiredMonthly)),
            style = MaterialTheme.typography.headlineSmall,
        )
        if (!goal.onTrack) {
            Text(
                text = stringResource(R.string.goals_shortfall, MoneyFormatter.format(goal.shortfallMonthly)),
                color = CfoTheme.extendedColors.negative,
            )
        }
        // The inputs, so the figure above is checkable rather than asserted.
        Text(
            text =
                stringResource(
                    R.string.goals_saved_of_target,
                    MoneyFormatter.format(goal.saved),
                    MoneyFormatter.format(goal.target),
                ),
        )
        Text(text = stringResource(R.string.goals_target_date, goal.targetDateIso))
        GoalEta(goal)
        Text(text = stringResource(GoalLabels.horizon(goal.horizon)), style = MaterialTheme.typography.bodySmall)
        Text(text = stringResource(R.string.goals_rule), style = MaterialTheme.typography.bodySmall)
        CfoSecondaryButton(
            text = stringResource(R.string.goals_edit),
            onClick = { onEvent(GoalsEvent.EditGoal(goal.goalId)) },
        )
        CfoSecondaryButton(
            text = stringResource(R.string.goals_editor_delete),
            onClick = { onEvent(GoalsEvent.DeleteGoal(goal.goalId)) },
        )
    }
}

/**
 * Where the user's own plan gets them.
 *
 * Why:    the absence is rendered rather than hidden. "No monthly plan set" is information; a blank
 *         line is not, and a made-up date would be worse than either (P-03).
 *
 *         **Three cases, not two.** The engine dates an already-funded goal *today*, which is true
 *         and reads absurdly beside a zero plan — "at ₹0.00 a month you get there 2026-08-30". Found
 *         by running the app, not by a test: every assertion about the figure passed. The engine is
 *         right; the sentence was wrong, so the branch is here rather than there.
 * Result: the composition. Input: [goal]. Output: none.
 * Changelog: 2026-08-30 — Created for issue 7.1.
 */
@Composable
private fun GoalEta(goal: GoalProjection) {
    val eta = goal.etaIsoDate
    val text =
        when {
            goal.remaining == Money.ZERO -> stringResource(R.string.goals_eta_reached)
            eta == null -> stringResource(R.string.goals_eta_never)
            else -> stringResource(R.string.goals_eta, MoneyFormatter.format(goal.plannedMonthly), eta)
        }
    Text(text = text, style = MaterialTheme.typography.bodySmall)
}
