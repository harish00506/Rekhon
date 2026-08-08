package com.aicfo.feature.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.toAppError
import com.aicfo.core.model.Money
import com.aicfo.data.repository.AccountRepository
import com.aicfo.data.repository.SmsDraft
import com.aicfo.data.repository.SmsRepository
import com.aicfo.data.repository.TransactionDraft
import com.aicfo.domain.engines.sms.SmsDirection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Holds the SMS review screen's state (issue 3.9; §18, §23, P-01, P-07, ARC-003, ARC-004).
 *
 * Why:  this is where the parser stops proposing and the user starts deciding. Two rules live here
 *       rather than in the composable so both are assertable without rendering anything: **nothing
 *       becomes a transaction without a tap** (P-07), and **the sign is derived from the alert's
 *       direction rather than typed** — a debit alert can only produce a negative row, so there is
 *       no path by which a misread spend is recorded as income.
 * What: exposes [uiState] and handles [SmsDraftsEvent]s.
 * Result: a bank alert becomes a transaction the user confirmed, tagged `sms` (FR-TXN-009).
 * Changelog: 2026-08-07 — Created for issue 3.9.
 *
 * **No message body ever reaches this class.** The repository hands over conclusions — an amount, a
 * direction, a payee — so there is nothing here that could be logged, put in a saved-state bundle,
 * or rendered into a screenshot test's golden image (P-01).
 *
 * Input:  [sms] — the drafts and the gate; [accounts] — the picker's options, from the same store
 *         the add screen uses so "which accounts are pickable" has one definition.
 * Output: an observable screen state.
 */
