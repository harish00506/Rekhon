package com.aicfo.core.datastore

import com.aicfo.core.model.Money

/*
 * The domain shapes callers see (issue 1.9; P-01, TIM-001).
 *
 * Why:  ARC-005's spirit — nothing outside this module should touch a generated protobuf type. The
 *       proto is a storage detail; if it were exposed, every ViewModel would import it and the
 *       schema could never change without touching the whole app.
 * What: small immutable Kotlin types the stores read and write.
 * Result: the store's public surface is ordinary Kotlin, and the proto stays internal.
 * Changelog: 2026-07-25 — Created for issue 1.9.
 */

/**
 * One consent decision as callers see it.
 *
 * Why:    P-01 requires consent to be revocable *and* auditable, so this carries the history rather
 *         than a bare flag: when it was granted, and when (if ever) it was withdrawn. Keeping the
 *         revocation timestamp after a re-grant would be misleading, so [revokedAtUtcMillis] is the
 *         withdrawal of the *current* record only.
 * Result: enough to answer "is this allowed?" and "since when?" without a second lookup.
 * Changelog: 2026-07-25 — Created for issue 1.9.
 *
 * Input:  [granted] — whether the feature may run right now; [grantedAtUtcMillis] /
 *         [revokedAtUtcMillis] — UTC epoch millis (TIM-001), `null` when it never happened.
 * Output: an immutable value.
 */
data class ConsentState(
    val granted: Boolean,
    val grantedAtUtcMillis: Long? = null,
    val revokedAtUtcMillis: Long? = null,
) {
    companion object {
        /**
         * What a feature nobody has answered for looks like.
         * Why:    **absence is never consent.** A missing record, an unknown id, an empty store —
         *         all of them must read as "no". This constant is the single expression of that
         *         rule so no call site can accidentally default the other way.
         */
        val NOT_GRANTED = ConsentState(granted = false)
    }
}

/**
 * How the user wants the app themed.
 * Why:    mirrors the proto enum so callers never import the generated type.
 * Result: a plain Kotlin enum for the settings screen.
 * Changelog: 2026-07-25 — Created for issue 1.9.
 */
enum class ThemeSetting {
    /** Follow the device. The default. */
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * The user's settings, as one immutable snapshot.
 *
 * Why:    a single value means the UI observes one Flow rather than four, and a screen can never
 *         render a half-updated mix of old and new settings.
 * Result: what `SettingsStore.observe()` emits.
 * Changelog: 2026-07-25 — Created for issue 1.9.
 *            2026-07-25 — Issue 2.1: carries what onboarding captured.
 *
 * Input:  [profileTimeZoneId] — IANA zone, `null` when unset; this is what `SystemClock`'s zone
 *         provider (issue 1.3) reads, so all calendar logic resolves in the user's zone.
 *         [currencyCode] — ISO-4217, `null` when unset. [privacyBlurEnabled] — issue 5.3.
 *         [theme]. [profileDisplayName] — local only, `null` when unset.
 *         [onboardingCompletedAtUtcMillis] — UTC epoch millis (TIM-001), `null` until first-run
 *         onboarding finishes; this is what decides the app's start destination.
 *         [quickSetup] — the optional FR-ONB-002 seeds. The app lock's state is **not** here — it
 *         lives behind its own narrow `AppLockStore` (issue 2.2), for the same reason consents do.
 *         [demoModeActive] — whether the app is showing the sample dataset (issue 2.4, FR-ONB-004);
 *         **independent of [isOnboarded]**, because the demo is reachable without a profile.
 * Output: an immutable value.
 */
data class SettingsSnapshot(
    val profileTimeZoneId: String? = null,
    val currencyCode: String? = null,
    val privacyBlurEnabled: Boolean = false,
    val theme: ThemeSetting = ThemeSetting.SYSTEM,
    val profileDisplayName: String? = null,
    val onboardingCompletedAtUtcMillis: Long? = null,
    val quickSetup: QuickSetupSeeds = QuickSetupSeeds(),
    val demoModeActive: Boolean = false,
) {
    /**
     * Whether first-run onboarding has been completed.
     * Why:    every caller asking "should we onboard?" would otherwise re-derive the
     *         null-means-never rule, and one of them would get it the wrong way round — which
     *         would either strand a new user on an empty dashboard or re-onboard an existing one.
     * Result: `true` once onboarding has finished.
     * Input:  none. Output: `Boolean`.
     */
    val isOnboarded: Boolean get() = onboardingCompletedAtUtcMillis != null
}

/**
 * The optional figures onboarding's quick-setup step collects (FR-ONB-002).
 *
 * Why:    issue 2.3 seeds budgets and the emergency-fund target from these, so they are captured
 *         once at first run rather than asked for again later. Each is independently optional —
 *         the whole step is skippable, and a user may answer one field and not the others.
 * Result: what `SettingsSnapshot.quickSetup` carries.
 * Changelog: 2026-07-25 — Created for issue 2.1.
 *
 * Input:  [monthlyIncome], [rentOrEmi], [typicalSavings] — [Money] (`Long` paise, MNY-001), `null`
 *         when the user did not answer. `null` rather than [Money.ZERO] on purpose: "I did not say"
 *         and "I earn nothing" would seed very different budgets.
 * Output: an immutable value.
 */
data class QuickSetupSeeds(
    val monthlyIncome: Money? = null,
    val rentOrEmi: Money? = null,
    val typicalSavings: Money? = null,
)

/**
 * The app lock's persisted state (issue 2.2; SEC-002, FR-SET-001).
 *
 * Why:    the lock has to answer three questions at launch, and getting any of them from memory
 *         would be a hole: is the lock on, how long may the app sit in the background, and how many
 *         failures have already happened. The last one especially — SEC-002's escalating lockout is
 *         only a defence if force-stopping the app does **not** reset it, which is the first thing
 *         someone with a stolen phone would try.
 * Result: what `AppLockStore.observe()` emits.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 *
 * Input:  [enabled] — whether the lock screen gates the app; [biometricEnabled] — whether to offer
 *         BiometricPrompt (independent of [enabled]; SEC-002 makes the PIN the fallback);
 *         [autoLockTimeoutSeconds] — background idle before re-locking, defaulted from the stored
 *         `0` to [DEFAULT_AUTO_LOCK_SECONDS]; [failedAttempts] — consecutive failures, the input to
 *         `LockoutPolicy`; [lastFailureAtUtcMillis] — when the last one happened (TIM-001), `null`
 *         when there have been none.
 * Output: an immutable value.
 */
data class AppLockSettings(
    val enabled: Boolean = false,
    val biometricEnabled: Boolean = false,
    val autoLockTimeoutSeconds: Int = DEFAULT_AUTO_LOCK_SECONDS,
    val failedAttempts: Int = 0,
    val lastFailureAtUtcMillis: Long? = null,
)

/**
 * The auto-lock timer's default, in seconds.
 *
 * SRS §23.1's threat table names it directly — "app-level lock (biometric/PIN) with auto-lock timer
 * (default 1 min)". It lives here rather than in the UI because the stored `0` has to resolve to it
 * at the point the proto is read; a default applied in the settings screen would leave every other
 * caller reading `0` and re-locking instantly.
 */
const val DEFAULT_AUTO_LOCK_SECONDS: Int = 60
