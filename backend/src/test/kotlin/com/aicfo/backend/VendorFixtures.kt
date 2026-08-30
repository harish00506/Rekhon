package com.aicfo.backend

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/*
 * What every vendor-source test needs (issue 6.7; P-08).
 *
 * Why:  the four sources differ in what they parse and agree on everything else — a recorded
 *       payload, a mock server, a frozen clock. Writing that four times would make each suite
 *       longer than the parser it tests, and would put four subtly different clocks in the build.
 * What: the fixture loader, a mock-server response helper, and the one frozen clock.
 * Result: every source is tested against bytes recorded from the real vendor, with **no network and
 *         no wall clock** — so the suite is deterministic (P-08) and runs in CI, which has never had
 *         either.
 * Changelog: 2026-08-30 — Created for issue 6.7.
 */

/**
 * Reads a recorded vendor payload.
 * Input:  [name] — the file under `src/test/resources/fixtures`. Output: its text.
 * Result: fails loudly rather than returning empty, so a renamed fixture is a red test and not a
 *   silently empty parse that still passes.
 */
internal fun fixture(name: String): String =
    requireNotNull(object {}.javaClass.getResource("/fixtures/$name")) {
        "missing fixture: /fixtures/$name"
    }.readText()

/** A `200` carrying [body] as JSON. */
internal fun ok(body: String): MockResponse =
    MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

/** A `200` carrying [body] as plain text — AMFI serves a text file, not JSON. */
internal fun okText(body: String): MockResponse =
    MockResponse().setResponseCode(200).setHeader("Content-Type", "text/plain").setBody(body)

/** An upstream that is broken rather than absent. */
internal fun serverError(): MockResponse = MockResponse().setResponseCode(500)

/**
 * The instant every source test is frozen at.
 *
 * Chosen so the vendor timestamps in the fixtures (`1787000000`) fall on **2026-08-17 in UTC and
 * 2026-08-18 in Asia/Kolkata**. A source that resolved dates in the host's zone, or in UTC, would
 * therefore produce a different `as_of` and fail — which is the only way to prove TIM-001's "calendar
 * logic uses the profile time zone" holds on this side of the wire too.
 */
internal fun frozenClock(at: String = "2026-08-30T12:00:00Z"): Clock =
    Clock.fixed(Instant.parse(at), ZoneId.of("Asia/Kolkata"))

/** The IST day the fixtures' `1787000000` falls on. In UTC it is the 17th. */
internal const val FIXTURE_AS_OF = "2026-08-18"

/**
 * A real OkHttp-backed [HttpFetch].
 *
 * Real, not a stub: the sources are given the mock server's own URL, so the request they build — its
 * path, its query and its headers — travels over an actual socket and is asserted on arrival. A fake
 * fetch would test the parser and none of the request.
 */
internal fun testFetch(): OkHttpFetch =
    OkHttpFetch(
        OkHttpClient.Builder()
            .callTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build(),
    )

private const val FETCH_TIMEOUT_SECONDS = 5L

/**
 * A clock a test can push forward (issue 6.7; P-08).
 *
 * Why:  two things here expire — the per-key cache and the AMFI index — and both hold internal state,
 *       so proving they expire needs one instance to see time move. The alternative is `Thread.sleep`
 *       for fifteen minutes, which is not an alternative.
 * What: a [Clock] whose instant is settable.
 * Result: TTL behaviour is asserted in milliseconds of wall time.
 * Changelog: 2026-08-30 — Created for issue 6.7.
 *
 * @property zone the market zone, so a date derived from this clock is the same one production
 *   would derive.
 */
internal class TestClock(
    private var now: Instant = Instant.parse("2026-08-30T12:00:00Z"),
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata"),
) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = TestClock(now, zone)

    override fun instant(): Instant = now

    /** Input: [by] — how far to move. Output: none. Result: every later read sees the new instant. */
    fun advance(by: Duration) {
        now = now.plus(by)
    }
}
