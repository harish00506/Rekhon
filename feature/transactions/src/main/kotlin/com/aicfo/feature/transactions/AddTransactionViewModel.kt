package com.aicfo.feature.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.Clock
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.toAppError
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.data.repository.AccountRepository
import com.aicfo.data.repository.SplitDraft
import com.aicfo.data.repository.SplitLineDraft
import com.aicfo.data.repository.TransactionDraft
import com.aicfo.data.repository.TransactionRepository
import com.aicfo.data.repository.TransferDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Holds the add-transaction screen's state (issue 3.1; FR-TXN-002, ARC-003, ARC-004).
 *
 * Why:  FR-TXN-002 is a MUST with a number in it — one tap to reach, ≤ 3 to complete — and most of
 *       that budget is won or lost here rather than in the composable. **The first account is
 *       preselected the moment the accounts arrive**, so the common path is amount → Save and the
 *       account picker costs nothing. A screen that opened with nothing chosen would be correct,
 *       render identically, and quietly break the requirement.
 * What: exposes [uiState] and handles [AddTransactionEvent]s.
 * Result: a transaction can be captured in two taps, and the tap count is a property of this class.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 *
 * **Two repositories, deliberately.** Accounts come from [AccountRepository] rather than being
 * duplicated onto [TransactionRepository]: "which accounts are pickable" already has one definition
 * (live, unarchived, active profile) and a second would drift from it. Both are interfaces injected
 * by constructor (ARC-003); neither ViewModel nor screen ever sees a Room type (ARC-005).
 *
 * **The typed amount is parsed once, at save** — by `AddTransactionUiState.amount`, which uses
 * `MoneyFormatter.parse` (MNY-001). Nothing in the UI layer does money arithmetic, and the
 * expense/income toggle becomes a *sign* before it crosses into `:data:repository`.
 *
 * **The `Clock` is here and nowhere above it** (issue 3.4). The date picker must not offer a day
 * before today, and "today" is a profile-zone question — TIM-001 makes the injected clock the only
 * sanctioned answer, and `CfoWallClockInDomain` would fail the build on a composable reaching for
 * `LocalDate.now()`. So the bound travels down in the state.
 *
 * Input:  [transactions] — the store that writes; [accounts] — the store the picker reads;
 *         [clock] — today, in the profile zone (issue 3.4).
 * Output: an observable screen state.
 */
