package com.aicfo.feature.transactions

import androidx.compose.runtime.Immutable
import com.aicfo.core.model.Account
import com.aicfo.core.model.Category
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.core.model.Transaction
import com.aicfo.core.model.TransactionSource
import java.time.LocalDate
import java.time.LocalTime

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
    val direction: TransactionDirection = TransactionDirection.EXPENSE,
    val accounts: List<Account> = emptyList(),
    val selectedAccountId: String? = null,
    val toAccountId: String? = null,
    val categories: List<Category> = emptyList(),
    val selectedCategoryId: String? = null,
    val isSplit: Boolean = false,
    val splitLines: List<SplitLineInput> = emptyList(),
    /**
     * The day the transaction will be booked on, or `null` for today (issue 3.4; FR-TXN-010).
     *
     * **`null` is not "unset", it is "today"**, and it stays the default so FR-TXN-002's tap budget
     * is untouched: a user recording an ordinary expense never opens the picker. The screen renders
     * `null` as the word "Today" rather than a date, because that is what the user means by it and
     * because deciding what today *is* needs the profile zone, which lives in the ViewModel's
     * injected `Clock` (TIM-001) and not in a composable.
     */
    val bookedOn: LocalDate? = null,
    /** Whether the date picker is open (issue 3.4). Part of the state so a test can drive it. */
    val isDatePickerOpen: Boolean = false,
    /**
     * The time of day, or `null` to let the app choose (FR-TXN-001's "date-time").
     *
     * **`null` is not midnight.** It means "the default", which the repository resolves to *now* for
     * today and to the start of the day for a future date — the behaviour every caller had before
     * this field existed. [bookedAtLabelIsNow] is what lets the screen say which of the two it is.
     */
    val bookedAt: LocalTime? = null,
    /** Whether the time picker is open. Part of the state so a test can drive it. */
    val isTimePickerOpen: Boolean = false,
    /**
     * The earliest day the picker offers — today, in the profile zone (issue 3.4).
     *
     * Supplied by the ViewModel from the injected `Clock` rather than read in the composable, and
     * defaulted to the epoch so a preview or a test that does not care renders without a clock. Its
     * real value arrives with the first state emission.
     *
     * **`ofEpochDay(0)`, not `LocalDate.EPOCH`** — that constant is API 34 and this app's minSdk is
     * 26 (NFR-008). Caught by `lintDebug`, not by any test: every unit test runs on the JVM, where
     * the constant exists, so this would have compiled, passed and crashed on a real phone.
     */
    val earliestBookableDate: LocalDate = LocalDate.ofEpochDay(0),
    /**
     * The current time of day in the profile zone, for seeding the time picker (FR-TXN-001).
     *
     * Supplied by the ViewModel from the injected `Clock` for the same reason
     * [earliestBookableDate] is: a composable may not read a clock (TIM-001), and the profile zone
     * is not the device's. Only ever a starting position — nothing is decided by it.
     */
    val nowInProfileZone: LocalTime = LocalTime.MIDNIGHT,
    /**
     * The payee or merchant (FR-TXN-001).
     *
     * **The field the add screen was missing.** `TransactionDraft.merchant` and the
     * `transactions.merchant` column have existed since issues 3.1 and 1.6, the list row falls back
     * to it for its title and the detail sheet renders it — but until this was added only
     * `DemoDataset` ever wrote one, so every row on a real profile read "Uncategorised" unless the
     * user happened to type a note.
     */
    val merchant: String = "",
    val note: String = "",
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val errorCode: String? = null,
) {
    /**
     * The amount as typed, once it is a representable non-zero figure.
     *
     * Why: `MoneyFormatter.parse` returns `null` for anything it cannot represent exactly rather
     *      than rounding it (MNY-001), and a zero is rejected here as well as in the repository —
     *      not to duplicate the rule but so that Save is *disabled* on an empty form rather than
     *      tapped and refused. **Unsigned**: the sign is applied by [signedAmount] for the two
     *      one-account directions, and by the repository for a transfer's two legs.
     */
    val amount: Money? get() = MoneyFormatter.parse(amountText)?.takeIf { it != Money.ZERO }

    /**
     * The signed amount an expense or income would be saved with, or `null` for a transfer.
     *
     * Why: the one place the toggle becomes a sign. A transfer has no single signed amount — it has
     *      two, one per leg — so this is deliberately `null` there rather than guessing which side
     *      the screen means. `TransferDraft` takes a positive [amount] instead.
     */
    val signedAmount: Money?
        get() =
            when (direction) {
                TransactionDirection.EXPENSE -> amount?.let { Money.ZERO - it }
                TransactionDirection.INCOME -> amount
                TransactionDirection.TRANSFER -> null
            }

    /** Whether the user is moving money between their own accounts (FR-TXN-003). */
    val isTransfer: Boolean get() = direction == TransactionDirection.TRANSFER

    /**
     * Whether to offer a merchant field (FR-TXN-001, FR-TXN-003).
     *
     * **Never for a transfer.** Moving money between your own accounts has no payee, which is why
     * `TransferDraft` has no merchant to carry one — the same reason a transfer has no category.
     */
    val hasMerchant: Boolean get() = !isTransfer

    /**
     * Whether the time field means "now" rather than a clock time the user picked.
     *
     * `null` [bookedAt] resolves to *now* on today and to the start of the day on a future date, so
     * the screen has to say which. Showing a stale clock time for "now" would be a lie by the second
     * the user read it.
     */
    val bookedAtLabelIsNow: Boolean get() = bookedAt == null && bookedOn == null

    /**
     * The accounts offered as a transfer destination.
     *
     * The source is excluded, because a transfer to the account the money is leaving moves nothing
     * — the repository refuses it, and offering it in the picker would be inviting the mistake.
     */
    val destinationChoices: List<Account> get() = accounts.filterNot { it.id == selectedAccountId }

    /**
     * Whether a transfer has somewhere to go.
     *
     * Two accounts are the minimum. A user with one account cannot transfer at all, and the screen
     * says so rather than showing an empty destination picker.
     */
    val canTransfer: Boolean get() = accounts.size >= 2

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
     *
     * **Always false for a transfer** (FR-TXN-003): a transfer is not spending, so it has no category
     * to pick. **And false for a split** (FR-TXN-004): the lines carry the categories, and one on the
     * parent as well would be a second, contradictory answer.
     */
    val hasCategories: Boolean get() = categories.isNotEmpty() && !isTransfer && !isSplit

    /**
     * Whether splitting is offered at all (issue 3.3; FR-TXN-004).
     *
     * Needs an amount to divide, and is meaningless for a transfer — moving money between your own
     * accounts is not spending, so there is nothing to attribute across categories.
     */
    val canSplit: Boolean get() = !isTransfer && amount != null

    /**
     * What the split lines currently add up to (FR-TXN-004).
     *
     * Lines that are not yet a figure contribute nothing, so a half-typed line reads as still owing
     * rather than as zero — which is what makes [splitRemainder] usable while the user is typing.
     */
    val splitTotal: Money
        get() = splitLines.fold(Money.ZERO) { running, line -> running + (line.amount ?: Money.ZERO) }

    /**
     * How much of the parent is still unattributed — **the running remainder** (FR-TXN-004).
     *
     * Why: the AC asks for it on screen, and it is the whole feedback loop of the editor: the user
     *      types, this moves, and Save unlocks exactly when it reaches zero.
     *
     *      **Unsigned, like everything else in this editor.** [amount] and each line's amount are
     *      both magnitudes — the direction belongs to the parent and is applied once, at save, by
     *      `toSplitDraftOrNull`. Mixing a signed parent with unsigned lines here is exactly how this
     *      first read as double the amount. `null` while the amount itself is not yet a figure.
     */
    val splitRemainder: Money? get() = amount?.let { it - splitTotal }

    /**
     * Whether the split lines are ready to write (FR-TXN-004).
     *
     * The same three rules the repository enforces — at least two lines, every one a real figure, and
     * a remainder of exactly zero. Duplicated here **only to disable the button**, so the user is
     * told before they commit rather than after; the store validates independently and is the
     * authority (§5).
     */
    val isSplitBalanced: Boolean
        get() =
            splitLines.size >= MIN_SPLIT_LINES &&
                splitLines.all { it.amount != null } &&
                splitRemainder == Money.ZERO

    /**
     * Whether Save may proceed.
     *
     * A representable non-zero amount, an account, and no write already done or in flight. **A
     * transfer additionally needs a destination that is not the source** — the repository refuses
     * that anyway, but a disabled button explains it before the user commits.
     *
     * **[isSaved] is part of it**, not only [isSaving]: the write completes fast enough that a
     * double-tap's second event usually arrives after [isSaving] has gone false again, while the
     * screen is still on its way out — and booking the movement twice is money the user never moved.
     */
    val canSave: Boolean
        get() =
            amount != null &&
                selectedAccountId != null &&
                !isSaving &&
                !isSaved &&
                (!isTransfer || (toAccountId != null && toAccountId != selectedAccountId)) &&
                (!isSplit || isSplitBalanced)
}

