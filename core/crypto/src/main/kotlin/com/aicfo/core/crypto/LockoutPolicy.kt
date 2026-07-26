package com.aicfo.core.crypto

/**
 * How long a wrong PIN costs you (SEC-002, §23.2).
 *
 * Why:  a PIN is four to six digits — a million guesses at most, and a machine does that in
 *       seconds. The only thing standing between a stolen phone and the user's entire financial
 *       history is that guessing is made *slow*. SEC-002 fixes the schedule: "5 failed PIN attempts
 *       → 30 s lockout doubling". After ten failures the attacker is waiting a quarter of an hour
 *       per guess, which is what makes brute force pointless.
 * What: pure arithmetic over a failure count and the instant of the last failure. No clock, no
 *       storage, no Android.
 * Result: the instant a lock ends, and whether a given instant is inside it.
 * Changelog: 2026-07-26 — Created for issue 2.2 (SEC-002).
 *
 * **Deliberately free of time and state (P-08).** "Now" is a parameter, supplied by the caller from
 * the injected `Clock` (TIM-001), and the failure count is supplied by the caller from DataStore.
 * That is what makes every rule here provable by a plain unit test with no fakes at all — and this
 * is security math, so it is proven attempt by attempt rather than sampled.
 *
 * **The counter must be persisted by the caller, not held in memory.** A lockout that an attacker
 * can clear by force-stopping the app is not a lockout. `SettingsStore` keeps it in Proto DataStore.
 */
object LockoutPolicy {
    /** SEC-002's threshold: the sixth wrong PIN is the first that costs a wait. */
    const val FREE_ATTEMPTS: Int = 5

    /** SEC-002's first penalty. Every further failure doubles it. */
    const val FIRST_LOCKOUT_MILLIS: Long = 30_000L

    /**
     * The ceiling on the doubling — one hour.
     *
     * SEC-002 does not name a cap. One is added anyway because unbounded doubling passes a century
     * before the fortieth attempt, which is not a lockout but a bricked app: a user who has simply
     * forgotten their PIN would have no route back except erase-and-restore, which destroys far
     * more than the wait was protecting. An hour per guess already makes brute force hopeless
     * (a 4-digit PIN would take over a year), so the cap costs no security.
     */
    const val MAX_LOCKOUT_MILLIS: Long = 60L * 60L * 1_000L

    /**
     * Bounds the shift so it can never overflow.
     *
     * `FIRST_LOCKOUT_MILLIS shl 20` is about a year in milliseconds — already far past
     * [MAX_LOCKOUT_MILLIS] — while `shl 64` would wrap back to the original value and quietly
     * unlock the app. Clamping the exponent before shifting is what makes that unreachable.
     */
    private const val MAX_DOUBLINGS: Int = 20

    /**
     * When the current lockout ends.
     *
     * Why:    the lock screen needs a single instant it can count down to, and the unlock path
     *         needs one value to compare against. Deriving it here rather than at each call site
     *         means the schedule exists in exactly one place.
     * What:   applies SEC-002's threshold and doubling, clamped by [MAX_LOCKOUT_MILLIS].
     * Result: the UTC instant the lock expires, or `null` when the user is not locked out at all.
     *         A count below the threshold — including a negative one from a corrupted store —
     *         returns `null` rather than throwing: crashing on every unlock attempt would lock the
     *         user out permanently, which is worse than the lockout this missed.
     * Input:  [failedAttempts] — consecutive failures since the last success, from DataStore;
     *         [lastFailureAtUtcMillis] — when the most recent one happened, UTC epoch millis
     *         stamped from the injected `Clock` (TIM-001).
     * Output: `Long?` — the UTC epoch millis the lock lifts, or `null`.
     * Changelog: 2026-07-26 — Created for issue 2.2.
     */
    fun lockedUntilUtcMillis(
        failedAttempts: Int,
        lastFailureAtUtcMillis: Long,
    ): Long? {
        if (failedAttempts < FREE_ATTEMPTS) return null
        val doublings = (failedAttempts - FREE_ATTEMPTS).coerceAtMost(MAX_DOUBLINGS)
        val duration = (FIRST_LOCKOUT_MILLIS shl doublings).coerceAtMost(MAX_LOCKOUT_MILLIS)
        return lastFailureAtUtcMillis + duration
    }

    /**
     * Whether unlocking is barred at this instant.
     *
     * Why:    the one question the lock screen and the ViewModel both ask. Keeping the comparison
     *         here rather than at two call sites means the boundary is decided once.
     * Result: `true` while the lock holds. The end instant itself is **not** locked — a lockout
     *         that were still closed at the moment its on-screen countdown reaches zero would read
     *         as a frozen app.
     * Input:  [failedAttempts] and [lastFailureAtUtcMillis] as in [lockedUntilUtcMillis];
     *         [nowUtcMillis] — from the injected `Clock`, never the wall clock (TIM-001).
     * Output: `Boolean`.
     * Changelog: 2026-07-26 — Created for issue 2.2.
     */
    fun isLockedOut(
        failedAttempts: Int,
        lastFailureAtUtcMillis: Long,
        nowUtcMillis: Long,
    ): Boolean {
        val lockedUntil = lockedUntilUtcMillis(failedAttempts, lastFailureAtUtcMillis) ?: return false
        return nowUtcMillis < lockedUntil
    }

    /**
     * How many tries are left before the next lockout.
     *
     * Why:    the lock screen warns the user before it bites — "2 attempts remaining" is the
     *         difference between a lockout that feels like a security feature and one that feels
     *         like a fault.
     * Result: the remaining attempts, floored at zero so the UI never shows a negative count.
     * Input:  [failedAttempts] — consecutive failures. Output: `Int` in `0..FREE_ATTEMPTS`.
     * Changelog: 2026-07-26 — Created for issue 2.2.
     */
    fun attemptsRemaining(failedAttempts: Int): Int = (FREE_ATTEMPTS - failedAttempts).coerceIn(0, FREE_ATTEMPTS)
}