@HiltViewModel
class AddTransactionViewModel
    @Inject
    constructor(
        private val transactions: TransactionRepository,
        private val accounts: AccountRepository,
        private val clock: Clock,
    ) : ViewModel() {
        private val _uiState =
            MutableStateFlow(
                AddTransactionUiState(
                    todayInProfileZone = clock.today(),
                    nowInProfileZone = clock.timeOfDay(),
                ),
            )

        /**
         * The screen's state.
         * Result: emits the current [AddTransactionUiState] and every update. Read-only to callers.
         */
        val uiState: StateFlow<AddTransactionUiState> = _uiState.asStateFlow()

        /**
         * The merchant text, as its own stream (issue 4.2).
         *
         * Why:    a `StateFlow` rather than reading [uiState] because the suggestion has to be
         *         *debounced*, and debouncing the whole UI state would delay every keystroke's
         *         echo on screen. This carries only the field the classifier reads.
         */
        private val merchantQuery = MutableStateFlow("")

        init {
            observeChoices()
            observeSuggestions()
        }

        /**
         * Keeps the account and category pickers filled.
         *
         * Why:    `combine` rather than two collectors, because the screen leaves loading when *both*
         *         have answered — an accounts list that arrived first would otherwise render an
         *         empty category row for an instant and, worse, `hasNoAccount` would flash true
         *         before the accounts arrived.
         *
         *         Archived accounts are excluded (the repository's default): FR-ACC-007 keeps a
         *         closed account's history but it is not somewhere new money moves.
         * Result: fills [AddTransactionUiState.accounts] and `categories`, preselects the first
         *         account when nothing is selected yet, and surfaces a read failure as `errorCode`.
         * Input:  none. Output: none (collects on `viewModelScope`).
         */
        private fun observeChoices() {
            combine(accounts.observeAccounts(), transactions.observeCategories()) { accts, cats -> accts to cats }
                .onEach { (accts, cats) ->
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            accounts = accts,
                            categories = cats,
                            // Preselected only while the user has not chosen — re-applying it on
                            // every emission would fight them the moment a balance changes elsewhere
                            // and the list re-emits. `?: firstOrNull` also clears a selection whose
                            // account has since been deleted.
                            selectedAccountId =
                                state.selectedAccountId
                                    ?.takeIf { id -> accts.any { it.id == id } }
                                    ?: accts.firstOrNull()?.id,
                            selectedCategoryId =
                                state.selectedCategoryId?.takeIf { id -> cats.any { it.id == id } },
                            // Re-read on every emission (issue 3.4): a form left open across
                            // midnight would otherwise keep offering yesterday as selectable, and
                            // the repository would refuse the save the picker had just allowed.
                            todayInProfileZone = clock.today(),
                            nowInProfileZone = clock.timeOfDay(),
                        )
                    }
                }
                // `toAppError` rather than the throwable's own message: that message may name a file
                // path, a column or an amount, and P-01 bans all three from anything user-visible.
                .catch { failure ->
                    _uiState.update { it.copy(isLoading = false, errorCode = failure.toAppError().code) }
                }
                .launchIn(viewModelScope)
        }

        /**
         * Keeps the category suggestion in step with the merchant field (issue 4.2; SRS §8.1).
         *
         * Why:    three operators, each load-bearing.
         *         - **`debounce`** because [TransactionRepository.suggestCategory] reads the
         *           database, and asking on every keystroke would run a query per character on the
         *           app's most-used screen for an answer that is thrown away a character later.
         *         - **`distinctUntilChanged`** because the field re-emits on edits that do not
         *           change the trimmed text.
         *         - **`mapLatest`** because it *cancels* the in-flight query when the next pause
         *           arrives. Without it a slow read for `SWIG` could land after a fast one for
         *           `SWIGGY` and pre-select the older answer — a race that is invisible in a test
         *           with an instant repository and reproducible on a real device.
         *
         *         **A failure is swallowed on purpose**, which is the opposite of what
         *         [observeChoices] does with the same kind of error. An empty account list makes the
         *         screen unusable and must be reported; a missing *suggestion* changes nothing the
         *         user cannot do by hand, and an error banner over an optional convenience would
         *         teach them to dismiss banners.
         * Result: sets or clears [AddTransactionUiState.suggestion], and pre-selects the proposed
         *         category **only while the user has not chosen one themselves** (P-07).
         * Input:  none. Output: none (collects on `viewModelScope`).
         * Changelog: 2026-08-10 — Created for issue 4.2.
         */
        @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
        private fun observeSuggestions() {
            merchantQuery
                .debounce(SUGGESTION_DEBOUNCE_MILLIS)
                .distinctUntilChanged()
                .mapLatest { merchant -> transactions.suggestCategory(merchant) }
                .onEach { outcome -> _uiState.update { it.withSuggestion(outcome) } }
                .catch { _uiState.update { it.copy(suggestion = null) } }
                .launchIn(viewModelScope)
        }

        /**
         * Handles something the user did.
         * Why:    one entry point, so the sealed interface's exhaustiveness guarantees no interaction
         *         is silently unhandled.
         * Result: applies the event. Input: [event]. Output: none.
         */
        fun onEvent(event: AddTransactionEvent) {
            when (event) {
                is AddTransactionEvent.AmountChanged -> _uiState.update { it.copy(amountText = event.value) }
                is AddTransactionEvent.DirectionChanged -> _uiState.update { it.withDirection(event.direction) }
                is AddTransactionEvent.AccountSelected -> _uiState.update { it.withSource(event.id) }
                is AddTransactionEvent.DestinationSelected -> _uiState.update { it.copy(toAccountId = event.id) }
                // isCategoryUserChosen, not just the id: from here on the suggestion stops
                // re-applying itself, whether they picked a category or cleared one (issue 4.2, P-07).
                is AddTransactionEvent.CategorySelected ->
                    _uiState.update { it.copy(selectedCategoryId = event.id, isCategoryUserChosen = true) }

                is AddTransactionEvent.SuggestionDismissed ->
                    _uiState.update {
                        it.copy(
                            suggestion = null,
                            isCategoryUserChosen = true,
                            // Clears the chip the suggestion pre-selected — dismissing a proposal
                            // that left its answer behind would dismiss nothing.
                            selectedCategoryId = it.selectedCategoryId.takeIf { id -> id != it.suggestion?.categoryId },
                        )
                    }

                is AddTransactionEvent.MerchantChanged -> {
                    merchantQuery.value = event.value
                    _uiState.update { it.copy(merchant = event.value) }
                }
                is AddTransactionEvent.NoteChanged -> _uiState.update { it.copy(note = event.value) }
                is SplitEvent -> _uiState.update { it.applySplit(event) }
                is ScheduleEvent -> _uiState.update { it.applySchedule(event) }
                AddTransactionEvent.Save -> save()
                AddTransactionEvent.DismissError -> _uiState.update { it.copy(errorCode = null) }
            }
        }

        /**
         * Writes the transaction, or the transfer.
         *
         * Why:    the draft is rebuilt from the state rather than trusting the button's `enabled`
         *         flag — a disabled button is a rendering, and an accessibility service or a fast
         *         double-tap can still deliver the event. [toDraftOrNull] and [toTransferDraftOrNull]
         *         are where every reason not to write is decided. The repository validates again
         *         independently; that is not duplication but the layer boundary doing its job (§5).
         *
         *         The two branches converge immediately: whichever call is made, the outcome is
         *         handled identically, because "did it save?" is the only thing the screen needs to
         *         know (issue 3.2).
         * Result: sets `isSaved` so the screen leaves, or `errorCode` and stays. Never both.
         * Input:  none. Output: none (launches on `viewModelScope`).
         */
        private fun save() {
            val state = _uiState.value
            val write: (suspend () -> Result<Any, com.aicfo.core.common.AppError>)? =
                when {
                    state.isTransfer ->
                        state.toTransferDraftOrNull()?.let { draft -> { transactions.createTransfer(draft) } }

                    state.isSplit ->
                        state.toSplitDraftOrNull()?.let { draft -> { transactions.createSplit(draft) } }

                    else -> state.toDraftOrNull()?.let { draft -> { transactions.create(draft) } }
                }
            if (write == null) return

            _uiState.update { it.copy(isSaving = true, errorCode = null) }
            viewModelScope.launch {
                val outcome = write()
                _uiState.update {
                    when (outcome) {
                        is Ok -> it.copy(isSaving = false, isSaved = true)
                        is Err -> it.copy(isSaving = false, errorCode = outcome.error.code)
                    }
                }
            }
        }
    }