/**
 * One split line as the user is entering it (issue 3.3; FR-TXN-004).
 *
 * Why:  the amount is **text**, not [Money], for the reason the parent amount is: `"1."` is a
 *       legitimate thing to have on screen mid-typing and is not an amount yet. Parsing once, here,
 *       through `MoneyFormatter.parse` keeps every money value in the screen coming from one place
 *       (MNY-001) — the screen still does no money math beyond adding parsed values.
 * What: what the user typed, and which category they picked for it.
 * Result: the editor can hold a half-finished line without pretending it is worth zero.
 * Changelog: 2026-08-02 — Created for issue 3.3.
 *
 * Input:  [amountText] — as typed, **unsigned**; [categoryId] — optional, `null` for every real
 *         profile until issue 4.1.
 * Output: an immutable value.
 */
@Immutable
data class SplitLineInput(
    val amountText: String = "",
    val categoryId: String? = null,
) {
    /**
     * The line's amount once it is a representable non-zero figure, else `null`.
     *
     * **Unsigned here.** The direction belongs to the parent, and the ViewModel applies its sign to
     * every line at save — so a user typing "600" into a line of an expense never has to think about
     * a minus, and one line can never end up signed against its siblings.
     */
    val amount: Money? get() = MoneyFormatter.parse(amountText)?.takeIf { it != Money.ZERO }
}

