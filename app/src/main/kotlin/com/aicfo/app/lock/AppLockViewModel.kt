package com.aicfo.app.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicfo.core.common.Clock
import com.aicfo.core.common.getOrNull
import com.aicfo.core.crypto.LockoutPolicy
import com.aicfo.core.crypto.PinVerifier
import com.aicfo.core.crypto.SessionLock
import com.aicfo.core.datastore.AppLockSettings
import com.aicfo.core.datastore.AppLockStore
import com.aicfo.core.model.AuditEvent
import com.aicfo.core.model.AuditMethod
import com.aicfo.data.repository.AuditLogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The app lock (issue 2.2; SEC-002, §23.1, ARC-004).
 *
 * Why:  this decides whether a person holding the phone sees the user's entire financial life. The
 *       expensive mistakes here are all in one direction — a path that ends up **unlocked** when it
 *       should not — so the class is written so that opening the session is a single, deliberate
 *       call ([SessionLock.unlock]) reached from exactly two places: a verified PIN and a
 *       successful class-3 biometric. Everything else, including every error, leaves the lock shut
 *       without needing to say so.
 * What: reads the stored lock state, verifies PINs, records the SEC-002 lockout, applies the §23.1
 *       auto-lock timer, and writes every outcome to `audit_log`.
 * Result: a `StateFlow` the gate renders, and a session flag the DI graph gates the database on.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 *
 * **Why the biometric prompt is not here.** `BiometricPrompt` needs a `FragmentActivity`, so
 * `MainActivity` owns it and reports the outcome as an event. That keeps this class free of Android
 * UI types and testable on the JVM — which is what lets the fail-secure matrix below be proven at
 * all.
 *
 * Input:  [appLockStore] — the persisted lock state and failure counter; [pinVerifier] — the
 *         Keystore-bound PIN check; [sessionLock] — the session gate the database provider reads;
 *         [auditLog] — where every outcome is recorded (§21.6); [clock] — TIM-001, never the wall
 *         clock.
 * Output: an observable [AppLockUiState].
 */
