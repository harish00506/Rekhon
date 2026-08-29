package com.aicfo.feature.transactions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.aicfo.core.model.Transaction

/**
 * Scan a receipt and confirm what it says (issue 3.8; FR-OCR-001..006, ARC-004).
 *
 * Why:  **the screen captures its own photo rather than being handed one.** A route carrying a
 *       content URI would need the URI to survive process death, would need a permission grant that
 *       outlives the activity that received it, and would put the picker's result somewhere the
 *       review screen has to trust. Launching the picker here means there is no URI to pass at all:
 *       the bytes are read once, in the callback, and go straight to the ViewModel.
 *
 *       **Neither capture path needs a runtime permission**, which is the reason both are system
 *       intents rather than CameraX. The system camera app owns the camera and the photo picker owns
 *       the gallery, so this app declares no `CAMERA` and no storage permission at all — the
 *       strongest possible reading of P-01 for a feature whose input is a photograph of the user's
 *       life.
 * What: a stateful entry point that owns the two launchers, and a stateless body.
 * Result: FR-OCR-001's two capture paths, FR-OCR-003's editable fields, FR-OCR-004's flags and save
 *       gate, and FR-OCR-006's merge offer.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 *
 * Input:  [onDone] — where to go once saved or cancelled; [modifier]; [viewModel].
 * Output: the rendered screen.
 */
@Composable
fun ReceiptReviewScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReceiptReviewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Navigation is a side effect of state, not of the tap: a save that failed must not leave the
    // screen, and the ViewModel is the only thing that knows whether it succeeded.
    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) onDone()
    }

    // `remember` with no key, so the destination file is decided once per visit to the screen. A new
    // one per recomposition would leave the camera writing to a file nobody then reads.
    val photoUri = remember { context.newPhotoUri() }
    val camera =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
            if (taken) context.readBytes(photoUri)?.let { viewModel.onEvent(ReceiptReviewEvent.ImagePicked(it)) }
        }
    val gallery =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
            picked?.let { uri ->
                context.readBytes(uri)?.let { viewModel.onEvent(ReceiptReviewEvent.ImagePicked(it)) }
            }
        }

    ReceiptReviewContent(
        uiState = uiState,
        onEvent = viewModel::onEvent,
        onTakePhoto = { camera.launch(photoUri) },
        onChoosePhoto = {
            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onCancel = onDone,
        modifier = modifier,
    )
}

/**
 * The review screen's body, with no dependencies of its own.
 * Why:    stateless so a Compose test drives it without Hilt, a camera or a database — FR-OCR-004's
 *         two rules are asserted against this function.
 * Result: the rendered content.
 * Input:  [uiState]; [onEvent] — events up (ARC-004); [onTakePhoto] and [onChoosePhoto] — the two
 *         capture paths, passed in because launching an intent is not something a test can do;
 *         [onCancel]; [modifier].
 * Output: the composition.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 */