/**
 * The fewest lines a split may have (FR-TXN-004).
 *
 * Mirrors `TransactionRepository.MIN_SPLIT_LINES`. One line is not a split — it is the transaction it
 * already was.
 */
internal const val MIN_SPLIT_LINES = 2

/**
 * Which way money is moving, as the add screen asks it (issue 3.2; FR-TXN-003).
 *
 * Why:  issue 3.1 had a boolean, because there were two answers. FR-TXN-003 adds a third that is not
 *       a direction at all — a transfer leaves one account *and* arrives in another — so a boolean
 *       can no longer carry it. A closed set means the compiler lists every case the screen and the
 *       ViewModel must handle.
 * What: the three things the user can be recording.
 * Result: adding a fourth kind of capture is a compile error until every `when` handles it.
 * Changelog: 2026-08-02 — Created for issue 3.2, replacing issue 3.1's `isExpense` boolean.
 *
 * **Not [com.aicfo.core.model.TransactionType].** That enum is the *stored* vocabulary and has five
 * values, two of which — the transfer legs — are a consequence of saving, not a thing the user picks.
 * This is the question the form asks; the repository decides which stored types answer it.
 */
enum class TransactionDirection {
    /** Money leaving, the common case and the default. */
    EXPENSE,

    /** Money arriving from outside. */
    INCOME,

    /** Money moving between two of the user's own accounts (FR-TXN-003). */
    TRANSFER,
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

    /** The user switched between expense, income and transfer. */
    data class DirectionChanged(val direction: TransactionDirection) : AddTransactionEvent

    /** The user picked which account the money moved in — the source, for a transfer. */
    data class AccountSelected(val id: String) : AddTransactionEvent