/**
 * Applies a change of direction, clearing what no longer applies (issue 3.2).
 * Why:    the three directions do not share every field, and leaving a stale one set is how a
 *         transfer gets saved with a category the user picked while it was an expense — which
 *         FR-TXN-003 forbids and the repository would silently drop. Switching *away* from a
 *         transfer clears the destination for the same reason.
 * Result: the state with [direction] applied and the now-irrelevant selections cleared.
 * Input:  the receiver; [direction] — what the user chose. Output: [AddTransactionUiState].
 * Changelog: 2026-08-02 — Created for issue 3.2.
 */
internal fun AddTransactionUiState.withDirection(direction: TransactionDirection): AddTransactionUiState =
    copy(
        direction = direction,
        selectedCategoryId = if (direction == TransactionDirection.TRANSFER) null else selectedCategoryId,
        toAccountId = if (direction == TransactionDirection.TRANSFER) toAccountId else null,
    )

/**
 * Applies a change of source account (issue 3.2).
 * Why:    picking the source that is already the destination would leave a transfer pointing at one
 *         account, which the repository refuses. Clearing the destination in that case makes the
 *         form ask again rather than presenting an invalid pair with Save quietly disabled.
 * Result: the state with the new source, and the destination cleared if it collided.
 * Input:  the receiver; [id] — the chosen account. Output: [AddTransactionUiState].
 * Changelog: 2026-08-02 — Created for issue 3.2.
 */
internal fun AddTransactionUiState.withSource(id: String): AddTransactionUiState =
    copy(selectedAccountId = id, toAccountId = toAccountId?.takeIf { it != id })

/**
 * Applies one split interaction to the state (issue 3.3; FR-TXN-004).
 * Why:    every split event resolves to a pure state transition, so they are handled in one
 *         exhaustive `when` here rather than as six more branches of the screen's main `onEvent` —
 *         which is both what detekt's complexity ceiling was objecting to and the honest shape: the
 *         split editor is a mode, and this is its reducer.
 * Result: the state with [event] applied. Input: the receiver; [event]. Output: [AddTransactionUiState].
 * Changelog: 2026-08-02 — Created for issue 3.3.
 */
internal fun AddTransactionUiState.applySplit(event: SplitEvent): AddTransactionUiState =
    when (event) {
        is SplitEvent.SplitToggled -> withSplit(event.isSplit)
        is SplitEvent.SplitLineAmountChanged ->
            withLine(event.index) { it.copy(amountText = event.value) }

        is SplitEvent.SplitLineCategorySelected ->
            withLine(event.index) { it.copy(categoryId = event.categoryId) }

        SplitEvent.SplitLineAdded -> copy(splitLines = splitLines + SplitLineInput())
        is SplitEvent.SplitLineRemoved ->
            copy(splitLines = splitLines.filterIndexed { index, _ -> index != event.index })

        SplitEvent.SplitEvenly -> splitEvenly()
    }

