package com.aicfo.feature.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.toAppError
import com.aicfo.data.repository.AccountRepository
import com.aicfo.data.repository.TransactionDraft
import com.aicfo.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
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
 * Input:  [transactions] — the store that writes; [accounts] — the store the picker reads.
 * Output: an observable screen state.
 */
@HiltViewModel
class AddTransactionViewModel
    @Inject
    constructor(
        private val transactions: TransactionRepository,
        private val accounts: AccountRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(AddTransactionUiState())

        /**
         * The screen's state.
         * Result: emits the current [AddTransactionUiState] and every update. Read-only to callers.
         */
        val uiState: StateFlow<AddTransactionUiState> = _uiState.asStateFlow()

        init {
            observeChoices()
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
         * Handles something the user did.
         * Why:    one entry point, so the sealed interface's exhaustiveness guarantees no interaction
         *         is silently unhandled.
         * Result: applies the event. Input: [event]. Output: none.
         */
        fun onEvent(event: AddTransactionEvent) {
            when (event) {
                is AddTransactionEvent.AmountChanged -> _uiState.update { it.copy(amountText = event.value) }
                is AddTransactionEvent.ExpenseChanged -> _uiState.update { it.copy(isExpense = event.isExpense) }
                is AddTransactionEvent.AccountSelected -> _uiState.update { it.copy(selectedAccountId = event.id) }
                is AddTransactionEvent.CategorySelected -> _uiState.update { it.copy(selectedCategoryId = event.id) }
                is AddTransactionEvent.NoteChanged -> _uiState.update { it.copy(note = event.value) }
                AddTransactionEvent.Save -> save()
                AddTransactionEvent.DismissError -> _uiState.update { it.copy(errorCode = null) }
            }
        }

        /**
         * Writes the transaction.
         *
         * Why:    the draft is rebuilt from the state rather than trusting the button's `enabled`
         *         flag — a disabled button is a rendering, and an accessibility service or a fast
         *         double-tap can still deliver the event. [toDraftOrNull] is where every reason not
         *         to write is decided. The repository validates again independently; that is not
         *         duplication but the layer boundary doing its job (§5).
         * Result: sets `isSaved` so the screen leaves, or `errorCode` and stays. Never both.
         * Input:  none. Output: none (launches on `viewModelScope`).
         */
        private fun save() {
            val draft = _uiState.value.toDraftOrNull() ?: return

            _uiState.update { it.copy(isSaving = true, errorCode = null) }
            viewModelScope.launch {
                val outcome = transactions.create(draft)
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
    val signedAmount = amount
    val accountId = selectedAccountId
    return if (canSave && signedAmount != null && accountId != null) {
        TransactionDraft(
            accountId = accountId,
            amount = signedAmount,
            categoryId = selectedCategoryId,
            note = note,
        )
    } else {
        null
    }
}