    /** The user picked where a transfer's money is going (FR-TXN-003). */
    data class DestinationSelected(val id: String) : AddTransactionEvent

    /** The user picked a category, or tapped the selected one again to clear it. */
    data class CategorySelected(val id: String?) : AddTransactionEvent

    /** The user typed in the merchant field (FR-TXN-001). */
    data class MerchantChanged(val value: String) : AddTransactionEvent

    /** The user typed in the note field. */
    data class NoteChanged(val value: String) : AddTransactionEvent

    /** The user tapped Save. */
    data object Save : AddTransactionEvent

    /** The user dismissed the error banner. */
    data object DismissError : AddTransactionEvent
}

/**
 * The three things a user can do to the booked date (issue 3.4; FR-TXN-010).
 *
 * Why:  grouped for the reason [SplitEvent] is — they arrive together, they are handled together in
 *       one `is ScheduleEvent ->` branch, and flattening three more members into
 *       [AddTransactionEvent] would push `onEvent` back past detekt's complexity ceiling that
 *       issue 3.3 had to extract to get under. Picking a day is its own small mode.
 * Result: adding a date interaction cannot silently grow the screen's main event handler.
 * Changelog: 2026-08-03 — Created for issue 3.4.
 */
sealed interface ScheduleEvent : AddTransactionEvent {
    /** The user tapped the date button. */
    data object DatePickerOpened : ScheduleEvent

    /** The user picked a day. Never in the past — the picker will not offer one. */
    data class DateSelected(val date: LocalDate) : ScheduleEvent

    /** The user confirmed or dismissed the picker. */
    data object DatePickerDismissed : ScheduleEvent

    /** The user tapped the time button (FR-TXN-001's "date-time"). */
    data object TimePickerOpened : ScheduleEvent

    /** The user picked a time of day. */
    data class TimeSelected(val time: LocalTime) : ScheduleEvent

    /** The user confirmed or dismissed the time picker. */
    data object TimePickerDismissed : ScheduleEvent
}

/**
 * The six things a user can do to a split (issue 3.3; FR-TXN-004).
 *
 * Why:  a nested sealed interface rather than six more members of [AddTransactionEvent] directly.
 *       They arrived together and they are all handled together — one `is SplitEvent ->` branch in
 *       `onEvent`, delegating to `applySplit`. Flattened into the parent they pushed `onEvent` past
 *       detekt's cyclomatic-complexity ceiling, which was a fair complaint: the split editor is its
 *       own mode, and grouping its events says so.
 * Result: adding a split interaction cannot silently grow the screen's main event handler.
 * Changelog: 2026-08-02 — Created for issue 3.3.
 */
sealed interface SplitEvent : AddTransactionEvent {
    /** The user turned splitting on or off. */
    data class SplitToggled(val isSplit: Boolean) : SplitEvent

    /** The user typed in one line's amount field. */
    data class SplitLineAmountChanged(val index: Int, val value: String) : SplitEvent

    /** The user picked, or cleared, one line's category. */
    data class SplitLineCategorySelected(val index: Int, val categoryId: String?) : SplitEvent

    /** The user added an empty line. */
    data object SplitLineAdded : SplitEvent

    /** The user removed one line. */
    data class SplitLineRemoved(val index: Int) : SplitEvent

    /**
     * The user asked for the parent to be divided equally.
     *
     * The one action that can always produce an exact division, because it goes through
     * `Money.split` — which is what makes ₹1,000 over three lines land as 333.34 / 333.33 / 333.33
     * rather than three amounts that quietly lose a paise.
     */
    data object SplitEvenly : SplitEvent
}

/**
 * Everything the user can do on the transactions list (issue 3.2; ARC-004).
 *
 * Why:  the list held no state and needed no events until FR-TXN-003 required "deleting one side
 *       deletes both" to be something a user can actually do.
 * Result: the list's complete input surface.
 * Changelog: 2026-08-02 — Created for issue 3.2.
 */
