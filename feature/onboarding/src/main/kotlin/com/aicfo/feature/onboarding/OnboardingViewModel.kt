package com.aicfo.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.flatMap
import com.aicfo.core.crypto.PinVerifier
import com.aicfo.core.datastore.AppLockStore
import com.aicfo.core.datastore.ConsentFeature
import com.aicfo.core.datastore.ConsentStore
import com.aicfo.core.datastore.OnboardingProfile
import com.aicfo.core.datastore.QuickSetupSeeds
import com.aicfo.core.datastore.SettingsStore
import com.aicfo.core.model.MoneyFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Holds the first-run onboarding flow's state (issue 2.1; ARC-004, P-01, P-04).
 *
 * Why:  this is the first ViewModel in the app that **writes**. Two rules shape it. Nothing reaches
 *       disk until the user finishes: an abandoned onboarding must leave no half-made profile
 *       behind, so every in-flight answer lives in [SavedStateHandle] instead. And the write itself
 *       is one atomic call, because a profile saved without its time zone would leave every date in
 *       the app resolving in the wrong zone (TIM-001) with nothing left to ask again.
 * What: exposes [uiState] and handles [OnboardingEvent]s, ending in one
 *       `SettingsStore.completeOnboarding` call plus the consent decision.
 * Result: a flow whose every state — including a failed save — is reachable in a unit test.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 *            2026-07-26 — Issue 2.2: the SECURITY step ADR-0002 reserved for it.
 *
 * Nothing here touches the network, and DataStore is local: the whole flow works in airplane mode
 * (P-04).
 *
 * Input:  [settingsStore] — where the profile lands; [consentStore] — the P-01 ledger;
 *         [appLockStore] and [pinVerifier] — the SEC-002 security step (issue 2.2);
 *         [clock] — supplies the device's current zone as the default, so this never reads
 *         `ZoneId.systemDefault()` itself (TIM-001); [savedState] — survives process death.
 * Output: an observable flow state.
 */
