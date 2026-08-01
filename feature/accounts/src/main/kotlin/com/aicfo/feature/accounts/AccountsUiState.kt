package com.aicfo.feature.accounts

import androidx.compose.runtime.Immutable
import com.aicfo.core.model.Account
import com.aicfo.core.model.AccountType

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
 *         so the wording stays in `strings.xml` (§21.6).
 * Output: an immutable snapshot for the composable.
 */
@Immutable
data class AccountsUiState(
    val isLoading: Boolean = true,
    val accounts: List<Account> = emptyList(),
    val showArchived: Boolean = false,
    val errorCode: String? = null,
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

    /** The user dismissed the error banner. */
    data object DismissError : AccountsEvent
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
 *         [isLoading]; [isSaving]; [isSaved] — the screen should leave; [errorCode].
 * Output: an immutable snapshot for the composable.
 */
@Immutable
data class AccountEditorUiState(
    val id: String? = null,
    val name: String = "",
    val type: AccountType = AccountType.BANK,
    val institution: String = "",
    val openingBalanceText: String = "",
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

    /** The user tapped Save. */
    data object Save : AccountEditorEvent

    /** The user dismissed the error banner. */
    data object DismissError : AccountEditorEvent
}
