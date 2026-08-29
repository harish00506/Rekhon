package com.aicfo.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicfo.core.datastore.ConsentFeature
import com.aicfo.core.designsystem.component.CfoButton
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoPinField
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens

/**
 * The screen that makes three frozen settings changeable again (FR-SET-001; P-01, SEC-002).
 *
 * Why:  monthly income, the per-feature consents and the app lock were all writable only by
 *       `:feature:onboarding`, which is unreachable after first run. The consent case is a golden
 *       rule violation rather than an inconvenience — **P-01 requires consent to be explicit,
 *       revocable and per-feature**, and revocation existed in the data layer with nothing able to
 *       trigger it. Five strings across three other modules used to point here before this existed.
 * What: the money plan, the consent ledger, and the app lock, in that order.
 * Result: the dashboard's needs/wants/savings split and Safe-to-Spend's preferred income basis stop
 *       being permanently empty for anyone who skipped the optional onboarding step.
 * Changelog: 2026-08-29 — Created for FR-SET-001.
 *
 * **No amounts are masked here**, unlike every other screen: these are fields the user is editing,
 * and a privacy blur over an input you are typing into would make it unusable. The screen shows
 * only what the user themselves just entered.
 *
 * Input:  [onDone] — pops back; [viewModel] — supplied by Hilt.
 * Output: the composition.
 */
@Composable
fun SettingsScreen(
    onDone: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsContent(uiState = uiState, onEvent = viewModel::onEvent, onDone = onDone)
}

/**
 * The screen, separated from Hilt so a test can drive it from a literal state.
 * Result: the composition. Input: [uiState]; [onEvent]; [onDone]. Output: none.
 * Changelog: 2026-08-29 — Created for FR-SET-001.
 */
@Composable
internal fun SettingsContent(
    uiState: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(CfoDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd),
    ) {
        Text(text = stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)

        uiState.errorCode?.let {
            CfoCard {
                Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
                    Text(text = stringResource(R.string.settings_error))
                    CfoSecondaryButton(
                        text = stringResource(R.string.settings_dismiss),
                        onClick = { onEvent(SettingsEvent.DismissError) },
                    )
                }
            }
        }

        MoneySection(uiState = uiState, onEvent = onEvent)
        ConsentSection(uiState = uiState, onEvent = onEvent)
        AppLockSection(uiState = uiState, onEvent = onEvent)

        CfoSecondaryButton(text = stringResource(R.string.settings_done), onClick = onDone)
    }
}

/**
 * The three seeds the quick-setup engine splits into envelopes.
 * Why:    this is the section that closes the reported defect. Until it existed the envelopes could
 *         only be written once, at onboarding, so a user who skipped the optional step saw "no
 *         budget yet" for ever.
 * Result: the composition. Input: [uiState]; [onEvent]. Output: none.
 * Changelog: 2026-08-29 — Created for FR-SET-001.
 */
