package com.aicfo.feature.transactions

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicfo.core.designsystem.component.CfoAmountText
import com.aicfo.core.designsystem.component.CfoButton
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens
import com.aicfo.data.repository.SmsDraft
import com.aicfo.domain.engines.sms.SmsDirection

/**
 * Review what the app read from bank alerts (issue 3.9; §18, §23, P-01, P-07, ARC-004).
 *
 * Why:  **this screen asks for the OS permission and nothing else does.** `READ_SMS` is a dangerous,
 *       Play-restricted permission (ADR-0013), and the moment to request it is when a user who has
 *       already opted in has navigated here to see their alerts — not at launch, not during
 *       onboarding, and never before the in-app consent exists. `SmsDraftsUiState.stage` encodes
 *       that order, so the request is unreachable from a state that has not passed the consent.
 *
 *       Every row is an offer. There is no "accept all", deliberately: the whole feature rests on
 *       the user having looked at each figure (P-07), and a bulk button would make the review a
 *       formality on exactly the alerts most likely to have been misread.
 * What: four faces — opt in, grant, nothing waiting, and the list.
 * Result: bank alerts become transactions the user confirmed, tagged `sms`.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 *
 * Input:  [onDone] — leave the screen; [viewModel] — injected by Hilt. Output: the composition.
 */
@Composable
fun SmsDraftsScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SmsDraftsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onEvent(SmsDraftsEvent.PermissionResult(granted))
        }

    SmsDraftsContent(
        uiState = uiState,
        onEvent = viewModel::onEvent,
        onDone = onDone,
        onRequestPermission = { permissionLauncher.launch(Manifest.permission.READ_SMS) },
        modifier = modifier,
    )
}

/**
 * The SMS review screen's body, with no dependencies of its own (issue 3.9).
 * Why:    stateless so the flow test can drive every one of the four faces without Hilt, a database
 *         or a real permission — including [SmsDraftsStage.PERMISSION_NEEDED], which is otherwise
 *         unreachable in a test because Robolectric cannot present Android's own dialog. The split
 *         `AddTransactionContent` already uses, for the same reason.
 * Result: the composition.
 * Input:  [uiState]; [onEvent] — events up (ARC-004); [onDone]; [onRequestPermission] — launches the
 *         system dialog, a lambda rather than a launcher so a test can count the asks; [modifier].
 * Output: the rendered screen.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 */
@Composable
fun SmsDraftsContent(
    uiState: SmsDraftsUiState,
    onEvent: (SmsDraftsEvent) -> Unit,
    onDone: () -> Unit,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(CfoDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd),
    ) {
        Text(text = stringResource(R.string.sms_title), style = MaterialTheme.typography.headlineSmall)
        // FR-ONB-003's promise, repeated where the data actually is. A user deciding whether to
        // grant a permission should not have to remember what an onboarding step said.
        Text(text = stringResource(R.string.sms_on_device), style = MaterialTheme.typography.bodyMedium)

        when (uiState.stage) {
            SmsDraftsStage.CONSENT_OFF -> ConsentOffCard()
            SmsDraftsStage.PERMISSION_NEEDED -> PermissionCard(onGrant = onRequestPermission)
            SmsDraftsStage.EMPTY -> EmptyCard(uiState = uiState, onEvent = onEvent)
            SmsDraftsStage.REVIEW -> {
                AccountPicker(uiState = uiState, onEvent = onEvent)
                uiState.drafts.forEach { draft ->
                    DraftCard(
                        draft = draft,
                        edit = uiState.editFor(draft),
                        canAccept = uiState.canAcceptDraft(draft),
                        onEvent = onEvent,
                    )
                }
            }
        }

        uiState.errorCode?.let { code ->
            // The code, mapped to copy at the UI edge — an AppError's own message is a non-localised
            // fallback and may never be shown (§21.6).
            Text(text = stringResource(R.string.sms_error, code), style = MaterialTheme.typography.bodyMedium)
            CfoSecondaryButton(
                text = stringResource(R.string.sms_dismiss_error),
                onClick = { onEvent(SmsDraftsEvent.DismissError) },
            )
        }

        CfoSecondaryButton(text = stringResource(R.string.sms_done), onClick = onDone)
    }
}

