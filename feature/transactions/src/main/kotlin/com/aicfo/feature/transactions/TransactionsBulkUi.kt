package com.aicfo.feature.transactions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.aicfo.core.designsystem.theme.CfoDimens

/*
 * The multi-select half of the transactions screen (issue 3.6; FR-TXN-008).
 *
 * Why:  split from `TransactionsFilterUi.kt` and `TransactionsScreen.kt` when both reached detekt's
 *       function-per-file ceiling (§21.6) — the same pressure that produced `RepositoryModule`
 *       from `CoreModule`. The seam is real rather than arithmetic: filtering narrows what the user
 *       *sees*, while everything here changes what they *have*, and the destructive half of the
 *       screen is worth reading in one place.
 * What: the contextual action bar, its two pickers, the selectable row, and the undo snackbar.
 * Result: FR-TXN-008's "multi-select recategorise, retag, delete (with undo snackbar)" in one file.
 * Changelog: 2026-08-04 — Created for issue 3.6.
 */

/**
 * The bar shown while rows are selected (issue 3.6; FR-TXN-008).
 *
 * Why:    FR-TXN-008's "multi-select recategorise, retag, delete" needs somewhere to put the three
 *         actions, and a bar that **replaces the title** is what tells the user the screen is in a
 *         different mode — a toolbar bolted below an unchanged heading reads as an extra control
 *         rather than as a mode they can leave.
 *
 *         **The count is first and the close button is leftmost**, the platform's contextual-action
 *         shape: the user's first question in this mode is "how many?" and their most likely action
 *         is "get me out of it".
 *
 *         **Recategorise and retag open their own small pickers.** The bar cannot hold a category
 *         list and a tag editor at one line high, and the destructive action must not sit next to a
 *         wall of chips the user is scanning.
 * Result: the composition. Input: [uiState], [onEvent]. Output: none.
 * Changelog: 2026-08-04 — Created for issue 3.6 (FR-TXN-008).
 */
@Composable
internal fun BulkActionBar(
    uiState: TransactionsUiState,
    onEvent: (TransactionsEvent) -> Unit,
) {
    var picker by remember { mutableStateOf(BulkPicker.NONE) }

    BulkActionRow(uiState = uiState, onPick = { picker = it }, onEvent = onEvent)

    BulkPickerSheets(
        picker = picker,
        uiState = uiState,
        onClose = { picker = BulkPicker.NONE },
        onEvent = onEvent,
    )
}

/**
 * Whichever small picker the action bar has open, or nothing (issue 3.6; FR-TXN-008).
 * Why:    extracted from [BulkActionBar] at detekt's 40-line ceiling (§21.6), and the seam is a real
 *         one: the bar is a row of controls, this is the modal each opens. Both pickers close
 *         themselves **before** raising their event, so the sheet is never left over a list that is
 *         changing underneath it.
 * Result: the composition, or nothing.
 * Input:  [picker]; [uiState]; [onClose]; [onEvent]. Output: none.
 * Changelog: 2026-08-04 — Created for issue 3.6.
 */
@Composable
private fun BulkPickerSheets(
    picker: BulkPicker,
    uiState: TransactionsUiState,
    onClose: () -> Unit,
    onEvent: (TransactionsEvent) -> Unit,
) {
    when (picker) {
        BulkPicker.NONE -> Unit
        BulkPicker.CATEGORY ->
            CategoryPickerSheet(
                uiState = uiState,
                onDismiss = onClose,
                onPick = { categoryId ->
                    onClose()
                    onEvent(BulkEvent.Recategorise(categoryId))
                },
            )

        BulkPicker.TAG ->
            TagPickerSheet(
                uiState = uiState,
                onDismiss = onClose,
                onApply = { names ->
                    onClose()
                    onEvent(BulkEvent.Retag(names))
                },
            )
    }
}

/**
 * Which small picker the action bar has open (issue 3.6).
 *
 * Why:  two booleans would allow a fourth state — both open — that means nothing. Local `remember`
 *       state rather than `UiState`, because it is a transient UI mode with no bearing on the data,
 *       the same call `AddTransactionScreen` makes for its pickers.
 * Changelog: 2026-08-04 — Created for issue 3.6.
 */
private enum class BulkPicker { NONE, CATEGORY, TAG }

