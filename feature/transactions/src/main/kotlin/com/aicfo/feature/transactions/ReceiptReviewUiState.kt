package com.aicfo.feature.transactions

import com.aicfo.core.model.Account
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.core.model.Transaction
import java.time.LocalDate

/**
 * Everything the receipt review screen renders (issue 3.8; FR-OCR-003, FR-OCR-004, ARC-004).
 *
 * Why:  FR-OCR-004 is a MUST with two clauses, and both live here rather than in the composable so
 *       both are assertable without rendering anything: **[canSave] is "prevent saving without an
 *       amount and date"**, and the three `…Flagged` booleans are "low-confidence fields are
 *       visually flagged". A screen that enforced either rule in its own layout would be a rule
 *       nobody could test and a reviewer could not find.
 * What: one immutable value per screen, as ARC-004 requires.
 * Result: the state the ViewModel exposes as a `StateFlow`.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 *
 * **The extracted values arrive as *text*, deliberately.** FR-OCR-003 says every field is "editable
 * before save", so each one is what the user is typing into rather than a parsed value the app is
 * holding — and the parse happens once, at the bottom of this file, through `MoneyFormatter` (MNY-001).
 *
 * Input:  [stage] — which of the screen's three faces to show; [accounts] — what the money can be
 *         spent from; [selectedAccountId]; [amountText] / [dateText] / [merchantText] / [taxText] —
 *         the editable fields, pre-filled from the parser; the three `…Flagged` flags —
 *         FR-OCR-004's markers, set when the parser's confidence fell below the rulebook floor;
 *         [duplicates] — FR-OCR-006's candidates, empty in the ordinary case; [isSaving];
 *         [isSaved] — the screen leaves on this; [errorCode] — an `AppError.code`, never a message.
 * Output: an immutable value.
 */
data class ReceiptReviewUiState(
    val stage: ReceiptStage = ReceiptStage.CAPTURE,
    val accounts: List<Account> = emptyList(),
    val selectedAccountId: String? = null,
    val amountText: String = "",
    val amountFlagged: Boolean = false,
    val dateText: String = "",
    val dateFlagged: Boolean = false,
    val merchantText: String = "",
    val merchantFlagged: Boolean = false,
    val taxText: String = "",
    val duplicates: List<Transaction> = emptyList(),
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val errorCode: String? = null,
) {
    /**
     * The typed amount, or `null` when it is not an exact paise figure.
     *
     * `MoneyFormatter.parse` refuses rather than rounds (MNY-001), which is what makes [canSave]
     * a statement about money rather than about whether the field is non-empty.
     */
    val amount: Money? get() = MoneyFormatter.parse(amountText)?.takeIf { it != Money.ZERO }

    /** The typed date, or `null` when it is not an ISO `yyyy-MM-dd` day (TIM-002). */
    val bookedOn: LocalDate? get() = runCatching { LocalDate.parse(dateText) }.getOrNull()

    /**
     * FR-OCR-004: *"prevent saving without an amount and date"*.
     *
     * **[isSaved] is in here for the reason `AddTransactionUiState.canSave` gives**: the write
     * completes fast enough that a double tap's second event can arrive after `isSaving` has gone
     * false again while the screen is still leaving — and without this, one tap too many books the
     * spend twice.
     */
    val canSave: Boolean
        get() = amount != null && bookedOn != null && selectedAccountId != null && !isSaving && !isSaved

    /** FR-OCR-006: the screen offers a merge instead of a save while this holds. */
    val hasDuplicates: Boolean get() = stage == ReceiptStage.REVIEW && duplicates.isNotEmpty()
}

/**
 * Which of the review screen's three faces is showing (issue 3.8).
 *
 * Why:    an enum rather than a pair of booleans, because the three are mutually exclusive and two
 *         booleans admit a fourth state that means nothing — "scanning and also capturing".
 * Result: read by the screen to choose what to render.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 */
enum class ReceiptStage {
    /** No photo yet: the camera and gallery buttons FR-OCR-001 requires. */
    CAPTURE,

    /** The recogniser and parser are running, on-device (FR-OCR-002). */
    SCANNING,

    /** The extracted fields, editable, with their confidence flags (FR-OCR-003, FR-OCR-004). */
    REVIEW,
}

/**
 * Something the user did on the receipt review screen (ARC-004).
 *
 * Why:    a sealed interface, so the ViewModel's `when` is exhaustive and no interaction can be
 *         silently unhandled.
 * Result: the argument to `ReceiptReviewViewModel.onEvent`.
 * Changelog: 2026-08-06 — Created for issue 3.8.
 */
sealed interface ReceiptReviewEvent {
    /**
     * A photo arrived from the camera or the picker (FR-OCR-001).
     *
     * A plain `class` rather than a `data class`: a `data class` around a [ByteArray] gets an
     * `equals` that compares references, which is a trap rather than a convenience — and nothing
     * compares two of these.
     */
    class ImagePicked(val bytes: ByteArray) : ReceiptReviewEvent

    /** The user wants to start again with a different photo. */
    data object Rescan : ReceiptReviewEvent

    /** FR-OCR-003: every field is editable before save. */
    data class AmountChanged(val value: String) : ReceiptReviewEvent

    /** FR-OCR-003. */
    data class DateChanged(val value: String) : ReceiptReviewEvent

    /** FR-OCR-003. */
    data class MerchantChanged(val value: String) : ReceiptReviewEvent

    /** Which account the money left. */
    data class AccountSelected(val id: String) : ReceiptReviewEvent

    /** Save a new transaction with the receipt attached (FR-OCR-005). */
    data object Save : ReceiptReviewEvent

    /** FR-OCR-006: attach the receipt to a transaction that already exists. */
    data class MergeInto(val transactionId: String) : ReceiptReviewEvent

    /** FR-OCR-006's other branch: the guard was wrong, save a second transaction anyway. */
    data object SaveAnyway : ReceiptReviewEvent

    /** Clear a failure so the user can try again. */
    data object DismissError : ReceiptReviewEvent
}
