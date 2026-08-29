package com.aicfo.feature.transactions

import androidx.compose.runtime.Immutable
import com.aicfo.core.model.Account
import com.aicfo.core.model.Category
import com.aicfo.core.model.CategoryNature
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.core.model.Tag
import com.aicfo.core.model.Transaction
import com.aicfo.core.model.TransactionSource
import com.aicfo.data.repository.TransactionFilter
import com.aicfo.domain.engines.nature.NatureVerdict
import com.aicfo.domain.engines.recurring.RecurringSeries
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
     * Today, in the profile zone (issue 3.4).
     *
     * Supplied by the ViewModel from the injected `Clock` rather than read in the composable, and
     * defaulted to the epoch so a preview or a test that does not care renders without a clock. Its
     * real value arrives with the first state emission.
     *
     * **It seeds the picker; it no longer bounds it.** Until ADR-0012 this was
     * `todayInProfileZone` and the picker refused every day before it, because a back-dated row
     * left the frozen net-worth series behind. `NetWorthRepository.repairStaleHistory` fixed the
     * consequence, so the bound went with it — the field is now only where the picker opens and what
     * "Today" means (see `withDate`).
     *
     * **`ofEpochDay(0)`, not `LocalDate.EPOCH`** — that constant is API 34 and this app's minSdk is
     * 26 (NFR-008). Caught by `lintDebug`, not by any test: every unit test runs on the JVM, where
     * the constant exists, so this would have compiled, passed and crashed on a real phone.
     */
    val todayInProfileZone: LocalDate = LocalDate.ofEpochDay(0),
    /**
     * The current time of day in the profile zone, for seeding the time picker (FR-TXN-001).
     *
     * Supplied by the ViewModel from the injected `Clock` for the same reason
     * [todayInProfileZone] is: a composable may not read a clock (TIM-001), and the profile zone
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
    /**
     * What Stage-1 auto-categorisation proposes for [merchant], if anything (issue 4.2; SRS §8.1).
     *
     * **`null` is the ordinary state**, not an error: an unfamiliar merchant proposes nothing and
     * §8.1 ends on the "Uncategorised" prompt, which here is simply the chip row with nothing
     * selected. The screen renders a suggestion as a pre-selected chip plus a line naming the rule
     * that fired (P-02) and a way to dismiss it (P-07).
     */
    val suggestion: CategorySuggestionUi? = null,
    /**
     * Whether the user has taken the category decision themselves (issue 4.2; P-07).
     *
     * Set by dismissing the suggestion **or by picking any category by hand**, and never cleared
     * while the screen is open. It is what stops the next keystroke in the merchant field
     * re-applying a proposal the user has already answered — a suggestion that keeps coming back
     * is not a suggestion, it is an argument.
     */
    val isCategoryUserChosen: Boolean = false,
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

    /** The user dismissed the category suggestion (issue 4.2; P-07). */
    data object SuggestionDismissed : AddTransactionEvent

    /** The user dismissed the error banner. */
    data object DismissError : AddTransactionEvent
}

/**
 * A Stage-1 category proposal, as the screen needs it (issue 4.2; SRS §8.1, P-02).
 *
 * Why:  the engine's `CategorySuggestion` carries a category **id** and an `EngineProvenance`, and
 *       neither is something a composable can render. Resolving the id to a name and pulling the
 *       cited rule out of the provenance happens once, in the ViewModel, so the screen stays a pure
 *       function of its state and the chip label cannot disagree with the chip that is selected.
 *
 *       [ruleId] is carried rather than dropped because P-02 is not decoration here: the app is
 *       filing the user's money somewhere they did not ask it to, and "why" has to have an answer
 *       more specific than "the app guessed". It is shown verbatim — `CLS-MER-001` — which is ugly
 *       and is the point: it is a citation into `ai/knowledge/classification-kb.json`, and a
 *       prettier paraphrase would not be one.
 * Result: everything the suggestion row renders, with nothing left to look up.
 * Changelog: 2026-08-10 — Created for issue 4.2.
 *
 * Input:  [categoryId] — the category being proposed, live on this profile; [categoryName] — its
 *         display name, resolved when the suggestion arrived; [ruleId] — the `CLS-*` row that
 *         fired. Output: an immutable value.
 */
