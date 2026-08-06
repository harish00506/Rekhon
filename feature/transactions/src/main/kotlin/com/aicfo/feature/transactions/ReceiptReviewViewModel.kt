package com.aicfo.feature.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.toAppError
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.data.repository.AccountRepository
import com.aicfo.data.repository.ReceiptRepository
import com.aicfo.data.repository.ReceiptScan
import com.aicfo.data.repository.TransactionDraft
import com.aicfo.domain.engines.receipt.ReceiptRules
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Holds the receipt review screen's state (issue 3.8; FR-OCR-003..006, ARC-003, ARC-004).
 *
 * Why:  this is where the parser stops proposing and the user starts deciding (P-07). Three rules
 *       live here rather than in the composable, so each is assertable without rendering anything:
 *       **the extracted values are pre-filled but never final**, **a field the parser was unsure of
 *       is flagged rather than silently accepted** (FR-OCR-004), and **a possible duplicate is
 *       offered as a merge rather than saved over** (FR-OCR-006).
 * What: exposes [uiState] and handles [ReceiptReviewEvent]s.
 * Result: a scanned receipt becomes a transaction the user confirmed, with its image attached.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 *
 * **The image bytes are held in this ViewModel and nowhere else** until the user saves. They are
 * never logged, never written to a temp file the app controls, and released when the screen goes
 * (P-01) — the one copy that persists is the encrypted blob the repository writes.
 *
 * Input:  [receipts] — the OCR pipeline; [accounts] — the picker's options, from the same store the
 *         add screen uses so "which accounts are pickable" has one definition.
 * Output: an observable screen state.
 */
