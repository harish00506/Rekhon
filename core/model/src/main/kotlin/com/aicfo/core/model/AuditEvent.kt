package com.aicfo.core.model

/**
 * The security events written to `audit_log` (issue 2.2; §21.6, §23).
 *
 * Why:  §21.6 bans PII and amounts from logs and sends security events to `audit_log` instead. The
 *       trap is that a log is only useful if it records *something*, and the obvious "something" —
 *       "PIN 4821 rejected for Harish at 21:04" — is exactly what must never be written. A **closed
 *       enum of codes** resolves that: what happened is recorded, who and with what is not, and the
 *       guarantee holds by construction rather than by remembering. There is no free-text field on
 *       an audit row for a future caller to put a name or a balance into.
 * What: the fixed vocabulary of auth events the app lock produces.
 * Result: an answerable "was there a burst of failed unlocks last Tuesday?" with nothing private
 *       stored to answer it.
 * Changelog: 2026-07-26 — Created for issue 2.2 (SEC-002).
 *
 * Pure Kotlin so `:data:repository`, `:app` and any later feature can all name the same event
 * without importing a Room type (ARC-005).
 *
 * Later issues extend this — 11.3 (consent revoked), 11.4 (erase-all), 8.1 (backup). Adding a
 * constant is additive and needs no migration: the column stores [name].
 */
enum class AuditEvent {
    /** The user unlocked the app. The method is recorded separately as [AuditMethod]. */
    APP_UNLOCK_SUCCESS,

    /**
     * An unlock attempt was refused — wrong PIN, a failed or cancelled biometric prompt, or a
     * credential that could not be read. Deliberately one code: distinguishing them in a stored log
     * would tell anyone reading it which factor to attack.
     */
    APP_UNLOCK_FAILURE,

    /** The app re-locked itself because it sat in the background past the auto-lock timer (§23.1). */
    APP_LOCKED_TIMEOUT,

    /** A lockout began after five consecutive failures (SEC-002). */
    APP_LOCKOUT_STARTED,

    /** A PIN was set or replaced. Never what it was. */
    PIN_SET,

    /** The app lock was switched on. */
    APP_LOCK_ENABLED,

    /** The app lock was switched off — worth recording precisely because it lowers protection. */
    APP_LOCK_DISABLED,
}

/**
 * Which factor an auth event went through (issue 2.2).
 *
 * Why:  SEC-002 provides two ways in, and "the biometric path has never once succeeded on this
 *       device" is a genuinely useful thing to be able to see when a user reports being locked out.
 *       Like [AuditEvent] it is a closed set of codes, so the column can never grow into a place
 *       someone writes a device name or a template id.
 * Result: the optional second column on an audit row.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 */
enum class AuditMethod {
    /** BiometricPrompt, class 3 / BIOMETRIC_STRONG. */
    BIOMETRIC,

    /** The PIN fallback. */
    PIN,
}