@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val settingsStore: SettingsStore,
        private val consentStore: ConsentStore,
        private val appLockStore: AppLockStore,
        private val pinVerifier: PinVerifier,
        clock: Clock,
        private val savedState: SavedStateHandle,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(savedState.restore(defaultZoneId = clock.zone().id))

        /**
         * The flow's state.
         * Result: emits the current [OnboardingUiState] and every update. Read-only to callers —
         *         `asStateFlow()` stops a composable writing to it (ARC-004).
         */
        val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

        /**
         * Whether this session has already written a consent grant.
         *
         * Why:  it lets a user who opts in, hits a failed save, then changes their mind be honoured
         *       exactly. Revoking unconditionally instead would stamp a withdrawal date on a
         *       consent that was never given — a false entry in a ledger whose whole purpose is
         *       being able to answer "since when?" truthfully (P-01).
         */
        private var consentGrantWritten = false

        /**
         * Handles something the user did.
         * Why:    one entry point, so the sealed interface's exhaustiveness guarantees no
         *         interaction is silently unhandled.
         * Result: applies the event, persisting anything that must survive process death.
         * Input:  [event]. Output: none.
         */
        fun onEvent(event: OnboardingEvent) {
            when (event) {
                OnboardingEvent.Next -> advance()
                OnboardingEvent.Back -> moveBy(-1)
                OnboardingEvent.SkipQuickSetup -> finish(withSeeds = false)
                OnboardingEvent.DismissError -> updateState { it.copy(errorCode = null) }
                is OnboardingEvent.Answer -> applyAnswer(event)
            }
        }

        /**
         * Records one edited answer.
         * Why:    split out of [onEvent] when issue 2.2's three new events pushed that function past
         *         detekt's complexity limit. The split is along a real seam rather than an arbitrary
         *         one: these are all "replace one field", while the four events above each *do*
         *         something. Both `when`s stay exhaustive over their own sealed type, so a new event
         *         is still a compile error until it is handled.
         * Result: updates [uiState] and the saved copy.
         * Input:  [event] — the edit. Output: none.
         * Changelog: 2026-07-26 — Extracted for issue 2.2.
         */
        private fun applyAnswer(event: OnboardingEvent.Answer) {
            when (event) {
                is OnboardingEvent.SmsConsentChanged -> updateState { it.copy(smsConsentGranted = event.granted) }
                is OnboardingEvent.DisplayNameChanged -> updateState { it.copy(displayName = event.name) }
                is OnboardingEvent.CurrencyChanged -> updateState { it.copy(currencyCode = event.currencyCode) }
                is OnboardingEvent.TimeZoneChanged -> updateState { it.copy(timeZoneId = event.zoneId) }
                is OnboardingEvent.AppLockToggled -> onAppLockToggled(event.enabled)
                is OnboardingEvent.PinChanged -> updateState { it.copy(pinText = event.pin, errorCode = null) }
                is OnboardingEvent.PinConfirmChanged ->
                    updateState { it.copy(pinConfirmText = event.pin, errorCode = null) }
                is OnboardingEvent.MonthlyIncomeChanged -> updateState { it.copy(monthlyIncomeText = event.text) }
                is OnboardingEvent.RentOrEmiChanged -> updateState { it.copy(rentOrEmiText = event.text) }
                is OnboardingEvent.TypicalSavingsChanged -> updateState { it.copy(typicalSavingsText = event.text) }
            }
        }

        /**
         * Moves on from the current step, or finishes if it is the last.
         * Why:    the security step can refuse (issue 2.2): a lock switched on with no usable PIN
         *         would leave the user with an app nothing opens, on their very first launch. Every
         *         other step always advances, so the guard is expressed once here rather than being
         *         re-derived per step.
         * Result: advances, finishes, or sets the error explaining what is missing.
         * Input:  none. Output: none.
         * Changelog: 2026-07-26 — Extracted for issue 2.2.
         */
        private fun advance() {
            val state = _uiState.value
            if (!state.canAdvance) {
                if (!state.isSaving) updateState { it.copy(errorCode = pinProblem(it)) }
                return
            }
            if (state.step.isLast) finish(withSeeds = true) else moveBy(1)
        }

        /**
         * Turns the app lock on or off during first run (SEC-002, FR-ONB-001 step 3).
         * Why:    switching it back off clears what was typed. A PIN left in memory behind a
         *         disabled toggle is a secret with no remaining purpose, and it would be written at
         *         Finish by any later refactor that forgot to re-check the flag.
         * Result: updates the toggle and, when turning off, blanks both PIN fields.
         * Input:  [enabled]. Output: none.
         * Changelog: 2026-07-26 — Created for issue 2.2.
         */
        private fun onAppLockToggled(enabled: Boolean) {
            updateState { state ->
                if (enabled) {
                    state.copy(appLockEnabled = true, errorCode = null)
                } else {
                    state.copy(appLockEnabled = false, pinText = "", pinConfirmText = "", errorCode = null)
                }
            }
        }

        /**
         * Steps forward or back through the flow.
         * Why:    clamped rather than trusting the caller — a Back on the first step must be a
         *         no-op, not an index the enum has no member for.
         * Result: updates [uiState]; ignored while a save is in flight.
         * Input:  [delta] — `+1` or `-1`. Output: none.
         */
        private fun moveBy(delta: Int) {
            if (_uiState.value.isSaving) return
            val steps = OnboardingStep.entries
            updateState { state ->
                state.copy(step = steps[(state.step.ordinal + delta).coerceIn(steps.indices)])
            }
        }

        /**
         * Writes everything the user answered and completes onboarding.
         *
         * Why:    the consent decision is written **before** the profile so that a failed profile
         *         save leaves nothing marked complete, and the user retries from a clean state. The
         *         profile write carries the completion flag, so it is deliberately last: whatever
         *         fails, the app is never "onboarded" without a time zone.
         * What:   applies the consent decision, then one atomic `completeOnboarding`.
         * Result: `isComplete` on success — the screen navigates on that. On failure, `errorCode`
         *         is set and `isComplete` stays false, because navigating away from a save that did
         *         not happen would strand the user on a dashboard with no profile.
         * Input:  [withSeeds] — `false` when the user skipped the optional quick-setup step, in
         *         which case no seed is written at all rather than a zero (issue 2.3 reads these).
         * Output: none (launches on `viewModelScope`, so it is cancelled with the screen).
         */
        private fun finish(withSeeds: Boolean) {
            val answers = _uiState.value
            if (answers.isSaving) return
            updateState { it.copy(isSaving = true, errorCode = null) }
            viewModelScope.launch {
                val outcome =
                    applyConsentDecision(answers.smsConsentGranted)
                        .flatMap { applyAppLockDecision(answers) }
                        .flatMap { settingsStore.completeOnboarding(answers.toProfile(withSeeds)) }
                updateState { state ->
                    when (outcome) {
                        is Ok -> state.copy(isSaving = false, isComplete = true)
                        is Err -> state.copy(isSaving = false, errorCode = outcome.error.code)
                    }
                }
            }
        }

        /**
         * Records the SMS-parsing decision (FR-ONB-003, P-01).
         * Why:    a decision of "no" needs no write — absence already reads as not granted, and
         *         writing one would be a revocation of something never granted. The exception is a
         *         user who opted in, hit a failed save, and changed their mind before retrying;
         *         [consentGrantWritten] is how that one case is honoured without inventing history.
         * Result: `Ok(Unit)` when there was nothing to write or the write succeeded; `Err` otherwise.
         * Input:  [granted] — the toggle's state. Output: `Result<Unit, AppError>`.
         */
        private suspend fun applyConsentDecision(granted: Boolean): Result<Unit, AppError> =
            when {
                granted -> consentStore.grant(ConsentFeature.SMS_PARSING).also { consentGrantWritten = it is Ok }
                consentGrantWritten -> consentStore.revoke(ConsentFeature.SMS_PARSING)
                else -> Ok(Unit)
            }

        /**
         * Sets the PIN and enables the lock, if the user asked for one (SEC-002).
         * Why:    **the PIN is written before the lock is enabled, never the other way round.** A
         *         lock switched on whose `setPin` then failed would leave the user staring at a
         *         prompt no PIN opens, on an app they have just finished setting up. This ordering
         *         makes the worst case a lock that is off — which they can simply turn on again.
         * What:   `setPin` then `setEnabled(true)`, or nothing at all when the step was declined.
         * Result: `Ok(Unit)` when there was nothing to do or both writes succeeded; the first
         *         failure otherwise, which stops `finish` before anything is marked complete.
         * Input:  [answers] — the captured state. Output: `Result<Unit, AppError>`.
         * Changelog: 2026-07-26 — Created for issue 2.2.
         */
        private suspend fun applyAppLockDecision(answers: OnboardingUiState): Result<Unit, AppError> =
            if (!answers.appLockEnabled) {
                Ok(Unit)
            } else {
                pinVerifier.setPin(answers.pinText).flatMap { appLockStore.setEnabled(true) }
            }

        /**
         * Applies a change and keeps the saved copy in step.
         * Why:    persisting in one place means a new field cannot be added to the state and
         *         silently left out of what survives process death.
         * Result: updates [uiState] and [savedState]. Input: [transform]. Output: none.
         */
        private fun updateState(transform: (OnboardingUiState) -> OnboardingUiState) {
            _uiState.update { current -> transform(current).also(savedState::persist) }
        }
    }

