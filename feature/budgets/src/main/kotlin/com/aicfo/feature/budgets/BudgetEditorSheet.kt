package com.aicfo.feature.budgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.aicfo.core.designsystem.component.CfoButton
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens

/**
 * The amount sheet (issue 4.4; FR-BUD-001, ARC-004).
 *
 * Why:  one sheet for setting a budget and for changing one, because they are the same field and the
 *       same validation — the shape `CategoryEditorSheet` already uses, and for the same reason:
 *       duplicating the form is how the two drift apart.
 * What: the amount field, the carry-over switch, and Save/Cancel.
 * Result: a budget can be set or changed without leaving the list it appears in.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 *
 * Input:  [state] — the sheet's own fields; [onEvent] — events up. Output: the composition.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BudgetEditorSheet(
    state: BudgetEditorState,
    onEvent: (BudgetsEvent) -> Unit,
) {
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd)) {
            Text(
                text = stringResource(R.string.budget_editor_title, state.categoryName),
                style = MaterialTheme.typography.titleMedium,
            )

            OutlinedTextField(
                value = state.amountText,
                onValueChange = { onEvent(BudgetsEvent.AmountChanged(it)) },
                label = { Text(stringResource(R.string.budget_editor_amount)) },
                singleLine = true,
                // Decimal, so the phone offers a keypad with a separator and no letters. It is a hint
                // to the keyboard, never a validation: the field still accepts anything, and
                // `MoneyFormatter.parse` is what decides whether it is money (MNY-001).
                keyboardOptions =
                    KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            RolloverToggle(state = state, onEvent = onEvent)

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
                verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs),
            ) {
                CfoButton(
                    text = stringResource(R.string.budget_editor_save),
                    onClick = { onEvent(BudgetsEvent.Save) },
                    enabled = state.canSave,
                )
                CfoSecondaryButton(
                    text = stringResource(R.string.budget_editor_cancel),
                    onClick = { onEvent(BudgetsEvent.CancelEdit) },
                )
            }
        }
    }
}

/**
 * FR-BUD-001's optional rollover.
 * Why:    split out of [BudgetEditorSheet] for the 40-line limit (§21.6). The switch carries an
 *         explanatory line rather than only a label, because "carry over" is ambiguous in exactly the
 *         way that matters — it says what happens to money left **unspent**, and says plainly that an
 *         overspend does not follow the user into next month.
 * Result: the composition. Input: [state]; [onEvent]. Output: none.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 */
@Composable
private fun RolloverToggle(
    state: BudgetEditorState,
    onEvent: (BudgetsEvent) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = stringResource(R.string.budget_editor_rollover),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.budget_editor_rollover_help),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = state.rolloverEnabled,
            onCheckedChange = { onEvent(BudgetsEvent.RolloverChanged(it)) },
        )
    }
}