@Immutable
data class CategorySuggestionUi(
    val categoryId: String,
    val categoryName: String,
    val ruleId: String,
)

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
     * Carries the **transaction**, not its id (issue 3.6). It used to carry an id that the ViewModel
     * resolved against a held snapshot of the whole list — and with paging there is no such
     * snapshot: the loaded pages are not the ledger. Every row already knows the transaction it was
     * built from, so handing it over removes the lookup rather than replacing it with a query.
     */
    data class RowTapped(val transaction: Transaction) : TransactionsEvent

    /** The user closed the detail sheet. */
    data object DetailDismissed : TransactionsEvent

    /**
     * FR-OCR-005: delete the image, keep the transaction (issue 3.8).
     *
     * Carries the attachment id rather than reading it from the open sheet, so the event says
     * exactly what it removes — and cannot remove a different receipt if the sheet has moved on.
     */
    data class ReceiptDeleted(val attachmentId: String) : TransactionsEvent

    /**
     * The user corrected what this money became, or withdrew a correction (issue 4.3; §8.3, P-07).
     *
     * **`null` is a real choice, not an absence.** It withdraws the override and hands the
     * transaction back to §8.3.1's decision order — which is the only reason the app can offer
     * "actually, use the rules" as an option at all.
     */
    data class NatureOverridden(val nature: CategoryNature?) : TransactionsEvent

    /** The user typed in the search field (issue 3.6; FR-TXN-007). */
    data class SearchChanged(val query: String) : TransactionsEvent
}

/**
 * The four things a user can do to the filter sheet (issue 3.6; FR-TXN-007).
 *
 * Why:  a nested sealed interface rather than four more members of [TransactionsEvent] directly, for
 *       the reason [SplitEvent] gives on the add screen: they arrive together, they are handled
 *       together in one `is FilterEvent ->` branch, and flattening them into the parent pushes
 *       `onEvent` past detekt's cyclomatic-complexity ceiling. Filtering is its own small mode.
 * Result: adding a facet cannot silently grow the list's main event handler.
 * Changelog: 2026-08-04 — Created for issue 3.6.
 */
sealed interface FilterEvent : TransactionsEvent {
    /** The user opened the filter sheet. */
    data object Opened : FilterEvent

    /** The user closed it. */
    data object Dismissed : FilterEvent

    /**
     * The user changed a facet.
     *
     * Carries **the whole filter**, not one field, so the ViewModel has nothing to merge and the
     * sheet cannot apply one facet against a copy of the state that has since moved on. The search
     * text is deliberately not in here — it has its own event, because it changes on every keystroke
     * while the sheet is shut.
     */
    data class Changed(val filter: TransactionFilter) : FilterEvent

    /** The user cleared every facet at once. */
    data object Cleared : FilterEvent
}

/**
 * The seven things a user can do in selection mode (issue 3.6; FR-TXN-008).
 *
 * Why:  grouped for the same reason [FilterEvent] is. Multi-select is a mode the screen enters and
 *       leaves, and its actions are meaningless outside it.
 * Result: adding a bulk action cannot silently grow the list's main event handler.
 * Changelog: 2026-08-04 — Created for issue 3.6.
 */
sealed interface BulkEvent : TransactionsEvent {
    /**
     * The user long-pressed a row, or tapped one while already selecting.
     *
     * A toggle rather than separate select/deselect events: the screen would otherwise have to know
     * whether the row is already selected, which is state it does not hold.
     */
    data class Toggled(val transactionId: String) : BulkEvent

    /** The user left selection mode. */
    data object Cleared : BulkEvent

    /** The user chose a category for the selection, or `null` to clear it. */
    data class Recategorise(val categoryId: String?) : BulkEvent

    /** The user set the selection's tags. An empty list removes every tag. */
    data class Retag(val tagNames: List<String>) : BulkEvent

    /** The user deleted the selection. */
    data object Delete : BulkEvent

    /** The user tapped Undo on the snackbar. */
    data object Undo : BulkEvent

    /** The snackbar went away without being tapped. */
    data object UndoDismissed : BulkEvent
}

