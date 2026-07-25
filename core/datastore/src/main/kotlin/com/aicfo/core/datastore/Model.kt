package com.aicfo.core.datastore

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
 *
 * Input:  [profileTimeZoneId] — IANA zone, `null` when unset; this is what `SystemClock`'s zone
 *         provider (issue 1.3) reads, so all calendar logic resolves in the user's zone.
 *         [currencyCode] — ISO-4217, `null` when unset. [privacyBlurEnabled] — issue 5.3.
 *         [theme].
 * Output: an immutable value.
 */
data class SettingsSnapshot(
    val profileTimeZoneId: String? = null,
    val currencyCode: String? = null,
    val privacyBlurEnabled: Boolean = false,
    val theme: ThemeSetting = ThemeSetting.SYSTEM,
)
