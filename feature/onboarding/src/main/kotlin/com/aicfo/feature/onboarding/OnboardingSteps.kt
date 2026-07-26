package com.aicfo.feature.onboarding

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.KeyboardType
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoListRow

/*
 * The four faces of the onboarding flow (issue 2.1; FR-ONB-001/002/003).
 *
 * Why:  split out of OnboardingScreen.kt, which had grown past detekt's per-file function limit.
 *       The chrome — the step counter, the error banner, the Back/Next/Finish row — belongs with
 *       the screen; the steps themselves are what changes as issues 2.2 and 2.5 add theirs, so they
 *       are easier to find and to review here.
 * What: one composable per step, plus the two small controls they share.
 * Result: adding a step is a new function in this file and a new enum constant, nothing else.
 * Changelog: 2026-07-25 — Split from OnboardingScreen.kt for issue 2.1.
 */

/**
 * Step 1 — welcome and the privacy pledge (FR-ONB-001).
 * Why:    the pledge is shown before anything is asked for, because P-01 is the product's premise
 *         and a promise made after the questions is not a promise.
 * Result: the composition. Input: none. Output: the rendered step.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 */
@Composable
internal fun WelcomeStep() {
    Text(text = stringResource(R.string.onboarding_welcome_title), style = MaterialTheme.typography.headlineSmall)
    Text(text = stringResource(R.string.onboarding_welcome_body))
    CfoCard {
        Text(
            text = stringResource(R.string.onboarding_welcome_pledge_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(text = stringResource(R.string.onboarding_welcome_pledge))
    }
}

/**
 * Step 2 — the SMS-parsing opt-in (FR-ONB-003, P-01).
 * Why:    FR-ONB-003 requires this to be separate, skippable, and explained with what is read plus
 *         the statement that parsing is on-device only. All three sentences are therefore part of
 *         the requirement, not decoration — and the switch starts **off**, because absence is never
 *         consent.
 * Result: the composition. Input: [uiState], [onEvent]. Output: the rendered step.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 */
@Composable
internal fun ConsentStep(
    uiState: OnboardingUiState,
    onEvent: (OnboardingEvent) -> Unit,
) {
    Text(text = stringResource(R.string.onboarding_consent_title), style = MaterialTheme.typography.headlineSmall)
    Text(text = stringResource(R.string.onboarding_consent_body))
    CfoCard {
        Text(text = stringResource(R.string.onboarding_consent_what_is_read))
        Text(text = stringResource(R.string.onboarding_consent_on_device))
    }
    CfoListRow(
        title = stringResource(R.string.onboarding_consent_toggle),
        // The whole row toggles, not just the switch: a label that looks tappable and is not is the
        // single most common complaint about settings rows, and it costs a user with a motor
        // impairment several attempts. One `toggleable` also means one control is announced, with
        // its label and its on/off state together, instead of an unlabelled switch beside a text.
        modifier =
            Modifier.toggleable(
                value = uiState.smsConsentGranted,
                role = Role.Switch,
                onValueChange = { onEvent(OnboardingEvent.SmsConsentChanged(it)) },
            ),
        trailing = {
            Switch(
                checked = uiState.smsConsentGranted,
                onCheckedChange = null,
                modifier = Modifier.clearAndSetSemantics {},
            )
        },
    )
    Text(
        text = stringResource(R.string.onboarding_consent_optional),
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * Step 3 — display name, currency and time zone (FR-ONB-001, TIM-001).
 * Why:    the time zone is the consequential answer here: it decides when the user's day and month
 *         roll over, and until this step nothing in the app has ever written it, so every date
 *         resolved in the device zone by fallback.
 * Result: the composition. Input: [uiState], [onEvent]. Output: the rendered step.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 */
@Composable
internal fun ProfileStep(
    uiState: OnboardingUiState,
    onEvent: (OnboardingEvent) -> Unit,
) {
    Text(text = stringResource(R.string.onboarding_profile_title), style = MaterialTheme.typography.headlineSmall)
    Text(text = stringResource(R.string.onboarding_profile_body))
    OutlinedTextField(
        value = uiState.displayName,
        onValueChange = { onEvent(OnboardingEvent.DisplayNameChanged(it)) },
        label = { Text(text = stringResource(R.string.onboarding_profile_name_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = stringResource(R.string.onboarding_profile_currency_label),
        style = MaterialTheme.typography.titleMedium,
    )
    OnboardingOptions.currencies.forEach { code ->
        ChoiceRow(label = code, selected = code == uiState.currencyCode) {
            onEvent(OnboardingEvent.CurrencyChanged(code))
        }
    }
    Text(
        text = stringResource(R.string.onboarding_profile_time_zone_label),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(text = stringResource(R.string.onboarding_profile_time_zone_help), style = MaterialTheme.typography.bodySmall)
    uiState.timeZoneOptions.forEach { zoneId ->
        ChoiceRow(label = zoneId, selected = zoneId == uiState.timeZoneId) {
            onEvent(OnboardingEvent.TimeZoneChanged(zoneId))
        }
    }
}

/**
 * Step 4 — the optional quick-setup seeds (FR-ONB-002).
 * Why:    issue 2.3 turns these into budgets and an emergency-fund target. They are amounts, so
 *         they are captured as text here and converted once, by `MoneyFormatter.parse` (MNY-001) —
 *         a half-typed "1." is not an amount and must not become one.
 * Result: the composition. Input: [uiState], [onEvent]. Output: the rendered step.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 */
@Composable
internal fun QuickSetupStep(
    uiState: OnboardingUiState,
    onEvent: (OnboardingEvent) -> Unit,
) {
    Text(text = stringResource(R.string.onboarding_quick_setup_title), style = MaterialTheme.typography.headlineSmall)
    Text(text = stringResource(R.string.onboarding_quick_setup_body))
    AmountField(
        value = uiState.monthlyIncomeText,
        label = stringResource(R.string.onboarding_quick_setup_income_label),
    ) { onEvent(OnboardingEvent.MonthlyIncomeChanged(it)) }
    AmountField(
        value = uiState.rentOrEmiText,
        label = stringResource(R.string.onboarding_quick_setup_rent_label),
    ) { onEvent(OnboardingEvent.RentOrEmiChanged(it)) }
    AmountField(
        value = uiState.typicalSavingsText,
        label = stringResource(R.string.onboarding_quick_setup_savings_label),
    ) { onEvent(OnboardingEvent.TypicalSavingsChanged(it)) }
}

/**
 * One selectable option.
 * Why:    a radio list rather than a dropdown — every option stays visible to a screen reader and
 *         at a 200% font, and there is no menu to open before a choice can be made.
 * Result: the row. Input: [label] — a currency or zone id, which is data, not copy; [selected];
 *         [onSelect]. Output: the composition.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 */
@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    CfoListRow(
        title = label,
        // Same reasoning as the consent row: the whole row is the control, announced once with its
        // label and selected state, rather than a 20dp circle beside an inert piece of text.
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        trailing = {
            RadioButton(selected = selected, onClick = null, modifier = Modifier.clearAndSetSemantics {})
        },
    )
}

/**
 * One optional rupee amount.
 * Why:    a decimal keyboard because the answer is always a number, and the ₹ prefix so the unit is
 *         never in doubt — the alternative is a user typing "45k" and the parser refusing it.
 * Result: the field. Input: [value], [label], [onValueChange]. Output: the composition.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 */
@Composable
private fun AmountField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(text = label) },
        prefix = { Text(text = stringResource(R.string.onboarding_quick_setup_amount_hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