/**
 * The user has not opted in (issue 3.9; P-01).
 * Why:    it says where the switch is rather than offering one, because consent belongs to the
 *         settings screen that owns the whole ledger of them — a second place to grant it would be
 *         a second place to get the wording wrong about what is being agreed to.
 * Result: the composition. Input: none. Output: the rendered card.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 */
@Composable
private fun ConsentOffCard() {
    CfoCard {
        Text(text = stringResource(R.string.sms_consent_off_title), style = MaterialTheme.typography.titleMedium)
        Text(text = stringResource(R.string.sms_consent_off_body))
    }
}

/**
 * Opted in, but Android has not been asked yet (issue 3.9; ADR-0013).
 * Why:    the explanation comes before the system dialog, not after it. Android's own dialog says
 *         only "Allow app to send and view SMS messages?", which is both broader than what this app
 *         does and silent about why — a user shown that with no context is being asked to guess.
 * Result: the composition. Input: [onGrant]. Output: the rendered card.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 */
@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    CfoCard {
        Text(text = stringResource(R.string.sms_permission_title), style = MaterialTheme.typography.titleMedium)
        Text(text = stringResource(R.string.sms_permission_body))
        CfoButton(text = stringResource(R.string.sms_permission_grant), onClick = onGrant)
    }
}

/**
 * Everything is granted and nothing is waiting (issue 3.9).
 * Why:    distinguishes "we looked and found nothing new" from "we have not looked", because those
 *         feel identical on screen and mean very different things to someone wondering whether the
 *         feature works at all.
 * Result: the composition. Input: [uiState], [onEvent]. Output: the rendered card.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 */
@Composable
private fun EmptyCard(
    uiState: SmsDraftsUiState,
    onEvent: (SmsDraftsEvent) -> Unit,
) {
    CfoCard {
        Text(text = stringResource(R.string.sms_empty_title), style = MaterialTheme.typography.titleMedium)
        Text(
            text =
                if (uiState.lastScanFound == null) {
                    stringResource(R.string.sms_empty_never_scanned)
                } else {
                    stringResource(R.string.sms_empty_after_scan)
                },
        )
        if (uiState.isScanning) {
            CircularProgressIndicator(
                modifier = Modifier.semantics { contentDescription = SCANNING_DESCRIPTION },
            )
        } else {
            CfoButton(text = stringResource(R.string.sms_scan), onClick = { onEvent(SmsDraftsEvent.Scan) })
        }
    }
}

/**
 * Which account the alerts are about (issue 3.9).
 * Why:    a bank alert quotes four masked digits, which is a hint for a person and not an identifier
 *         this app can resolve — so the account is the user's to choose, once, for the whole list.
 *         Per-row would be more precise and would turn a two-tap review into a six-tap one for the
 *         overwhelmingly common case of a phone that gets alerts from one bank.
 * Result: the composition. Input: [uiState], [onEvent]. Output: the rendered picker.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountPicker(
    uiState: SmsDraftsUiState,
    onEvent: (SmsDraftsEvent) -> Unit,
) {
    Text(text = stringResource(R.string.sms_account), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd)) {
        uiState.accounts.forEach { account ->
            FilterChip(
                selected = account.id == uiState.selectedAccountId,
                onClick = { onEvent(SmsDraftsEvent.AccountSelected(account.id)) },
                label = { Text(account.name) },
            )
        }
    }
}

/**
 * One alert, as an offer (issue 3.9; P-02, P-07).
 * Why:    it shows the sender and the payee beside the amount, which together are how a person
 *         recognises their own transaction — P-02's "show the work" for a reading rather than a
 *         calculation. A draft the parser was unsure of carries the flag **as a word and a content
 *         description**, not as a colour: §21.6's accessibility line, and the same choice the
 *         receipt review screen makes.
 * Result: the composition. Input: [draft], [canAccept], [onEvent]. Output: the rendered card.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 */
