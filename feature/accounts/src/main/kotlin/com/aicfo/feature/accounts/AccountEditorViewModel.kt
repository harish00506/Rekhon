package com.aicfo.feature.accounts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Account
import com.aicfo.core.model.CreditCard
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.data.repository.AccountDraft
import com.aicfo.data.repository.AccountRepository
import com.aicfo.data.repository.CreditCardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Holds the account editor's state (issue 2.5; ARC-003, ARC-004, FR-ACC-001).
 *
 * Why:  one ViewModel serves both create and edit, because the fields, the validation and the save
 *       are identical and the only difference is whether an id arrived. Two screens would drift:
 *       the day a field is added, one of them would get it.
 * What: exposes [uiState] and handles [AccountEditorEvent]s.
 * Result: creating and editing an account are provably the same code path.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 *
 * **The typed amount is parsed once, here, at save** — `MoneyFormatter.parse` (MNY-001). The state
 * carries text while the user is typing, because `"1."` is a legitimate thing to have on screen and
 * is not an amount. Nothing in the UI layer does money arithmetic.
 *
 * Input:  [repository] — the accounts store; [savedState] — carries the route's `accountId`, which
 *         is `null` when creating. Output: an observable screen state.
 */
@HiltViewModel
class AccountEditorViewModel
    @Inject
    constructor(
        private val repository: AccountRepository,
        private val cards: CreditCardRepository,
        savedState: SavedStateHandle,
    ) : ViewModel() {
        private val accountId: String? = savedState.get<String>(ACCOUNT_ID_KEY)?.takeIf { it.isNotBlank() }

        private val _uiState = MutableStateFlow(AccountEditorUiState(isLoading = accountId != null))

        /**
         * The screen's state.
         * Result: emits the current [AccountEditorUiState] and every update. Read-only to callers.
         */
        val uiState: StateFlow<AccountEditorUiState> = _uiState.asStateFlow()

        init {
            accountId?.let(::load)
        }

        /**
         * Loads the account being edited.
         * Why:    the form must open on the stored values, not on blanks — an editor that opens
         *         empty and saves would silently clear the account's name.
         * Result: fills the fields, or sets `errorCode` when the account is gone.
         * Input:  [id]. Output: none (launches on `viewModelScope`).
         */
        private fun load(id: String) {
            viewModelScope.launch {
                when (val outcome = repository.find(id)) {
                    is Ok -> {
                        // The card's terms are a second read, and only for a card. Folding them into
                        // the same state update rather than emitting twice keeps the form from
                        // flashing an empty card section before it fills (ARC-004).
                        val card = (cards.find(id) as? Ok)?.value
                        _uiState.update { outcome.value.toEditorState().withCard(card) }
                    }
                    is Err -> _uiState.update { it.copy(isLoading = false, errorCode = outcome.error.code) }
                }
            }
        }

        /**
         * Handles something the user did.
         * Why:    one entry point, so the sealed interface's exhaustiveness guarantees no
         *         interaction is silently unhandled.
         * Result: applies the event. Input: [event]. Output: none.
         */
        fun onEvent(event: AccountEditorEvent) {
            when (event) {
                is AccountEditorEvent.NameChanged -> _uiState.update { it.copy(name = event.value) }
                is AccountEditorEvent.TypeChanged -> _uiState.update { it.copy(type = event.value) }
                is AccountEditorEvent.InstitutionChanged -> _uiState.update { it.copy(institution = event.value) }
                is AccountEditorEvent.OpeningBalanceChanged ->
                    _uiState.update { it.copy(openingBalanceText = event.value) }

                is AccountEditorEvent.IncludeInNetWorthChanged ->
                    _uiState.update { it.copy(includeInNetWorth = event.value) }

                is AccountEditorEvent.CardFieldChanged -> _uiState.update { it.withCardField(event.field, event.value) }

                AccountEditorEvent.Save -> save()
                AccountEditorEvent.DismissError -> _uiState.update { it.copy(errorCode = null) }
            }
        }

        /**
         * Writes the form.
         *
         * Why:    the same call for both cases, chosen by whether an id is held. A blank amount
         *         parses to zero rather than failing, because an account opened at zero is ordinary
         *         and refusing it would make the field feel required when it is not — but a *bad*
         *         amount ("12.345", or something that would overflow) is refused, because guessing
         *         what the user meant about money is the one thing this app must never do (P-03).
         * Result: sets `isSaved` so the screen leaves, or `errorCode` and stays.
         * Input:  none. Output: none (launches on `viewModelScope`).
         */
        private fun save() {
            val state = _uiState.value
            if (!state.canSave) return

            val openingBalance = state.parsedOpeningBalance()
            if (openingBalance == null) {
                _uiState.update { it.copy(errorCode = VALIDATION_ERROR_CODE) }
                return
            }

            val draft =
                AccountDraft(
                    name = state.name,
                    type = state.type,
                    openingBalance = openingBalance,
                    currencyCode = DEFAULT_CURRENCY_CODE,
                    institution = state.institution,
                    includeInNetWorth = state.includeInNetWorth,
                )

            _uiState.update { it.copy(isSaving = true, errorCode = null) }
            viewModelScope.launch {
                val outcome = accountId?.let { repository.update(it, draft) } ?: repository.create(draft)
                if (outcome is Err) {
                    _uiState.update { it.copy(isSaving = false, errorCode = outcome.error.code) }
                    return@launch
                }

                // The card's terms are written **after** the account exists, because they are keyed
                // by its id — and on create that id does not exist until the line above returns.
                val savedId = (outcome as Ok).value.id
                val cardOutcome = saveCardTerms(savedId, state)
                _uiState.update {
                    when (cardOutcome) {
                        is Err -> it.copy(isSaving = false, errorCode = cardOutcome.error.code)
                        else -> it.copy(isSaving = false, isSaved = true)
                    }
                }
            }
        }

        /**
         * Writes the card's terms, if this is a card and they are complete.
         *
         * Why:    separate from the account write and after it, because `credit_card` is keyed by
         *         `account_id` — on a create, that id does not exist until the account is stored.
         *
         *         **Silence rather than an error when the fields are blank.** A credit-card account
         *         with no terms yet is a supported state: the user made the account and will fill in
         *         the limit later, and refusing the save would make three optional fields feel
         *         mandatory. `hasCardTerms` is all-or-nothing precisely so a *partial* set cannot
         *         reach here and produce a card that computes nothing.
         * Result: `Ok(Unit)` when there was nothing to write or the write succeeded; the repository's
         *         `Err` otherwise — including `account.notACreditCard`, which is a real answer if the
         *         type was changed away between typing and saving.
         * Input:  [id] — the saved account; [state] — the form. Output: `Result<Unit, AppError>`.
         * Changelog: 2026-08-17 — Created for issue 6.1.
         */
        private suspend fun saveCardTerms(
            id: String,
            state: AccountEditorUiState,
        ): Result<Unit, AppError> {
            if (!state.showsCardFields || !state.hasCardTerms) return Ok(Unit)
            val card = state.toCreditCard(id) ?: return Err(AppError.Validation(VALIDATION_ERROR_CODE))
            return cards.save(card)
        }

        companion object {
            /** The route argument this ViewModel reads. Must match `CfoRoute.AccountEditor`'s property. */
            const val ACCOUNT_ID_KEY = "accountId"

            /**
             * The currency every account is created in for now.
             *
             * P-06 makes this app India-native and the profile carries a currency, but nothing yet
             * lets a user hold a second one — a multi-currency account needs FX rates (§20.1's
             * `fx_rates`) that no issue has built. Hardcoding one here rather than pretending to
             * support many is the honest version; issue 13.x is where it becomes a real choice.
             */
            const val DEFAULT_CURRENCY_CODE = "INR"

            /** The `AppError.Validation` code, so the screen can look up wording in `strings.xml`. */
            const val VALIDATION_ERROR_CODE = "validation"
        }
    }

