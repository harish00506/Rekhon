package com.aicfo.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.flatMap
import com.aicfo.core.common.toAppError
import com.aicfo.core.crypto.PinVerifier
import com.aicfo.core.datastore.AppLockStore
import com.aicfo.core.datastore.ConsentFeature
import com.aicfo.core.datastore.ConsentStore
import com.aicfo.core.datastore.SettingsStore
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.data.repository.BackupRepository
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
 * Holds the settings screen's state (FR-SET-001; ARC-003, ARC-004, P-01).
 *
 * Why:  three settings — monthly income, the per-feature consents and the app lock — were writable
 *       only by `:feature:onboarding`, which pops itself `inclusive = true` and is never reachable
 *       again. That froze all three at whatever was chosen in the first minute of the app's life.
 *       The consent case is the serious one: **P-01 requires consent to be explicit, revocable and
 *       per-feature**, and revocation was implemented in the data layer but had no way to be
 *       triggered. This ViewModel is that way.
 *
 *       **Every write goes through a store or a coordinator, never through a DAO** (ARC-005), and
 *       the money plan is derived by the quick-setup engine rather than split here (P-03).
 * What: exposes [uiState] and handles [SettingsEvent]s.
 * Result: a screen whose every state is reachable and assertable without a device.
 * Changelog: 2026-08-29 — Created for FR-SET-001.
 *   2026-09-18 — Issue 8.1: the encrypted backup.
 *
 * Input:  [settingsStore] — the seeds this screen prefills from; [consentStore] — the ledger;
 *         [appLockStore] — the lock flag; [pinVerifier] — writes the PIN before the flag (SEC-002);
 *         [moneyPlan] — derives and persists the envelope plan; [backupRepository] — seals the
 *         encrypted backup (issue 8.1).
 * Output: an observable screen state.
 */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val settingsStore: SettingsStore,
        private val consentStore: ConsentStore,
        private val appLockStore: AppLockStore,
        private val pinVerifier: PinVerifier,
        private val moneyPlan: MoneyPlanWriter,
        private val backupRepository: BackupRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(SettingsUiState())

        /** The screen's state; every field a `val`, replaced wholesale on each emission (ARC-004). */
        val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

        init {
            observeSettings()
            observeConsents()
            observeAppLock()
        }

        /**
         * Handles one event from the screen.
         * Why:    one entry point, so a control added later cannot reach a store directly.
         * Result: the state advances, and a write is launched where the event asks for one.
         * Input:  [event]. Output: none.
         * Changelog: 2026-08-29 — Created for FR-SET-001.
         */
        fun onEvent(event: SettingsEvent) {
            when (event) {
                is SettingsEvent.MonthlyIncomeChanged ->
                    _uiState.update { it.copy(monthlyIncomeText = event.value, fieldError = null) }

                is SettingsEvent.RentOrEmiChanged -> _uiState.update { it.copy(rentOrEmiText = event.value) }
                is SettingsEvent.TypicalSavingsChanged -> _uiState.update { it.copy(typicalSavingsText = event.value) }
                SettingsEvent.SaveMoneyPlan -> saveMoneyPlan()
                is SettingsEvent.ConsentToggled -> toggleConsent(event.feature, event.granted)
                is SettingsEvent.PinChanged -> _uiState.update { it.copy(pinText = event.value, fieldError = null) }
                is SettingsEvent.AppLockToggled -> toggleAppLock(event.enabled)
                SettingsEvent.DismissError -> _uiState.update { it.copy(errorCode = null, fieldError = null) }
                is SettingsEvent.Backup -> onBackupEvent(event)
            }
        }

        /**
         * Handles the encrypted backup's events (issue 8.1; SEC-005).
         * Why:    split from [onEvent] so each stays readable, and so the backup flow is one unit.
         * Result: the backup form advances, or a backup is sealed.
         * Input:  [event]. Output: none.
         * Changelog: 2026-09-18 — Created for issue 8.1.
         */
        private fun onBackupEvent(event: SettingsEvent.Backup) {
            when (event) {
                is SettingsEvent.BackupPassphraseChanged ->
                    _uiState.updateBackup { it.copy(passphraseText = event.value) }
                is SettingsEvent.BackupConfirmationChanged ->
                    _uiState.updateBackup { it.copy(confirmationText = event.value) }
                is SettingsEvent.BackupAcknowledged -> _uiState.updateBackup { it.copy(acknowledged = event.checked) }
                SettingsEvent.CreateBackup -> createBackup()
                is SettingsEvent.BackupWritten ->
                    _uiState.updateBackup {
                        it.copy(status = if (event.written) BackupStatus.Written else BackupStatus.Failed(WRITE_FAILED))
                    }
                SettingsEvent.BackupDismissed -> _uiState.updateBackup { it.copy(status = BackupStatus.Idle) }
            }
        }

        /**
         * Seals a backup under the typed passphrase (SEC-005, P-01).
         * Why:    **the passphrase leaves the screen state before the seal starts** — it is copied
         *         into a `CharArray` the repository zero-fills, and both fields are cleared in the
         *         same update that shows "sealing". Nothing keeps it after this function.
         *         The blocker is re-checked here rather than trusting the disabled button, so a stale
         *         tap can never reach the repository without the consent.
         * Result: [BackupStatus.ReadyToWrite] with the sealed bytes, or [BackupStatus.Failed].
         * Input:  none (reads [uiState]). Output: none.
         * Changelog: 2026-09-18 — Created for issue 8.1.
         */
        private fun createBackup() {
            val state = _uiState.value
            if (!state.canCreateBackup) return
            val passphrase = state.backup.passphraseText.toCharArray()
            _uiState.update { it.copy(backup = BackupUiState(status = BackupStatus.Sealing)) }
            viewModelScope.launch {
                val status =
                    when (val outcome = backupRepository.create(passphrase)) {
                        is Ok -> BackupStatus.ReadyToWrite(outcome.value)
                        is Err ->
                            BackupStatus.Failed(
                                (outcome.error as? AppError.Validation)?.field ?: outcome.error.code,
                            )
                    }
                _uiState.updateBackup { it.copy(status = status) }
            }
        }

        /**
         * Prefills the three amounts from what is stored.
         * Why:    the screen must show what the user last said, not a blank form — a blank form
         *         invites them to overwrite a good plan with an empty one.
         * Result: [uiState] carries the stored seeds, rendered as plain rupee text.
         * Input:  none. Output: none (collects on `viewModelScope`).
         * Changelog: 2026-08-29 — Created for FR-SET-001.
         */
        private fun observeSettings() {
            settingsStore.observe()
                .onEach { result ->
                    val seeds = (result as? Ok)?.value?.quickSetup
                    _uiState.update { state ->
                        state.copy(
                            monthlyIncomeText = state.monthlyIncomeText.ifBlank { seeds?.monthlyIncome.plain() },
                            rentOrEmiText = state.rentOrEmiText.ifBlank { seeds?.rentOrEmi.plain() },
                            typicalSavingsText = state.typicalSavingsText.ifBlank { seeds?.typicalSavings.plain() },
                            isLoading = false,
                        )
                    }
                }
                .catch { cause -> raise(cause.toAppError()) }
                .launchIn(viewModelScope)
        }

        /**
         * Keeps the consent switches in step with the ledger (P-01).
         * Why:    read from the store rather than held locally, so a revocation made anywhere —
         *         including by a future consents dashboard — is reflected here without a reload.
         * Result: [uiState] carries every feature and whether it is granted.
         * Input:  none. Output: none.
         * Changelog: 2026-08-29 — Created for FR-SET-001.
         */
        private fun observeConsents() {
            consentStore.observeAll()
                .onEach { result ->
                    val granted =
                        (result as? Ok)?.value.orEmpty().mapValues { (_, state) -> state.granted }
                    // Every feature is listed, not only the granted ones: a consent the user has
                    // never touched still needs a switch, and absence is never consent.
                    val all = ConsentFeature.entries.associateWith { granted[it] == true }
                    _uiState.update { state ->
                        // A revoked backup consent also drops a sealed backup still waiting for the
                        // file picker: P-01's revocation stops the data path, including one already
                        // half-way out (issue 8.1).
                        val dropSealed =
                            all[ConsentFeature.CLOUD_BACKUP] != true && state.backup.status is BackupStatus.ReadyToWrite
                        state.copy(
                            consents = all,
                            isLoading = false,
                            backup = if (dropSealed) state.backup.copy(status = BackupStatus.Idle) else state.backup,
                        )
                    }
                }
                .catch { cause -> raise(cause.toAppError()) }
                .launchIn(viewModelScope)
        }

        /** Keeps the lock switch in step with the stored flag. Input: none. Output: none. */
        private fun observeAppLock() {
            appLockStore.observe()
                .onEach { result ->
                    val enabled = (result as? Ok)?.value?.enabled == true
                    _uiState.update { it.copy(appLockEnabled = enabled) }
                }
                .catch { cause -> raise(cause.toAppError()) }
                .launchIn(viewModelScope)
        }

        /**
         * Derives and stores the money plan.
         * Why:    this is what unfreezes the dashboard's needs/wants/savings split and Safe-to-Spend's
         *         preferred income basis — both read envelopes that only onboarding could write.
         * Result: on success the seeds and the envelopes are both stored and the screen confirms it;
         *         on refusal the income field is marked rather than the banner raised, because it is
         *         input the user can fix.
         * Input:  none (reads [uiState]). Output: none.
         * Changelog: 2026-08-29 — Created for FR-SET-001.
         */
        private fun saveMoneyPlan() {
            val state = _uiState.value
            if (!state.canSaveMoney) {
                _uiState.update { it.copy(fieldError = INCOME_FIELD) }
                return
            }
            viewModelScope.launch {
                when (
                    val outcome =
                        moneyPlan.save(state.monthlyIncomeText, state.rentOrEmiText, state.typicalSavingsText)
                ) {
                    is Ok -> _uiState.update { it.copy(savedAtLeastOnce = true, fieldError = null, errorCode = null) }
                    is Err ->
                        if (outcome.error is AppError.Validation) {
                            _uiState.update { it.copy(fieldError = INCOME_FIELD) }
                        } else {
                            raise(outcome.error)
                        }
                }
            }
        }

        /**
         * Grants or revokes one consent (P-01).
         * Why:    the switch is not flipped optimistically. The store is the truth and
         *         [observeConsents] is already collecting it, so the UI moves when the write lands —
         *         which means a failed revocation never leaves a switch claiming the feature is off
         *         while it is still on. For a privacy control that lie is the whole risk.
         * Result: the ledger is updated and the collector re-emits.
         * Input:  [feature]; [granted] — the state the user asked for. Output: none.
         * Changelog: 2026-08-29 — Created for FR-SET-001.
         */
        private fun toggleConsent(
            feature: ConsentFeature,
            granted: Boolean,
        ) {
            viewModelScope.launch {
                val outcome = if (granted) consentStore.grant(feature) else consentStore.revoke(feature)
                if (outcome is Err) raise(outcome.error)
            }
        }

        /**
         * Turns the app lock on or off (SEC-002).
         * Why:    **the PIN is written before the flag, never the other way round** — the ordering
         *         `AppLockSetup` records for onboarding, and for the same reason: a lock switched on
         *         whose `setPin` then failed leaves the user at a prompt no PIN opens. Turning it off
         *         clears the PIN afterwards, so a re-enable cannot silently inherit an old one.
         * Result: the flag moves and the collector re-emits.
         * Input:  [enabled] — the state the user asked for. Output: none.
         * Changelog: 2026-08-29 — Created for FR-SET-001.
         */
        private fun toggleAppLock(enabled: Boolean) {
            val pin = _uiState.value.pinText
            if (enabled && pin.length < SettingsUiState.MIN_PIN_LENGTH) {
                _uiState.update { it.copy(fieldError = PIN_FIELD) }
                return
            }
            viewModelScope.launch {
                val outcome: Result<Unit, AppError> =
                    if (enabled) {
                        pinVerifier.setPin(pin).flatMap { appLockStore.setEnabled(true) }
                    } else {
                        appLockStore.setEnabled(false).flatMap { pinVerifier.clearPin() }
                    }
                when (outcome) {
                    is Ok -> _uiState.update { it.copy(pinText = "", fieldError = null, errorCode = null) }
                    is Err -> raise(outcome.error)
                }
            }
        }

        /** Raises a failure into the banner. Input: [error]. Output: none. */
        private fun raise(error: AppError) {
            _uiState.update { it.copy(errorCode = error.code, isLoading = false) }
        }

        private companion object {
            const val INCOME_FIELD = "monthlyIncome"
            const val PIN_FIELD = "pin"

            /** The picker returned but the bytes did not reach the file. */
            const val WRITE_FAILED = "backup.writeFailed"
        }
    }

