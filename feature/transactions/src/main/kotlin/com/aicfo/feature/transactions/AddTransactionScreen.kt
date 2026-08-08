package com.aicfo.feature.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicfo.core.designsystem.component.CfoButton
import com.aicfo.core.designsystem.component.CfoCard
import com.aicfo.core.designsystem.component.CfoSecondaryButton
import com.aicfo.core.designsystem.theme.CfoDimens

/**
 * Capture one transaction (issue 3.1; FR-TXN-002, FR-TXN-001, ARC-004).
 *
 * Why:  FR-TXN-002 is a MUST with a number in it — reachable in one tap, completable in ≤ 3 for a
 *       common expense. **The layout is arranged around that budget, not around the fields.** The
 *       amount field takes focus on entry so the keypad is already up; the account is preselected by
 *       the ViewModel and its picker is hidden entirely when there is only one; the category row is
 *       hidden when the profile has no categories. What is left for the common expense is: type the
 *       amount, tap Save. One tap on the FAB to get here makes two.
 * What: a stateful entry point and a stateless body.
 * Result: a transaction is captured in two taps, and the third is spare for a category or income.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 *
 * Input:  [onDone] — where to go once saved or cancelled; [onScanReceipt] — opens issue 3.8's
 *         receipt scanner, a *different destination* rather than a mode of this screen, because
 *         capture has its own three-stage flow and its own back behaviour; [onReviewSms] — issue
 *         3.9's bank-alert review, a separate destination for the same reason; [modifier]; [viewModel].
 * Output: the rendered screen.
 */
@Composable
fun AddTransactionScreen(
    onDone: () -> Unit,
    onScanReceipt: () -> Unit,
    onReviewSms: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddTransactionViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Navigation is a side effect of state, not of the tap: a save that failed must not leave the
    // screen, and the ViewModel is the only thing that knows whether it succeeded.
    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) onDone()
    }

    AddTransactionContent(
        uiState = uiState,
        onEvent = viewModel::onEvent,
        onCancel = onDone,
        captureRoutes = CaptureRoutes(onScanReceipt = onScanReceipt, onReviewSms = onReviewSms),
        modifier = modifier,
    )
}

/**
 * Where the add screen's two automated alternatives go (issues 3.8, 3.9).
 *
 * Why:    one value rather than two more lambdas, because the second one took
 *         [AddTransactionContent] past detekt's six-parameter ceiling (§21.6) — and the grouping is
 *         real: both are "leave this form for a capture path that fills it in for you", both are
 *         separate destinations, and a screen adding a third would add it here rather than widening
 *         the signature again. Defaulted to no-ops so the existing call sites and previews are
 *         unchanged.
 * Result: the argument to [AddTransactionContent].
 * Changelog: 2026-08-07 — Created for issue 3.9.
 *
 * Input:  [onScanReceipt] — issue 3.8's scanner; [onReviewSms] — issue 3.9's bank-alert review.
 * Output: an immutable value.
 */
data class CaptureRoutes(
    val onScanReceipt: () -> Unit = {},
    val onReviewSms: () -> Unit = {},
)

/**
 * The add screen's body, with no dependencies of its own.
 * Why:    stateless so the tap-count test can drive it without Hilt, a database or a navigator —
 *         FR-TXN-002's budget is asserted against this function.
 * Result: the rendered content.
 * Input:  [uiState]; [onEvent] — events up (ARC-004); [onCancel]; [onScanReceipt] — issue 3.8's
 *         [captureRoutes] — where the two automated alternatives go, defaulted to no-ops so the
 *         thirty existing call sites and previews are unchanged; [modifier].
 * Output: the composition.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 *            2026-08-06 — Issue 3.8: the "Scan a receipt" action.
 *            2026-08-07 — Issue 3.9: the "From your bank messages" action.
 */