/**
 * Picks a category for the whole selection (issue 3.6; FR-TXN-008).
 * Why:    a sheet rather than a dropdown, because the taxonomy issue 4.1 builds will be long enough
 *         to scroll and a menu that outgrows the screen is a menu the user cannot reach the end of.
 *         **"None" is offered first**: clearing a category in bulk is a legitimate edit and the
 *         repository supports it, so leaving it out would make the operation one-way.
 * Result: the composition. Input: [uiState], [onDismiss], [onPick]. Output: none.
 * Changelog: 2026-08-04 — Created for issue 3.6.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CategoryPickerSheet(
    uiState: TransactionsUiState,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(CfoDimens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
        ) {
            Text(
                text = stringResource(R.string.transactions_bulk_recategorise),
                style = MaterialTheme.typography.titleMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
                FilterChip(
                    selected = false,
                    onClick = { onPick(null) },
                    label = { Text(stringResource(R.string.transactions_bulk_no_category)) },
                )
                uiState.categories.forEach { category ->
                    FilterChip(
                        selected = false,
                        onClick = { onPick(category.id) },
                        label = { Text(category.name) },
                    )
                }
            }
            // Empty for every real profile until issue 4.1 seeds a taxonomy. Said out loud rather
            // than shown as an empty row, which reads as a broken picker.
            if (uiState.categories.isEmpty()) {
                Text(
                    text = stringResource(R.string.transactions_bulk_no_categories_yet),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * Sets the selection's tags (issue 3.6; FR-TXN-008's "retag").
 *
 * Why:    a text field **and** chips for what already exists. The field is what makes a first tag
 *         possible at all — there is no other way to create one — and the chips are what stop the
 *         user retyping `goa-trip` slightly differently the second time and ending up with two tags
 *         they cannot tell apart. Tapping a chip appends it to the field rather than applying it
 *         immediately, so a retag can name several labels in one go.
 *
 *         **Applying an empty field removes every tag**, which is what the repository's contract
 *         says an empty list means. It is the only way to untag in bulk, so the button stays enabled.
 * Result: the composition. Input: [uiState], [onDismiss], [onApply]. Output: none.
 * Changelog: 2026-08-04 — Created for issue 3.6.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TagPickerSheet(
    uiState: TransactionsUiState,
    onDismiss: () -> Unit,
    onApply: (List<String>) -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(CfoDimens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
        ) {
            Text(
                text = stringResource(R.string.transactions_bulk_retag),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text(stringResource(R.string.transactions_tags_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
                uiState.availableTags.forEach { tag ->
                    FilterChip(
                        selected = false,
                        onClick = { typed = appendTag(typed, tag.name) },
                        label = { Text(tag.name) },
                    )
                }
            }
            TextButton(onClick = { onApply(typed.splitTags()) }) {
                Text(stringResource(R.string.transactions_bulk_apply))
            }
        }
    }
}

/**
 * One row, selectable by long press (issue 3.6; FR-TXN-008).
 *
 * Why:    FR-TXN-008's multi-select needs a way in that does not cost a permanent control on every
 *         row. Long press is the platform idiom; once selecting, a plain tap toggles instead of
 *         opening the detail sheet, because a user picking rows does not want a sheet over them.
 *
 *         **The delete affordance is hidden while selecting.** Two destructive controls on screen at
 *         once — a per-row bin and a bulk Delete — is how a user deletes the wrong thing.
 * Result: the composition. Input: [item], [uiState], [onEvent]. Output: none.
 * Changelog: 2026-08-04 — Created for issue 3.6 (FR-TXN-008).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SelectableRow(
    item: TransactionListItem.Row,
    uiState: TransactionsUiState,
    onEvent: (TransactionsEvent) -> Unit,
) {
    val row = item.row
    ListRow(
        row = row,
        accountNames = uiState.accountNames,
        selection = RowSelection(isSelected = item.isSelected, showDelete = !uiState.isSelecting),
        onDelete = { onEvent(TransactionsEvent.Delete(row.id)) },
        modifier =
            Modifier.combinedClickable(
                onClick = {
                    if (uiState.isSelecting) {
                        onEvent(BulkEvent.Toggled(row.id))
                    } else {
                        onEvent(TransactionsEvent.RowTapped(row.transaction))
                    }
                },
                onLongClick = { onEvent(BulkEvent.Toggled(row.id)) },
            ),
    )
}

/**
 * Shows and dismisses the undo snackbar (issue 3.6; FR-TXN-008).
 *
 * Why:    FR-TXN-008 asks for delete "with undo snackbar". A `LaunchedEffect` keyed on the batch, so
 *         a *new* delete replaces the message rather than queueing behind the old one — a queue
 *         would let a user tap Undo on a snackbar describing a delete two operations ago.
 *
 *         **The dismiss event fires either way.** Whether the user tapped Undo or let it time out,
 *         the batch has to leave the state; an armed undo that outlived its snackbar would restore
 *         rows on the next unrelated tap.
 * Result: no composition of its own — it drives [hostState].
 * Input:  [uiState], [hostState], [onEvent]. Output: none.
 * Changelog: 2026-08-04 — Created for issue 3.6 (FR-TXN-008).
 */
