package com.aicfo.core.common

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * A [Clock] a test controls completely.
 *
 * Why:  P-08 requires fixed input to give fixed output, and half the app's inputs are dates —
 *       "is this bill overdue?", "did the budget month roll over?", "is this the third salary in a
 *       row?". With the real clock those tests either drift or need `Thread.sleep`. This makes
 *       time an argument. It ships from `testFixtures` so every module injects the same double
 *       rather than writing its own (task 1.1.3 DoD).
 * What: a mutable instant and zone, movable by [advanceBy] and [setZone].
 * Result: a test can sit at 23:59 on the last day of a month, step one minute, and assert the
 *       rollover — in milliseconds of wall time.
 * Changelog: 2026-07-25 — Created for issue 1.3.
 *
 * Not thread-safe, deliberately: a test that mutates the clock from two threads has a
 * determinism problem the fake should not hide. Use a `TestDispatcher` instead.
 *
 * Input:  [initialMillis] — the starting instant, UTC epoch millis; [initialZone] — the profile zone.
 * Output: a [Clock] frozen until the test moves it.
 */
class FakeClock(
    initialMillis: Long = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(),
    initialZone: ZoneId = ZoneId.of("Asia/Kolkata"),
) : Clock {
    private var currentMillis: Long = initialMillis
    private var currentZone: ZoneId = initialZone

    /** Input: none. Output: the instant the test last set, unchanged by real time passing. */
    override fun nowUtcMillis(): Long = currentMillis

    /** Input: none. Output: the profile zone the test last set. */
    override fun zone(): ZoneId = currentZone

    /**
     * Moves the clock.
     * Why:    boundary tests need to step across midnight, a month end or a due date.
     * Result: time advances cumulatively; a negative [duration] winds it back.
     * Input:  [duration] — how far to move. Output: none (mutates this clock).
     */
    fun advanceBy(duration: Duration) {
        currentMillis += duration.toMillis()
    }

    /**
     * Jumps to an exact instant.
     * Why:    clearer than computing a delta when a test needs a specific date.
     * Result: the clock reports [epochMillis] until moved again.
     * Input:  [epochMillis] — UTC epoch millis. Output: none (mutates this clock).
     */
    fun setTo(epochMillis: Long) {
        currentMillis = epochMillis
    }

    /**
     * Changes the profile time zone.
     * Why:    models the user travelling or correcting their zone — the case where the same
     *         instant becomes a different date.
     * Result: subsequent calendar answers resolve in [zone].
     * Input:  [zone] — the new profile zone. Output: none (mutates this clock).
     */
    fun setZone(zone: ZoneId) {
        currentZone = zone
    }
}