/**
 * Converts the typed answers into what the store writes.
 * Why:    the one place text becomes [com.aicfo.core.model.Money] (MNY-001) — the screen shows
 *         text, the store takes exact paise, and `MoneyFormatter.parse` is the only bridge. An
 *         unparseable or skipped field becomes `null`, never ₹0: issue 2.3 seeds budgets from these
 *         and "I did not say" must not arrive as "I earn nothing".
 * Result: an [OnboardingProfile] ready for one atomic write.
 * Input:  the receiver; [withSeeds] — `false` when the optional step was skipped.
 * Output: [OnboardingProfile].
 * Changelog: 2026-07-25 — Created for issue 2.1.
 */
internal fun OnboardingUiState.toProfile(withSeeds: Boolean): OnboardingProfile =
    OnboardingProfile(
        timeZoneId = timeZoneId,
        currencyCode = currencyCode,
        displayName = displayName.trim(),
        quickSetup =
            if (!withSeeds) {
                QuickSetupSeeds()
            } else {
                QuickSetupSeeds(
                    monthlyIncome = MoneyFormatter.parse(monthlyIncomeText),
                    rentOrEmi = MoneyFormatter.parse(rentOrEmiText),
                    typicalSavings = MoneyFormatter.parse(typicalSavingsText),
                )
            },
    )