@Composable
private fun DraftCard(
    draft: SmsDraft,
    edit: SmsDraftEdit,
    canAccept: Boolean,
    onEvent: (SmsDraftsEvent) -> Unit,
) {
    CfoCard {
        DraftDetails(draft)
        DraftFields(draft = draft, edit = edit, onEvent = onEvent)
        Row(horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd)) {
            CfoButton(
                text = stringResource(R.string.sms_accept),
                onClick = { onEvent(SmsDraftsEvent.Accept(draft.id)) },
                enabled = canAccept,
            )
            CfoSecondaryButton(
                text = stringResource(R.string.sms_dismiss),
                onClick = { onEvent(SmsDraftsEvent.Dismiss(draft.id)) },
            )
        }
    }
}

/**
 * The two fields the user may correct before accepting (issue 3.9; P-07).
 *
 * Why:    **the parser proposes and the user decides, and deciding includes correcting.** Without
 *         these, a draft the parser flagged could only be accepted as read or dismissed — and
 *         dismissing means re-typing the whole transaction by hand, which is the manual entry this
 *         feature exists to save. Pre-filled from what was read, so the common case is still two
 *         taps and nothing is typed at all.
 *
 *         **There is no field for the direction.** Editing can change how much moved, never which
 *         way: that came from the alert's own wording, and a screen that let it be flipped would let
 *         a misread spend be filed as income.
 * Result: the composition. Input: [draft]; [edit] — the current text; [onEvent]. Output: the fields.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 */
@Composable
private fun DraftFields(
    draft: SmsDraft,
    edit: SmsDraftEdit,
    onEvent: (SmsDraftsEvent) -> Unit,
) {
    OutlinedTextField(
        value = edit.amountText,
        onValueChange = { onEvent(SmsDraftsEvent.AmountEdited(draft.id, it)) },
        label = { Text(stringResource(R.string.sms_amount)) },
        singleLine = true,
        // Decimal, not Number: paise matter, and MoneyFormatter refuses anything finer (MNY-001).
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = edit.merchantText,
        onValueChange = { onEvent(SmsDraftsEvent.MerchantEdited(draft.id, it)) },
        label = { Text(stringResource(R.string.sms_merchant)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * What one alert says, without the buttons (issue 3.9; P-02).
 * Why:    split from [DraftCard] to stay inside detekt's forty-line ceiling (§21.6), and the seam is
 *         a real one: everything here is the reading being shown, and nothing here can change it.
 * Result: the composition. Input: [draft]. Output: the rendered detail lines.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 */
@Composable
private fun DraftDetails(draft: SmsDraft) {
    CfoAmountText(amount = draft.signedAmount())
    Text(
        text =
            if (draft.direction == SmsDirection.DEBIT) {
                stringResource(R.string.sms_direction_debit)
            } else {
                stringResource(R.string.sms_direction_credit)
            },
        style = MaterialTheme.typography.bodyMedium,
    )
    draft.counterparty?.let { Text(text = it, style = MaterialTheme.typography.bodyLarge) }
    Text(
        text = stringResource(R.string.sms_from_sender, draft.sender, draft.bookedOn.toString()),
        style = MaterialTheme.typography.bodySmall,
    )
    draft.accountTail?.let {
        Text(text = stringResource(R.string.sms_account_tail, it), style = MaterialTheme.typography.bodySmall)
    }
    if (draft.isLowConfidence) {
        Text(
            text = stringResource(R.string.sms_low_confidence),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { contentDescription = LOW_CONFIDENCE_DESCRIPTION },
        )
    }
}

/**
 * The content description a test finds the flag by.
 *
 * A constant rather than a string resource, for the reason the receipt screen's equivalent gives:
 * it is a test handle, not user-visible copy, so `CfoHardcodedUiString` is not the rule it breaks.
 */
private const val LOW_CONFIDENCE_DESCRIPTION = "sms-draft-low-confidence"

/** The content description a test finds the scan spinner by. See [LOW_CONFIDENCE_DESCRIPTION]. */
private const val SCANNING_DESCRIPTION = "sms-scanning"
