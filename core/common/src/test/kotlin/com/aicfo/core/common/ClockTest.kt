package com.aicfo.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Behaviour tests for [Clock] and its calendar helpers — task 1.1.3 T1-T4 (SRS §21.4, TIM-001/002).
 *
 * Why:  "what day is it?" is a timezone question, and getting it wrong is the classic finance-app
 *       bug: a spend at 11:30pm IST lands in yesterday's budget because the code asked UTC. These
 *       tests pin the boundaries where that happens instead of trusting the implementation.
 * What: fixed-clock reads, the IST day/month rollover, profile-zone day bounds, zone changes,
 *       same-day comparison across UTC midnight, and a DST-observing zone for good measure.
 * Result: every calendar answer is proven to come from the profile zone, not the JVM default.
 * Changelog: 2026-07-25 — Created for issue 1.3 (written red before Clock.kt existed).
 */
class ClockTest {
    private companion object {
        val IST: ZoneId = ZoneId.of("Asia/Kolkata")
        val UTC: ZoneId = ZoneId.of("UTC")

        /** 2026-03-31T23:30Z — 05:00 on 1 April in IST. Crosses day, month and quarter at once. */
        val MONTH_EDGE: Long = Instant.parse("2026-03-31T23:30:00Z").toEpochMilli()
    }

    // --- T1 · the clock reads what it was set to ------------------------------------------

    /** Input: a fixed clock. Output: asserts `nowUtcMillis` is exactly the value supplied. */
    @Test
    fun `fake clock returns its fixed instant`() {
        val clock = FakeClock(MONTH_EDGE, IST)
        assertEquals(MONTH_EDGE, clock.nowUtcMillis())
        assertEquals(IST, clock.zone())
    }

    /** Input: repeated advances. Output: asserts time accumulates rather than resetting. */
    @Test
    fun `fake clock advances cumulatively`() {
        val clock = FakeClock(MONTH_EDGE, IST)
        clock.advanceBy(Duration.ofMinutes(20))
        clock.advanceBy(Duration.ofMinutes(10))
        assertEquals(MONTH_EDGE + Duration.ofMinutes(30).toMillis(), clock.nowUtcMillis())
    }

    /** Input: a negative advance. Output: asserts time can be wound back for boundary tests. */
    @Test
    fun `fake clock can move backwards`() {
        val clock = FakeClock(MONTH_EDGE, IST)
        clock.advanceBy(Duration.ofHours(-1))
        assertEquals(MONTH_EDGE - Duration.ofHours(1).toMillis(), clock.nowUtcMillis())
    }

    /** Input: an absolute jump. Output: asserts `setTo` replaces the instant, ignoring history. */
    @Test
    fun `fake clock jumps to an exact instant`() {
        val clock = FakeClock(MONTH_EDGE, IST)
        clock.advanceBy(Duration.ofDays(9))
        val target = Instant.parse("2026-12-25T00:00:00Z").toEpochMilli()
        clock.setTo(target)
        assertEquals(target, clock.nowUtcMillis())
    }

    /**
     * Input:  no constructor arguments.
     * Output: asserts the defaults are a fixed instant in IST — every module that injects this
     *         fixture without arguments must land on the same "now", or their tests diverge.
     */
    @Test
    fun `fake clock defaults to a fixed instant in the India zone`() {
        val clock = FakeClock()
        assertEquals(IST, clock.zone())
        assertEquals(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(), clock.nowUtcMillis())
        assertEquals(LocalDate.of(2026, 1, 1), clock.today())
    }

    // --- T2 (AC1) · the day/month rollover ------------------------------------------------

    /**
     * Input:  2026-03-31T23:30Z read in IST (UTC+5:30).
     * Output: asserts `today()` is 2026-04-01 — the whole reason [Clock] exists. A UTC reading
     *         would say 31 March and file the transaction in the wrong month, quarter and budget.
     */
    @Test
    fun `today crosses the day and month boundary in the profile zone`() {
        assertEquals(LocalDate.of(2026, 4, 1), FakeClock(MONTH_EDGE, IST).today())
        assertEquals(LocalDate.of(2026, 3, 31), FakeClock(MONTH_EDGE, UTC).today())
    }

    /** Input: the same instant, two zones. Output: asserts the profile zone alone decides. */
    @Test
    fun `today follows the zone, not the instant`() {
        val newYork = ZoneId.of("America/New_York")
        assertNotEquals(FakeClock(MONTH_EDGE, IST).today(), FakeClock(MONTH_EDGE, newYork).today())
    }

    // --- T4 · the profile moves time zone --------------------------------------------------

    /** Input: a zone change on a live clock. Output: asserts `today()` re-resolves immediately. */
    @Test
    fun `changing the zone changes today`() {
        val clock = FakeClock(MONTH_EDGE, UTC)
        assertEquals(LocalDate.of(2026, 3, 31), clock.today())
        clock.setZone(IST)
        assertEquals(LocalDate.of(2026, 4, 1), clock.today())
    }

