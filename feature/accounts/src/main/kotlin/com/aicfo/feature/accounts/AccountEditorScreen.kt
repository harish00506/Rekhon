package com.aicfo.feature.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicfo.core.designsystem.component.CfoButton
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.core.model.AccountType

/**
 * Create or edit one account (issue 2.5; FR-ACC-001, ARC-004).
 *
 * Why:  one screen for both, because the fields, the validation and the save are identical. The
 *       only difference is whether the route carried an id, and that is decided in the ViewModel
 *       rather than here — the composable stays a function of state.
 * What: a stateful entry point and a stateless body.
 * Result: an account of any of the eleven types can be created and changed.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 *
 * Input:  [onDone] — where to go once saved or cancelled; [modifier]; [viewModel].
 * Output: the rendered screen.
 */
@Composable
fun AccountEditorScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AccountEditorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Navigation is a side effect of state, not of the tap: a save that failed must not leave the
    // screen, and the ViewModel is the only thing that knows whether it succeeded.
    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) onDone()
    }

    AccountEditorContent(
        uiState = uiState,
        onEvent = viewModel::onEvent,
        onCancel = onDone,
        modifier = modifier,
    )
}

/**
 * The editor's body, with no dependencies of its own.
 * Result: the rendered content.
 * Input:  [uiState]; [onEvent] — events up (ARC-004); [onCancel]; [modifier].
 * Output: the composition.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 */
@Composable
fun AccountEditorContent(
    uiState: AccountEditorUiState,
    onEvent: (AccountEditorEvent) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                // Scrollable because the type picker is eleven rows, and at 200% font the form is
                // taller than any phone (§21.6's accessibility line).
                .verticalScroll(rememberScrollState())
                .padding(CfoDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd),
    ) {
        Text(
            text =
                stringResource(
                    if (uiState.isEditing) {
                        R.string.account_editor_title_edit
                    } else {
                        R.string.account_editor_title_new
                    },
                ),
            style = MaterialTheme.typography.headlineSmall,
        )

        uiState.errorCode?.let { code ->
            EditorErrorBanner(code = code, onDismiss = { onEvent(AccountEditorEvent.DismissError) })
        }

        EditorFields(uiState = uiState, onEvent = onEvent)

        TypePicker(selected = uiState.type, onSelect = { onEvent(AccountEditorEvent.TypeChanged(it)) })

        Row(horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            CfoButton(
                text = stringResource(R.string.account_editor_save),
                onClick = { onEvent(AccountEditorEvent.Save) },
                enabled = uiState.canSave,
            )
            CfoSecondaryButton(text = stringResource(R.string.account_editor_cancel), onClick = onCancel)
        }
    }
}

/**
 * The three text fields, with the two explanations that go under the amount.
 * Why:    split from [AccountEditorContent] to stay within the 40-line function limit (§21.6). The
 *         seam is the form itself against its chrome — the title, the error and the actions.
 * Result: the composition. Input: [uiState], [onEvent]. Output: none.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 */
@Composable
private fun EditorFields(
    uiState: AccountEditorUiState,
    onEvent: (AccountEditorEvent) -> Unit,
) {
    OutlinedTextField(
        value = uiState.name,
        onValueChange = { onEvent(AccountEditorEvent.NameChanged(it)) },
        label = { Text(stringResource(R.string.account_editor_name)) },
        placeholder = { Text(stringResource(R.string.account_editor_name_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = uiState.institution,
        onValueChange = { onEvent(AccountEditorEvent.InstitutionChanged(it)) },
        label = { Text(stringResource(R.string.account_editor_institution)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = uiState.openingBalanceText,
        onValueChange = { onEvent(AccountEditorEvent.OpeningBalanceChanged(it)) },
        label = { Text(stringResource(R.string.account_editor_opening_balance)) },
        // A decimal keypad, not a number pad: a liability is entered negative, and the minus sign
        // is missing from KeyboardType.Number on most IMEs.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    // P-02: the user must be able to see *why* the current balance is not theirs to type here, not
    // merely discover that it is missing.
    Text(
        text = stringResource(R.string.account_editor_opening_balance_help),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        text = stringResource(R.string.account_editor_liability_help),
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * The editor's error banner.
 * Why:    `liveRegion` so a screen reader announces a failure the user did not go looking for.
 * Result: the composition. Input: [code] — an `AppError.code`; [onDismiss]. Output: none.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 */
@Composable
private fun EditorErrorBanner(
    code: String,
    onDismiss: () -> Unit,
) {
    CfoCard {
        Column(
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
        ) {
            Text(
                text = stringResource(AccountLabels.errorMessage(code)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            CfoSecondaryButton(text = stringResource(R.string.accounts_error_dismiss), onClick = onDismiss)
        }
    }
}

/**
 * FR-ACC-001's eleven types, as a single-choice list.
 * Why:    a radio list rather than a dropdown — eleven is too many to remember behind a closed
 *         control, and `selectable` with a `RadioButton` gives a screen reader the whole set and its
 *         current choice, which a custom menu would not.
 * Result: the composition.
 * Input:  [selected]; [onSelect]. Output: none.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 */
@Composable
private fun TypePicker(
    selected: AccountType,
    onSelect: (AccountType) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs)) {
        Text(
            text = stringResource(R.string.account_editor_type),
            style = MaterialTheme.typography.titleSmall,
        )
        AccountType.entries.forEach { type ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        // On the Row, not the RadioButton: the whole line is the target, which is
                        // what keeps it above the 48dp minimum at any font scale.
                        .selectable(selected = type == selected, onClick = { onSelect(type) })
                        .padding(vertical = CfoDimens.spaceXs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
            ) {
                RadioButton(selected = type == selected, onClick = null)
                Text(
                    text = stringResource(AccountLabels.typeLabel(type)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
