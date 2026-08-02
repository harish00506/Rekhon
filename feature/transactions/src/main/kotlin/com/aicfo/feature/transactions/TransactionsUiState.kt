package com.aicfo.feature.transactions

import androidx.compose.runtime.Immutable
import com.aicfo.core.model.Account
import com.aicfo.core.model.Category
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.core.model.Transaction

/**
 * Everything the add-transaction screen renders, as one value (issue 3.1; ARC-004, FR-TXN-002).
 *
 * Why:  §21.2 requires one immutable state class per screen exposed as a `StateFlow`, so a screen
 *       can never render a half-updated mix and every state it can be in is nameable in a test.
 *       Here that matters more than usual: FR-TXN-002's ≤ 3 taps is a claim about what is *already
 *       chosen* when the screen opens, and [selectedAccountId] being pre-filled is what makes it
 *       true. A test asserts the tap count against this value, not against a screenshot.
 * What: the amount as it is being typed, the direction, the pickable accounts and categories, and
 *       the outcome of saving.
 * Result: the add screen is a pure function of this value.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 *
 * **[amountText] is text, not [Money]** — the same reasoning `AccountEditorUiState` gives: `"1."` is
 * a legitimate thing to have on screen mid-typing and is not an amount yet. It is parsed once, at
 * save, by `MoneyFormatter.parse` (MNY-001). The screen never does money math.
 *
 * **[isExpense] is a UI concern only.** Below this class the direction is the amount's *sign* and
 * nothing else (`TransactionDraft.amount`), so there is exactly one representation of it in the
 * store and no way for a flag and a sign to disagree. This flag exists because "−250" is a worse
 * thing to ask a user to type than "250, expense".
 *
 * Input:  [amountText]; [isExpense] — the toggle, defaulting to the common case; [accounts] — what
 *         the user may pick, name-ordered; [selectedAccountId] — pre-filled with the first account
 *         so the common path needs no tap; [categories] — **empty for every real profile until issue
 *         4.1**, which the screen renders by hiding the row entirely; [selectedCategoryId];
 *         [note]; [isLoading]; [isSaving]; [isSaved] — the screen should leave; [errorCode] — an
 *         `AppError.code`, never a message, so the wording stays in `strings.xml` (§21.6).
 * Output: an immutable snapshot for the composable.
 */
@Immutable
data class AddTransactionUiState(
    val amountText: String = "",
    val isExpense: Boolean = true,
    val accounts: List<Account> = emptyList(),
    val selectedAccountId: String? = null,
    val categories: List<Category> = emptyList(),
    val selectedCategoryId: String? = null,
    val note: String = "",
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val errorCode: String? = null,
) {
    /**
     * The signed amount the draft would carry, or `null` while the field is not yet an amount.
     *
     * Why: the one place the toggle becomes a sign. `MoneyFormatter.parse` returns `null` for
     *      anything it cannot represent exactly rather than rounding it (MNY-001), and a zero is
     *      rejected here as well as in the repository — not to duplicate the rule but so that Save
     *      is *disabled* on an empty form rather than tapped and refused.
     */
    val amount: Money?
        get() =
            MoneyFormatter.parse(amountText)
                ?.takeIf { it != Money.ZERO }
                ?.let { if (isExpense) Money.ZERO - it else it }

    /**
     * Whether the user has no account to spend from.
     *
     * A real state, not an error: FR-ONB-001's fourth step creates one, but a user who skipped it,
     * or archived their last account, lands here. Saving is impossible until they add one, and the
     * screen has to say so rather than showing a Save button that always fails.
     */
    val hasNoAccount: Boolean get() = !isLoading && accounts.isEmpty()

    /**
     * Whether the category row has anything to offer.
     *
     * False for every real profile until issue 4.1 seeds a taxonomy — only demo mode has categories
     * today. The row is hidden rather than shown empty, because an empty picker reads as a broken
     * one, and because FR-TXN-002's tap budget must not be spent on a control with no options.
     */
    val hasCategories: Boolean get() = categories.isNotEmpty()

    /**
     * Whether Save may proceed.
     *
     * A representable non-zero amount, an account to spend from, and no write already done or in
     * flight. **[isSaved] is part of it**, not only [isSaving]: the write completes fast enough that
     * a double-tap's second event usually arrives after [isSaving] has gone false again, while the
     * screen is still on its way out — and booking the spend twice is money the user never spent.
     */
    val canSave: Boolean get() = amount != null && selectedAccountId != null && !isSaving && !isSaved
}