/**
 * The two answers a user can give a proposed series (issue 3.7; FR-TXN-006).
 *
 * Why:  grouped for the same reason [FilterEvent] and [BulkEvent] are — they arrive together, they
 *       are handled in one `is RecurringEvent ->` branch, and flattening them into the parent pushes
 *       `onEvent` past detekt's cyclomatic-complexity ceiling.
 *
 *       **Both carry the whole [RecurringSeries]**, not a merchant name. The repository writes the
 *       amount, cadence and next-due date the engine derived, and a screen that passed back only an
 *       identifier would force a second lookup against a list that has since re-emitted.
 * Result: adding a third answer cannot silently grow the list's main event handler.
 * Changelog: 2026-08-05 — Created for issue 3.7.
 */
sealed interface RecurringEvent : TransactionsEvent {
    /** The user accepted the proposal — FR-TXN-006's "user confirms to create a Recurring Rule". */
    data class Confirm(val series: RecurringSeries) : RecurringEvent

    /** The user said it is not recurring. Recorded, so it is never proposed again (P-07). */
    data class Dismiss(val series: RecurringSeries) : RecurringEvent
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
 * **The paged rows are not in here, and that is deliberate** (issue 3.6). `PagingData` is a stream
 * of load events, not a value: putting it in an immutable snapshot would make every `copy` of this
 * class restart the list. The ViewModel exposes it as a second flow, and this class holds everything
 * *around* the rows — what the user has narrowed to, what they have selected, and what they can undo.
 *
 * Input:  [isLoading]; [accountNames] — id → display name, so a transfer row can say "HDFC Savings →
 *         Cash Wallet" (issue 3.2); [errorCode] — an `AppError.code`, never a message.
 * Output: an immutable snapshot for the composable.
 */
@Immutable
data class TransactionsUiState(
    val isLoading: Boolean = true,
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
     * Everything the list is narrowed by (issue 3.6; FR-TXN-007).
     *
     * Held whole rather than as ten fields, because the ViewModel turns it straight into a query:
     * one value here is one query below, and a screen that assembled facets separately could show a
     * chip the query never saw.
     */
    val filter: TransactionFilter = TransactionFilter(),
    /** Whether the filter sheet is open (issue 3.6). Part of the state so a test can drive it. */
    val isFilterSheetOpen: Boolean = false,
    /**
     * The sources present anywhere in the ledger, in enum order (issue 3.5, reworked in 3.6).
     *
     * **Read from the database, not derived from the rows on screen.** Issue 3.5 derived it from the
     * loaded window, which was right while the window *was* everything; with paging, "loaded" is the
     * first page, so chips built from it would appear and vanish as the user scrolled.
     */
    val availableSources: List<TransactionSource> = emptyList(),
    /** The tags the profile has, name-ordered (issue 3.6). Empty is the normal state. */
    val availableTags: List<Tag> = emptyList(),
    /** The categories a bulk recategorise can choose from (issue 3.6; FR-TXN-008). */
    val categories: List<Category> = emptyList(),
    /**
     * The rows the user has selected, by id (issue 3.6; FR-TXN-008).
     *
     * **A `Set`, so a row cannot be selected twice** — the toggle is what the screen sends, and a
     * list would grow a duplicate on any event the UI managed to deliver twice.
     */
    val selection: Set<String> = emptySet(),
    /** Whether a bulk operation is in flight (issue 3.6), so the action bar can refuse a second. */
    val isBulkRunning: Boolean = false,
    /**
     * What the undo snackbar can put back, or `null` when there is nothing to undo (FR-TXN-008).
     *
     * Holds the ids the repository said it **actually** removed, which for a transfer is more than
     * the user selected — restoring only the selection would bring back one leg of a transfer and
     * leave the money it moved in one account with no counterpart.
     */
    val undo: UndoBatch? = null,
    /**
     * The transaction whose detail sheet is open, or `null` (issue 3.5).
     *
     * The whole [Transaction], not an id, so the sheet is a pure function of the state and needs no
     * lookup of its own. The ViewModel resolves the id it was handed — including for a transfer,
     * where the row carries one leg's id.
     */
    val detail: Transaction? = null,
    /**
     * What §8.3.1 decided the open transaction's money became, or `null` (issue 4.3; §8.3).
     *
     * **`null` means "not worked out yet", not "no nature"** — every transaction has one (§8.3), and
     * the verdict arrives a moment after the sheet does because deciding it needs five joins. The
     * sheet renders the section only once it is here, so an opening sheet never flashes a nature it
     * is about to replace.
     *
     * The whole verdict rather than the nature alone, because the rule that fired and the
     * review flag are what the section is *for* (P-02): "Need, because of the category" and "Need,
     * but this is five times your usual" are different things to tell someone.
     */
    val detailNature: NatureVerdict? = null,
    /**
     * The receipt attached to the open transaction, decrypted for display, or `null` (issue 3.8;
     * FR-OCR-005).
     *
     * **The plaintext lives only as long as the sheet does.** It is not written to a file, not
     * cached and not logged — the one durable copy is the encrypted blob the repository keeps
     * (P-01). `null` covers three cases the sheet renders identically: the transaction was typed, the
     * image has been deleted, or it has not been decrypted yet.
     */
    val detailReceipt: ReceiptImage? = null,
    /**
     * The recurring series the detector is proposing (issue 3.7; FR-TXN-006).
     *
     * **Proposals, not rules.** Nothing here exists in the database yet; each one is a pattern the
     * engine found in rows the user already entered, waiting for the confirmation FR-TXN-006
     * requires (P-07). Empty is the normal state — a new profile has no series, and the section
     * renders nothing rather than an empty-state apology.
     */
    val suggestions: List<RecurringSeries> = emptyList(),
    val errorCode: String? = null,
) {
    /** Whether the user is picking rows for a bulk action (issue 3.6; FR-TXN-008). */
    val isSelecting: Boolean get() = selection.isNotEmpty()

    /**
     * Category names by id, for a row that has nothing else to call itself (issue 4.1).
     *
     * Why: the list row's title falls back note → merchant → category → "Uncategorised", and until
     *      4.1 seeded a taxonomy the third step could not exist — no real profile had a category to
     *      attach, so "Uncategorised" was true by construction. It stopped being true the moment the
     *      seed landed, and the row went on saying it. Derived from [categories], which is already
     *      loaded for the bulk recategorise picker, rather than adding a second read of the same
     *      table.
     */
    val categoryNames: Map<String, String> get() = categories.associate { it.id to it.name }

    /** Whether the scheduled section has anything to show (issue 3.4). */
    val hasUpcoming: Boolean get() = upcoming.isNotEmpty()

    /**
     * Whether the recurring section has anything to propose (issue 3.7; FR-TXN-006).
     *
     * **Hidden while selecting**, because a proposal is not part of the selection a bulk action
     * operates on, and offering a Confirm button beside an action bar counting rows would invite the
     * user to think the two are related.
     */
    val hasSuggestions: Boolean get() = suggestions.isNotEmpty() && !isSelecting

    /** The search text, so the field is a pure function of the state (issue 3.6). */
    val searchQuery: String get() = filter.query.orEmpty()

    /**
     * Whether the source filter is worth showing at all (issue 3.5).
     *
     * **Two or more, not one or more.** A real profile today is entirely hand-typed, so a chip row
     * reading "All · Manual" would offer a choice between a thing and the same thing — noise on the
     * screen the user looks at most. It appears when there is genuinely something to separate: a
     * demo dataset, a reconciliation adjustment, and later a receipt or an SMS.
     */
    val hasSourceFilter: Boolean get() = availableSources.size >= MIN_SOURCES_TO_FILTER
}

/**
 * A bulk delete the user can still take back (issue 3.6; FR-TXN-008).
 *
 * Why:  the snackbar has to say how many rows went and be able to put back exactly those rows. The
 *       two are different numbers when a transfer is involved — deleting one leg takes both — so the
 *       count the user is shown is the **selection** they made while the ids are what the repository
 *       actually removed. Reporting "2 deleted" for a one-row selection would be alarming and true;
 *       reporting "1" and restoring 2 is what the user meant.
 * What: what to say, and what to restore.
 * Result: undo is exact rather than approximately right.
 * Changelog: 2026-08-04 — Created for issue 3.6 (FR-TXN-008).
 *
 * Input:  [ids] — every id the repository removed, siblings included; [selectedCount] — how many
 *         rows the user picked, which is what the snackbar counts.
 * Output: an immutable value.
 */
@Immutable
data class UndoBatch(
    val ids: List<String>,
    val selectedCount: Int,
)

/**
 * The fewest distinct sources that make a filter meaningful (issue 3.5).
 *
 * One source is not a choice. Named rather than inlined so the rule is greppable from the test that
 * pins it.
 */
internal const val MIN_SOURCES_TO_FILTER = 2

/**
 * One entry in the paged list: a day header or a transaction (issue 3.6; FR-TXN-007).
 *
 * Why:  FR-TXN-007 asks for "grouping by day with daily totals" **and** paging, and the two pull in
 *       opposite directions — grouping wants a whole day at once, paging delivers a fixed number of
 *       rows. `PagingData.insertSeparators` is how Paging reconciles them: the headers are inserted
 *       into the stream as the pages arrive, so a day that straddles a page boundary gets exactly
 *       one header rather than two or none.
 * What: the closed set of things the list can render.
 * Result: adding a third kind of entry is a compile error until the screen handles it.
 * Changelog: 2026-08-04 — Created for issue 3.6.
 */
@Immutable
sealed interface TransactionListItem {
    /** The stable list key, so a page arriving does not re-render or re-order what is above it. */
    val key: String

