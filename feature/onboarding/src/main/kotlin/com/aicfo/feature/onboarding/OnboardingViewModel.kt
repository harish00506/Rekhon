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
import com.aicfo.core.datastore.OnboardingProfile
import com.aicfo.core.datastore.QuickSetupSeeds
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.MoneyFormatter
import com.aicfo.data.repository.DemoModeRepository
import com.aicfo.data.repository.ProfileSeed
import com.aicfo.data.repository.QuickSetupRepository
import com.aicfo.domain.engines.quicksetup.QuickSetupPlan
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
 * What: exposes [uiState] and handles [OnboardingEvent]s, ending in one [OnboardingWriter] call
 *       that applies the consent decision and completes onboarding together.
 * Result: a flow whose every state — including a failed save — is reachable in a unit test.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 *            2026-07-26 — Issue 2.2: the SECURITY step ADR-0002 reserved for it.
 *            2026-07-27 — Issue 2.3: the quick-setup seeds are derived live and persisted at Finish.
 *            2026-07-28 — Issue 2.4: the demo-mode escape hatch, which writes nothing this class does.
 *            2026-07-28 — Issue 2.5: the ACCOUNT step, which finally satisfies FR-ONB-001.
 *
 * Nothing here touches the network, and both stores are local: the whole flow works in airplane
 * mode (P-04).
 *
 * Input:  [writer] — the consent decision and the profile, written in the order they must be
 *         (issue 2.4, extracted from here);
 *         [appLockSetup] — the SEC-002 security step, which owns the order its two writes
 *         must happen in (issue 2.2);
 *         [financialSetup] — derives the budget from the seeds, persists it, and writes the first
 *         account, so this class parses no amount and computes no figure of its own (P-03; issues
 *         2.3, 2.5);
 *         [demoMode] — loads the sample dataset for the FR-ONB-004 escape hatch, which deliberately
 *         bypasses every write this class otherwise makes (issue 2.4);
 *         [clock] — supplies the profile zone and the instants, so this never reads
 *         `ZoneId.systemDefault()` or the wall clock itself (TIM-001); [savedState] — survives
 *         process death.
 * Output: an observable flow state.
 */
