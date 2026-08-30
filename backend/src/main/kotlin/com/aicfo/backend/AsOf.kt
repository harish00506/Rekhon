package com.aicfo.backend

import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/*
 * Turning a vendor's notion of "when" into the contract's `as_of` (issue 6.7; TIM-001, TIM-002).
 *
 * Why:  every upstream says when differently — CoinGecko an epoch second, AMFI `29-Aug-2026`,
 *       a metals API an epoch, a rates API an ISO date — and the contract says one thing:
 *       ISO `yyyy-MM-dd`, date-only. Doing that conversion in four places is four chances to read a
 *       wall clock or to pick up the server's time zone instead of the market's.
 * What: two extensions on the injected [Clock].
 * Result: `as_of` is always the market's calendar day, resolved in the clock's zone.
 * Changelog: 2026-08-30 — Created for issue 6.7.
 *
 * The clock is injected everywhere (TIM-001) and carries `Asia/Kolkata` in production, because these
 * are Indian market days: a gold price stamped near midnight UTC belongs to the next Indian day, and
 * labelling it as the previous one would make it read a day stale the moment it arrived.
 */

/**
 * The calendar day an epoch-second timestamp falls on.
 * Input:  [epochSeconds] — the vendor's timestamp. Output: ISO `yyyy-MM-dd`.
 */
internal fun Clock.isoDateAt(epochSeconds: Long): String =
    Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalDate().toString()

/**
 * Today, for a vendor that publishes a price with no timestamp at all.
 * Input:  none. Output: ISO `yyyy-MM-dd`.
 * Result: the honest fallback. Claiming a date the vendor did not give would be worse than claiming
 *   the day we asked, and the client ages the value from this either way.
 */
internal fun Clock.todayIso(): String = LocalDate.now(this).toString()