@Composable
fun AddTransactionContent(
    uiState: AddTransactionUiState,
    onEvent: (AddTransactionEvent) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    captureRoutes: CaptureRoutes = CaptureRoutes(),
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                // **`imePadding` before `verticalScroll`, and it is load-bearing.** The app draws
                // edge-to-edge, so the keypad overlays the window rather than resizing it: without
                // this the Column still believes it has the full screen, finds nothing to scroll,
                // and Save sits *behind* the keypad with no way to reach it. Found on the emulator
                // on a profile with four accounts and twelve categories — the short form a new user
                // sees hid the bug completely.
                .imePadding()
                // Scrollable because at 200% font the form is taller than any phone, and the keypad
                // takes half of what is left (§21.6's accessibility line).
                .verticalScroll(rememberScrollState())
                .padding(CfoDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd),
    ) {
        Text(
            text = stringResource(R.string.add_txn_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        uiState.errorCode?.let { code ->
            AddTransactionErrorBanner(code = code, onDismiss = { onEvent(AddTransactionEvent.DismissError) })
        }

        AmountField(uiState = uiState, onEvent = onEvent)
        DirectionToggle(uiState = uiState, onEvent = onEvent)

        if (uiState.hasNoAccount) {
            Text(
                text = stringResource(R.string.add_txn_no_account),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        AccountPicker(uiState = uiState, onEvent = onEvent)
        DestinationPicker(uiState = uiState, onEvent = onEvent)
        CategoryPicker(uiState = uiState, onEvent = onEvent)
        // Issue 3.4: below the pickers rather than above them, so the ≤ 3-tap path never scrolls
        // past it (FR-TXN-002), and pre-filled with today so it costs that path nothing.
        ScheduleField(uiState = uiState, onEvent = onEvent)
        SplitToggle(uiState = uiState, onEvent = onEvent)
        SplitEditor(uiState = uiState, onEvent = onEvent)
        NoteFieldAndActions(uiState = uiState, onEvent = onEvent, onCancel = onCancel)
        CaptureAlternatives(captureRoutes)
    }
}

/**
 * The two ways to have the form filled in for you (issues 3.8, 3.9).
 * Why:    **last on purpose, and together.** FR-TXN-002 budgets the typed path at three taps, and an
 *         action above the amount field would put a choice in front of the user before the thing
 *         they came here to do. Both of these are the alternative, not the default. Extracted so
 *         [AddTransactionContent] stays inside detekt's forty-line ceiling (§21.6).
 * Result: the composition. Input: [routes]. Output: the rendered actions.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 */
@Composable
private fun CaptureAlternatives(routes: CaptureRoutes) {
    CfoSecondaryButton(text = stringResource(R.string.add_txn_scan), onClick = routes.onScanReceipt)
    CfoSecondaryButton(text = stringResource(R.string.add_txn_review_sms), onClick = routes.onReviewSms)
}

/**
 * The optional note and the two buttons.
 * Why:    split from [AddTransactionContent] to stay within detekt's 40-line function limit (§21.6).
 *         The seam is a real one: everything above it is a decision that affects what is saved, and
 *         these two are the free-text afterthought and the commit.
 * Result: the composition. Input: [uiState], [onEvent], [onCancel]. Output: none.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 */
@Composable
private fun NoteFieldAndActions(
    uiState: AddTransactionUiState,
    onEvent: (AddTransactionEvent) -> Unit,
    onCancel: () -> Unit,
) {
    // FR-TXN-001's payee/merchant. **Hidden for a transfer** — moving money between your own
    // accounts has no payee, which is why `TransferDraft` has no field to carry one.
    if (uiState.hasMerchant) {
        OutlinedTextField(
            value = uiState.merchant,
            onValueChange = { onEvent(AddTransactionEvent.MerchantChanged(it)) },
            label = { Text(stringResource(R.string.add_txn_merchant)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    OutlinedTextField(
        value = uiState.note,
        onValueChange = { onEvent(AddTransactionEvent.NoteChanged(it)) },
        label = { Text(stringResource(R.string.add_txn_note)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
        CfoButton(
            text = stringResource(R.string.add_txn_save),
            onClick = { onEvent(AddTransactionEvent.Save) },
            enabled = uiState.canSave,
        )
        CfoSecondaryButton(text = stringResource(R.string.add_txn_cancel), onClick = onCancel)
    }
}

/**
 * The amount field, focused on entry.
 * Why:    **the autofocus is load-bearing for FR-TXN-002**, not a nicety — without it the user's
 *         first tap goes on the field and the budget is three taps before they have chosen anything.
 *         `KeyboardType.Decimal` rather than `Number` because paise are entered after a decimal
 *         point; the sign is the toggle's job, so no minus key is needed here.
 * Result: the composition. Input: [uiState], [onEvent]. Output: none.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 */
@Composable
private fun AmountField(
    uiState: AddTransactionUiState,
    onEvent: (AddTransactionEvent) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    OutlinedTextField(
        value = uiState.amountText,
        onValueChange = { onEvent(AddTransactionEvent.AmountChanged(it)) },
        label = { Text(stringResource(R.string.add_txn_amount)) },
        placeholder = { Text(stringResource(R.string.add_txn_amount_hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
    )
}

/**
 * Expense, income or transfer, as a three-option single choice.
 * Why:    a toggle rather than asking the user to type a minus sign. For the two one-account
 *         directions this is where "250, expense" becomes −25000 paise; for a transfer it is where
 *         the form changes shape, because a transfer needs a second account and has no category.
 *         Expense stays the default and stays first, so FR-TXN-002's common path is untouched.
 *
 *         **Transfer is disabled with one account** (issue 3.2): there is nowhere to move money to,
 *         and a control that accepts a tap and then refuses to save explains nothing. `FlowRow`
 *         because three labels do not fit on one line at 200% font.
 *
 *         `selectable` on the **Row**, not the `RadioButton`: a 20dp control beside inert text is
 *         under the 48dp minimum and is announced to a screen reader as two unrelated things.
 * Result: the composition. Input: [uiState], [onEvent]. Output: none.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 *            2026-08-02 — Issue 3.2: a third option, and the boolean became `TransactionDirection`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DirectionToggle(
    uiState: AddTransactionUiState,
    onEvent: (AddTransactionEvent) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceMd)) {
        DIRECTION_LABELS.forEach { (direction, label) ->
            val enabled = direction != TransactionDirection.TRANSFER || uiState.canTransfer
            Row(
                modifier =
                    Modifier
                        .selectable(
                            selected = uiState.direction == direction,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { onEvent(AddTransactionEvent.DirectionChanged(direction)) },
                        )
                        .padding(vertical = CfoDimens.spaceXs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
            ) {
                RadioButton(selected = uiState.direction == direction, onClick = null, enabled = enabled)
                Text(text = stringResource(label), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * The three directions in the order the toggle offers them.
 *
 * Expense first because it is the default and the common case; a list rather than three copies of
 * the same `Row` so adding a direction cannot leave one of them styled differently.
 */
private val DIRECTION_LABELS =
    listOf(
        TransactionDirection.EXPENSE to R.string.add_txn_expense,
        TransactionDirection.INCOME to R.string.add_txn_income,
        TransactionDirection.TRANSFER to R.string.add_txn_transfer,
    )

/**
 * Where a transfer's money is going (issue 3.2; FR-TXN-003).
 * Why:    shown **only for a transfer**, because it is the one direction with a second account. The
 *         source is absent from the options — the repository refuses a transfer to the account the
 *         money is leaving, and offering it here would invite the mistake rather than prevent it.
 *
 *         Nothing is preselected. Unlike the source account, there is no defensible default: the
 *         whole point of the choice is which of the user's other accounts the money lands in, and
 *         guessing would be as likely to be wrong as right.
 * Result: the composition, or nothing. Input: [uiState], [onEvent]. Output: none.
 * Changelog: 2026-08-02 — Created for issue 3.2.
 */
@Composable
private fun DestinationPicker(
    uiState: AddTransactionUiState,
    onEvent: (AddTransactionEvent) -> Unit,
) {
    if (!uiState.isTransfer) return

    Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs)) {
        Text(
            text = stringResource(R.string.add_txn_to_account),
            style = MaterialTheme.typography.titleSmall,
        )
        uiState.destinationChoices.forEach { account ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = account.id == uiState.toAccountId,
                            role = Role.RadioButton,
                            onClick = { onEvent(AddTransactionEvent.DestinationSelected(account.id)) },
                        )
                        .padding(vertical = CfoDimens.spaceXs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
            ) {
                RadioButton(selected = account.id == uiState.toAccountId, onClick = null)
                Text(text = account.name, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * Which account the money moved in.
 * Why:    **hidden entirely when there is one account**, which is the state most users are in after
 *         FR-ONB-001's fourth step. A picker with a single option costs a tap of FR-TXN-002's budget
 *         and answers a question the user has no choice about. The ViewModel has preselected the
 *         first account either way, so hiding this changes nothing about what is saved.
 *         **A transfer always shows it**, even with one account visible in the list, because "which
 *         account is this leaving?" is half the question a transfer asks and an unlabelled implicit
 *         source would make the destination picker meaningless (issue 3.2).
 * Result: the composition, or nothing. Input: [uiState], [onEvent]. Output: none.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 *            2026-08-02 — Issue 3.2: always shown for a transfer, and labelled "From" there.
 */
@Composable
private fun AccountPicker(
    uiState: AddTransactionUiState,
    onEvent: (AddTransactionEvent) -> Unit,
) {
    if (uiState.accounts.size <= 1 && !uiState.isTransfer) return

    Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs)) {
        Text(
            text =
                stringResource(
                    if (uiState.isTransfer) R.string.add_txn_from_account else R.string.add_txn_account,
                ),
            style = MaterialTheme.typography.titleSmall,
        )
        uiState.accounts.forEach { account ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = account.id == uiState.selectedAccountId,
                            role = Role.RadioButton,
                            onClick = { onEvent(AddTransactionEvent.AccountSelected(account.id)) },
                        )
                        .padding(vertical = CfoDimens.spaceXs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
            ) {
                RadioButton(selected = account.id == uiState.selectedAccountId, onClick = null)
                Text(text = account.name, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * FR-TXN-002's middle step — the category chips.
 * Why:    **hidden when the profile has no categories**, which is every real profile until issue 4.1
 *         seeds a taxonomy; only demo mode has any today. An empty picker reads as a broken one, and
 *         a control with no options must not sit in the tap budget. Tapping the selected chip again
 *         clears it, because a category chosen by mistake would otherwise be unclearable without
 *         leaving the screen.
 *
 *         Chips rather than a list: this is one tap of the three, so the options have to be visible
 *         and reachable without opening anything. `FlowRow` wraps them at any font scale.
 * Result: the composition, or nothing. Input: [uiState], [onEvent]. Output: none.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryPicker(
    uiState: AddTransactionUiState,
    onEvent: (AddTransactionEvent) -> Unit,
) {
    if (!uiState.hasCategories) return

    Column(verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceXs)) {
        Text(text = stringResource(R.string.add_txn_category), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm)) {
            uiState.categories.forEach { category ->
                val selected = category.id == uiState.selectedCategoryId
                FilterChip(
                    selected = selected,
                    onClick = {
                        onEvent(AddTransactionEvent.CategorySelected(if (selected) null else category.id))
                    },
                    label = { Text(category.name) },
                )
            }
        }
    }
}

/**
 * The add screen's error banner.
 * Why:    `liveRegion` so a screen reader announces a failure the user did not go looking for. The
 *         banner shows a message looked up from the code (§21.6) — never the code, and never
 *         anything the throwable said (P-01).
 * Result: the composition. Input: [code] — an `AppError.code`; [onDismiss]. Output: none.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 *            2026-08-06 — Issue 3.8: made `internal` so the receipt review screen renders failures
 *            identically. One banner rather than two: the two screens surface the same
 *            `AppError.code`s and a second copy would drift in wording and in accessibility.
 */
@Composable
internal fun AddTransactionErrorBanner(
    code: String,
    onDismiss: () -> Unit,
) {
    CfoCard {
        Column(
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(CfoDimens.spaceSm),
        ) {
            Text(
                text = stringResource(TransactionLabels.errorMessage(code)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            CfoSecondaryButton(text = stringResource(R.string.add_txn_error_dismiss), onClick = onDismiss)
        }
    }
}