@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val writer: OnboardingWriter,
        private val appLockSetup: AppLockSetup,
        private val financialSetup: FinancialSetupCoordinator,
        private val demoMode: DemoModeRepository,
        private val clock: Clock,
        private val savedState: SavedStateHandle,
    ) : ViewModel() {
        private val _uiState =
            MutableStateFlow(savedState.restore(defaultZoneId = clock.zone().id).withDerivedPlan(financialSetup))

        /**
         * The flow's state.
         * Result: emits the current [OnboardingUiState] and every update. Read-only to callers —
         *         `asStateFlow()` stops a composable writing to it (ARC-004).
         */
        val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

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
                OnboardingEvent.SkipQuickSetup -> advance(skipping = true)
                OnboardingEvent.StartDemo -> startDemo()
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
                is OnboardingEvent.AccountNameChanged -> updateState { it.copy(accountName = event.name) }
                is OnboardingEvent.AccountTypeChanged -> updateState { it.copy(accountType = event.type) }
                is OnboardingEvent.AccountOpeningBalanceChanged ->
                    updateState { it.copy(accountOpeningBalanceText = event.text) }
                is OnboardingEvent.AppLockToggled -> onAppLockToggled(event.enabled)
                is OnboardingEvent.PinChanged -> updateState { it.copy(pinText = event.pin, errorCode = null) }
                is OnboardingEvent.PinConfirmChanged ->
                    updateState { it.copy(pinConfirmText = event.pin, errorCode = null) }
                is OnboardingEvent.MonthlyIncomeChanged ->
                    updateState { it.copy(monthlyIncomeText = event.text).withDerivedPlan(financialSetup) }
                is OnboardingEvent.RentOrEmiChanged ->
                    updateState { it.copy(rentOrEmiText = event.text).withDerivedPlan(financialSetup) }
                is OnboardingEvent.TypicalSavingsChanged ->
                    updateState { it.copy(typicalSavingsText = event.text).withDerivedPlan(financialSetup) }
            }
        }

        /**
         * Moves on from the current step — by answering it, or by skipping it (issues 2.2, 2.5).
         *
         * Why:    Next and Skip are the same movement and differ in one thing only: whether the
         *         step's answers count. Keeping them as one function is what stops the two drifting
         *         over where "the last step" finishes.
         *
         *         **The security step can refuse** (issue 2.2): a lock switched on with no usable
         *         PIN would leave the user with an app nothing opens, on their very first launch.
         *         Every other step always advances, so the guard is expressed once here.
         *
         *         **Skipping quick setup has to be remembered** (issue 2.5). It used to be the last
         *         step, so Skip and "finish without seeds" were one action; appending the ACCOUNT
         *         step separated them. The flag is remembered rather than the typed amounts being
         *         cleared, so a user who skips and then steps back still sees what they wrote. The
         *         account step needs no equivalent — a blank name already means skipped, and one
         *         representation of that is better than two that could disagree.
         * Result: advances, finishes, or sets the error explaining what is missing.
         * Input:  [skipping] — `true` when the user pressed Skip rather than Next.
         * Output: none.
         * Changelog: 2026-07-26 — Extracted for issue 2.2.
         *            2026-07-28 — Issue 2.5: absorbed Skip, which is no longer always Finish.
         */
        private fun advance(skipping: Boolean = false) {
            val state = _uiState.value
            if (!state.canAdvance) {
                if (!state.isSaving) updateState { it.copy(errorCode = pinProblem(it)) }
                return
            }
            if (skipping && state.step == OnboardingStep.QUICK_SETUP) {
                updateState { it.copy(quickSetupSkipped = true) }
            }
            if (state.step.isLast) finish(withSeeds = !_uiState.value.quickSetupSkipped) else moveBy(1)
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
         *         fails, the app is never "onboarded" without a time zone. Both of those now live
         *         inside [OnboardingWriter], which is the class that can actually guarantee the
         *         order rather than merely describe it.
         *
         *         Issue 2.3 appends the seeds **after** that flag, and the ordering is the opposite
         *         trade on purpose. Both orders lose something if the second write fails: seeds
         *         first would leave budget rows belonging to an onboarding that never completed,
         *         and the user would be sent back through a flow that then writes them again.
         *         Flag first means the worst case is an onboarded user whose budget is missing —
         *         recoverable from Settings, and it leaves no orphan rows behind. The error is
         *         still surfaced either way.
         *
         *         **The app lock moved to first in issue 2.4**, when the consent and profile writes
         *         were extracted together. Nothing depended on it being second: its own internal
         *         order (a PIN stored before the lock is switched on, issue 2.2) is what matters,
         *         and that is `AppLockSetup`'s to keep. The worst case it introduces — a lock
         *         enabled for an onboarding that then failed — leaves the user with a PIN they just
         *         chose and were shown, not a locked-out app.
         * What:   sets up the app lock, applies the consent decision and the profile as one ordered
         *         pair, then writes the quick-setup rows.
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
                    appLockSetup.apply(answers.appLockEnabled, answers.pinText)
                        .flatMap { writer.apply(answers.smsConsentGranted, answers.toProfile(withSeeds)) }
                        .flatMap { applyQuickSetup(answers, withSeeds) }
                        .flatMap { applyFirstAccount(answers) }
                updateState { state ->
                    when (outcome) {
                        is Ok -> state.copy(isSaving = false, isComplete = true)
                        is Err -> state.copy(isSaving = false, errorCode = outcome.error.code)
                    }
                }
            }
        }

        /**
         * Loads the sample dataset and leaves for the dashboard (issue 2.4; FR-ONB-004).
         *
         * Why:    **this writes no profile, and that is the requirement, not an optimisation.**
         *         FR-ONB-004 says the demo is available "without creating a profile", so none of
         *         what [finish] does happens here — no consent decision, no app lock, no
         *         `completeOnboarding`. The user is exactly as un-onboarded afterwards as before,
         *         which is what lets them come back to this flow when they leave the demo.
         * What:   asks the repository to seed the demo profile and set the flag.
         * Result: `isDemoStarted` on success, which the screen navigates on. On failure the error is
         *         surfaced and the user stays on the welcome step, because sending them to a
         *         dashboard whose sample data was never written would show an empty app labelled as
         *         a demo.
         * Input:  none. Output: none (launches on `viewModelScope`).
         */
        private fun startDemo() {
            if (_uiState.value.isSaving) return
            updateState { it.copy(isSaving = true, errorCode = null) }
            viewModelScope.launch {
                val outcome = demoMode.enter()
                updateState { state ->
                    when (outcome) {
                        is Ok -> state.copy(isSaving = false, isDemoStarted = true)
                        is Err -> state.copy(isSaving = false, errorCode = outcome.error.code)
                    }
                }
            }
        }

        /**
         * Persists the quick-setup seeds as real rows (issue 2.3; FR-ONB-002).
         *
         * Why:    **the skip path never gets here.** [withSeeds] is false when the user skipped, and
         *         an unanswered step produces an empty plan anyway — two independent reasons
         *         nothing is written, because "nothing is fabricated if skipped" is an acceptance
         *         criterion and one guard is one refactor away from being removed. The repository
         *         holds the third: an empty plan writes no row at all, not even the profile.
         * What:   hands the derived plan and the profile answers to the repository, which writes
         *         them in one transaction.
         * Result: `Ok(Unit)` when there was nothing to write or the write succeeded; `Err`
         *         otherwise, which leaves `isComplete` false and surfaces the code.
         * Input:  [answers] — the captured state; [withSeeds]. Output: `Result<Unit, AppError>`.
         * Changelog: 2026-07-27 — Created for issue 2.3.
         */
        private suspend fun applyQuickSetup(
            answers: OnboardingUiState,
            withSeeds: Boolean,
        ): Result<Unit, AppError> {
            val plan: QuickSetupPlan = answers.quickSetupPlan.takeIf { withSeeds } ?: return Ok(Unit)
            return financialSetup.persist(
                plan = plan,
                profile =
                    ProfileSeed(
                        displayName = answers.displayName.trim(),
                        timeZoneId = answers.timeZoneId,
                        currencyCode = answers.currencyCode,
                    ),
            )
        }

        /**
         * Writes the first account and attaches the seeded rules to it (issue 2.5; FR-ONB-001).
         *
         * Why:    **last in the chain, and that is required rather than incidental.** The account is
         *         scoped to a profile, and the profile row is written by [applyQuickSetup]
         *         immediately before — running these the other way round would create an account
         *         under a profile that does not exist yet. The rules it attaches to are written
         *         there too.
         *
         *         Unlike the steps before it there is no `withSeeds`-style flag: a blank name *is*
         *         the skip, and the coordinator treats it as one. One representation of "skipped"
         *         rather than two that could disagree.
         * Result: `Ok(Unit)` when the step was skipped or both writes landed; `Err` otherwise, which
         *         leaves `isComplete` false and surfaces the code.
         * Input:  [answers] — the captured state. Output: `Result<Unit, AppError>`.
         * Changelog: 2026-07-28 — Created for issue 2.5.
         */
        private suspend fun applyFirstAccount(answers: OnboardingUiState): Result<Unit, AppError> =
            financialSetup.persistFirstAccount(
                name = answers.accountName.trim(),
                type = answers.accountType,
                openingBalanceText = answers.accountOpeningBalanceText,
                currencyCode = answers.currencyCode,
                profileId = QuickSetupRepository.DEFAULT_PROFILE_ID,
            )

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
 * Re-derives the quick-setup plan from whatever is currently typed (issue 2.3, FR-ONB-002).
 *
 * Why:    the summary updates as the user types, so this runs on every keystroke — which is
 *         affordable only because the engine is pure and reads no clock of its own. It is
 *         **recomputed rather than remembered**: keeping a stale plan while the amounts moved is
 *         how a screen ends up showing a budget for a figure the user has already changed.
 *
 *         A top-level function rather than a member (issue 2.5): it needs nothing from the
 *         ViewModel but the coordinator, and moving it out is what kept that class inside detekt's
 *         function limit without raising the limit.
 * What:   parses the three fields, hands them to the engine with the current month, and stores the
 *         result.
 * Result: a state whose `quickSetupPlan` matches its three amount fields; `null` when nothing
 *         parses yet or the engine rejects the input — the summary then renders nothing rather than
 *         a partial budget.
 * Input:  the receiver; [setup] — derives the plan. Output: [OnboardingUiState].
 * Changelog: 2026-07-27 — Created for issue 2.3.
 *            2026-07-28 — Issue 2.5: moved out of OnboardingViewModel.
 */
