package com.aicfo.feature.categories

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.aicfo.core.designsystem.component.CfoButton
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.model.CategoryNature

/**
 * The add/edit sheet (issue 4.1; FR-SET-001, ARC-004).
 *
 * Why:  one sheet for both, distinguished by [CategoryEditorState.id], because add and edit are the
 *       same three fields and the same validation — the shape the account editor already uses, and
 *       for the same reason: duplicating the form is how the two drift apart.
 * What: the name field, the nature chips, the parent picker, and Save/Cancel.
 * Result: a category can be created or changed without leaving the list it appears in.
 * Changelog: 2026-08-08 — Created for issue 4.1.
 *
 * Input:  [state] — the sheet's own fields; [uiState] — for the parent options, which are the rest
 *         of the taxonomy; [onEvent] — events up. Output: the composition.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CategoryEditorSheet(
    state: CategoryEditorState,
    uiState: CategoriesUiState,
    onEvent: (CategoriesEvent) -> Unit,
) {
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd)) {
            Text(text = stringResource(state.titleRes()), style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = state.name,
                onValueChange = { onEvent(CategoriesEvent.NameChanged(it)) },
                label = { Text(stringResource(R.string.category_editor_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            NaturePicker(state = state, onEvent = onEvent)

            ParentPicker(state = state, uiState = uiState, onEvent = onEvent)

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
                verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs),
            ) {
                CfoButton(
                    text = stringResource(R.string.category_editor_save),
                    onClick = { onEvent(CategoriesEvent.Save) },
                    enabled = state.canSave,
                )
                CfoSecondaryButton(
                    text = stringResource(R.string.category_editor_cancel),
                    onClick = { onEvent(CategoriesEvent.CancelEdit) },
                )
            }
        }
    }
}

/**
 * Result: the sheet's heading — the one thing that tells adding and editing apart, since the fields
 *         and the validation are identical. Input: the receiver. Output: a string resource id.
 * Changelog: 2026-08-08 — Created for issue 4.1.
 */
@StringRes
private fun CategoryEditorState.titleRes(): Int =
    if (isEditing) R.string.category_editor_title_edit else R.string.category_editor_title_add

/**
 * The nature picker.
 * Why:    chips rather than a dropdown, so all five of §8.3's values are visible at once — this is
 *         the field the 50/30/20 rings, true spend and the Purchase Advisor all branch on, and
 *         hiding it behind a tap makes the most consequential choice on the form the least visible.
 * Result: the composition. Input: [state]; [onEvent]. Output: none.
 * Changelog: 2026-08-08 — Created for issue 4.1; split out when the sheet passed detekt's 40-line
 *            limit, which is the same seam `CfoNavHost` and `AccountsScreen` were split along.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NaturePicker(
    state: CategoryEditorState,
    onEvent: (CategoriesEvent) -> Unit,
) {
    Text(
        text = stringResource(R.string.category_editor_nature),
        style = MaterialTheme.typography.labelLarge,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs),
    ) {
        // `entries`, not a hand-written list: a sixth nature must appear here the day it is added,
        // and CategoryLabels already fails to compile until it has a label.
        CategoryNature.entries.forEach { nature ->
            FilterChip(
                selected = state.nature == nature,
                onClick = { onEvent(CategoriesEvent.NatureChanged(nature)) },
                label = { Text(stringResource(CategoryLabels.natureLabel(nature))) },
            )
        }
    }
}

/**
 * The parent picker.
 * Why:    chips rather than a dropdown, so every option is visible without a second tap — the list
 *         is short by construction, because only top-level categories may be parents. The "nothing"
 *         chip is first and is a real option rather than an absence: clearing a parent is an edit a
 *         user makes on purpose.
 * Result: the composition, or nothing at all when the profile has no top-level category to nest
 *         under — an empty label above an empty row would read as a broken control.
 * Input:  [state]; [uiState]; [onEvent]. Output: the composition.
 * Changelog: 2026-08-08 — Created for issue 4.1.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParentPicker(
    state: CategoryEditorState,
    uiState: CategoriesUiState,
    onEvent: (CategoriesEvent) -> Unit,
) {
    val options = uiState.parentOptions(excludingId = state.id)
    if (options.isEmpty()) return
    Text(
        text = stringResource(R.string.category_editor_parent),
        style = MaterialTheme.typography.labelLarge,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs),
    ) {
        FilterChip(
            selected = state.parentId == null,
            onClick = { onEvent(CategoriesEvent.ParentChanged(null)) },
            label = { Text(stringResource(R.string.category_editor_parent_none)) },
        )
        options.forEach { option ->
            FilterChip(
                selected = state.parentId == option.id,
                onClick = { onEvent(CategoriesEvent.ParentChanged(option.id)) },
                label = { Text(option.name) },
            )
        }
    }
}

/**
 * The delete confirmation (issue 4.1; P-02).
 *
 * Why:  deleting a category does not delete the money spent in it — the transactions keep their
 *       `category_id` and start reading as "Uncategorised". The user is told that, and told how many
 *       rows it applies to, **before** they confirm rather than discovering it afterwards on the
 *       transactions list.
 * What: the count (or the honest absence of one) and the two actions.
 * Result: an irreversible-looking action the user can size up first.
 * Changelog: 2026-08-08 — Created for issue 4.1.
 *
 * Input:  [confirmation]; [onEvent]. Output: the composition.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DeleteConfirmationCard(
    confirmation: DeleteConfirmation,
    onEvent: (CategoriesEvent) -> Unit,
) {
    CfoCard {
        Column(
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
        ) {
            Text(
                text = stringResource(R.string.categories_delete_title, confirmation.name),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(text = confirmation.usageMessage(), style = MaterialTheme.typography.bodyMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
                verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs),
            ) {
                CfoButton(
                    text = stringResource(R.string.categories_delete_confirm),
                    onClick = { onEvent(CategoriesEvent.ConfirmDelete) },
                )
                CfoSecondaryButton(
                    text = stringResource(R.string.categories_delete_cancel),
                    onClick = { onEvent(CategoriesEvent.CancelDelete) },
                )
            }
        }
    }
}

/**
 * Result: what the confirmation says about usage.
 *
 * Three cases, and the third is the one worth stating plainly: a `null` count means the query has
 * not answered — either it is still running or it failed — and the dialog says so rather than
 * claiming zero. Telling a user nothing uses a category when the app does not know would be the one
 * wrong thing to say here (P-02). Output: a [String].
 */
@Composable
private fun DeleteConfirmation.usageMessage(): String =
    when {
        usageCount == null -> stringResource(R.string.categories_delete_counting)
        usageCount == 0 -> stringResource(R.string.categories_delete_unused)
        else -> pluralStringResource(R.plurals.categories_delete_usage, usageCount, usageCount)
    }
