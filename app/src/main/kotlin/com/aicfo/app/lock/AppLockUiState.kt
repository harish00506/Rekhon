package com.aicfo.app.lock

import androidx.compose.runtime.Immutable

/**
 * Whether the app is showing its contents (issue 2.2; SEC-002, ARC-004).
 *
 * Why:  three states rather than two, because "we have not read the setting yet" is genuinely
 *       different from "locked" and from "open". Collapsing it into either is a bug in one
 *       direction or the other: treated as open, a returning user sees their balances flash on
 *       screen before the lock appears; treated as locked, a user who never enabled the lock sees a
 *       PIN prompt blink at them on every launch.
 * Result: what [AppLockUiState.status] carries.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 */
enum class LockStatus {
    /**
     * The stored setting has not been read yet. **Renders nothing** — never the app's contents.
     * This is the fail-secure default, and it is the initial value on purpose.
     */
    CHECKING,

    /** The lock screen is showing. No financial data may be rendered behind it. */
    LOCKED,

    /**
     * Access is permitted for this session — either the user unlocked, or the lock is switched off.
     * The two are deliberately one state: everything downstream only asks "may I show data?".
     */
    UNLOCKED,
}

/**
 * Everything the lock screen renders, as one value (ARC-004).
 *
 * Why:  §21.2 requires one immutable state class per screen exposed as a `StateFlow`. Here it also
 *       carries the security-relevant counters, so a test can name every state the lock can be in —
 *       which for this screen is the point: the states that must **not** show data are exactly the
 *       ones worth being able to assert.
 * What: the status, what the user has typed, and the SEC-002 attempt/lockout counters.
 * Result: every state the lock can be in is nameable in a test.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 *
 * **The default is [LockStatus.CHECKING], and that is a security decision.** A default of
 * `UNLOCKED` would mean any path that failed to load the setting — a storage error, a crash in the
 * collector, a future refactor that forgets to start it — opens the app. Defaulting closed means
 * the same bug produces an app that will not open, which is recoverable and immediately obvious.
 *
 * Input:  [status]; [pinEntry] — what has been typed, never persisted or logged;
 *         [attemptsRemaining] — from `LockoutPolicy`; [lockoutRemainingMillis] — `null` when not
 *         locked out, otherwise how long is left at the moment this state was produced;
 *         [biometricAvailable] — whether the device has a usable class-3 sensor;
 *         [biometricEnabled] — whether the user turned it on; [isVerifying] — a check is in flight;
 *         [errorCode] — an `AppError.code` or one of [WRONG_PIN] / [BIOMETRIC_FAILED], never a
 *         message, so the wording stays in `strings.xml` (§21.6).
 * Output: an immutable snapshot for the composable.
 */
@Immutable
data class AppLockUiState(
    val status: LockStatus = LockStatus.CHECKING,
    val pinEntry: String = "",
    val attemptsRemaining: Int = 0,
    val lockoutRemainingMillis: Long? = null,
    val biometricAvailable: Boolean = false,
    val biometricEnabled: Boolean = false,
    val isVerifying: Boolean = false,
    val errorCode: String? = null,
) {
    /** Whether SEC-002's lockout is currently in force, in which case entry is disabled. */
    val isLockedOut: Boolean get() = lockoutRemainingMillis != null

    /** Whether to offer the fingerprint/face button: the device can, and the user asked for it. */
    val canUseBiometric: Boolean get() = biometricAvailable && biometricEnabled && !isLockedOut

    /** Whether Unlock should be tappable — a plausible PIN, no lockout, nothing already in flight. */
    val canSubmitPin: Boolean get() = pinEntry.length >= MIN_PIN_LENGTH && !isLockedOut && !isVerifying

    /** Whether the app's own content may be composed. The single question the gate asks. */
    val showsContent: Boolean get() = status == LockStatus.UNLOCKED

    companion object {
        /** Matches `TinkPinVerifier`'s floor, so Unlock cannot be tapped on a PIN it would reject. */
        const val MIN_PIN_LENGTH = 4

        /** The PIN did not match. A fixed code — the reason never says which digit was wrong. */
        const val WRONG_PIN = "wrong_pin"

        /**
         * The biometric prompt did not succeed — cancelled, failed, or errored.
         *
         * One code for all three, deliberately. Telling a user which of "no match" and "sensor
         * unavailable" occurred is also telling anyone holding the phone which factor to attack.
         */
        const val BIOMETRIC_FAILED = "biometric_failed"
    }
}

/**
 * Everything the user (or the host activity) can do at the lock screen (ARC-004).
 *
 * Why:    events flow **up** through a sealed interface, so the composable stays a function of
 *         state and the compiler lists every interaction the ViewModel must handle. This matters
 *         more here than on an ordinary screen: an unhandled event on a lock screen is a door that
 *         does not close.
 * Result: the lock's complete input surface.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 */
sealed interface AppLockEvent {
    /** The user typed. Already filtered to digits by `CfoPinField`. */
    data class PinChanged(
        val pin: String,
    ) : AppLockEvent

    /** The user submitted the PIN. */
    data object PinSubmitted : AppLockEvent

    /**
     * BiometricPrompt reported success (SEC-002, class 3).
     *
     * Only `MainActivity` sends this, because only it owns the prompt. That is also why the
     * ViewModel does no biometric work itself: the platform has already decided, and the ViewModel's
     * job is to record the outcome and open the session.
     */
    data object BiometricSucceeded : AppLockEvent

    /**
     * BiometricPrompt did not succeed — cancelled, errored, or no match.
     *
     * **Must leave the app locked.** Collapsed to one event for the same reason
     * [AppLockUiState.BIOMETRIC_FAILED] is one code.
     */
    data object BiometricFailed : AppLockEvent

    /** The lockout's countdown reached zero, so entry can be re-enabled. */
    data object LockoutElapsed : AppLockEvent

    /** The app went to the background at this instant — the start of the auto-lock timer (§23.1). */
    data object Backgrounded : AppLockEvent

    /** The app came back. Re-locks if it was away longer than the auto-lock timeout. */
    data object Foregrounded : AppLockEvent

    /** The user dismissed the error message. */
    data object DismissError : AppLockEvent
}