sealed interface TransactionsEvent {
    /**
     * The user deleted a row.
     *
     * Carries **a transaction id**, not a transfer id, even for a transfer row — the screen passes
     * the leg it was rendering and the repository decides whether a sibling goes with it. A screen
     * that had to know which case it held would be a screen that can get it wrong.
     */
    data class Delete(val transactionId: String) : TransactionsEvent

    /** The user dismissed the error banner. */
    data object DismissError : TransactionsEvent

    /** The user chose a source chip, or `null` for "All" (issue 3.5; FR-TXN-009). */
    data class SourceFilterSelected(val source: TransactionSource?) : TransactionsEvent

    /**
     * The user tapped a row to see everything about it (issue 3.5).
     *
     * Carries the same id [Delete] does — for a transfer, one leg's — so the screen does not have to
     * know which kind of row it is holding.
     */
    data class RowTapped(val transactionId: String) : TransactionsEvent

    /** The user closed the detail sheet. */
    data object DetailDismissed : TransactionsEvent
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
 * Input:  [isLoading]; [days] — newest day first, each with its rows newest first; [accountNames] —
 *         id → display name, so a transfer row can say "HDFC Savings → Cash Wallet" (issue 3.2);
 *         [errorCode] — an `AppError.code`, never a message.
 * Output: an immutable snapshot for the composable.
 */
@Immutable
data class TransactionsUiState(
    val isLoading: Boolean = true,
    val days: List<TransactionDay> = emptyList(),
    /**
     * The future-dated transactions, soonest day first (issue 3.4; FR-TXN-010).
     *
     * **A separate list, not a flag on a row, and that is the whole design.** [days] holds actuals
     * and nothing else, so [TransactionDay.total] cannot accidentally include a payment that has
     * not happened — there is no filter to forget. The two lists come from two repository flows
     * whose windows abut at today, so a row is in exactly one of them and moves between them on its
     * own date with no write.
     */
    val upcoming: List<TransactionDay> = emptyList(),
    val accountNames: Map<String, String> = emptyMap(),
    /**
     * The source the list is narrowed to, or `null` for all of them (issue 3.5; FR-TXN-009).
     *
     * Applied by the ViewModel **before** grouping, so [TransactionDay.total] recomputes to the
     * filtered rows with no extra code — a day showing three of its five transactions must not still
     * total all five.
     */
    val sourceFilter: TransactionSource? = null,
    /**
     * The sources actually present in the loaded window, in enum order (issue 3.5).
     *
     * **Computed from the *unfiltered* rows.** Deriving it from what is on screen would delete every
     * other chip the moment one was chosen, leaving the user no way back to "All". Enum order rather
     * than order of encounter, so the chips do not rearrange as rows arrive.
     */
    val availableSources: List<TransactionSource> = emptyList(),
    /**
     * The transaction whose detail sheet is open, or `null` (issue 3.5).
     *
     * The whole [Transaction], not an id, so the sheet is a pure function of the state and needs no
     * lookup of its own. The ViewModel resolves the id it was handed — including for a transfer,
     * where the row carries one leg's id.
     */
    val detail: Transaction? = null,
    val errorCode: String? = null,
) {
    /**
     * Whether to show the "nothing yet" line rather than a list.
     *
     * The same three-way distinction [AddTransactionUiState.hasNoAccount] makes and
     * `AccountsUiState.isEmpty` made before it: still loading is not empty, and **a failed read is
     * not empty either** — rendering a database that would not open as a cheerful "no transactions
     * yet" hides the failure from the one user who most needs to see it.
     *
     * **A profile with only scheduled rows is not empty** (issue 3.4): the user has entered
     * something, and telling them they have not would read as the app having lost it.
     *
     * **Nor is a filtered-to-nothing list** (issue 3.5) — `sourceFilter == null` is the fourth
     * clause and it is the same argument a fourth time. Without it the screen rendered *both* this
     * message and [isFilteredEmpty]'s at once, which a test caught: "no transactions yet, tap + to
     * add your first one" is a lie told to a user who has plenty and has merely narrowed the view.
     */
    val isEmpty: Boolean
        get() =
            !isLoading && errorCode == null && sourceFilter == null &&
                days.isEmpty() && upcoming.isEmpty()

    /** Whether the scheduled section has anything to show (issue 3.4). */
    val hasUpcoming: Boolean get() = upcoming.isNotEmpty()

    /**
     * Whether the source filter is worth showing at all (issue 3.5).
     *
     * **Two or more, not one or more.** A real profile today is entirely hand-typed, so a chip row
     * reading "All · Manual" would offer a choice between a thing and the same thing — noise on the
     * screen the user looks at most. It appears when there is genuinely something to separate: a
     * demo dataset, a reconciliation adjustment, and later a receipt or an SMS.
     */
    val hasSourceFilter: Boolean get() = availableSources.size >= MIN_SOURCES_TO_FILTER

    /**
     * Whether a filter is hiding rows the user has (issue 3.5).
     *
     * Distinguishes "this profile has nothing" from "this filter matches nothing" — [isEmpty] would
     * otherwise render a filtered-to-nothing list as the cheerful "no transactions yet" invitation,
     * which is the same failure that made a failed read look empty.
     */
    val isFilteredEmpty: Boolean get() = sourceFilter != null && days.isEmpty() && upcoming.isEmpty()
}

/**
 * The fewest distinct sources that make a filter meaningful (issue 3.5).
 *
 * One source is not a choice. Named rather than inlined so the rule is greppable from the test that
 * pins it.
 */
internal const val MIN_SOURCES_TO_FILTER = 2

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
    val rows: List<TransactionRow>,
) {
    /**
     * The day's net movement: outflows are negative, so this is a signed total, not a spend figure.
     *
     * **A transfer contributes nothing**, which is arithmetically true — its legs are `-X` and `+X` —
     * and is why collapsing the pair into one row does not change this figure (issue 3.2).
     */
    val total: Money get() = rows.fold(Money.ZERO) { running, row -> running + row.netAmount }
}

