package com.aicfo.feature.transactions

import com.aicfo.core.model.Account
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.data.repository.SmsAccess
import com.aicfo.data.repository.SmsDraft

/**
 * Everything the SMS review screen renders (issue 3.9; §18, §23, P-01, P-07, ARC-004).
 *
 * Why:  [stage] is derived here rather than in the composable, and it is the whole reason this file
 *       exists. The screen has four genuinely different things to say — you have not opted in, you
 *       have opted in but Android has not been asked, there is nothing to review, here is what
 *       arrived — and the rule for choosing between them is a privacy rule, not a layout one. A
 *       composable that worked it out from three booleans would be a rule nobody could test and a
 *       reviewer could not find, and getting it wrong means asking for a dangerous permission the
 *       user never agreed to.
 * What: one immutable value per screen, as ARC-004 requires.
 * Result: the state the ViewModel exposes as a `StateFlow`.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 *
 * Input:  [access] — the two independent permissions; [drafts] — what is waiting, newest first;
 *         [accounts] — what the money moved through, for the picker; [selectedAccountId];
 *         [isScanning]; [lastScanFound] — how many new drafts the last scan recorded, `null` before
 *         any scan, so the screen can say "nothing new" without claiming it never looked;
 *         [errorCode] — an `AppError.code`, never a message (P-01); [edits] — corrections the user
 *         has typed, keyed by draft id, absent until they touch a field.
 * Output: an immutable value.
 */
data class SmsDraftsUiState(
    val access: SmsAccess = SmsAccess(),
    val drafts: List<SmsDraft> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val selectedAccountId: String? = null,
    val isScanning: Boolean = false,
    val lastScanFound: Int? = null,
    val edits: Map<String, SmsDraftEdit> = emptyMap(),
    val errorCode: String? = null,
) {
    /**
     * Which of the screen's four faces to show.
     *
     * **The consent is checked before the permission, and the order matters.** Asking Android for
     * `READ_SMS` from a user who has not turned the feature on would be requesting a dangerous
     * permission for something they never agreed to — the exact inversion P-01 exists to prevent.
     * So the in-app switch always comes first, and the system dialog is only ever reachable behind it.
     */
    val stage: SmsDraftsStage
        get() =
            when {
                !access.consentGranted -> SmsDraftsStage.CONSENT_OFF
                !access.permissionGranted -> SmsDraftsStage.PERMISSION_NEEDED
                drafts.isEmpty() -> SmsDraftsStage.EMPTY
                else -> SmsDraftsStage.REVIEW
            }

    /**
     * Whether a draft can be turned into a transaction.
     *
     * An account is required because a bank alert does not name one this app knows — it quotes four
     * masked digits, which is a hint for the user and not an identifier. Without a chosen account
     * there is nowhere for the money to have moved from.
     */
    val canAccept: Boolean get() = selectedAccountId != null && !isScanning

    /**
     * What the fields on one draft's row currently hold.
     * Why:    the parsed values are a *starting point*, not the answer (P-07) — so the row renders
     *         whatever the user has typed, falling back to what was read. Absent until they touch a
     *         field, so an untouched draft cannot drift from what the parser actually concluded.
     *
     *         The amount round-trips through `MoneyFormatter`: `format` produces `₹1,250.00` and
     *         `parse` reads exactly that back, so pre-filling costs no precision (MNY-001).
     * Result: the text to render. Input: [draft]. Output: [SmsDraftEdit].
     */
    fun editFor(draft: SmsDraft): SmsDraftEdit =
        edits[draft.id] ?: SmsDraftEdit(
            amountText = MoneyFormatter.format(draft.amount),
            merchantText = draft.counterparty.orEmpty(),
        )

    /**
     * The amount that would be saved for one draft, or `null` when the field is not a usable figure.
     * Why:    `MoneyFormatter.parse` **refuses rather than rounds** (MNY-001), so this is what makes
     *         [canAcceptDraft] a statement about money rather than about whether a field is non-empty.
     *         Zero is refused too: a transaction that moved nothing is not one worth recording.
     * Result: the amount as a positive magnitude. Input: [draft]. Output: `Money?`.
     */
    fun editedAmount(draft: SmsDraft): Money? =
        MoneyFormatter.parse(editFor(draft).amountText)?.takeIf { it > Money.ZERO }

    /**
     * Whether this draft can become a transaction.
     * Why:    per draft rather than per screen, because the user can leave one row's amount
     *         unreadable while accepting another — and a single screen-wide flag would disable both.
     * Result: `true` when an account is chosen and the typed amount is a real figure.
     * Input:  [draft]. Output: `Boolean`.
     */
    fun canAcceptDraft(draft: SmsDraft): Boolean = canAccept && editedAmount(draft) != null
}