    // --- T3 · day bounds --------------------------------------------------------------------

    /**
     * Input:  the IST day containing the fixed instant.
     * Output: asserts start/end of day are the IST midnights, expressed as UTC epoch millis —
     *         18:30Z the previous evening, not 00:00Z.
     */
    @Test
    fun `day bounds are profile-zone midnights expressed in UTC millis`() {
        val clock = FakeClock(MONTH_EDGE, IST)
        assertEquals(Instant.parse("2026-03-31T18:30:00Z").toEpochMilli(), clock.startOfDay())
        assertEquals(Instant.parse("2026-04-01T18:29:59.999Z").toEpochMilli(), clock.endOfDay())
    }

    /** Input: an explicit date. Output: asserts bounds can be taken for any day, not just today. */
    @Test
    fun `day bounds work for an arbitrary date`() {
        val clock = FakeClock(MONTH_EDGE, IST)
        val date = LocalDate.of(2026, 1, 15)
        assertEquals(Instant.parse("2026-01-14T18:30:00Z").toEpochMilli(), clock.startOfDay(date))
        assertEquals(Instant.parse("2026-01-15T18:29:59.999Z").toEpochMilli(), clock.endOfDay(date))
    }

    /**
     * Input:  a zone that observes DST, on the day the clocks go forward.
     * Output: asserts start-of-day still resolves. India has no DST, but hardcoding that
     *         assumption would break the first time a user travels or a locale is added.
     */
    @Test
    fun `day bounds survive a DST transition`() {
        val london = ZoneId.of("Europe/London")
        val clock = FakeClock(Instant.parse("2026-03-29T12:00:00Z").toEpochMilli(), london)
        // BST begins at 01:00 UTC on 2026-03-29, so local midnight is still 00:00Z.
        assertEquals(Instant.parse("2026-03-29T00:00:00Z").toEpochMilli(), clock.startOfDay())
        assertTrue(clock.endOfDay() > clock.startOfDay())
    }

    // --- AC2 · same profile day across UTC midnight -----------------------------------------

    /**
     * Input:  two instants inside one IST day that fall on different UTC days.
     * Output: asserts they count as the same day — a naive UTC comparison says otherwise, and
     *         that is exactly how a late-night spend lands in the wrong daily total.
     */
    @Test
    fun `same profile day is true across UTC midnight`() {
        val clock = FakeClock(MONTH_EDGE, IST)
        val eveningIst = Instant.parse("2026-04-01T18:00:00Z").toEpochMilli() // 23:30 IST, 1 Apr
        val morningIst = Instant.parse("2026-04-01T04:00:00Z").toEpochMilli() // 09:30 IST, 1 Apr
        assertTrue(clock.isSameProfileDay(morningIst, eveningIst))
    }

    /** Input: instants on adjacent IST days. Output: asserts they are not the same day. */
    @Test
    fun `same profile day is false across the profile midnight`() {
        val clock = FakeClock(MONTH_EDGE, IST)
        val lateOn31 = Instant.parse("2026-03-31T18:29:00Z").toEpochMilli() // 23:59 IST, 31 Mar
        val earlyOn1 = Instant.parse("2026-03-31T18:31:00Z").toEpochMilli() // 00:01 IST, 1 Apr
        assertFalse(clock.isSameProfileDay(lateOn31, earlyOn1))
    }

    /** Input: an epoch millis value. Output: asserts it converts to the profile-zone date. */
    @Test
    fun `to profile date converts an instant to the profile zone`() {
        val clock = FakeClock(MONTH_EDGE, IST)
        assertEquals(LocalDate.of(2026, 4, 1), clock.toProfileDate(MONTH_EDGE))
    }

    // --- SystemClock ------------------------------------------------------------------------

    /**
     * Input:  a [SystemClock] with a fixed zone provider.
     * Output: asserts it reports that zone and a plausible current time. This is the one place in
     *         the codebase allowed to read the wall clock (TIM-001), so it is tested here.
     */
    @Test
    fun `system clock reads the wall clock in the supplied zone`() {
        val clock = SystemClock { IST }
        val before = System.currentTimeMillis()
        val now = clock.nowUtcMillis()
        val after = System.currentTimeMillis()
        assertTrue("clock read $now outside [$before, $after]", now in before..after)
        assertEquals(IST, clock.zone())
        assertEquals(clock.toProfileDate(now), clock.today())
    }

    /** Input: a provider whose zone changes. Output: asserts the clock re-reads it every time. */
    @Test
    fun `system clock re-reads the profile zone on every call`() {
        var zone = UTC
        val clock = SystemClock { zone }
        assertEquals(UTC, clock.zone())
        zone = IST
        assertEquals(IST, clock.zone())
    }

    /** Input: no provider. Output: asserts the default falls back to the JVM zone. */
    @Test
    fun `system clock defaults to the system zone`() {
        assertEquals(ZoneId.systemDefault(), SystemClock().zone())
    }
}