/**
 * Reads the typed opening balance.
 * Why:    a blank field means zero — an account opened at nothing is ordinary. Anything else goes
 *         through `MoneyFormatter.parse`, which returns `null` for a value it cannot represent
 *         exactly rather than rounding it (MNY-001).
 * Result: the amount, or `null` when the text is not an exactly representable amount.
 * Input:  the receiver. Output: `Money?`.
 * Changelog: 2026-07-28 — Created for issue 2.5.
 */
internal fun AccountEditorUiState.parsedOpeningBalance(): Money? =
    if (openingBalanceText.isBlank()) Money.ZERO else MoneyFormatter.parse(openingBalanceText)

/**
 * Fills the editor from a stored account.
 * Why:    the balance is deliberately **not** carried into the form. It is derived (DB-001) and is
 *         not the user's to set; correcting one is FR-ACC-006's reconciliation flow, which posts an
 *         adjustment transaction rather than mutating the row. Only the opening balance is editable.
 * Result: an [AccountEditorUiState] showing the stored values.
 * Input:  the receiver. Output: [AccountEditorUiState].
 * Changelog: 2026-07-28 — Created for issue 2.5.
 */
internal fun Account.toEditorState(): AccountEditorUiState =
    AccountEditorUiState(
        id = id,
        name = name,
        type = type,
        institution = institution.orEmpty(),
        openingBalanceText = MoneyFormatter.format(openingBalance),
        includeInNetWorth = includeInNetWorth,
        isLoading = false,
    )