@Suppress("LongParameterList") // Five of the six are the screen's distinct exits — two capture
// paths FR-OCR-001 requires separately, events up, cancel — and folding any pair into one lambda
// with a discriminator would hide which one a test is driving.
@Composable
fun ReceiptReviewContent(
    uiState: ReceiptReviewUiState,
    onEvent: (ReceiptReviewEvent) -> Unit,
    onTakePhoto: () -> Unit,
    onChoosePhoto: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                // `imePadding` before `verticalScroll`, for the reason the add screen documents: the
                // app draws edge-to-edge, so without it Save sits behind the keypad.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(CfoDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd),
    ) {
        Text(
            text = stringResource(R.string.receipt_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        uiState.errorCode?.let { code ->
            AddTransactionErrorBanner(code = code, onDismiss = { onEvent(ReceiptReviewEvent.DismissError) })
        }

        when (uiState.stage) {
            ReceiptStage.CAPTURE -> CaptureChoices(onTakePhoto = onTakePhoto, onChoosePhoto = onChoosePhoto)
            ReceiptStage.SCANNING -> ScanningIndicator()
            ReceiptStage.REVIEW -> ReceiptFieldsSection(uiState = uiState, onEvent = onEvent)
        }

        CfoSecondaryButton(text = stringResource(R.string.receipt_cancel), onClick = onCancel)
    }
}

/**
 * FR-OCR-001's two capture paths, and the promise that goes with them.
 * Why:    the on-device line is shown **here**, before the photo is taken, rather than afterwards:
 *         FR-OCR-002's guarantee matters to a user at the moment they are deciding whether to point
 *         a camera at their shopping, not once the app already has it (P-02).
 * Result: the composition. Input: [onTakePhoto]; [onChoosePhoto]. Output: none.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 */
@Composable
private fun CaptureChoices(
    onTakePhoto: () -> Unit,
    onChoosePhoto: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
        Text(
            text = stringResource(R.string.receipt_capture_prompt),
            style = MaterialTheme.typography.bodyMedium,
        )
        CfoButton(text = stringResource(R.string.receipt_take_photo), onClick = onTakePhoto)
        CfoSecondaryButton(text = stringResource(R.string.receipt_choose_photo), onClick = onChoosePhoto)
        Text(
            text = stringResource(R.string.receipt_on_device),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The on-device pipeline running. Result: the composition. Input: none. Output: none. */
@Composable
private fun ScanningIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
    ) {
        CircularProgressIndicator()
        Text(text = stringResource(R.string.receipt_scanning), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The extracted fields, editable and flagged (FR-OCR-003, FR-OCR-004, FR-OCR-006).
 * Why:    extracted from [ReceiptReviewContent] at detekt's 40-line limit (§21.6), and the seam is
 *         the honest one: everything here renders only once a photo has been read.
 * Result: the composition. Input: [uiState]; [onEvent]. Output: none.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 */
@Composable
private fun ReceiptFieldsSection(
    uiState: ReceiptReviewUiState,
    onEvent: (ReceiptReviewEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd)) {
        EditableExtractedFields(uiState = uiState, onEvent = onEvent)
        ReceiptAccountPicker(uiState = uiState, onEvent = onEvent)
        if (uiState.hasDuplicates) {
            DuplicateOffer(duplicates = uiState.duplicates, onEvent = onEvent)
        } else {
            CfoButton(
                text = stringResource(R.string.receipt_save),
                onClick = { onEvent(ReceiptReviewEvent.Save) },
                // FR-OCR-004: "MUST prevent saving without an amount and date". The rule is
                // `canSave`'s, not this line's — the ViewModel refuses too, because a disabled
                // button is a rendering and an accessibility service can still deliver the tap.
                enabled = uiState.canSave,
            )
        }
        CfoSecondaryButton(
            text = stringResource(R.string.receipt_retake),
            onClick = { onEvent(ReceiptReviewEvent.Rescan) },
        )
    }
}

/**
 * The three fields FR-OCR-003 makes editable, plus the read-only GST line.
 * Why:    extracted from [ReceiptFieldsSection] at detekt's 40-line limit (§21.6). The seam is a
 *         real one: everything here is *what the parser read*, and everything left above it is what
 *         the user does about it.
 *
 *         **GST is read-only.** FR-OCR-003 calls tax best-effort and nothing in the app consumes it
 *         yet, so an editable field would be asking the user to correct a figure that changes
 *         nothing — a chore dressed as a feature.
 * Result: the composition. Input: [uiState]; [onEvent]. Output: none.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 */
@Composable
private fun EditableExtractedFields(
    uiState: ReceiptReviewUiState,
    onEvent: (ReceiptReviewEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd)) {
        ReceiptField(
            labelId = R.string.receipt_amount,
            value = uiState.amountText,
            flagged = uiState.amountFlagged,
            keyboardType = KeyboardType.Decimal,
            onValueChange = { onEvent(ReceiptReviewEvent.AmountChanged(it)) },
        )
        ReceiptField(
            labelId = R.string.receipt_date,
            value = uiState.dateText,
            flagged = uiState.dateFlagged,
            onValueChange = { onEvent(ReceiptReviewEvent.DateChanged(it)) },
        )
        ReceiptField(
            labelId = R.string.receipt_merchant,
            value = uiState.merchantText,
            flagged = uiState.merchantFlagged,
            onValueChange = { onEvent(ReceiptReviewEvent.MerchantChanged(it)) },
        )
        if (uiState.taxText.isNotEmpty()) {
            Text(
                text = stringResource(R.string.receipt_tax) + " " + uiState.taxText,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * One editable extracted field, with FR-OCR-004's low-confidence marker.
 * Why:    **the flag is a word, not a colour.** Colour alone is invisible to a screen reader and to
 *         a colour-blind user, and this marker is the only thing telling them which figure the app
 *         is unsure about — so it is the field's supporting text *and* its content description, and
 *         the error styling is a third signal rather than the only one.
 * Result: the composition. Input: [labelId]; [value]; [flagged]; [keyboardType]; [onValueChange].
 * Output: none.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 */
@Composable
private fun ReceiptField(
    labelId: Int,
    value: String,
    flagged: Boolean,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val label = stringResource(labelId)
    val warning = stringResource(R.string.receipt_low_confidence)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = flagged,
        supportingText = if (flagged) ({ Text(warning) }) else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { if (flagged) contentDescription = "$label. $warning" },
    )
}

/**
 * Which account the receipt's money left.
 * Why:    chips rather than a dropdown, matching the add screen — and hidden entirely when there is
 *         one account, because a picker with one option is a tap that decides nothing.
 * Result: the composition, or nothing. Input: [uiState]; [onEvent]. Output: none.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReceiptAccountPicker(
    uiState: ReceiptReviewUiState,
    onEvent: (ReceiptReviewEvent) -> Unit,
) {
    if (uiState.accounts.size <= 1) return
    Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs)) {
        Text(text = stringResource(R.string.receipt_account), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs)) {
            uiState.accounts.forEach { account ->
                FilterChip(
                    selected = account.id == uiState.selectedAccountId,
                    onClick = { onEvent(ReceiptReviewEvent.AccountSelected(account.id)) },
                    label = { Text(account.name) },
                )
            }
        }
    }
}

/**
 * FR-OCR-006's offer: merge, or save a second transaction anyway.
 * Why:    **"save anyway" is not a formality.** The guard matches on amount within 1% and date
 *         within a day, which two identical coffees on one afternoon satisfy exactly — so the app
 *         says what it noticed and the user decides which it was (P-07). Refusing to save would be
 *         the app overruling someone about their own money.
 * Result: the composition. Input: [duplicates]; [onEvent]. Output: none.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 */
@Composable
private fun DuplicateOffer(
    duplicates: List<Transaction>,
    onEvent: (ReceiptReviewEvent) -> Unit,
) {
    CfoCard {
        Column(
            modifier = Modifier.padding(CfoDimens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
        ) {
            Text(
                text = stringResource(R.string.receipt_duplicate_title),
                style = MaterialTheme.typography.titleSmall,
            )
            duplicates.forEach { candidate ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
                ) {
                    CfoAmountText(amount = candidate.amount)
                    Text(
                        text = TransactionLabels.dayHeader(candidate.bookedOn),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                CfoButton(
                    text = stringResource(R.string.receipt_duplicate_merge),
                    onClick = { onEvent(ReceiptReviewEvent.MergeInto(candidate.id)) },
                )
            }
            CfoSecondaryButton(
                text = stringResource(R.string.receipt_duplicate_save_anyway),
                onClick = { onEvent(ReceiptReviewEvent.SaveAnyway) },
            )
        }
    }
}