    /**
     * A day's date and its net total (FR-TXN-007).
     *
     * **[total] comes from the database, not from the rows below it.** A page boundary can fall
     * inside a day, so a total folded from what is loaded would be short until the user scrolled.
     *
     * Input: [isoDate] — the profile-zone day (TIM-002); [total] — the day's signed net movement.
     */
    @Immutable
    data class DayHeader(
        val isoDate: String,
        val total: Money,
    ) : TransactionListItem {
        override val key: String get() = "day:$isoDate"
    }

    /**
     * One transaction, ordinary or a whole transfer.
     *
     * Input: [row] — the rendering model; [isSelected] — whether it is in the bulk selection.
     */
    @Immutable
    data class Row(
        val row: TransactionRow,
        val isSelected: Boolean = false,
    ) : TransactionListItem {
        override val key: String get() = "txn:${row.id}"
    }
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
    /**
     * The transaction this row was built from (issue 3.6).
     *
     * On the interface rather than only on [Single], so the detail sheet can be opened from any row
     * without the screen branching on which kind it holds. For a transfer it is the leg the query
     * returned — which is the row on screen, and either leg identifies the pair to the store.
     */
    val transaction: Transaction

    /** The id the delete action carries — for a transfer, either leg will do (the store decides). */
    val id: String get() = transaction.id

