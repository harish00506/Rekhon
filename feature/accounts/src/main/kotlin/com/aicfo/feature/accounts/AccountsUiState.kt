package com.aicfo.feature.accounts

import androidx.compose.runtime.Immutable
import com.aicfo.core.model.Account
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter

/**
 * Everything the accounts list renders, as one value (ARC-004).
 *
 * Why:  §21.2 requires one immutable state class per screen exposed as a `StateFlow`, so a screen
 *       can never render a half-updated mix and every state it can be in is nameable in a test.
 * What: the loading flag, the accounts, the archived toggle, and any error to surface.
 * Result: the list screen is a pure function of this value.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 *
 * **[accounts] holds `Account`, a `:core:model` type, never a Room entity** (ARC-005). Each carries
 * both its opening balance and its balance derived from transactions (DB-001).
 *
 * Input:  [isLoading]; [accounts] — name-ordered, empty for a user with none, which the screen
 *         renders as an empty state; [showArchived] — FR-ACC-007's toggle, off by default so
 *         closed accounts stay out of the way; [errorCode] — an `AppError.code`, never a message,
 *         so the wording stays in `strings.xml` (§21.6); [reconciling] — FR-ACC-006's panel when
 *         one is open, `null` when it is not (issue 2.7).
 * Output: an immutable snapshot for the composable.
 */
@Immutable
data class AccountsUiState(
    val isLoading: Boolean = true,
    val accounts: List<Account> = emptyList(),
    val showArchived: Boolean = false,
    val errorCode: String? = null,
    /**
     * The open reconciliation panel, or `null` (issue 2.7; FR-ACC-006).
     *
     * A nullable member of this state rather than a second screen: reconciling is a two-field
     * decision *about a row already on screen*, and the list behind it must keep updating while it
     * is open — the balance it shows is the one the delta is measured against.
     */
    val reconciling: ReconcileState? = null,
) {
    /**
     * Whether to show the "no accounts yet" invitation rather than a list.
     *
     * Why: three different situations produce an empty [accounts] list and only one of them is
     *      "you have no accounts". Still loading is not it — the invitation would flash before the
     *      store answered. **And neither is a failed read**, which is the case this guard exists
     *      for: a database that would not open would otherwise render as a cheerful invitation to
     *      add an account, hiding the failure from the one user who most needs to see it.
     */
    val isEmpty: Boolean get() = !isLoading && errorCode == null && accounts.isEmpty()
}

/**
 * Everything the user can do on the accounts list (ARC-004).
 *
 * Why:    events flow **up** through a sealed interface, so the composable holds no logic and the
 *         compiler lists every interaction the ViewModel must handle.
 * Result: the screen's complete input surface.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 */
sealed interface AccountsEvent {
    /** The user toggled FR-ACC-007's archived accounts into or out of the list. */
    data class ToggleArchived(val show: Boolean) : AccountsEvent

    /** The user archived or restored one account. */
    data class SetArchived(val id: String, val archived: Boolean) : AccountsEvent

    /** The user deleted one account. Soft, per DB-002. */
    data class Delete(val id: String) : AccountsEvent

    /** The user opened FR-ACC-006's reconciliation panel on one account (issue 2.7). */
    data class OpenReconcile(val id: String) : AccountsEvent

    /** The user typed the balance from their statement (issue 2.7). */
    data class StatementChanged(val value: String) : AccountsEvent

    /** The user confirmed the adjustment (issue 2.7). */
    data object ConfirmReconcile : AccountsEvent

    /** The user closed the panel without adjusting anything (issue 2.7). */
    data object CancelReconcile : AccountsEvent

    /** The user dismissed the error banner. */
    data object DismissError : AccountsEvent
}

/**
 * The reconciliation panel's state (issue 2.7; FR-ACC-006, P-02, ARC-004).
 *
 * Why:  FR-ACC-006 corrects a balance by posting an adjustment, so the user has to be shown the
 *       three figures the adjustment is made of *before* they commit to it — what the app holds,
 *       what they typed, and the difference. Holding them together in one value is what stops the
 *       panel rendering a delta that belongs to a different balance than the one above it.
 * What: the account being reconciled, the text as it is being typed, and the write's progress.
 * Result: every state the panel can be in is nameable in a test.
 * Changelog: 2026-08-02 — Created for issue 2.7.
 *
 * **[statementText] is text, not [Money]** — the same reasoning [AccountEditorUiState] gives for
 * its amounts: `"1."` and `"-"` are legitimate things to have on screen mid-typing and are not
 * amounts yet. It is parsed by `MoneyFormatter.parse` (MNY-001), which returns `null` rather than
 * rounding anything it cannot represent exactly.
 *
 * **[delta] is a preview, and the repository does not trust it.** `AccountRepository.reconcile`
 * re-derives the balance inside its own transaction and computes the delta again there. Showing a
 * figure here and writing a different one would be indefensible — but so would writing the one
 * shown, because the list behind this panel is live and [account] may be seconds old. The screen
 * explains; the store decides.
 *
 * Input:  [account] — the row being reconciled, carrying its derived balance; [statementText] —
 *         what the user is typing; [isSaving] — the write is in flight, so confirm is disabled.
 * Output: an immutable value.
 */