@HiltViewModel
class ReceiptReviewViewModel
    @Inject
    constructor(
        private val receipts: ReceiptRepository,
        private val accounts: AccountRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ReceiptReviewUiState())

        /** The screen's state. Result: emits the current [ReceiptReviewUiState] and every update. */
        val uiState: StateFlow<ReceiptReviewUiState> = _uiState.asStateFlow()

        /**
         * The photo, kept until the user saves or leaves.
         *
         * Not in [ReceiptReviewUiState]: a `StateFlow` of a value holding a multi-megabyte array
         * would be compared and copied on every keystroke in the amount field, and a screenshot test
         * would have to construct one. The state carries what is *rendered*; this carries what is
         * *saved*.
         */
        private var imageBytes: ByteArray? = null

        init {
            observeAccounts()
        }

        /**
         * Keeps the account picker filled.
         * Why:    the same read the add screen uses, so a closed account is unpickable in both
         *         places (FR-ACC-007) without either screen knowing the rule.
         * Result: fills `accounts` and preselects the first, as `AddTransactionViewModel` does — the
         *         common case is one account and the picker should cost the user nothing.
         * Input:  none. Output: none (collects on `viewModelScope`).
         */
        private fun observeAccounts() {
            accounts.observeAccounts()
                .onEach { available ->
                    _uiState.update { state ->
                        state.copy(
                            accounts = available,
                            selectedAccountId =
                                state.selectedAccountId
                                    ?.takeIf { id -> available.any { it.id == id } }
                                    ?: available.firstOrNull()?.id,
                        )
                    }
                }
                // `toAppError` rather than the throwable's message: that message may name a file
                // path, a column or an amount, and P-01 bans all three from anything user-visible.
                .catch { failure -> _uiState.update { it.copy(errorCode = failure.toAppError().code) } }
                .launchIn(viewModelScope)
        }

        /**
         * Handles something the user did.
         * Why:    one entry point, so the sealed interface's exhaustiveness guarantees no
         *         interaction is silently unhandled.
         * Result: applies the event. Input: [event]. Output: none.
         */
        fun onEvent(event: ReceiptReviewEvent) {
            when (event) {
                is ReceiptReviewEvent.ImagePicked -> scan(event.bytes)
                ReceiptReviewEvent.Rescan -> rescan()
                is ReceiptReviewEvent.AmountChanged -> _uiState.update { it.copy(amountText = event.value) }
                is ReceiptReviewEvent.DateChanged -> _uiState.update { it.copy(dateText = event.value) }
                is ReceiptReviewEvent.MerchantChanged -> _uiState.update { it.copy(merchantText = event.value) }
                is ReceiptReviewEvent.AccountSelected -> _uiState.update { it.copy(selectedAccountId = event.id) }
                ReceiptReviewEvent.Save -> save()
                ReceiptReviewEvent.SaveAnyway -> save()
                is ReceiptReviewEvent.MergeInto -> merge(event.transactionId)
                ReceiptReviewEvent.DismissError -> _uiState.update { it.copy(errorCode = null) }
            }
        }

        /**
         * Runs the on-device pipeline over one photo (FR-OCR-002).
         * Why:    the bytes are kept **before** the scan rather than after it, so a photo that read
         *         as nothing can still be attached to the transaction the user then types by hand —
         *         which is exactly §18's "failures fall back to manual entry pre-filled with
         *         whatever was extracted".
         * Result: moves to [ReceiptStage.REVIEW] with the fields pre-filled and flagged, or leaves
         *         the screen on [ReceiptStage.CAPTURE] with an error when the bytes were not an image.
         * Input:  [bytes] — the photo. Output: none (launches on `viewModelScope`).
         */
        private fun scan(bytes: ByteArray) {
            imageBytes = bytes
            _uiState.update { it.copy(stage = ReceiptStage.SCANNING, errorCode = null) }
            viewModelScope.launch {
                when (val outcome = receipts.scan(bytes)) {
                    is Ok -> _uiState.update { it.withScan(outcome.value) }
                    is Err ->
                        _uiState.update {
                            it.copy(stage = ReceiptStage.CAPTURE, errorCode = outcome.error.code)
                        }
                }
            }
        }

        /**
         * Throws the photo away and asks for another.
         * Why:    the fields go with it. Keeping a previous receipt's amount pre-filled under a new
         *         photo is how a user saves last week's total against today's shop.
         * Result: back to [ReceiptStage.CAPTURE], with the account selection kept.
         * Input:  none. Output: none.
         */
        private fun rescan() {
            imageBytes = null
            _uiState.update {
                ReceiptReviewUiState(
                    accounts = it.accounts,
                    selectedAccountId = it.selectedAccountId,
                )
            }
        }

        /**
         * Writes the transaction and its receipt (FR-OCR-005).
         * Why:    the draft is rebuilt from the state rather than trusting the button's `enabled`
         *         flag — a disabled button is a rendering, and an accessibility service or a fast
         *         double tap can still deliver the event. [ReceiptReviewUiState.canSave] is the one
         *         place that decides not to write.
         * Result: sets `isSaved` so the screen leaves, or `errorCode` and stays. Never both.
         * Input:  none. Output: none (launches on `viewModelScope`).
         */
        private fun save() {
            val state = _uiState.value
            val draft = state.toDraftOrNull() ?: return
            _uiState.update { it.copy(isSaving = true, errorCode = null) }
            viewModelScope.launch { finish(receipts.save(draft, imageBytes)) }
        }

        /**
         * Attaches this receipt to a transaction that already exists (FR-OCR-006).
         * Why:    the requirement's alternative to a duplicate. The existing row is not edited: it is
         *         the money that actually moved, and a parser's reading is not better evidence.
         * Result: sets `isSaved`, or `errorCode`. Input: [transactionId]. Output: none.
         */
        private fun merge(transactionId: String) {
            val bytes = imageBytes ?: return
            _uiState.update { it.copy(isSaving = true, errorCode = null) }
            viewModelScope.launch { finish(receipts.mergeInto(transactionId, bytes)) }
        }

        /**
         * Applies the outcome of a write.
         * Why:    one place, because "did it save?" is the only thing the screen needs to know and
         *         both writes answer it identically.
         * Result: `isSaved` or `errorCode`, never both.
         * Input:  [outcome] — from either write. Output: none.
         */
        private fun finish(outcome: com.aicfo.core.common.Result<Any, com.aicfo.core.common.AppError>) {
            _uiState.update {
                when (outcome) {
                    is Ok -> it.copy(isSaving = false, isSaved = true)
                    is Err -> it.copy(isSaving = false, errorCode = outcome.error.code)
                }
            }
        }
    }

