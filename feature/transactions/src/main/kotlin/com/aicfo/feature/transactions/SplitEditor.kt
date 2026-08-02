package com.aicfo.feature.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import com.aicfo.core.designsystem.component.CfoAmountText
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.model.Money

/*
 * The split editor (issue 3.3; FR-TXN-004).
 *
 * Split out of `AddTransactionScreen.kt` when that file passed detekt's eleven-function ceiling.
 * The seam is a real one rather than an arbitrary cut: everything here is the one optional mode
 * that turns a single amount into N attributed lines, and none of it renders at all unless the
 * user asks for it. `AddTransactionScreen` calls exactly two of these — [SplitToggle] and
 * [SplitEditor] — and the rest are private to this file.
 */

/**
 * The opt-in that turns one amount into N lines (issue 3.3; FR-TXN-004).
 * Why:    **opt-in is the constraint.** FR-TXN-002 pins a common expense at two taps, asserted by a
 *         test, so a split control that was always expanded would spend a tap the budget does not
 *         have. Hidden entirely until there is an amount to divide, and for a transfer, which is not
 *         spending and has nothing to attribute.
 *
 *         `toggleable` on the **Row**, not the `Switch`: a 20dp control beside inert text is under
 *         the 48dp minimum and reads to a screen reader as two unrelated things — the same rule
 *         `:feature:accounts`'s net-worth toggle follows.
 * Result: the composition, or nothing. Input: [uiState], [onEvent]. Output: none.
 * Changelog: 2026-08-02 — Created for issue 3.3.
 */
@Composable
internal fun SplitToggle(
    uiState: AddTransactionUiState,
    onEvent: (AddTransactionEvent) -> Unit,
) {
    if (!uiState.canSplit) return

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = uiState.isSplit,
                    role = Role.Switch,
                    onValueChange = { onEvent(SplitEvent.SplitToggled(it)) },
                )
                .defaultMinSize(minHeight = CfoDimens.minTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
    ) {
        Switch(checked = uiState.isSplit, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics {})
        Text(text = stringResource(R.string.add_txn_split), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The line editor and its running remainder (issue 3.3; FR-TXN-004).
 * Why:    the remainder is the whole feedback loop the AC asks for — the user types, it moves, and
 *         Save unlocks exactly when it reaches zero. It is rendered as a label plus a
 *         [CfoAmountText] rather than interpolated into a string, so Indian digit grouping stays in
 *         `MoneyFormatter` (P-06) and the figure is announced as an amount.
 *
 *         **"Split evenly" is the only affordance that can always balance the form**, because it
 *         goes through `Money.split`'s largest-remainder rule. Offering it beside the running total
 *         is what stops a user hand-rounding three ways and losing a paise.
 * Result: the composition, or nothing. Input: [uiState], [onEvent]. Output: none.
 * Changelog: 2026-08-02 — Created for issue 3.3.
 */
@Composable
internal fun SplitEditor(
    uiState: AddTransactionUiState,
    onEvent: (AddTransactionEvent) -> Unit,
) {
    if (!uiState.isSplit) return

    Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
        uiState.splitLines.forEachIndexed { index, line ->
            SplitLineRow(index = index, line = line, uiState = uiState, onEvent = onEvent)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CfoSecondaryButton(
                text = stringResource(R.string.add_txn_split_add_line),
                onClick = { onEvent(SplitEvent.SplitLineAdded) },
            )
            CfoSecondaryButton(
                text = stringResource(R.string.add_txn_split_evenly),
                onClick = { onEvent(SplitEvent.SplitEvenly) },
            )
        }
        SplitRemainder(remainder = uiState.splitRemainder)
    }
}

/**
 * One editable line: an amount, a category, and a way to remove it.
 * Why:    split from [SplitEditor] to stay inside detekt's 40-line function limit (§21.6), and
 *         because a line is a self-contained thing — it is the unit the user adds and removes.
 *         The category chips reuse the same `FilterChip` shape as the parent's picker, so a line
 *         behaves the way the rest of the screen already taught the user it would.
 * Result: the composition. Input: [index], [line], [uiState], [onEvent]. Output: none.
 * Changelog: 2026-08-02 — Created for issue 3.3.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SplitLineRow(
    index: Int,
    line: SplitLineInput,
    uiState: AddTransactionUiState,
    onEvent: (AddTransactionEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = line.amountText,
                onValueChange = { onEvent(SplitEvent.SplitLineAmountChanged(index, it)) },
                label = { Text(stringResource(R.string.add_txn_split_line_amount)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { onEvent(SplitEvent.SplitLineRemoved(index)) },
                modifier = Modifier.defaultMinSize(CfoDimens.minTouchTarget),
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.add_txn_split_remove_line),
                )
            }
        }
        SplitLineCategories(index = index, line = line, uiState = uiState, onEvent = onEvent)
    }
}

/**
 * One line's category chips.
 * Why:    split from [SplitLineRow] to keep it inside detekt's 40-line limit (§21.6), and because
 *         this is the part that disappears entirely for a real profile — which has no categories
 *         until issue 4.1, so the whole row collapses to an amount and a remove button.
 *
 *         Tapping the selected chip clears it, the same gesture the parent's picker uses, so a
 *         category chosen by mistake does not need the line deleting to undo.
 * Result: the composition, or nothing. Input: [index], [line], [uiState], [onEvent]. Output: none.
 * Changelog: 2026-08-02 — Created for issue 3.3.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SplitLineCategories(
    index: Int,
    line: SplitLineInput,
    uiState: AddTransactionUiState,
    onEvent: (AddTransactionEvent) -> Unit,
) {
    if (uiState.categories.isEmpty()) return

    FlowRow(horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
        uiState.categories.forEach { category ->
            val selected = category.id == line.categoryId
            FilterChip(
                selected = selected,
                onClick = {
                    onEvent(
                        SplitEvent.SplitLineCategorySelected(
                            index,
                            if (selected) null else category.id,
                        ),
                    )
                },
                label = { Text(category.name) },
            )
        }
    }
}

/**
 * How much of the parent is still unattributed.
 * Why:    a label plus [CfoAmountText], never an amount interpolated into a sentence — that is the
 *         rule this module's `strings.xml` states, and it is what keeps ₹1,23,456.78 formatting in
 *         one place (P-06). `liveRegion` so a screen reader announces the figure changing, since it
 *         is the only thing that explains why Save is still disabled.
 * Result: the composition, or nothing while there is no amount yet.
 * Input:  [remainder]. Output: none.
 * Changelog: 2026-08-02 — Created for issue 3.3.
 */
@Composable
private fun SplitRemainder(remainder: Money?) {
    if (remainder == null) return

    Row(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.add_txn_split_remaining),
            style = MaterialTheme.typography.titleSmall,
        )
        // showSign = false: the remainder's own sign follows the parent's direction, and a "−" here
        // would read as money owed rather than as an amount still to be attributed.
        CfoAmountText(amount = remainder, showSign = false)
    }
}
