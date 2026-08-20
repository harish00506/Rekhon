package com.aicfo.feature.accounts

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.aicfo.core.designsystem.theme.CfoDimens

// The account editor's loan section (issue 6.2; FR-ACC-003).
//
// A file of its own rather than three more composables in `AccountEditorScreen.kt`, which was
// already at detekt's ceiling for functions in one file. The seam is a good one regardless: these
// three are the only composables in the module that know what a tenure or a basis point is, and the
// editor beside them stays about the fields all eleven types share.

/**
 * The loan's terms (issue 6.2; FR-ACC-003).
 *
 * Why:    shown only for `LOAN`, the argument [CardFields] makes for its own type. A sibling of
 *         that composable rather than a second branch inside it: the two sections share no field,
 *         and the day one of them grows a row the other must not have to be read to be sure.
 *
 *         **The first EMI is a full date, unlike a card's two day-of-month fields.** A card bills
 *         on the 5th of every month for ever; a loan's schedule is anchored — instalment 47 falls
 *         on a specific day of a specific year, and a day number alone cannot say which month the
 *         loan started in.
 *
 *         **The rate is typed in percent**, because that is what the sanction letter says. It
 *         becomes basis points in `toLoan` (MNY-002), once, at save.
 * Result: the composition. Input: [uiState], [onEvent]. Output: none.
 * Changelog: 2026-08-20 — Created for issue 6.2.
 */
@Composable
internal fun LoanFields(
    uiState: AccountEditorUiState,
    onEvent: (AccountEditorEvent) -> Unit,
) {
    Text(
        text = stringResource(R.string.account_editor_loan_section),
        style = MaterialTheme.typography.titleSmall,
    )
    LoanTermField(R.string.account_editor_loan_principal, uiState.principalText, LoanField.PRINCIPAL, onEvent)
    Row(horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
        Box(Modifier.weight(1f)) {
            LoanTermField(R.string.account_editor_loan_rate, uiState.annualRateText, LoanField.ANNUAL_RATE, onEvent)
        }
        Box(Modifier.weight(1f)) {
            LoanTermField(
                R.string.account_editor_loan_tenure,
                uiState.tenureMonthsText,
                LoanField.TENURE_MONTHS,
                onEvent,
                keyboard = KeyboardType.Number,
            )
        }
    }
    LoanDateAndOverride(uiState = uiState, onEvent = onEvent)
}

/**
 * The loan section's last two fields and the sentence explaining them.
 * Why:    split from [LoanFields] purely to stay inside the 40-line function limit (§21.6). The
 *         seam is the two terms every loan has against the one it may not — a lender's own EMI —
 *         and the prose that says why that one is optional (P-02).
 * Result: the composition. Input: [uiState], [onEvent]. Output: none.
 * Changelog: 2026-08-20 — Created for issue 6.2.
 */
@Composable
private fun LoanDateAndOverride(
    uiState: AccountEditorUiState,
    onEvent: (AccountEditorEvent) -> Unit,
) {
    LoanTermField(
        R.string.account_editor_loan_first_emi,
        uiState.firstEmiDateText,
        LoanField.FIRST_EMI_DATE,
        onEvent,
        // A plain text keypad: an ISO date is digits and hyphens, and KeyboardType.Number has no
        // hyphen on most IMEs — the same gap the opening-balance field hits with the minus sign.
        keyboard = KeyboardType.Text,
    )
    LoanTermField(R.string.account_editor_loan_emi_override, uiState.emiOverrideText, LoanField.EMI_OVERRIDE, onEvent)
    Text(
        text = stringResource(R.string.account_editor_loan_help),
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * One loan field.
 * Why:    five near-identical text fields, the reason [CardField] exists one section up — writing
 *         them out five times is how one ends up wired to the wrong event.
 * Result: the composition.
 * Input:  [label] — a string resource; [value]; [field]; [onEvent]; [keyboard] — a plain number pad
 *         for the tenure and plain text for the date, which needs a hyphen no number pad offers.
 *         The date's format lives in its **label** rather than a placeholder: a placeholder
 *         disappears the moment the user types, which is exactly when they need to see it.
 * Output: none.
 * Changelog: 2026-08-20 — Created for issue 6.2.
 */
@Composable
private fun LoanTermField(
    @StringRes label: Int,
    value: String,
    field: LoanField,
    onEvent: (AccountEditorEvent) -> Unit,
    keyboard: KeyboardType = KeyboardType.Decimal,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onEvent(AccountEditorEvent.LoanFieldChanged(field, it)) },
        label = { Text(stringResource(label)) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
