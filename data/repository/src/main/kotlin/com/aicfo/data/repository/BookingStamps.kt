package com.aicfo.data.repository

import com.aicfo.core.common.Clock
import com.aicfo.core.common.startOfDay
import java.time.LocalDate
import java.time.LocalTime

/*
 * Future-dated transactions: the one place a booked date becomes the three stamps a row carries
 * (issue 3.4; FR-TXN-010, TIM-001, TIM-002).
 *
 * A separate file from `TransactionRepository.kt` for the reason `SplitDrafts.kt` is one — that file
 * is at detekt's eleven-function ceiling. The seam is real: everything here is about **which day a
 * row belongs to**, and none of it knows what the row is.
 */

/**
 * The three time values a transaction row carries, derived from one booked date.
 *
 * Why:  `create`, `writeTransferLegs` and `writeSplit` each need the same four values, and each used
 *       to compute them itself — which is how a transfer's two legs could have landed on different
 *       days. Computing them once, in one place, makes "both legs share one booked day" structural
 *       rather than something a reader has to check by comparing two blocks.
 * What: the ISO day (TIM-002), the ordering instant (TIM-001), the posting stamp, and now.
 * Result: a row's dates cannot disagree with each other.
 * Changelog: 2026-08-03 — Created for issue 3.4 (FR-TXN-010).
 *
 * Input:  [bookedOnIsoDate] — the profile-zone day, ISO `yyyy-MM-dd`; [occurredAtUtcMillis] — the
 *         instant the list orders by; [postedAtUtcMillis] — `null` for a future-dated row;
 *         [nowUtcMillis] — the write instant, for `created_at`/`updated_at`.
 * Output: an immutable value.
 */
internal data class BookingStamps(
    val bookedOnIsoDate: String,
    val occurredAtUtcMillis: Long,
    val postedAtUtcMillis: Long?,
    val nowUtcMillis: Long,
)

/**
 * Resolves a user-chosen booked date into the stamps a row is written with (FR-TXN-010).
 *
 * Why:    FR-TXN-010 requires future-dated transactions to be supported and "excluded from actuals".
 *         This function is where a date the user picked becomes that exclusion, and it makes three
 *         decisions that are each easy to get wrong at a call site:
 *
 *         **Any day is accepted, past or future.** Issue 3.4 refused the past, because
 *         `net_worth_snapshot` holds one frozen row per past day and nothing recomputed them — a row
 *         inserted into last week left those days' stored figures behind. That was true until
 *         `NetWorthRepository.repairStaleHistory` was written, which corrects exactly the days a
 *         back-dated row invalidates. With the consequence fixed, the refusal was the only thing
 *         left, and a rule with no remaining reason is a rule to delete. **ADR-0012** records it and
 *         supersedes ADR-0011's narrower provenance-based version.
 *
 *         **A future row's instant is the start of its own day, not now.** The recent list orders by
 *         `occurred_at_utc_millis`, so stamping "now" would sort a payment scheduled for next month
 *         in among today's. [Clock.startOfDay] resolves local midnight through the profile
 *         `ZoneId`, which is what makes this correct across a DST boundary — java.time moves to the
 *         first valid instant on a day whose midnight does not exist, where a naive
 *         `date * 86_400_000` would land an hour into the previous day.
 *
 *         **`postedAt` is stamped only when the day has already arrived.** `null` is what
 *         `TransactionDao.postDue` looks for, so a row written for tomorrow is exactly what the
 *         worker will pick up when tomorrow comes. It is a record, not the balance gate — see
 *         `docs/adr/0010-future-dated-posting.md`.
 *         **A user-set [atTime] overrides all of that**, and is resolved through the profile
 *         `ZoneId` rather than by adding milliseconds to a midnight — `ZonedDateTime` moves to the
 *         first valid instant on a day whose local clock skips the chosen hour, where arithmetic on
 *         an offset would silently land an hour earlier or on the previous day.
 * Result: the stamps. Non-null today — the signature stays nullable because `create`,
 *         `createTransfer` and `createSplit` all report a `null` as
 *         `AppError.Validation("bookedOn")`, and keeping the seam costs one `?:` at three call
 *         sites while leaving somewhere for a future refusal to live.
 * Input:  the receiver — the injected [Clock] (TIM-001, never the wall clock); [bookedOn] — the day
 *         the user picked, or `null` to mean today, which is what every caller before issue 3.4
 *         meant implicitly; [atTime] — the time of day (FR-TXN-001's "date-time"), or `null` to
 *         keep the stamped default.
 * Output: `BookingStamps?`.
 * Changelog: 2026-08-03 — Created for issue 3.4 (FR-TXN-010).
 *            2026-08-03 — [atTime], so FR-TXN-001's "date-time" is a thing the user can state
 *            rather than one the app always assumes.
 *            2026-08-06 — [allowPast] for issue 3.8 (FR-OCR-003), then removed with the refusal
 *            itself once `repairStaleHistory` made back-dating safe for every path; see ADR-0012.
 */
internal fun Clock.stampsFor(
    bookedOn: LocalDate?,
    atTime: LocalTime? = null,
): BookingStamps? {
    val today = today()
    val date = bookedOn ?: today
    val now = nowUtcMillis()
    val isToday = date == today
    // A back-dated row is *already* posted: the day has arrived and the money has moved, so
    // `postDue` must never pick it up again (ADR-0011). Only a future row is left unposted.
    val hasArrived = !date.isAfter(today)
    return BookingStamps(
        bookedOnIsoDate = date.toString(),
        occurredAtUtcMillis =
            when {
                atTime != null -> date.atTime(atTime).atZone(zone()).toInstant().toEpochMilli()
                isToday -> now
                else -> startOfDay(date)
            },
        // Unchanged by the time: **posting is a property of the day, not the hour** (ADR-0010). A
        // row booked for later today is already in today's balance, so it is posted now; one booked
        // for tomorrow at 09:00 is not posted at all until tomorrow, whatever hour was chosen.
        postedAtUtcMillis = now.takeIf { hasArrived },
        nowUtcMillis = now,
    )
}
