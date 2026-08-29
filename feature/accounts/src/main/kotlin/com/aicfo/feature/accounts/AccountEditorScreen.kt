package com.aicfo.feature.accounts

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
    BalanceHelp()
    NetWorthToggle(
        checked = uiState.includeInNetWorth,
        onCheckedChange = { onEvent(AccountEditorEvent.IncludeInNetWorthChanged(it)) },
    )
    // Issue 6.1: the module's first type branch. Everything above is true of all eleven types;
    // a limit and a statement day are true of exactly one.
    if (uiState.showsCardFields) {
        CardFields(uiState = uiState, onEvent = onEvent)
    }
    // Issue 6.2: the second. Mutually exclusive with the branch above — an account is one type.
    if (uiState.showsLoanFields) {
        LoanFields(uiState = uiState, onEvent = onEvent)
    }
}

/**
 * The two sentences under the opening-balance field.
 * Why:    P-02 — the user must be able to see *why* the current balance is not theirs to type here,
 *         not merely discover that it is missing. Extracted from [EditorFields] when issue 6.1's
 *         card branch pushed that function past the 40-line limit (§21.6); the seam is the fields
 *         themselves against the prose explaining them.
 * Result: the composition. Input: none. Output: none.
 * Changelog: 2026-08-17 — Extracted for issue 6.1.
 */
@Composable
private fun BalanceHelp() {
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
 * The credit-card terms (issue 6.1; FR-ACC-002).
 *
 * Why:    shown only for `CREDIT_CARD`, because a limit and a statement day mean nothing on a
 *         savings account and offering them would invite a user to fill in fields the app ignores.
 *         Split into its own composable to stay inside the 40-line function limit (§21.6), and
 *         because 6.2's loan terms will sit beside it as a sibling rather than inside `EditorFields`.
 *
 *         **The two days are separate fields rather than a date picker.** FR-ACC-002 stores days,
 *         not dates: a card bills on the 5th every month, and asking for a date would make the user
 *         answer a question about one particular month.
 * Result: the composition. Input: [uiState], [onEvent]. Output: none.
 * Changelog: 2026-08-17 — Created for issue 6.1.
 */
@Composable
private fun CardFields(
    uiState: AccountEditorUiState,
    onEvent: (AccountEditorEvent) -> Unit,
) {
    Text(
        text = stringResource(R.string.account_editor_card_section),
        style = MaterialTheme.typography.titleSmall,
    )
    CardField(R.string.account_editor_card_limit, uiState.creditLimitText, CardField.LIMIT, onEvent)
    Row(horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
        Box(Modifier.weight(1f)) {
            CardField(
                R.string.account_editor_card_statement_day,
                uiState.statementDayText,
                CardField.STATEMENT_DAY,
                onEvent,
                numeric = true,
            )
        }
        Box(Modifier.weight(1f)) {
            CardField(
                R.string.account_editor_card_due_day,
                uiState.dueDayText,
                CardField.DUE_DAY,
                onEvent,
                numeric = true,
            )
        }
    }
    CardField(R.string.account_editor_card_last_statement, uiState.lastStatementText, CardField.LAST_STATEMENT, onEvent)
    CardField(R.string.account_editor_card_minimum_due, uiState.minimumDueText, CardField.MINIMUM_DUE, onEvent)
    // P-02: the user should know why three of these are asked for together and two are not.
    Text(
        text = stringResource(R.string.account_editor_card_help),
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * One card field.
 * Why:    five near-identical text fields, differing in label, value, which [CardField] they emit
 *         and whether the keypad shows a decimal point. Writing them out five times is how one of
 *         them ends up wired to the wrong event.
 * Result: the composition.
 * Input:  [label] — a string resource; [value]; [field]; [onEvent]; [numeric] — `true` for the two
 *         day fields, which are whole numbers and get a plain number pad.
 * Output: none.
 * Changelog: 2026-08-17 — Created for issue 6.1.
 */
@Composable
private fun CardField(
    @StringRes label: Int,
    value: String,
    field: CardField,
    onEvent: (AccountEditorEvent) -> Unit,
    numeric: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onEvent(AccountEditorEvent.CardFieldChanged(field, it)) },
        label = { Text(stringResource(label)) },
        keyboardOptions =
            KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Decimal,
            ),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * FR-ACC-005's opt-out (issue 2.6).
 *
 * Why:    `toggleable` on the **Row**, not the `Switch` — the same pattern the accounts list landed
 *         for its archived toggle. A 20dp switch beside inert text is under the 48dp minimum and is
 *         announced to a screen reader as two unrelated things.
 *
 *         The explanation sits under it because "count towards net worth" is not self-explanatory:
 *         a user needs to know it is *not* the same as archiving, and that the account stays in
 *         their list either way.
 * Result: the composition. Input: [checked], [onCheckedChange]. Output: none.
 * Changelog: 2026-08-01 — Created for issue 2.6.
 */
@Composable
private fun NetWorthToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .defaultMinSize(minHeight = CfoDimens.minTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
    ) {
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics {})
        Text(
            text = stringResource(R.string.account_editor_include_in_networth),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Text(
        text = stringResource(R.string.account_editor_include_in_networth_help),
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