@Composable
private fun MoneySection(
    uiState: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
) {
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            Text(text = stringResource(R.string.settings_money_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.settings_money_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MoneyFields(uiState = uiState, onEvent = onEvent)
            CfoButton(
                text = stringResource(R.string.settings_money_save),
                onClick = { onEvent(SettingsEvent.SaveMoneyPlan) },
            )
            if (uiState.savedAtLeastOnce) {
                Text(
                    text = stringResource(R.string.settings_money_saved),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * The three amount fields, and the income's validation message.
 * Why:    split out of [MoneySection] to keep it under the 40-line limit (§21.6) — the same
 *         pressure that split the account editor's type-specific forms out.
 * Result: the composition. Input: [uiState]; [onEvent]. Output: none.
 * Changelog: 2026-08-29 — Created for FR-SET-001.
 */
@Composable
private fun MoneyFields(
    uiState: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
) {
    AmountField(
        value = uiState.monthlyIncomeText,
        label = stringResource(R.string.settings_monthly_income),
        onValueChange = { onEvent(SettingsEvent.MonthlyIncomeChanged(it)) },
    )
    if (uiState.fieldError == INCOME_FIELD) {
        Text(
            text = stringResource(R.string.settings_income_required),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    AmountField(
        value = uiState.rentOrEmiText,
        label = stringResource(R.string.settings_rent_or_emi),
        onValueChange = { onEvent(SettingsEvent.RentOrEmiChanged(it)) },
    )
    AmountField(
        value = uiState.typicalSavingsText,
        label = stringResource(R.string.settings_typical_savings),
        onValueChange = { onEvent(SettingsEvent.TypicalSavingsChanged(it)) },
    )
}

/**
 * One switch per consent (P-01).
 * Why:    every feature is listed, including the ones the user has never touched, because a consent
 *         with no switch is a consent that cannot be revoked — which is the defect this closes.
 * Result: the composition. Input: [uiState]; [onEvent]. Output: none.
 * Changelog: 2026-08-29 — Created for FR-SET-001.
 */
@Composable
private fun ConsentSection(
    uiState: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
) {
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            Text(text = stringResource(R.string.settings_consents_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.settings_consents_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ConsentFeature.entries.forEach { feature ->
                ToggleRow(
                    label = stringResource(feature.label()),
                    checked = uiState.consents[feature] == true,
                    onCheckedChange = { onEvent(SettingsEvent.ConsentToggled(feature, it)) },
                )
            }
        }
    }
}

/**
 * The app lock, and the PIN it needs before it can be turned on (SEC-002).
 * Result: the composition. Input: [uiState]; [onEvent]. Output: none.
 * Changelog: 2026-08-29 — Created for FR-SET-001.
 */
@Composable
private fun AppLockSection(
    uiState: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
) {
    CfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            Text(text = stringResource(R.string.settings_lock_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.settings_lock_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The PIN field is shown only while the lock is off, because that is the only direction
            // that needs one: turning the lock off clears the PIN rather than asking for it again.
            if (!uiState.appLockEnabled) {
                CfoPinField(
                    value = uiState.pinText,
                    onValueChange = { onEvent(SettingsEvent.PinChanged(it)) },
                    label = stringResource(R.string.settings_pin),
                )
                if (uiState.fieldError == PIN_FIELD) {
                    Text(
                        text = stringResource(R.string.settings_pin_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            ToggleRow(
                label = stringResource(R.string.settings_lock_toggle),
                checked = uiState.appLockEnabled,
                onCheckedChange = { onEvent(SettingsEvent.AppLockToggled(it)) },
            )
        }
    }
}

/**
 * A labelled switch, announced as one node.
 * Why:    a label and a switch announced separately are two fragments a screen-reader user has to
 *         reassemble, the same argument the accounts row makes for its utilisation bar.
 * Result: the composition. Input: [label]; [checked]; [onCheckedChange]. Output: none.
 * Changelog: 2026-08-29 — Created for FR-SET-001.
 */
@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = label },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * A rupee amount field.
 * Why:    a decimal keyboard, because every one of these is money and a text keyboard invites input
 *         `MoneyFormatter.parse` will reject.
 * Result: the composition. Input: [value]; [label]; [onValueChange]. Output: none.
 * Changelog: 2026-08-29 — Created for FR-SET-001.
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
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The string each consent is labelled with.
 * Why:    a `when` rather than a map, so adding a `ConsentFeature` fails to compile until somebody
 *         writes its label — a consent shown with a blank name is one nobody can make a decision
 *         about (P-01).
 * Result: the label's resource id. Input: the receiver. Output: the id.
 * Changelog: 2026-08-29 — Created for FR-SET-001.
 */
private fun ConsentFeature.label(): Int =
    when (this) {
        ConsentFeature.SMS_PARSING -> R.string.consent_sms_parsing
        ConsentFeature.MARKET_DATA -> R.string.consent_market_data
        ConsentFeature.CLOUD_LLM -> R.string.consent_cloud_llm
        ConsentFeature.CLOUD_BACKUP -> R.string.consent_cloud_backup
    }

/** The field name the ViewModel reports when the income is missing or unparseable. */
private const val INCOME_FIELD = "monthlyIncome"

/** The field name the ViewModel reports when the PIN is too short to enable the lock. */
private const val PIN_FIELD = "pin"