/**
 * Turns splitting on or off (issue 3.3; FR-TXN-004).
 * Why:    turning it **on** seeds [MIN_SPLIT_LINES] empty lines, because an editor that opens with
 *         nothing in it asks the user to discover an "add line" button before it does anything —
 *         and one line is not a split anyway. Turning it **off** clears them rather than keeping
 *         them hidden: a stale set of lines that reappeared later, half-matching a different amount,
 *         is a worse surprise than retyping two figures.
 *
 *         Splitting also clears any category picked on the parent — the lines carry the categories
 *         now, and two answers would contradict each other.
 * Result: the state with [AddTransactionUiState.isSplit] applied and the lines seeded or cleared.
 * Input:  the receiver; [isSplit]. Output: [AddTransactionUiState].
 * Changelog: 2026-08-02 — Created for issue 3.3.
 */
internal fun AddTransactionUiState.withSplit(isSplit: Boolean): AddTransactionUiState =
    copy(
        isSplit = isSplit,
        selectedCategoryId = if (isSplit) null else selectedCategoryId,
        splitLines = if (isSplit) List(MIN_SPLIT_LINES) { SplitLineInput() } else emptyList(),
    )

/**
 * Applies a change to one split line.
 * Why:    one helper for every per-line edit, so an out-of-range index — which a stale recomposition
 *         can genuinely deliver after a line is removed — is ignored in **one** place rather than
 *         crashing the screen from whichever event handler happened to receive it.
 * Result: the state with the line at [index] transformed, or unchanged when there is no such line.
 * Input:  the receiver; [index]; [change] — applied to that line. Output: [AddTransactionUiState].
 * Changelog: 2026-08-02 — Created for issue 3.3.
 */
internal fun AddTransactionUiState.withLine(
    index: Int,
    change: (SplitLineInput) -> SplitLineInput,
): AddTransactionUiState =
    if (index !in splitLines.indices) {
        this
    } else {
        copy(splitLines = splitLines.mapIndexed { at, line -> if (at == index) change(line) else line })
    }

/**
 * Divides the parent equally across the current lines (issue 3.3; FR-TXN-004).
 *
 * Why:    **this is the one action that can always produce an exact division**, because it goes
 *         through `Money.split`, whose largest-remainder rule hands the spare paise to the earliest
 *         parts — ₹1,000 over three lines becomes 333.34 / 333.33 / 333.33, summing to exactly the
 *         parent. Dividing by hand and rounding each part is precisely the "rounding drift"
 *         FR-TXN-004 forbids, and no amount of HALF_EVEN on individual parts would fix it.
 *
 *         Categories are kept: the user has usually chosen what each line is *for* before deciding
 *         the amounts should be equal.
 * Result: every line's text set to its share, leaving the remainder at zero. Unchanged when there is
 *         no amount yet or no lines to divide across.
 * Input:  the receiver. Output: [AddTransactionUiState].
 * Changelog: 2026-08-02 — Created for issue 3.3.
 */
internal fun AddTransactionUiState.splitEvenly(): AddTransactionUiState {
    val total = amount
    if (total == null || splitLines.isEmpty()) return this
    val shares = total.split(splitLines.size)
    return copy(
        splitLines =
            splitLines.mapIndexed { index, line ->
                line.copy(amountText = MoneyFormatter.format(shares[index]).stripCurrency())
            },
    )
}

/**
 * Removes the currency symbol and grouping from a formatted amount.
 * Why:    `MoneyFormatter.format` produces `₹333.34`, which is right for display and wrong to put
 *         back into an input the user then edits — `MoneyFormatter.parse` accepts it, but the field
 *         would read as though the app had typed a currency symbol on their behalf. Stripping to
 *         bare digits keeps the field looking like something a person typed.
 * Result: the amount as plain digits and a decimal point.
 * Input:  the receiver — a formatted amount. Output: [String].
 * Changelog: 2026-08-02 — Created for issue 3.3.
 */
private fun String.stripCurrency(): String = filter { it.isDigit() || it == '.' }

/**
 * Turns the screen's state into a split draft, or refuses (issue 3.3; FR-TXN-004).
 * Why:    the split counterpart of [toDraftOrNull], and the place the **parent's sign is applied to
 *         every line**. The user types unsigned figures into the editor; below this point a line of
 *         an expense is negative like its parent, which is what lets the repository check "the lines
 *         sum to the parent" as one comparison of two signed values.
 * Result: the [SplitDraft] to write, or `null` when the state is not one that should write.
 * Input:  the receiver. Output: `SplitDraft?`.
 * Changelog: 2026-08-02 — Created for issue 3.3.
 */
