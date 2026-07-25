package com.aicfo.core.common

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The app's source of "now" — the only sanctioned way to read time.
 *
 * Why:  TIM-001 (SRS §21.4) bans `System.currentTimeMillis()`, `Instant.now()` and
 *       `LocalDate.now()` in domain code, for two reasons. First, correctness: "what day is it?"
 *       is a question about the **profile time zone**, not UTC and not the JVM default — a spend
 *       at 23:30 IST belongs to that day's budget, though UTC has already rolled over. Second,
 *       testability (P-08): an engine that reads the wall clock cannot be given a fixed input, so
 *       its forecasts and due-date rules can never be pinned by a test.
 * What: three reads — the instant as UTC epoch millis, the profile zone, and today's date in that
 *       zone. Calendar arithmetic lives in the extension functions below rather than on the
 *       interface, so an implementation only has to get these three right.
 * Result: every date-dependent decision in the app is reproducible and timezone-correct.
 * Changelog: 2026-07-25 — Created for issue 1.3 / task 1.1.3 (TIM-001, TIM-002, §21.4).
 *
 * Uses `java.time` rather than `kotlinx-datetime`: minSdk is 26 and `java.time` is native from
 * API 26 (NFR-008), so the pinned §21.3 stack needs no extra dependency.
 */
interface Clock {
    /**
     * The current instant.
     * Why:    TIM-001 — timestamps are UTC epoch millis everywhere, DB to UI.
     * Result: milliseconds since the Unix epoch, UTC.
     * Input:  none. Output: [Long] epoch millis.
     */
    fun nowUtcMillis(): Long

    /**
     * The profile time zone — the user's zone, not the device's and not UTC.
     * Why:    every calendar answer depends on it, so it is read, never assumed.
     * Result: the [ZoneId] all day/month boundaries are computed in.
     * Input:  none. Output: [ZoneId].
     */
    fun zone(): ZoneId

    /**
     * Today's date in the profile zone.
     * Why:    the single most-asked question in the app (budgets, due dates, day rollover), and
     *         the one most often got wrong by reading UTC.
     * Result: a date-only value (TIM-002 — never a midnight timestamp).
     * Input:  none. Output: [LocalDate] in [zone].
     */
    fun today(): LocalDate = toProfileDate(nowUtcMillis())
}

/**
 * Converts an instant to its date in the profile zone.
 * Why:    a stored transaction timestamp is UTC millis; deciding which *day* it belongs to is a
 *         profile-zone question, and doing it inline at each call site is how the two get mixed up.
 * Result: the calendar date the instant fell on for this user.
 * Input:  [epochMillis] — UTC epoch millis. Output: [LocalDate] in [Clock.zone].
 * Changelog: 2026-07-25 — Created for issue 1.3.
 */
fun Clock.toProfileDate(epochMillis: Long): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone()).toLocalDate()

/**
 * The first instant of a day in the profile zone.
 * Why:    "this month's spend" is a range query against UTC millis columns, so the boundaries have
 *         to be the profile zone's midnights — in IST that is 18:30Z the previous evening, not
 *         00:00Z. Using UTC midnight silently shifts every daily and monthly total by 5.5 hours.
 * What:   resolves local midnight through [ZoneId], which handles DST gaps correctly (java.time
 *         moves to the first valid instant when midnight does not exist).
 * Result: an inclusive lower bound for range queries.
 * Input:  [date] — the day; defaults to [Clock.today]. Output: UTC epoch millis.
 * Changelog: 2026-07-25 — Created for issue 1.3.
 */
fun Clock.startOfDay(date: LocalDate = today()): Long = date.atStartOfDay(zone()).toInstant().toEpochMilli()

/**
 * The last instant of a day in the profile zone.
 * Why:    the inclusive upper bound that pairs with [startOfDay]; computed as the next day's start
 *         minus one milli so no gap can open between consecutive days.
 * Result: an inclusive upper bound for range queries (`.999` of the final second).
 * Input:  [date] — the day; defaults to [Clock.today]. Output: UTC epoch millis.
 * Changelog: 2026-07-25 — Created for issue 1.3.
 */
fun Clock.endOfDay(date: LocalDate = today()): Long = startOfDay(date.plusDays(1)) - 1L

/**
 * Whether two instants fall on the same day for this user.
 * Why:    the naive `a / 86_400_000 == b / 86_400_000` compares UTC days and is wrong for every
 *         zone with an offset — in IST it splits a single day at 05:30 local.
 * Result: true when both instants map to the same profile-zone date.
 * Input:  [first], [second] — UTC epoch millis. Output: [Boolean].
 * Changelog: 2026-07-25 — Created for issue 1.3.
 */
fun Clock.isSameProfileDay(
    first: Long,
    second: Long,
): Boolean = toProfileDate(first) == toProfileDate(second)

/**
 * The production [Clock] — the one place allowed to read the wall clock.
 *
 * Why:    TIM-001 has to be enforceable, which means exactly one call site of
 *         `System.currentTimeMillis()` in the codebase. This is it; everything else injects.
 * What:   reads the system clock, and the profile zone through a provider so that when the zone
 *         becomes a user setting (Proto DataStore, issue 1.9) only the lambda changes — every
 *         call re-reads it, so a zone change takes effect immediately.
 * Result: real time, in the profile zone, behind the same interface tests replace.
 * Changelog: 2026-07-25 — Created for issue 1.3.
 *
 * Input:  [zoneProvider] — supplies the profile zone; defaults to the device zone until issue 1.9
 *         wires the setting.
 * Output: a [Clock] over the system clock.
 */
class SystemClock(
    private val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
) : Clock {
    /** Input: none. Output: the wall clock as UTC epoch millis. */
    override fun nowUtcMillis(): Long = System.currentTimeMillis()

    /** Input: none. Output: the profile zone, re-read on every call. */
    override fun zone(): ZoneId = zoneProvider()
}