/**
 * Fills the card section from stored terms (issue 6.1; FR-ACC-002).
 * Why:    the same argument [toEditorState] makes for the account: an editor that opens blank and
 *         saves would silently clear the card's limit. `null` means the account has no terms yet,
 *         which leaves the section empty rather than zeroed.
 * Result: the state with the card fields filled, or unchanged.
 * Input:  the receiver; [card] — the stored terms, or `null`. Output: [AccountEditorUiState].
 * Changelog: 2026-08-17 — Created for issue 6.1.
 */
internal fun AccountEditorUiState.withCard(card: CreditCard?): AccountEditorUiState =
    if (card == null) {
        this
    } else {
        copy(
            creditLimitText = MoneyFormatter.format(card.creditLimit),
            statementDayText = card.statementDay.toString(),
            dueDayText = card.dueDay.toString(),
            lastStatementText = card.lastStatement?.let(MoneyFormatter::format).orEmpty(),
            minimumDueText = card.minimumDue?.let(MoneyFormatter::format).orEmpty(),
        )
    }

/**
 * Applies one card-field edit (issue 6.1).
 * Why:    the `when` is exhaustive over [CardField], so adding a term to that enum is a compile
 *         error here rather than a field that silently never updates.
 * Result: the state with that one field changed.
 * Input:  the receiver; [field]; [value] — as typed. Output: [AccountEditorUiState].
 * Changelog: 2026-08-17 — Created for issue 6.1.
 */
internal fun AccountEditorUiState.withCardField(
    field: CardField,
    value: String,
): AccountEditorUiState =
    when (field) {
        CardField.LIMIT -> copy(creditLimitText = value)
        CardField.STATEMENT_DAY -> copy(statementDayText = value)
        CardField.DUE_DAY -> copy(dueDayText = value)
        CardField.LAST_STATEMENT -> copy(lastStatementText = value)
        CardField.MINIMUM_DUE -> copy(minimumDueText = value)
    }

/**
 * Parses the card section (issue 6.1; FR-ACC-002, MNY-001).
 *
 * Why:    parsed once, at save, for the reason [parsedOpeningBalance] gives — `"2,00,0"` is a real
 *         intermediate state and re-parsing on every keystroke would fight the user.
 *
 *         **Every failure returns `null` rather than a substituted value.** A limit that will not
 *         parse, a statement day of 45, a negative minimum — each would otherwise become a plausible
 *         card the app computes real advice from. `CreditCard`'s own `require`s would throw on some
 *         of them, and a throw inside a ViewModel is a crash; catching them here turns the whole
 *         section into one honest validation error.
 * Result: the card, or `null` when anything in the section is not exactly representable.
 * Input:  the receiver; [accountId] — the account these terms belong to. Output: `CreditCard?`.
 * Changelog: 2026-08-17 — Created for issue 6.1.
 */
internal fun AccountEditorUiState.toCreditCard(accountId: String): CreditCard? {
    val limit = MoneyFormatter.parse(creditLimitText) ?: return null
    val statementDay = statementDayText.trim().toIntOrNull() ?: return null
    val dueDay = dueDayText.trim().toIntOrNull() ?: return null
    // Blank is "not entered", which is a real state for both. A typed value that will not parse is
    // not — it is a mistake, and substituting null for it would silently discard what was typed.
    val lastStatement = lastStatementText.takeIf { it.isNotBlank() }?.let { MoneyFormatter.parse(it) ?: return null }
    val minimumDue = minimumDueText.takeIf { it.isNotBlank() }?.let { MoneyFormatter.parse(it) ?: return null }

    return runCatching {
        CreditCard(
            accountId = accountId,
            creditLimit = limit,
            statementDay = statementDay,
            dueDay = dueDay,
            lastStatement = lastStatement,
            minimumDue = minimumDue,
        )
    }.getOrNull()
}