@Composable
internal fun UndoSnackbar(
    uiState: TransactionsUiState,
    hostState: SnackbarHostState,
    onEvent: (TransactionsEvent) -> Unit,
) {
    val batch = uiState.undo ?: return
    val message = pluralStringResource(R.plurals.transactions_deleted, batch.selectedCount, batch.selectedCount)
    val undoLabel = stringResource(R.string.transactions_undo)
    LaunchedEffect(batch) {
        val result = hostState.showSnackbar(message = message, actionLabel = undoLabel)
        onEvent(if (result == SnackbarResult.ActionPerformed) BulkEvent.Undo else BulkEvent.UndoDismissed)
    }
}

/**
 * How a row is drawn while the screen is in selection mode (issue 3.6; FR-TXN-008).
 *
 * Why:  two booleans that always travel together, and passing them separately pushed [ListRow] past
 *       detekt's parameter ceiling (§21.6). They are one fact — "the screen is selecting, and this
 *       row is/is not in the selection" — so a type says it better than two flags.
 * Result: the row can tint itself and hide its bin without a second parameter list.
 * Changelog: 2026-08-04 — Created for issue 3.6.
 *
 * Input:  [isSelected]; [showDelete] — false while selecting, because a per-row bin beside a bulk
 *         Delete is two destructive controls on one screen. Output: an immutable value.
 */
@Immutable
internal data class RowSelection(
    val isSelected: Boolean = false,
    val showDelete: Boolean = true,
)

/**
 * The action bar's controls (issue 3.6; FR-TXN-008).
 * Why:    extracted from [BulkActionBar] at detekt's 40-line ceiling (§21.6). The bar owns which
 *         picker is open; this owns what the user can press.
 *
 *         **The count is first and the close button leftmost**, the platform's contextual-action
 *         shape: the user's first question in this mode is "how many?" and their most likely action
 *         is "get me out of it". Every control is disabled while a bulk write is in flight, so a
 *         second delete cannot be queued behind the first.
 * Result: the composition. Input: [uiState]; [onPick] — opens a picker; [onEvent]. Output: none.
 * Changelog: 2026-08-04 — Created for issue 3.6.
 */
@Composable
private fun BulkActionRow(
    uiState: TransactionsUiState,
    onPick: (BulkPicker) -> Unit,
    onEvent: (TransactionsEvent) -> Unit,
) {
    val count = uiState.selection.size
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
    ) {
        IconButton(
            onClick = { onEvent(BulkEvent.Cleared) },
            modifier = Modifier.defaultMinSize(CfoDimens.minTouchTarget),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.transactions_selection_clear),
            )
        }
        Text(
            // ICU plural: "1 selected" must not read as "1 selecteds" (§21.6).
            text = pluralStringResource(R.plurals.transactions_selected, count, count),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onPick(BulkPicker.CATEGORY) }, enabled = !uiState.isBulkRunning) {
            Text(stringResource(R.string.transactions_bulk_recategorise))
        }
        TextButton(onClick = { onPick(BulkPicker.TAG) }, enabled = !uiState.isBulkRunning) {
            Text(stringResource(R.string.transactions_bulk_retag))
        }
        BulkDeleteButton(enabled = !uiState.isBulkRunning, onEvent = onEvent)
    }
}

/**
 * The action bar's delete control (issue 3.6; FR-TXN-008).
 * Why:    the one destructive control on the screen, so it is worth naming rather than leaving as
 *         the tail of a row — and extracting it puts [BulkActionRow] back under detekt's 40-line
 *         ceiling (§21.6). Tinted with the error colour and icon-only, so the content description
 *         is the whole of what a screen reader announces.
 * Result: the composition. Input: [enabled]; [onEvent]. Output: none.
 * Changelog: 2026-08-04 — Created for issue 3.6.
 */
@Composable
private fun BulkDeleteButton(
    enabled: Boolean,
    onEvent: (TransactionsEvent) -> Unit,
) {
    IconButton(
        onClick = { onEvent(BulkEvent.Delete) },
        enabled = enabled,
        modifier = Modifier.defaultMinSize(CfoDimens.minTouchTarget),
    ) {
        Icon(
            imageVector = Icons.Filled.Delete,
            contentDescription = stringResource(R.string.transactions_bulk_delete),
            tint = MaterialTheme.colorScheme.error,
        )
    }
}