/**
 * One draft's editable fields (issue 3.9; P-07).
 *
 * Why:    the parser proposes and the user decides, and "decides" has to include *correcting*. A
 *         flagged draft the user could only accept-as-read or dismiss would push them into
 *         re-typing the whole transaction by hand — which is the manual entry this feature exists
 *         to save. **The amount is a magnitude**: the direction still comes from the alert, so
 *         editing can change how much moved but never which way.
 * Result: the value in [SmsDraftsUiState.edits].
 * Changelog: 2026-08-07 — Created for issue 3.9.
 *
 * Input:  [amountText] — what the user is typing, parsed through `MoneyFormatter` (MNY-001);
 *         [merchantText] — the payee, blank when the alert named none and they have not supplied one.
 * Output: an immutable value.
 */
data class SmsDraftEdit(
    val amountText: String,
    val merchantText: String,
)

/**
 * Which of the SMS review screen's four faces is showing (issue 3.9).
 *
 * Why:    an enum rather than a set of booleans, because they are mutually exclusive and booleans
 *         admit combinations that mean nothing — "not opted in and also reviewing drafts".
 * Result: read by the screen to choose what to render, and by its tests to assert the privacy order.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 */
enum class SmsDraftsStage {
    /** The user has not turned SMS parsing on. Nothing has been read, and nothing will be. */
    CONSENT_OFF,

    /** Opted in, but Android has not granted `READ_SMS` yet — or it was revoked from Settings. */
    PERMISSION_NEEDED,

    /** Everything is granted and there is nothing waiting, which is the ordinary steady state. */
    EMPTY,

    /** Drafts are waiting for a decision (P-07). */
    REVIEW,
}

/**
 * Something the user did on the SMS review screen (ARC-004).
 *
 * Why:    a sealed interface, so the ViewModel's `when` is exhaustive and no interaction can be
 *         silently unhandled.
 * Result: the argument to `SmsDraftsViewModel.onEvent`.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 */
sealed interface SmsDraftsEvent {
    /**
     * The OS permission dialog returned.
     *
     * Carries the outcome rather than being a bare "refresh", because the ViewModel cannot observe
     * a permission change — Android has no callback for one, so the screen has to say what happened.
     */
    data class PermissionResult(val granted: Boolean) : SmsDraftsEvent

    /** Read the inbox from the stored cursor. */
    data object Scan : SmsDraftsEvent

    /** Which account the alerts are about. */
    data class AccountSelected(val id: String) : SmsDraftsEvent

    /** The user corrected what the parser read for one draft (P-07). */
    data class AmountEdited(val draftId: String, val value: String) : SmsDraftsEvent

    /** The user named or renamed the payee for one draft. */
    data class MerchantEdited(val draftId: String, val value: String) : SmsDraftsEvent

    /** Turn one draft into a transaction (P-07 — this is the tap that makes it real). */
    data class Accept(val draftId: String) : SmsDraftsEvent

    /** Say no to one draft, for good. */
    data class Dismiss(val draftId: String) : SmsDraftsEvent

    /** Clear a failure so the user can try again. */
    data object DismissError : SmsDraftsEvent
}
