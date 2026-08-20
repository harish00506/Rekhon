package com.aicfo.feature.accounts

import androidx.compose.runtime.Immutable
import com.aicfo.core.model.Account
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.domain.engines.card.CardStatus
import com.aicfo.domain.engines.loan.AmortisationRow

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
    /**
     * The computed figures for every credit card, keyed by account id (issue 6.1; FR-ACC-002).
     *
     * A map rather than a field on [Account], because a card's status is derived — it is the engine's
     * answer about a row, not part of the row. An account of type `CREDIT_CARD` that is **absent**
     * here is a card whose terms have not been entered, which the row renders as a prompt rather
     * than as a 0% bar (P-03).
     */
    val cards: Map<String, CardStatus> = emptyMap(),
    /**
     * The **next** instalment due on every loan, keyed by account id (issue 6.2; FR-ACC-003).
     *
     * One row, not a schedule: the list shows a single line per loan and building 240 rows to
     * render it would be waste that only appears on somebody else's phone. Which instalment is
     * "next" is decided in the repository, where the clock is (TIM-001).
     *
     * A `LOAN` account **absent** here has either no terms entered yet — rendered as a prompt
     * rather than a ₹0 EMI (P-03) — or is repaid, and a repaid loan has no next EMI to name.
     */
    val loans: Map<String, AmortisationRow> = emptyMap(),
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
    // --- Issue 6.1: credit-card terms (FR-ACC-002) -------------------------------------------
    //
    // The first type-specific fields in this state, and the module's first type branch anywhere.
    // Held as text like the opening balance is, for the same reason: a half-typed "2,00,0" is a
    // real intermediate state and parsing on every keystroke would fight the user. They are parsed
    // once, on Save.
    //
    // Nullable-by-blank rather than a nested `CardFields?`: the ten other types simply leave them
    // empty, and a nested object would need creating the moment the user picked CREDIT_CARD and
    // discarding the moment they changed their mind — state that exists only to be thrown away.
    val creditLimitText: String = "",
    val statementDayText: String = "",
    val dueDayText: String = "",
    val lastStatementText: String = "",
    val minimumDueText: String = "",
    // --- Issue 6.2: loan terms (FR-ACC-003) ---------------------------------------------------
    //
    // The second type branch, held as text for the reason the card fields are: a half-typed "8." is
    // a real intermediate state. Parsed once, on Save.
    //
    // [annualRateText] is typed in **percent** ("8.5") and stored in basis points (850) — the
    // conversion is one line in `toLoan`, and it is the only place in this module that knows it.
    val principalText: String = "",
    val annualRateText: String = "",
    val tenureMonthsText: String = "",
    val firstEmiDateText: String = "",
    val emiOverrideText: String = "",
) {
    /** Whether this is an edit of an existing account rather than a new one. */
    val isEditing: Boolean get() = id != null

    /**
     * Whether the card section is on screen (issue 6.1).
     *
     * A limit and a statement day mean nothing on a savings account, and showing them would invite
     * a user to fill in fields the app would then ignore. Issue 6.2 added [showsLoanFields] beside
     * this one.
     */
    val showsCardFields: Boolean get() = type == AccountType.CREDIT_CARD

    /**
     * Whether the loan section is on screen (issue 6.2; FR-ACC-003).
     *
     * The same argument [showsCardFields] makes, and deliberately the same **shape**: an exact type
     * match rather than `type.isLiability`, which would hand a credit card a tenure in months and a
     * loan a statement day.
     */
    val showsLoanFields: Boolean get() = type == AccountType.LOAN

    /**
     * Whether the card's terms are complete enough to store (issue 6.1).
     *
     * All three or none. A card row with a limit and no statement day cannot produce a cycle, and
     * one with days and no limit cannot produce a ratio — either way the screen would show a card
     * that computes nothing, which is worse than a card that has not been set up. Leaving all three
     * blank is the supported state: a credit-card account with no terms yet.
     */
    val hasCardTerms: Boolean
        get() = creditLimitText.isNotBlank() && statementDayText.isNotBlank() && dueDayText.isNotBlank()

    /**
     * Whether the loan's terms are complete enough to store (issue 6.2).
     *
     * All four or none, for the reason [hasCardTerms] gives: there is no schedule without every one
     * of principal, rate, tenure and a first EMI date — three of four produces nothing at all, not
     * a partial answer. **The rate is required even at zero**: an interest-free family loan is a
     * real case the engine handles, and a blank field cannot be told apart from an unanswered one,
     * so the user types `0` and means it.
     *
     * The EMI override is not here. It is genuinely optional — its absence means "derive it".
     */
    val hasLoanTerms: Boolean
        get() =
            principalText.isNotBlank() && annualRateText.isNotBlank() &&
                tenureMonthsText.isNotBlank() && firstEmiDateText.isNotBlank()

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

    /**
     * The user typed in one of the credit-card fields (issue 6.1, FR-ACC-002).
     *
     * One event with a [field] rather than five events, unusually for this codebase — because the
     * five are the same action on the same section and the ViewModel handles them identically. Five
     * cases in the `when` would be five identical bodies differing only in which `copy` argument
     * they name.
     */
    data class CardFieldChanged(val field: CardField, val value: String) : AccountEditorEvent

    /**
     * The user typed in one of the loan fields (issue 6.2, FR-ACC-003).
     *
     * A second event rather than widening [CardFieldChanged] to a shared field enum: the two
     * sections are never on screen together, and one enum spanning both would make every `when`
     * over it carry five cases that cannot happen.
     */
    data class LoanFieldChanged(val field: LoanField, val value: String) : AccountEditorEvent

    /** The user tapped Save. */
    data object Save : AccountEditorEvent

    /** The user dismissed the error banner. */
    data object DismissError : AccountEditorEvent
}