@Immutable
data class ReconcileState(
    val account: Account,
    val statementText: String = "",
    val isSaving: Boolean = false,
) {
    /**
     * The statement balance, once it is a representable amount.
     *
     * Blank is `null` rather than zero, unlike the editor's opening-balance field: there, an
     * account opened at nothing is ordinary and a blank field has an obvious meaning. Here a blank
     * field means the user has not answered yet, and treating it as ₹0 would offer to wipe the
     * account's balance on an empty form.
     */
    val statement: Money? get() = MoneyFormatter.parse(statementText)

    /** The adjustment that would be posted, or `null` while the statement is not yet an amount. */
    val delta: Money? get() = statement?.let { it - account.balance }

    /** Whether confirming would post anything — a matching statement needs no adjustment. */
    val canConfirm: Boolean get() = !isSaving && delta != null
}

/**
 * Everything the account editor renders, as one value (ARC-004).
 *
 * Why:  the editor serves both create and edit, and the difference is one nullable id rather than
 *       two screens — the fields, the validation and the save are identical, and duplicating them
 *       is how the two drift apart.
 * What: the form fields as the user is typing them, plus the outcome of saving.
 * Result: every state the editor can be in is assertable.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 *
 * **[openingBalanceText] is text, not [com.aicfo.core.model.Money]** — the same reasoning
 * `OnboardingUiState` gives for its amounts: `"1."` is a legitimate thing to have on screen and is
 * not an amount yet. It is parsed once, at save, by `MoneyFormatter.parse` (MNY-001). The screen
 * never does money math.
 *
 * Input:  [id] — `null` when creating; [name]; [type]; [institution]; [openingBalanceText];
 *         [includeInNetWorth] — FR-ACC-005's opt-out, on by default (issue 2.6); [isLoading];
 *         [isSaving]; [isSaved] — the screen should leave; [errorCode].
 * Output: an immutable snapshot for the composable.
 */
@Immutable
data class AccountEditorUiState(
    val id: String? = null,
    val name: String = "",
    val type: AccountType = AccountType.BANK,
    val institution: String = "",
    val openingBalanceText: String = "",
    val includeInNetWorth: Boolean = true,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val errorCode: String? = null,
) {
    /** Whether this is an edit of an existing account rather than a new one. */
    val isEditing: Boolean get() = id != null

    /**
     * Whether Save may proceed.
     *
     * Only the name is checked, and only for blankness — the same rule the repository enforces
     * (`AccountDraft.validated`). The opening balance is *not* required: an account opened at zero
     * is ordinary, and a blank field means zero rather than an error. Duplicating the amount rule
     * here is how the screen and the store would come to disagree.
     */
    val canSave: Boolean get() = name.isNotBlank() && !isSaving
}

/**
 * Everything the user can do in the account editor (ARC-004).
 * Changelog: 2026-07-28 — Created for issue 2.5.
 */
sealed interface AccountEditorEvent {
    /** The user typed in the name field. */
    data class NameChanged(val value: String) : AccountEditorEvent

    /** The user picked one of FR-ACC-001's eleven types. */
    data class TypeChanged(val value: AccountType) : AccountEditorEvent

    /** The user typed in the institution field. */
    data class InstitutionChanged(val value: String) : AccountEditorEvent

    /** The user typed in the opening-balance field. */
    data class OpeningBalanceChanged(val value: String) : AccountEditorEvent

    /** The user toggled whether this account counts towards net worth (issue 2.6, FR-ACC-005). */
    data class IncludeInNetWorthChanged(val value: Boolean) : AccountEditorEvent

    /** The user tapped Save. */
    data object Save : AccountEditorEvent

    /** The user dismissed the error banner. */
    data object DismissError : AccountEditorEvent
}