/**
 * Fills the review screen from what the parser read (issue 3.8; FR-OCR-003, FR-OCR-004).
 *
 * Why:    **every field is pre-filled and every field is flagged independently.** §18 says a failed
 *         read falls back to manual entry pre-filled with whatever *was* extracted, so a receipt
 *         whose date smudged still hands over its total — and the flag is per field because the
 *         parser's confidence is per field. A blank field is left blank rather than flagged: nothing
 *         was read, so there is nothing for the user to check.
 *
 *         Amounts are put into the field as **plain digits**, not through `MoneyFormatter.format`:
 *         a field reading `₹365.80` looks like the app typed a currency symbol on the user's behalf,
 *         and they then have to delete it to edit.
 * Result: the state the review screen renders.
 * Input:  the receiver; [scan] — the parser's reading plus any duplicate candidates.
 * Output: [ReceiptReviewUiState].
 * Changelog: 2026-08-06 — Created for issue 3.8.
 */
internal fun ReceiptReviewUiState.withScan(scan: ReceiptScan): ReceiptReviewUiState {
    val floor = ReceiptRules().lowConfidenceBps
    val fields = scan.fields
    return copy(
        stage = ReceiptStage.REVIEW,
        amountText = fields.total?.value?.let { MoneyFormatter.format(it).stripCurrencySymbol() }.orEmpty(),
        amountFlagged = fields.total?.let { it.confidenceBps < floor } ?: false,
        dateText = fields.date?.value.orEmpty(),
        dateFlagged = fields.date?.let { it.confidenceBps < floor } ?: false,
        merchantText = fields.merchant?.value.orEmpty(),
        merchantFlagged = fields.merchant?.let { it.confidenceBps < floor } ?: false,
        taxText = fields.tax?.value?.let { MoneyFormatter.format(it) }.orEmpty(),
        duplicates = scan.duplicates,
    )
}

/**
 * Turns the review screen's state into a draft, or refuses (issue 3.8; FR-OCR-004).
 * Why:    the one place that decides not to write, so the Save button's `enabled` flag and the
 *         double-tap guard cannot drift apart — the same argument `toDraftOrNull` makes on the add
 *         screen. **The amount becomes negative here**: a receipt is money the user spent, and the
 *         sign is the direction (MNY-001), so nothing below the UI layer sees a flag.
 * Result: the [TransactionDraft] to write, or `null` when the state is not one that should write.
 * Input:  the receiver. Output: `TransactionDraft?`.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 */
internal fun ReceiptReviewUiState.toDraftOrNull(): TransactionDraft? {
    if (!canSave) return null
    // Read into locals so the compiler can smart-cast them past the null checks below. `canSave`
    // has already established all three; this is the compiler being told, not a second check.
    val magnitude = amount
    val accountId = selectedAccountId
    val day = bookedOn
    return if (magnitude != null && accountId != null && day != null) {
        TransactionDraft(
            accountId = accountId,
            amount = Money.ZERO - magnitude,
            merchant = merchantText.takeIf { it.isNotBlank() },
            // FR-TXN-010: a receipt is always in the past, and the repository stamps the day.
            bookedOn = day,
            // FR-TXN-009 is the repository's to stamp — a screen that named its own provenance would
            // be making a claim rather than recording one.
        )
    } else {
        null
    }
}

/**
 * Strips the currency symbol and grouping from a formatted amount.
 * Why:    the same reason the split editor does it — a field the user is about to edit should look
 *         like something a person typed, not like output. `MoneyFormatter.parse` accepts either.
 * Result: bare digits and a decimal point. Input: the receiver. Output: [String].
 * Changelog: 2026-08-06 — Created for issue 3.8.
 */
private fun String.stripCurrencySymbol(): String = filter { it.isDigit() || it == '.' }