/**
 * Everything the user can do on the add-transaction screen (ARC-004).
 *
 * Why:    events flow **up** through a sealed interface, so the composable holds no logic and the
 *         compiler lists every interaction the ViewModel must handle.
 * Result: the screen's complete input surface.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 */
sealed interface AddTransactionEvent {
    /** The user typed in the amount field. */
    data class AmountChanged(val value: String) : AddTransactionEvent

    /** The user switched between expense and income. */
    data class ExpenseChanged(val isExpense: Boolean) : AddTransactionEvent

    /** The user picked which account the money moved in. */
    data class AccountSelected(val id: String) : AddTransactionEvent

    /** The user picked a category, or tapped the selected one again to clear it. */
    data class CategorySelected(val id: String?) : AddTransactionEvent

    /** The user typed in the note field. */
    data class NoteChanged(val value: String) : AddTransactionEvent

    /** The user tapped Save. */
    data object Save : AddTransactionEvent

    /** The user dismissed the error banner. */
    data object DismissError : AddTransactionEvent
}

/**
 * Everything the recent-transactions list renders, as one value (issue 3.1; ARC-004).
 *
 * Why:  the save has to be *observable* — a capture path whose result the user cannot see is one
 *       they cannot trust. This is the smallest screen that shows it landed.
 * What: the loading flag, the transactions grouped by the day they were booked on, and any error.
 * Result: the list screen is a pure function of this value.
 * Changelog: 2026-08-02 — Created for issue 3.1 (replacing 1.10's placeholder screen).
 *
 * **Deliberately no search, filters, paging or bulk edit.** FR-TXN-007 and FR-TXN-008 are issue
 * 3.6's, and the repository behind this deliberately reads a fixed 30-day window rather than
 * everything. Growing this class is how 3.6 gets built early and badly.
 *
 * Input:  [isLoading]; [days] — newest day first, each with its transactions newest first;
 *         [errorCode] — an `AppError.code`, never a message.
 * Output: an immutable snapshot for the composable.
 */
@Immutable
data class TransactionsUiState(
    val isLoading: Boolean = true,
    val days: List<TransactionDay> = emptyList(),
    val errorCode: String? = null,
) {
    /**
     * Whether to show the "nothing yet" line rather than a list.
     *
     * The same three-way distinction [AddTransactionUiState.hasNoAccount] makes and
     * `AccountsUiState.isEmpty` made before it: still loading is not empty, and **a failed read is
     * not empty either** — rendering a database that would not open as a cheerful "no transactions
     * yet" hides the failure from the one user who most needs to see it.
     */
    val isEmpty: Boolean get() = !isLoading && errorCode == null && days.isEmpty()
}

/**
 * One day's transactions with its total (issue 3.1; FR-TXN-007's grouping half).
 *
 * Why:  FR-TXN-007 asks for "grouping by day with daily totals", and grouping in the state rather
 *       than in the composable is what lets a test assert it. **[total] is computed with [Money]'s
 *       overflow-checked arithmetic**, never a raw `Long` sum (MNY-001) — and it is computed here,
 *       from the transactions, rather than passed in beside them, so the two cannot disagree.
 * What: the booked day and the rows booked on it.
 * Result: a day header, its rows, and a total that is provably the sum of them.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 *
 * Input:  [isoDate] — ISO `yyyy-MM-dd` in the profile zone (TIM-002), which is what the rows were
 *         grouped on; [transactions] — newest first.
 * Output: an immutable value.
 */
@Immutable
data class TransactionDay(
    val isoDate: String,
    val transactions: List<Transaction>,
) {
    /** The day's net movement: outflows are negative, so this is a signed total, not a spend figure. */
    val total: Money get() = transactions.fold(Money.ZERO) { running, txn -> running + txn.amount }
}