@HiltViewModel
class SmsDraftsViewModel
    @Inject
    constructor(
        private val sms: SmsRepository,
        private val accounts: AccountRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(SmsDraftsUiState())

        /** The screen's state. Result: emits the current [SmsDraftsUiState] and every update. */
        val uiState: StateFlow<SmsDraftsUiState> = _uiState.asStateFlow()

        init {
            observeAccess()
            observeDrafts()
            observeAccounts()
        }

        /**
         * Handles something the user did.
         * Why:    one entry point, so the sealed interface's exhaustiveness guarantees no
         *         interaction is silently unhandled.
         * Result: applies the event. Input: [event]. Output: none.
         */
        fun onEvent(event: SmsDraftsEvent) {
            when (event) {
                is SmsDraftsEvent.PermissionResult -> onPermissionResult(event.granted)
                SmsDraftsEvent.Scan -> scan()
                is SmsDraftsEvent.AccountSelected -> _uiState.update { it.copy(selectedAccountId = event.id) }
                is SmsDraftsEvent.AmountEdited -> edit(event.draftId) { it.copy(amountText = event.value) }
                is SmsDraftsEvent.MerchantEdited -> edit(event.draftId) { it.copy(merchantText = event.value) }
                is SmsDraftsEvent.Accept -> accept(event.draftId)
                is SmsDraftsEvent.Dismiss -> dismiss(event.draftId)
                SmsDraftsEvent.DismissError -> _uiState.update { it.copy(errorCode = null) }
            }
        }

        /**
         * Keeps the two permission flags current.
         * Why:    the consent can be revoked from the settings screen and the OS permission from
         *         Android's, both while this screen is open. Observing rather than reading once means
         *         the screen falls back to [SmsDraftsStage.CONSENT_OFF] the moment either goes.
         * Result: fills `access`. Input: none. Output: none (collects on `viewModelScope`).
         */
        private fun observeAccess() {
            sms.observeAccess()
                .onEach { access -> _uiState.update { it.copy(access = access) } }
                .catch { failure -> _uiState.update { it.copy(errorCode = failure.toAppError().code) } }
                .launchIn(viewModelScope)
        }

        /**
         * Keeps the review list current.
         * Why:    the repository emits empty while the consent is off, so this needs no gate of its
         *         own — the one in `SmsRepository` is the only one, which is what stops a future
         *         screen from forgetting it.
         * Result: fills `drafts`. Input: none. Output: none (collects on `viewModelScope`).
         */
        private fun observeDrafts() {
            sms.observePending()
                .onEach { drafts -> _uiState.update { it.copy(drafts = drafts) } }
                .catch { failure -> _uiState.update { it.copy(errorCode = failure.toAppError().code) } }
                .launchIn(viewModelScope)
        }

        /**
         * Keeps the account picker filled.
         * Why:    the same read the add and receipt screens use, so a closed account is unpickable
         *         everywhere (FR-ACC-007) without any screen knowing the rule.
         * Result: fills `accounts` and preselects the first — the common case is one account and the
         *         picker should cost the user nothing.
         * Input:  none. Output: none (collects on `viewModelScope`).
         */
        private fun observeAccounts() {
            accounts.observeAccounts()
                .onEach { available ->
                    _uiState.update { state ->
                        state.copy(
                            accounts = available,
                            selectedAccountId =
                                state.selectedAccountId
                                    ?.takeIf { id -> available.any { it.id == id } }
                                    ?: available.firstOrNull()?.id,
                        )
                    }
                }
                // `toAppError` rather than the throwable's message: that message may name a file
                // path, a column or an amount, and P-01 bans all three from anything user-visible.
                .catch { failure -> _uiState.update { it.copy(errorCode = failure.toAppError().code) } }
                .launchIn(viewModelScope)
        }

        /**
         * Reacts to the system permission dialog.
         * Why:    Android offers no callback when a permission changes, so the screen has to report
         *         what the dialog returned. A grant is followed immediately by a scan, because the
         *         user tapped "allow" in order to see their alerts — making them tap again would be
         *         asking twice for one decision.
         * Result: updates `access` and scans on a grant. Input: [granted]. Output: none.
         */
        private fun onPermissionResult(granted: Boolean) {
            _uiState.update { it.copy(access = it.access.copy(permissionGranted = granted)) }
            if (granted) scan()
        }

        /**
         * Reads the inbox from the stored cursor.
         * Why:    `isScanning` is set before and cleared after whatever happens, including a
         *         failure — a spinner that survives an error is a screen the user has to leave.
         * Result: updates `lastScanFound`, or `errorCode`. Input: none. Output: none.
         */
        private fun scan() {
            _uiState.update { it.copy(isScanning = true, errorCode = null) }
            viewModelScope.launch {
                when (val result = sms.scan()) {
                    is Ok -> _uiState.update { it.copy(isScanning = false, lastScanFound = result.value) }
                    is Err -> _uiState.update { it.copy(isScanning = false, errorCode = result.error.code) }
                }
            }
        }

        /**
         * Applies one keystroke to one draft's fields.
         * Why:    seeded from [SmsDraftsUiState.editFor] rather than from an empty value, so the
         *         first keystroke in the payee field does not wipe the amount the parser read.
         * Result: updates `edits`. Input: [draftId]; [change]. Output: none.
         */
        private fun edit(
            draftId: String,
            change: (SmsDraftEdit) -> SmsDraftEdit,
        ) {
            _uiState.update { state ->
                val draft = state.drafts.firstOrNull { it.id == draftId } ?: return@update state
                state.copy(edits = state.edits + (draftId to change(state.editFor(draft))))
            }
        }

        /**
         * Turns one draft into a transaction (P-07, FR-TXN-009).
         *
         * Why:    **the sign comes from the alert's direction, not from the screen.** A debit alert
         *         can only produce a negative row and a credit alert a positive one, so there is no
         *         path by which a misread spend is recorded as income — the user's choice is whether
         *         to save it at all, and into which account.
         * Result: the draft leaves the list, or `errorCode` is set and it stays pending.
         * Input:  [draftId]. Output: none.
         */
        private fun accept(draftId: String) {
            val state = _uiState.value
            val draft = state.drafts.firstOrNull { it.id == draftId }
            val accountId = state.selectedAccountId
            // The corrected figure if the user typed one, the parser's reading otherwise. Refused
            // rather than defaulted when the field is not a usable amount — saving "what they meant"
            // is exactly the guess this whole feature declines to make. All three checks in one
            // guard so the function keeps to detekt's two-return ceiling (§21.6); each is a state
            // the button is already disabled in, so reaching here at all means a stale tap.
            val magnitude = draft?.let { state.editedAmount(it) }
            if (draft == null || accountId == null || magnitude == null) return
            val merchant = state.editFor(draft).merchantText.trim().ifBlank { null }
            viewModelScope.launch {
                val result =
                    sms.accept(
                        draftId,
                        TransactionDraft(
                            accountId = accountId,
                            amount = draft.signedMagnitude(magnitude),
                            merchant = merchant,
                            bookedOn = draft.bookedOn,
                        ),
                    )
                if (result is Err) _uiState.update { it.copy(errorCode = result.error.code) }
            }
        }

        /**
         * Says no to one draft, for good.
         * Result: the draft leaves the list and no later scan re-proposes it.
         * Input:  [draftId]. Output: none.
         */
        private fun dismiss(draftId: String) {
            viewModelScope.launch {
                val result = sms.dismiss(draftId)
                if (result is Err) _uiState.update { it.copy(errorCode = result.error.code) }
            }
        }
    }

/**
 * The draft's amount with the sign its direction implies (issue 3.9; MNY-001).
 * Why:    the parser returns a positive magnitude because an alert states a direction in words, not
 *         in a sign — so exactly one place must apply it, and this is that place. Written as
 *         `Money(-minor)` rather than `amount * -1` because [Money] has no unary minus and the
 *         multiplication would be a needless overflow check on a value that cannot overflow.
 * Result: negative for a debit, positive for a credit.
 * Input:  the receiver. Output: [Money].
 * Changelog: 2026-08-07 — Created for issue 3.9.
 */
internal fun SmsDraft.signedAmount(): Money = signedMagnitude(amount)

/**
 * A magnitude with the sign this draft's direction implies (issue 3.9; MNY-001).
 * Why:    the user may correct *how much* moved, but never *which way* — the direction is what the
 *         alert stated, and there is no field on the screen that can change it. Taking the magnitude
 *         as an argument rather than reading [SmsDraft.amount] is what lets the edited figure go
 *         through the identical sign rule as the parsed one, instead of a second copy of it.
 * Result: negative for a debit, positive for a credit.
 * Input:  the receiver; [magnitude] — a positive amount. Output: [Money].
 * Changelog: 2026-08-07 — Extracted from [signedAmount] when drafts became editable.
 */
internal fun SmsDraft.signedMagnitude(magnitude: Money): Money =
    when (direction) {
        SmsDirection.DEBIT -> Money(-magnitude.minor)
        SmsDirection.CREDIT -> magnitude
    }
