package com.aicfo.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lockout schedule, pinned to SEC-002 (issue 2.2, §23.2).
 *
 * Why:  SEC-002 is one sentence — "5 failed PIN attempts → 30 s lockout doubling" — and every part
 *       of it is a number an implementation can get subtly wrong: off by one on the threshold, a
 *       first lockout that fires on the 4th or 6th attempt, a doubling that starts from 60 s, an
 *       overflow that wraps a very large attempt count back into "not locked". Each of those would
 *       be invisible in normal use and would hand an attacker with a stolen phone an unlimited
 *       number of PIN guesses. So the schedule is pinned attempt by attempt rather than sampled.
 * What: the threshold, the first three lockout durations, the cap, the boundary at the instant the
 *       lockout expires, and the defensive cases (negative counter, huge counter).
 * Result: the schedule cannot drift without a test going red.
 * Changelog: 2026-07-26 — Created for issue 2.2 (written red before LockoutPolicy.kt existed).
 */
class LockoutPolicyTest {
    private val failedAt = 1_700_000_000_000L

    // --- the threshold: SEC-002 says five ------------------------------------------------

    /**
     * Input:  attempt counts 0 through 4.
     * Output: asserts none of them locks. Four wrong PINs is a person mistyping, not an attack;
     *         locking earlier than SEC-002 says would be its own usability bug.
     */
    @Test
    fun `fewer than five failures never locks`() {
        (0..4).forEach { attempts ->
            assertNull(
                "$attempts failures must not lock — SEC-002 sets the threshold at 5",
                LockoutPolicy.lockedUntilUtcMillis(attempts, failedAt),
            )
        }
    }

    /**
     * Input:  exactly five failures.
     * Output: asserts the first lockout is 30 s from the last failure, not from some other anchor.
     */
    @Test
    fun `the fifth failure locks for thirty seconds`() {
        assertEquals(
            failedAt + 30_000L,
            LockoutPolicy.lockedUntilUtcMillis(5, failedAt),
        )
    }

    // --- the doubling --------------------------------------------------------------------

    /**
     * Input:  attempts 5, 6, 7, 8.
     * Output: asserts 30 s, 60 s, 120 s, 240 s — each exactly twice the last.
     */
    @Test
    fun `each further failure doubles the lockout`() {
        val expected =
            mapOf(
                5 to 30_000L,
                6 to 60_000L,
                7 to 120_000L,
                8 to 240_000L,
            )
        expected.forEach { (attempts, duration) ->
            assertEquals(
                "$attempts failures should lock for ${duration}ms",
                failedAt + duration,
                LockoutPolicy.lockedUntilUtcMillis(attempts, failedAt),
            )
        }
    }

    /**
     * Input:  an attempt count far past the cap.
     * Output: asserts the lockout stops growing at [LockoutPolicy.MAX_LOCKOUT_MILLIS]. Without a
     *         cap the doubling reaches centuries within 40 attempts, which is indistinguishable
     *         from bricking the app for a user who simply forgot their PIN — and the recovery path
     *         (erase and restore from backup) is far more destructive than a long wait.
     */
    @Test
    fun `the lockout is capped`() {
        assertEquals(
            failedAt + LockoutPolicy.MAX_LOCKOUT_MILLIS,
            LockoutPolicy.lockedUntilUtcMillis(40, failedAt),
        )
    }

    /**
     * Input:  an attempt count large enough to overflow a naive `30_000 shl (n - 5)`.
     * Output: asserts the result is still the cap and still in the future. A shift overflow would
     *         wrap to a negative or tiny duration and unlock the app — the exact inversion of what
     *         this policy is for, reachable by an attacker simply by guessing enough times.
     */
    @Test
    fun `an absurd attempt count cannot wrap the lockout open`() {
        val lockedUntil = LockoutPolicy.lockedUntilUtcMillis(Int.MAX_VALUE, failedAt)
        assertEquals(failedAt + LockoutPolicy.MAX_LOCKOUT_MILLIS, lockedUntil)
        assertTrue("the lockout must stay in the future", lockedUntil!! > failedAt)
    }

    /**
     * Input:  a negative attempt count, which only a corrupted stored counter could produce.
     * Output: asserts it reads as "not locked" rather than throwing. A crash on every unlock
     *         attempt would lock the user out permanently, which is worse than the missed lockout.
     */
    @Test
    fun `a negative attempt count does not throw`() {
        assertNull(LockoutPolicy.lockedUntilUtcMillis(-1, failedAt))
    }

    // --- is it locked right now? ----------------------------------------------------------

    /**
     * Input:  five failures, and a "now" one millisecond before the lockout ends.
     * Output: asserts still locked.
     */
    @Test
    fun `still locked one millisecond before expiry`() {
        assertTrue(LockoutPolicy.isLockedOut(5, failedAt, failedAt + 29_999L))
    }

    /**
     * Input:  five failures, and a "now" exactly at the lockout's end.
     * Output: asserts **not** locked. The boundary is deliberately inclusive of release: a lockout
     *         that ends "at" a time and is still closed at that time never ends at the moment the
     *         countdown on screen reaches zero, which reads to the user as a frozen app.
     */
    @Test
    fun `unlocked at the exact expiry instant`() {
        assertFalse(LockoutPolicy.isLockedOut(5, failedAt, failedAt + 30_000L))
    }

    /**
     * Input:  four failures and a "now" long before any lockout would end.
     * Output: asserts not locked — below the threshold no instant is locked.
     */
    @Test
    fun `below the threshold no instant is locked`() {
        assertFalse(LockoutPolicy.isLockedOut(4, failedAt, failedAt))
    }

    /**
     * Input:  a clock that has moved backwards relative to the recorded failure.
     * Output: asserts still locked. A user changing the device clock backwards must not shorten a
     *         lockout — but this is why the counter itself is persisted rather than derived from
     *         time alone.
     */
    @Test
    fun `winding the clock back does not end a lockout`() {
        assertTrue(LockoutPolicy.isLockedOut(5, failedAt, failedAt - 1_000_000L))
    }

    // --- what the UI shows ----------------------------------------------------------------

    /**
     * Input:  attempt counts either side of the threshold.
     * Output: asserts the countdown the lock screen shows, floored at zero so it never reads
     *         "-2 attempts remaining".
     */
    @Test
    fun `attempts remaining counts down to zero and stops`() {
        assertEquals(5, LockoutPolicy.attemptsRemaining(0))
        assertEquals(1, LockoutPolicy.attemptsRemaining(4))
        assertEquals(0, LockoutPolicy.attemptsRemaining(5))
        assertEquals(0, LockoutPolicy.attemptsRemaining(99))
    }
}