    /** What this row contributes to its day's total. Zero for a transfer, by construction. */
    val netAmount: Money

    /**
     * An ordinary one-account movement: an expense, an income, or a balance adjustment.
     *
     * Input: [transaction] — the row as stored. Output: an immutable value.
     */
    @Immutable
    data class Single(override val transaction: Transaction) : TransactionRow {
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
     * Input:  [transferId] — the shared link; [transaction] — the leg the query returned, which is
     *         also the id the delete action names; [outAccountId] and [inAccountId] — where the
     *         money left and arrived; [amount] — positive paise.
     * Output: an immutable value.
     */
    @Immutable
    data class TransferPair(
        val transferId: String,
        override val transaction: Transaction,
        val outAccountId: String,
        val inAccountId: String,
        val amount: Money,
    ) : TransactionRow {
        override val netAmount: Money get() = Money.ZERO
    }
}

/**
 * A decrypted receipt, held only while its sheet is open (issue 3.8; FR-OCR-005, P-01).
 *
 * Why:    the id travels with the bytes because the delete action needs it, and a plain `class`
 *         rather than a `data class` because a generated `equals` over a [ByteArray] compares
 *         references — a trap dressed as a convenience. The state class holding this is compared on
 *         every update, so identity comparison is also the behaviour that is wanted: the same
 *         instance is the same receipt.
 * Result: the type of [TransactionsUiState.detailReceipt].
 * Changelog: 2026-08-06 — Created for issue 3.8.
 *
 * Input:  [attachmentId] — the row this came from; [bytes] — the decrypted JPEG.
 * Output: an immutable value.
 */
class ReceiptImage(
    val attachmentId: String,
    val bytes: ByteArray,
)
