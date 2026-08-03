package com.aicfo.data.repository

import com.aicfo.core.common.Clock
import com.aicfo.core.common.startOfDay
import java.time.LocalDate

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
 *         **A past date is refused.** Back-dating is not what this issue is for, and it is not
 *         harmless: `net_worth_snapshot` already holds one written row per past day, and nothing
 *         recomputes them, so a row inserted into last week would make the sparkline disagree with
 *         today's figure. Issue 3.6 owns editing, and can revisit it with the recompute it needs.
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
 * Result: the stamps, or `null` when [bookedOn] is before today — which the caller reports as
 *         `AppError.Validation("bookedOn")`. `null` rather than an exception because §5 forbids
 *         exceptions across a layer boundary.
 * Input:  the receiver — the injected [Clock] (TIM-001, never the wall clock); [bookedOn] — the day
 *         the user picked, or `null` to mean today, which is what every caller before issue 3.4
 *         meant implicitly.
 * Output: `BookingStamps?`.
 * Changelog: 2026-08-03 — Created for issue 3.4 (FR-TXN-010).
 */
internal fun Clock.stampsFor(bookedOn: LocalDate?): BookingStamps? {
    val today = today()
    val date = bookedOn ?: today
    if (date.isBefore(today)) return null
    val now = nowUtcMillis()
    val isToday = date == today
    return BookingStamps(
        bookedOnIsoDate = date.toString(),
        occurredAtUtcMillis = if (isToday) now else startOfDay(date),
        postedAtUtcMillis = now.takeIf { isToday },
        nowUtcMillis = now,
    )
}
