package com.aicfo.feature.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.aicfo.core.designsystem.component.CfoButton
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.designsystem.theme.CfoTheme

/**
 * The add/edit form (issue 7.1; §15).
 *
 * Why:    **this is the file that makes the whole feature reachable.** Issue 6.7 found a market-data
 *         stack in which every layer shipped and no editor field did, so nothing could ever be
 *         priced. A goals engine with no way to enter a goal would be the same bug again.
 * What:   five fields — a name, a target, a date, what is saved, and the monthly plan.
 * Result: the composition. Input: [state]; [onEvent]. Output: none.
 * Changelog: 2026-08-30 — Created for issue 7.1.
 *
 * Two of the five may be left blank, and the help text says so. "Saved so far" defaults to zero
 * because a goal just created has nothing in it; the monthly plan defaults to zero because deciding
 * it is the thing the required-monthly figure is meant to help with — demanding it up front would
 * ask the user for the answer before showing them the question.
 */
@Composable
internal fun GoalEditor(
    state: GoalEditorState,
    onEvent: (GoalsEvent) -> Unit,
) {
    CfoCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
        ) {
            state.fieldError?.let {
                Text(
                    text = stringResource(R.string.goals_editor_error),
                    color = CfoTheme.extendedColors.negative,
                )
            }
            EditorFields(state = state, onEvent = onEvent)
            CfoButton(
                text = stringResource(R.string.goals_editor_save),
                onClick = { onEvent(GoalsEvent.SaveEditor) },
            )
            CfoSecondaryButton(
                text = stringResource(R.string.goals_editor_cancel),
                onClick = { onEvent(GoalsEvent.CancelEditor) },
            )
        }
    }
}

/**
 * The five inputs.
 * Why:    split out to keep [GoalEditor] within the 40-line limit (§21.6).
 * Result: the composition. Input: [state]; [onEvent]. Output: none.
 */
@Composable
private fun EditorFields(
    state: GoalEditorState,
    onEvent: (GoalsEvent) -> Unit,
) {
    OutlinedTextField(
        value = state.name,
        onValueChange = { onEvent(GoalsEvent.NameChanged(it)) },
        label = { Text(stringResource(R.string.goals_editor_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    MoneyField(
        value = state.target,
        label = stringResource(R.string.goals_editor_target),
        onValueChange = { onEvent(GoalsEvent.TargetChanged(it)) },
    )
    OutlinedTextField(
        value = state.targetDate,
        onValueChange = { onEvent(GoalsEvent.TargetDateChanged(it)) },
        label = { Text(stringResource(R.string.goals_editor_target_date)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    MoneyField(
        value = state.saved,
        label = stringResource(R.string.goals_editor_saved),
        supporting = stringResource(R.string.goals_editor_saved_help),
        onValueChange = { onEvent(GoalsEvent.SavedChanged(it)) },
    )
    MoneyField(
        value = state.plannedMonthly,
        label = stringResource(R.string.goals_editor_planned),
        supporting = stringResource(R.string.goals_editor_planned_help),
        onValueChange = { onEvent(GoalsEvent.PlannedMonthlyChanged(it)) },
    )
}

/**
 * An amount field.
 * Why:    three of the five inputs are money and differ only in their labels; writing the decimal
 *         keyboard and the width three times is three chances to forget one.
 * Result: the composition. Input: [value]; [label]; [supporting]; [onValueChange]. Output: none.
 */
@Composable
private fun MoneyField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}