/**
 * One line in the transactions list (issue 3.2; FR-TXN-003).
 *
 * Why:  FR-TXN-003 calls a transfer "a single logical record ... not two unlinked transactions", so
 *       the list cannot simply render every row the repository returns — a ₹5,000 transfer would
 *       appear twice and read as ₹10,000 of activity. A closed set of row kinds means the screen
 *       handles both and the compiler checks it did.
 * Result: a transfer occupies one line; everything else is unchanged from issue 3.1.
 * Changelog: 2026-08-02 — Created for issue 3.2.
 */
@Immutable
sealed interface TransactionRow {
    /** The id the delete action carries — for a transfer, either leg will do (the store decides). */
    val id: String

    /** What this row contributes to its day's total. Zero for a transfer, by construction. */
    val netAmount: Money

    /**
     * An ordinary one-account movement: an expense, an income, or a balance adjustment.
     *
     * Input: [transaction] — the row as stored. Output: an immutable value.
     */
    @Immutable
    data class Single(val transaction: Transaction) : TransactionRow {
        override val id: String get() = transaction.id
        override val netAmount: Money get() = transaction.amount

        /**
         * How many categories this one amount is attributed across, or `null` when it is not split
         * (issue 3.3).
         *
         * The **amount is unchanged** by splitting — the parent still holds all of it — so the row
         * says how many lines there are rather than showing them, which would repeat the money.
         */
        val splitLineCount: Int? get() = transaction.splits.size.takeIf { transaction.isSplit }
    }

    /**
     * Both legs of a transfer, as one line (FR-TXN-003).
     *
     * **[amount] is positive** — the size of the movement, matching `Transfer`. [netAmount] is zero
     * because the legs cancel, which is what keeps the day total honest when a pair is collapsed.
     *
     * Input:  [transferId] — the shared link; [id] — the leg the delete action names; [outAccountId]
     *         and [inAccountId] — where the money left and arrived; [amount] — positive paise.
     * Output: an immutable value.
     */
    @Immutable
    data class TransferPair(
        val transferId: String,
        override val id: String,
        val outAccountId: String,
        val inAccountId: String,
        val amount: Money,
    ) : TransactionRow {
        override val netAmount: Money get() = Money.ZERO
    }
}