private fun OnboardingUiState.withDerivedPlan(setup: FinancialSetupCoordinator): OnboardingUiState =
    copy(quickSetupPlan = setup.derive(monthlyIncomeText, rentOrEmiText, typicalSavingsText))

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
    this[KEY_QUICK_SETUP_SKIPPED] = state.quickSetupSkipped
    this[KEY_ACCOUNT_NAME] = state.accountName
    this[KEY_ACCOUNT_TYPE] = state.accountType.storedValue
    this[KEY_ACCOUNT_OPENING] = state.accountOpeningBalanceText
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
        quickSetupSkipped = get<Boolean>(KEY_QUICK_SETUP_SKIPPED) ?: false,
        accountName = get<String>(KEY_ACCOUNT_NAME).orEmpty(),
        // Falls back rather than throwing, for the same reason the step name does: a value written
        // by another build must not crash the relaunch.
        accountType = get<String>(KEY_ACCOUNT_TYPE)?.let(AccountType::fromStored) ?: AccountType.BANK,
        accountOpeningBalanceText = get<String>(KEY_ACCOUNT_OPENING).orEmpty(),
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
private const val KEY_QUICK_SETUP_SKIPPED = "onboarding.quickSetupSkipped"
private const val KEY_ACCOUNT_NAME = "onboarding.accountName"
private const val KEY_ACCOUNT_TYPE = "onboarding.accountType"
private const val KEY_ACCOUNT_OPENING = "onboarding.accountOpening"