internal fun AddTransactionUiState.toSplitDraftOrNull(): SplitDraft? {
    if (!canSave) return null
    val signed = signedAmount
    val accountId = selectedAccountId
    val lines = splitLines.map { it.amount }
    return if (signed != null && accountId != null && lines.none { it == null }) {
        SplitDraft(
            accountId = accountId,
            amount = signed,
            lines =
                splitLines.mapIndexed { index, line ->
                    val magnitude = requireNotNull(lines[index])
                    SplitLineDraft(
                        amount = if (signed < Money.ZERO) Money.ZERO - magnitude else magnitude,
                        categoryId = line.categoryId,
                    )
                },
            merchant = merchant,
            note = note,
            // Issue 3.4: on the parent only — the lines divide one amount that moves on one day.
            bookedOn = bookedOn,
            bookedAt = bookedAt,
        )
    } else {
        null
    }
}

/**
 * Turns the screen's state into a transfer draft, or refuses (issue 3.2; FR-TXN-003).
 * Why:    the transfer counterpart of [toDraftOrNull], and it exists for the same reason — one place
 *         that decides not to write. **The amount is positive here**, unlike the signed amount an
 *         expense carries: the two legs' signs are the repository's to apply, and a screen that
 *         guessed one would be answering "signed which way?" for a movement that is both.
 * Result: the [TransferDraft] to write, or `null` when the state is not one that should write.
 * Input:  the receiver. Output: `TransferDraft?`.
 * Changelog: 2026-08-02 — Created for issue 3.2.
 */
internal fun AddTransactionUiState.toTransferDraftOrNull(): TransferDraft? {
    if (!canSave) return null
    val amount = amount
    val from = selectedAccountId
    val to = toAccountId
    return if (amount != null && from != null && to != null) {
        TransferDraft(
            fromAccountId = from,
            toAccountId = to,
            amount = amount,
            note = note,
            // Issue 3.4: both legs take it, so a scheduled transfer cannot straddle two days.
            bookedOn = bookedOn,
            bookedAt = bookedAt,
            // No merchant: a transfer between your own accounts has no payee, which is why
            // `TransferDraft` has no field for one (FR-TXN-003).
        )
    } else {
        null
    }
}

/**
 * Turns the screen's state into a draft, or refuses.
 *
 * Why:    **one place that decides not to write**, so the button's `enabled` state, the double-tap
 *         guard and the amount rule cannot drift apart — and so each of them is assertable without a
 *         ViewModel. Four reasons to refuse:
 *         - already saving, or **already saved**. The second is the one that matters: the write
 *           completes fast enough that a double-tap's second event usually arrives *after*
 *           `isSaving` has gone false again, while the screen is still on its way out. Without this,
 *           one tap too many books the spend twice — money the user never spent, in the one flow
 *           they use most.
 *         - the amount is not an exactly representable non-zero figure (`MoneyFormatter.parse`
 *           returns `null` rather than rounding — guessing about money is the one thing this app
 *           must never do, P-03).
 *         - there is no account to spend from.
 *
 *         **This is where the expense/income toggle becomes a sign** (via
 *         [AddTransactionUiState.amount]), so nothing below the UI layer ever sees the flag.
 * Result: the [TransactionDraft] to write, or `null` when the state is not one that should write.
 * Input:  the receiver. Output: `TransactionDraft?`.
 * Changelog: 2026-08-02 — Created for issue 3.1.
 */
internal fun AddTransactionUiState.toDraftOrNull(): TransactionDraft? {
    // Read into locals so the compiler can smart-cast them past the null checks below.
    // `signedAmount` is null for a transfer, which is what keeps this path from writing one.
    val signedAmount = signedAmount
    val accountId = selectedAccountId
    return if (canSave && signedAmount != null && accountId != null) {
        TransactionDraft(
            accountId = accountId,
            amount = signedAmount,
            categoryId = selectedCategoryId,
            // FR-TXN-001. Blank collapses to null in the repository's `validated()`, so an untouched
            // field is indistinguishable from one that was never offered.
            merchant = merchant,
            note = note,
            // Issue 3.4: `null` means today, which is what every path before it meant (FR-TXN-010).
            bookedOn = bookedOn,
            bookedAt = bookedAt,
        )
    } else {
        null
    }
}