/**
 * Replaces the backup form inside the screen state (issue 8.1).
 * Why:    every backup event changes only [SettingsUiState.backup]; one helper keeps each of them a
 *         single line. File-level rather than a method, so the ViewModel's surface stays the flows
 *         it owns (detekt's function budget is a readability limit, and this is plumbing).
 * Result: the state with [transform] applied to its backup form.
 * Input:  the receiver — the screen's mutable state; [transform]. Output: none.
 * Changelog: 2026-09-18 — Created for issue 8.1.
 */
private fun MutableStateFlow<SettingsUiState>.updateBackup(transform: (BackupUiState) -> BackupUiState) {
    update { it.copy(backup = transform(it.backup)) }
}

/**
 * Renders a stored amount as text for a prefilled field.
 * Why:    `MoneyFormatter.format` is what the holding editor prefills with, and
 *         `MoneyFormatter.parse` round-trips it — so the value the user sees is one they could have
 *         typed, and saving without editing cannot change it.
 * Result: the formatted amount, or `""` when it was never supplied (P-03 — absent, not zero, so an
 *         empty field rather than `₹0.00`, which the user would have to notice and delete).
 * Input:  the receiver, `null` when unsupplied. Output: the text.
 * Changelog: 2026-08-29 — Created for FR-SET-001.
 */
private fun Money?.plain(): String = this?.let(MoneyFormatter::format).orEmpty()