@HiltViewModel
class AppLockViewModel
    @Inject
    constructor(
        private val appLockStore: AppLockStore,
        private val pinVerifier: PinVerifier,
        private val sessionLock: SessionLock,
        private val auditLog: AuditLogRepository,
        private val clock: Clock,
    ) : ViewModel() {
        /** The parts of the state the user is driving, as opposed to the parts on disk. */
        private val input = MutableStateFlow(LockInput())

        /**
         * When the app went to the background, or `null` while it is in the foreground.
         *
         * Not persisted: a cold start is already locked because [SessionLock] starts closed, so
         * there is nothing for a stored value to add.
         */
        private var backgroundedAtUtcMillis: Long? = null

        /**
         * What the lock screen renders.
         *
         * Combined rather than assembled by hand because all three inputs change independently —
         * the failure counter is written to disk by this same class, and the screen's "2 attempts
         * remaining" has to follow it without the screen polling.
         */
        val uiState: StateFlow<AppLockUiState> =
            combine(
                appLockStore.observe(),
                sessionLock.isUnlocked,
                input,
            ) { stored, unlocked, current ->
                // A read failure is deliberately NOT distinguished from "locked" here: `getOrNull`
                // collapses it to null, and a null `AppLockSettings` falls through to LOCKED below.
                // Erring the other way would mean a storage fault opens the app.
                toUiState(stored.getOrNull(), unlocked, current)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
                // CHECKING, not UNLOCKED: the gate must render nothing until the setting is known.
                initialValue = AppLockUiState(),
            )

        init {
            viewModelScope.launch {
                val stored = appLockStore.observe().first().getOrNull()
                // The lock being switched off is the one case where the session opens without the
                // user proving anything. A read failure leaves it shut — `stored` is null then, and
                // `enabled != false`.
                if (stored?.enabled == false) sessionLock.unlock()
                input.value = input.value.copy(pinSet = pinVerifier.isPinSet().getOrNull() == true)
            }
        }

        /**
         * Handles one event from the screen or the host activity.
         * Why:    a single entry point means every interaction is listed in one `when`, and the
         *         compiler reports a new event nobody handled — on a lock screen, an unhandled
         *         event is a door that does not close.
         * Result: state updates, and at most one session transition.
         * Input:  [event] — see [AppLockEvent]. Output: none.
         * Changelog: 2026-07-26 — Created for issue 2.2.
         */
        fun onEvent(event: AppLockEvent) {
            when (event) {
                is AppLockEvent.PinChanged ->
                    input.value = input.value.copy(pin = event.pin, errorCode = null)

                AppLockEvent.PinSubmitted -> submitPin()
                AppLockEvent.BiometricSucceeded -> unlock(AuditMethod.BIOMETRIC)
                AppLockEvent.BiometricFailed -> recordFailure(AuditMethod.BIOMETRIC, AppLockUiState.BIOMETRIC_FAILED)
                AppLockEvent.LockoutElapsed ->
                    input.value = input.value.copy(lockoutNudge = input.value.lockoutNudge + 1)

                AppLockEvent.Backgrounded -> backgroundedAtUtcMillis = clock.nowUtcMillis()
                AppLockEvent.Foregrounded -> relockIfIdleTooLong()
                AppLockEvent.DismissError -> input.value = input.value.copy(errorCode = null)
            }
        }

        /**
         * Tells the screen whether a class-3 sensor exists, as the platform reports it.
         * Why:    only the activity can ask `BiometricManager`, and the answer can change while the
         *         app is installed (a user enrols a fingerprint, or removes the last one). So it is
         *         pushed in rather than cached at construction.
         * Result: the biometric button appears only when it would actually work.
         * Input:  [available] — the platform's answer for BIOMETRIC_STRONG. Output: none.
         * Changelog: 2026-07-26 — Created for issue 2.2.
         */
        fun setBiometricAvailable(available: Boolean) {
            input.value = input.value.copy(biometricAvailable = available)
        }

        /**
         * Verifies the typed PIN (SEC-002).
         * Why:    the fallback factor. Every outcome other than a verified match must leave the lock
         *         shut and cost the user an attempt, or the lockout never bites.
         * Result: unlocks on a match; otherwise records a failure and clears the field.
         * Input:  none (reads the current [input]). Output: none.
         * Changelog: 2026-07-26 — Created for issue 2.2.
         */
        private fun submitPin() {
            val pin = input.value.pin
            if (pin.length < AppLockUiState.MIN_PIN_LENGTH) return
            viewModelScope.launch {
                input.value = input.value.copy(isVerifying = true)
                // `getOrNull() == true` and not `!= false`: an Err — an unreadable or truncated
                // credential — must count as a failure, not as a pass.
                val verified = pinVerifier.verify(pin).getOrNull() == true
                input.value = input.value.copy(isVerifying = false, pin = "")
                if (verified) unlock(AuditMethod.PIN) else recordFailure(AuditMethod.PIN, AppLockUiState.WRONG_PIN)
            }
        }

        /**
         * Opens the session after a verified factor.
         * Why:    **the only path in this class that unlocks anything.** Keeping it to one function
         *         means a reviewer can check every caller — there are two, both above, and both
         *         reached only after a factor actually verified.
         * Result: the failure record is cleared, the session opens, and the success is audited.
         * Input:  [method] — which factor succeeded. Output: none.
         * Changelog: 2026-07-26 — Created for issue 2.2.
         */
        private fun unlock(method: AuditMethod) {
            viewModelScope.launch {
                appLockStore.clearFailedUnlocks()
                sessionLock.unlock()
                input.value = input.value.copy(pin = "", errorCode = null)
                auditLog.record(AuditEvent.APP_UNLOCK_SUCCESS, method)
            }
        }

        /**
         * Records a refused attempt (SEC-002, §21.6).
         * Why:    the counter is what makes the lockout escalate, and it is written to disk before
         *         anything else so that killing the app mid-attempt cannot lose it.
         * Result: the stored counter increases, the error is surfaced, and the event is audited —
         *         plus a second `APP_LOCKOUT_STARTED` row on the attempt that crosses the threshold.
         *         **Never unlocks.**
         * Input:  [method] — which factor failed; [errorCode] — what to show. Output: none.
         * Changelog: 2026-07-26 — Created for issue 2.2.
         */
        private fun recordFailure(
            method: AuditMethod,
            errorCode: String,
        ) {
            viewModelScope.launch {
                appLockStore.recordFailedUnlock()
                input.value = input.value.copy(errorCode = errorCode, pin = "")
                auditLog.record(AuditEvent.APP_UNLOCK_FAILURE, method)

                // Read back rather than counting locally: the stored value is the one LockoutPolicy
                // will be asked about, and it is the one that survived the write.
                val stored = appLockStore.observe().first().getOrNull()
                if (stored != null && stored.failedAttempts == LockoutPolicy.FREE_ATTEMPTS) {
                    // Exactly at the threshold, so the row is written once per lockout rather than
                    // on every attempt after it.
                    auditLog.record(AuditEvent.APP_LOCKOUT_STARTED)
                }
            }
        }

        /**
         * Re-locks if the app sat in the background past the auto-lock timeout (§23.1).
         * Why:    the threat is a phone left unlocked on a desk, so time away is what matters, not
         *         time since unlock. Measured with the injected `Clock` (TIM-001).
         * Result: the session closes and the timeout is audited, or nothing happens if the user was
         *         away only briefly. Either way the background marker is cleared.
         * Input:  none. Output: none.
         * Changelog: 2026-07-26 — Created for issue 2.2.
         */
        private fun relockIfIdleTooLong() {
            val leftAt = backgroundedAtUtcMillis ?: return
            backgroundedAtUtcMillis = null
            viewModelScope.launch {
                val stored = appLockStore.observe().first().getOrNull() ?: return@launch
                if (!stored.enabled) return@launch
                val idleMillis = clock.nowUtcMillis() - leftAt
                if (idleMillis >= stored.autoLockTimeoutSeconds * MILLIS_PER_SECOND) {
                    sessionLock.lock()
                    input.value = input.value.copy(pin = "", errorCode = null)
                    auditLog.record(AuditEvent.APP_LOCKED_TIMEOUT)
                }
            }
        }

        /**
         * Folds the three sources into what the screen renders.
         * Why:    the status rule is the security-critical line in this class, so it is one
         *         expression that can be read in full rather than scattered across the combine.
         * Result: an [AppLockUiState]; `CHECKING` only before the setting is known, `UNLOCKED` only
         *         when the lock is off or the session is open, `LOCKED` for everything else —
         *         including a storage failure, which arrives here as a null [stored].
         * Input:  [stored] — the persisted lock state, `null` when it could not be read;
         *         [unlocked] — the session flag; [current] — the user-driven parts.
         * Output: [AppLockUiState].
         * Changelog: 2026-07-26 — Created for issue 2.2.
         */
        private fun toUiState(
            stored: AppLockSettings?,
            unlocked: Boolean,
            current: LockInput,
        ): AppLockUiState {
            val status =
                when {
                    stored == null -> LockStatus.LOCKED
                    !stored.enabled -> LockStatus.UNLOCKED
                    unlocked -> LockStatus.UNLOCKED
                    else -> LockStatus.LOCKED
                }
            val lockedUntil =
                stored?.lastFailureAtUtcMillis?.let { failedAt ->
                    LockoutPolicy.lockedUntilUtcMillis(stored.failedAttempts, failedAt)
                }
            val remaining = lockedUntil?.minus(clock.nowUtcMillis())?.takeIf { it > 0 }
            return AppLockUiState(
                status = status,
                pinEntry = current.pin,
                attemptsRemaining = LockoutPolicy.attemptsRemaining(stored?.failedAttempts ?: 0),
                lockoutRemainingMillis = remaining,
                biometricAvailable = current.biometricAvailable && current.pinSet,
                biometricEnabled = stored?.biometricEnabled == true,
                isVerifying = current.isVerifying,
                errorCode = current.errorCode,
            )
        }

        private companion object {
            /** Keeps the flow alive across a configuration change without leaking it. */
            const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L

            /** The auto-lock timeout is stored in seconds; the clock speaks millis. */
            const val MILLIS_PER_SECOND = 1_000L
        }
    }

/**
 * The parts of the lock's state the user drives rather than the disk.
 *
 * Why:    kept apart from [AppLockSettings] so that a re-emission from DataStore — which happens on
 *         every failure the ViewModel itself records — cannot wipe what the user is mid-way through
 *         typing.
 * Result: an internal value combined into [AppLockUiState].
 * Changelog: 2026-07-26 — Created for issue 2.2.
 *
 * Input:  [pin] — the digits typed, held in memory only and cleared on every outcome;
 *         [isVerifying]; [errorCode]; [biometricAvailable] — the platform's answer; [pinSet] —
 *         whether a credential exists; [lockoutNudge] — bumped when a lockout elapses, purely to
 *         re-run the combine so the countdown clears.
 * Output: an immutable value.
 */
private data class LockInput(
    val pin: String = "",
    val isVerifying: Boolean = false,
    val errorCode: String? = null,
    val biometricAvailable: Boolean = false,
    val pinSet: Boolean = false,
    val lockoutNudge: Int = 0,
)
