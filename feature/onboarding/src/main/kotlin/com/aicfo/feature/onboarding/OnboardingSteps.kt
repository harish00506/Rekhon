package com.aicfo.feature.onboarding

import androidx.annotation.StringRes
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
import com.aicfo.core.designsystem.component.CfoPinField
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.model.AccountType

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
 * Step 1 — welcome, the privacy pledge, and the way into the demo (FR-ONB-001, FR-ONB-004).
 * Why:    the pledge is shown before anything is asked for, because P-01 is the product's premise
 *         and a promise made after the questions is not a promise.
 *
 *         The demo entry sits **here, on the first step**, for the same reason: FR-ONB-004 says the
 *         sample data must be available *without creating a profile*, and an escape hatch placed
 *         after the questions is one the user has already answered their way past. It is a secondary
 *         button because setting the app up for real is still the main path.
 * Result: the composition. Input: [uiState], [onEvent]. Output: the rendered step.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 *            2026-07-28 — Issue 2.4: the demo entry point.
 */
@Composable
internal fun WelcomeStep(
    uiState: OnboardingUiState,
    onEvent: (OnboardingEvent) -> Unit,
) {
    Text(text = stringResource(R.string.onboarding_welcome_title), style = MaterialTheme.typography.headlineSmall)
    Text(text = stringResource(R.string.onboarding_welcome_body))
    CfoCard {
        Text(
            text = stringResource(R.string.onboarding_welcome_pledge_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(text = stringResource(R.string.onboarding_welcome_pledge))
    }
    CfoCard {
        Text(
            text = stringResource(R.string.onboarding_demo_title),
            style = MaterialTheme.typography.titleMedium,
        )
        // Says what the demo is *and* that leaving it erases everything, because the question a
        // privacy-first user asks before tapping is "what does this leave behind?" (P-02).
        Text(text = stringResource(R.string.onboarding_demo_body))
        CfoSecondaryButton(
            text = stringResource(R.string.onboarding_demo_action),
            onClick = { onEvent(OnboardingEvent.StartDemo) },
            enabled = !uiState.isSaving,
        )
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
 *            2026-07-27 — Issue 2.3: the derived plan is shown live beneath the fields.
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
    uiState.quickSetupPlan?.let { QuickSetupSummary(plan = it) }
}

/**
 * Step 6 — the first account and its opening balance (FR-ONB-001 step 4; issue 2.5).
 *
 * Why:    the last of FR-ONB-001's four steps, deferred by ADR-0002 until a repository existed to
 *         store it. **Skippable, and skipping means leaving the name blank** — there is no separate
 *         Skip action because there is nothing to skip past: an unnamed account is simply not
 *         created, and inventing an empty one for a user who declined would be fabricating data
 *         (P-03).
 *
 *         Only three fields, against the eleven-type editor in `:feature:accounts`: a first-run flow
 *         asking about gold, crypto and receivables would be asking a question the user has no
 *         reason to answer yet. The common cases are here; everything else is one screen away.
 * Result: the composition. Input: [uiState], [onEvent]. Output: the rendered step.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 */
@Composable
internal fun AccountStep(
    uiState: OnboardingUiState,
    onEvent: (OnboardingEvent) -> Unit,
) {
    Text(text = stringResource(R.string.onboarding_account_title), style = MaterialTheme.typography.headlineSmall)
    Text(text = stringResource(R.string.onboarding_account_body))
    OutlinedTextField(
        value = uiState.accountName,
        onValueChange = { onEvent(OnboardingEvent.AccountNameChanged(it)) },
        label = { Text(stringResource(R.string.onboarding_account_name_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    ONBOARDING_ACCOUNT_TYPES.forEach { type ->
        ChoiceRow(
            label = stringResource(onboardingAccountTypeLabel(type)),
            selected = uiState.accountType == type,
        ) { onEvent(OnboardingEvent.AccountTypeChanged(type)) }
    }
    AmountField(
        value = uiState.accountOpeningBalanceText,
        label = stringResource(R.string.onboarding_account_balance_label),
    ) { onEvent(OnboardingEvent.AccountOpeningBalanceChanged(it)) }
    Text(text = stringResource(R.string.onboarding_account_balance_help))
}

/**
 * The account types worth offering during first run.
 * Why:    a subset of FR-ACC-001's eleven, deliberately. A first-run flow is not the place to ask
 *         whether someone holds SGBs — these four cover what almost everyone has on day one, and
 *         the full set lives in the accounts editor, one screen away.
 * Result: the ordered options. Changelog: 2026-07-28 — Created for issue 2.5.
 */
internal val ONBOARDING_ACCOUNT_TYPES: List<AccountType> =
    listOf(AccountType.BANK, AccountType.CASH, AccountType.CREDIT_CARD, AccountType.INVESTMENT)

/**
 * Result: the label for one of [ONBOARDING_ACCOUNT_TYPES]. Input: [type]. Output: a string resource.
 *
 * `:feature:accounts` has its own copy of this mapping and cannot share it — features never depend
 * on each other (ARC-001) — so this covers only the four offered here and falls back for the rest.
 */
@StringRes
private fun onboardingAccountTypeLabel(type: AccountType): Int =
    when (type) {
        AccountType.CASH -> R.string.onboarding_account_type_cash
        AccountType.CREDIT_CARD -> R.string.onboarding_account_type_credit_card
        AccountType.INVESTMENT -> R.string.onboarding_account_type_investment
        else -> R.string.onboarding_account_type_bank
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

/**
 * Step 4 — the app lock (FR-ONB-001 step 3, SEC-002, issue 2.2).
 * Why:    ADR-0002 deferred this step to issue 2.2 and fixed its position after [ProfileStep] — the
 *         profile exists by then, which is what a PIN is protecting. It is **skippable**: SEC-002
 *         describes the lock, it does not require enabling it during first run, and a user who
 *         declines here can turn it on later in settings (FR-SET-001).
 * Result: the composition. Input: [uiState], [onEvent]. Output: the rendered step.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 */
@Composable
internal fun SecurityStep(
    uiState: OnboardingUiState,
    onEvent: (OnboardingEvent) -> Unit,
) {
    Text(text = stringResource(R.string.onboarding_security_title), style = MaterialTheme.typography.headlineSmall)
    Text(text = stringResource(R.string.onboarding_security_body))
    CfoCard {
        Text(text = stringResource(R.string.onboarding_security_explainer))
    }
    CfoListRow(
        title = stringResource(R.string.onboarding_security_toggle),
        // Same reasoning as the consent row: the whole row is the control, announced once with its
        // label and on/off state, rather than an unlabelled switch beside an inert piece of text.
        modifier =
            Modifier.toggleable(
                value = uiState.appLockEnabled,
                role = Role.Switch,
                onValueChange = { onEvent(OnboardingEvent.AppLockToggled(it)) },
            ),
        trailing = {
            Switch(
                checked = uiState.appLockEnabled,
                onCheckedChange = null,
                modifier = Modifier.clearAndSetSemantics {},
            )
        },
    )
    if (uiState.appLockEnabled) {
        PinFields(uiState = uiState, onEvent = onEvent)
    }
    Text(
        text = stringResource(R.string.onboarding_security_optional),
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * The choose-and-confirm PIN pair, shown only once the lock is switched on.
 * Why:    hidden until asked for, because a first-run screen that opens with two PIN boxes reads as
 *         a demand rather than the offer ADR-0002 requires this step to be. Extracted from
 *         [SecurityStep] to keep that function inside detekt's length limit.
 * Result: the composition. Input: [uiState], [onEvent]. Output: the two fields plus their help text.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 *
 * The confirm field exists because a mistyped PIN set here is not recoverable by trying again — the
 * next thing that asks for it is the lock screen, and the user has only their memory of what they
 * meant to type.
 */
@Composable
private fun PinFields(
    uiState: OnboardingUiState,
    onEvent: (OnboardingEvent) -> Unit,
) {
    CfoPinField(
        value = uiState.pinText,
        onValueChange = { onEvent(OnboardingEvent.PinChanged(it)) },
        label = stringResource(R.string.onboarding_security_pin_label),
    )
    CfoPinField(
        value = uiState.pinConfirmText,
        onValueChange = { onEvent(OnboardingEvent.PinConfirmChanged(it)) },
        label = stringResource(R.string.onboarding_security_pin_confirm_label),
    )
    Text(
        text = stringResource(R.string.onboarding_security_pin_help),
        style = MaterialTheme.typography.bodySmall,
    )
}