/**
 * Which credit-card field an edit was for (issue 6.1; FR-ACC-002).
 *
 * Why:  a closed set rather than a string key, so a typo is a compile error and the `when` that
 *       applies the edit has to stay exhaustive when 6.2 or a later issue adds a term.
 * Changelog: 2026-08-17 — Created for issue 6.1.
 */
enum class CardField {
    /** The sanctioned limit, in rupees as typed. */
    LIMIT,

    /** Day of the month the statement is cut, 1..31. */
    STATEMENT_DAY,

    /** Day of the month the payment is due, 1..31. May precede the statement day. */
    DUE_DAY,

    /** The amount on the most recent statement. Optional — a new card has none. */
    LAST_STATEMENT,

    /** The minimum payment on that statement. Optional. */
    MINIMUM_DUE,
}

/**
 * Which loan field an edit was for (issue 6.2; FR-ACC-003).
 *
 * Why:  a closed set rather than a string key, the reason [CardField] gives — a typo becomes a
 *       compile error, and the `when` that applies the edit must stay exhaustive when a later
 *       issue adds a term (a moratorium, say, or a floating-rate reset).
 * Changelog: 2026-08-20 — Created for issue 6.2.
 */
enum class LoanField {
    /** The sanctioned amount, in rupees as typed. */
    PRINCIPAL,

    /** The annual interest rate, typed in **percent** — `8.5`, not `850` (MNY-002 converts at save). */
    ANNUAL_RATE,

    /** How many monthly instalments the loan runs for, 1..600. */
    TENURE_MONTHS,

    /** The first EMI's date, ISO `yyyy-MM-dd` (TIM-002). Every later one steps a month from it. */
    FIRST_EMI_DATE,

    /**
     * The lender's own EMI, in rupees. Optional — blank means "work it out from the terms above".
     *
     * Offered because banks round the closed form their own way, and a schedule that disagrees with
     * the borrower's statement by ₹2 a month is a schedule they stop trusting.
     */
    EMI_OVERRIDE,
}