/**
 * Names why the security step will not advance (issue 2.2).
 * Why:    the two cases need different wording — "that is too short" and "those do not match" are
 *         not the same instruction — and the ViewModel deals only in codes so the wording stays in
 *         `strings.xml` (§21.6).
 * Result: an error code, or `null` when there is nothing wrong.
 * Input:  [state] — the current answers. Output: `String?`.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 */
private fun pinProblem(state: OnboardingUiState): String? =
    when {
        !state.appLockEnabled -> null
        !state.isPinWellFormed -> ERROR_PIN_TOO_SHORT
        !state.pinsMatch -> ERROR_PIN_MISMATCH
        else -> null
    }

/** The typed PIN is not 4–6 digits, so `TinkPinVerifier` would reject it. */
const val ERROR_PIN_TOO_SHORT: String = "pin_too_short"

/** The two PIN fields differ — almost always a typo, and worth catching before it is stored. */
const val ERROR_PIN_MISMATCH: String = "pin_mismatch"

/**
 * Saves the answers so far.
 * Why:    process death mid-onboarding must not cost the user their answers — they are several
 *         screens in and nothing has reached disk yet by design.
 * Result: writes each field to [SavedStateHandle]. Input: [state]. Output: none.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 *            2026-07-26 — Issue 2.2: the app-lock toggle is saved; the **PIN deliberately is not**.
 *
 * **The PIN is not written here, and that is the point.** This bundle is persisted by the platform,
 * so a PIN in it would be a plaintext credential on disk — which is exactly what verifying against
 * a Keystore-bound MAC exists to avoid. Losing a half-typed PIN to process death is the right trade.
 */
private fun SavedStateHandle.persist(state: OnboardingUiState) {
    this[KEY_STEP] = state.step.name
    this[KEY_APP_LOCK] = state.appLockEnabled
    this[KEY_SMS_CONSENT] = state.smsConsentGranted
    this[KEY_DISPLAY_NAME] = state.displayName
    this[KEY_CURRENCY] = state.currencyCode
    this[KEY_TIME_ZONE] = state.timeZoneId
    this[KEY_INCOME] = state.monthlyIncomeText
    this[KEY_RENT] = state.rentOrEmiText
    this[KEY_SAVINGS] = state.typicalSavingsText
}

/**
 * Rebuilds the answers after process death.
 * Why:    an unknown step name — written by an older or newer build — falls back to the first step
 *         rather than throwing, because a crash on relaunch is far worse than repeating a welcome
 *         screen. `isSaving` and `isComplete` are deliberately **not** restored: a save that was
 *         in flight when the process died did not finish, and restoring "complete" would skip a
 *         profile that was never written.
 * Result: the restored state, or a fresh one.
 * Input:  the receiver; [defaultZoneId] — the device zone, used when nothing was saved.
 * Output: [OnboardingUiState].
 * Changelog: 2026-07-25 — Created for issue 2.1.
 */
private fun SavedStateHandle.restore(defaultZoneId: String): OnboardingUiState =
    OnboardingUiState(
        step = OnboardingStep.entries.firstOrNull { it.name == get<String>(KEY_STEP) } ?: OnboardingStep.WELCOME,
        smsConsentGranted = get<Boolean>(KEY_SMS_CONSENT) ?: false,
        appLockEnabled = get<Boolean>(KEY_APP_LOCK) ?: false,
        displayName = get<String>(KEY_DISPLAY_NAME).orEmpty(),
        currencyCode = get<String>(KEY_CURRENCY) ?: DEFAULT_CURRENCY_CODE,
        timeZoneId = get<String>(KEY_TIME_ZONE) ?: defaultZoneId,
        deviceZoneId = defaultZoneId,
        monthlyIncomeText = get<String>(KEY_INCOME).orEmpty(),
        rentOrEmiText = get<String>(KEY_RENT).orEmpty(),
        typicalSavingsText = get<String>(KEY_SAVINGS).orEmpty(),
    )

private const val KEY_STEP = "onboarding.step"
private const val KEY_SMS_CONSENT = "onboarding.smsConsent"
private const val KEY_APP_LOCK = "onboarding.appLockEnabled"
private const val KEY_DISPLAY_NAME = "onboarding.displayName"
private const val KEY_CURRENCY = "onboarding.currency"
private const val KEY_TIME_ZONE = "onboarding.timeZone"
private const val KEY_INCOME = "onboarding.income"
private const val KEY_RENT = "onboarding.rent"
private const val KEY_SAVINGS = "onboarding.savings"
