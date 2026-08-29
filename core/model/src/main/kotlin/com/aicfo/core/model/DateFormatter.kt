package com.aicfo.core.model

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle

/**
 * Renders a stored ISO date as a person reads it (§21.6, TIM-002).
 *
 * Why:  a booked day is stored as an ISO `yyyy-MM-dd` string, which is exactly right for sorting,
 *       grouping and SQL range bounds — and exactly wrong to put in front of a user. This is the
 *       one place that conversion happens, for the same reason [MoneyFormatter] is the one place a
 *       `Money` becomes "₹1,23,456.78": two implementations are two chances to disagree about what
 *       a date looks like, and the second one is always the one nobody reviewed.
 *
 *       **It lives in `:core:model`, beside [MoneyFormatter], rather than in a feature.** It was a
 *       private helper in `:feature:transactions` until the dashboard needed the same thing (issue
 *       5.1) and could not have it — ARC-001 forbids one feature depending on another, so the
 *       choice was to duplicate the formatter or to move it below both. The dashboard shipped with
 *       a raw `2026-08-15` on screen because neither had been done; a 2026-08-16 review caught it.
 * What: locale-aware date rendering, with the raw value as its own fallback.
 * Result: every screen that shows a stored date shows the same thing.
 * Changelog: 2026-08-16 — Created, lifting `TransactionLabels.dayHeader` (issue 3.1) here unchanged.
 *
 * **Reads no clock.** It formats a date it is given rather than comparing it to today, which is why
 * there is no "Today"/"Yesterday" wording — that needs the profile zone's current date, and the only
 * sanctioned source of that is the injected `Clock` (TIM-001), which a formatter has no business
 * holding.
 */
object DateFormatter {
    /**
     * Renders one stored day.
     * Why:    `FormatStyle.MEDIUM` follows the device locale, so an Indian user sees "15 Aug 2026"
     *         and an American one sees "Aug 15, 2026" without this object hardcoding either (§21.6).
     * Result: the localised date, or **the input unchanged** when it is not a date this build can
     *         parse — a row showing the raw value is better than a list that crashes on one bad row.
     * Input:  [isoDate] — ISO `yyyy-MM-dd` (TIM-002). Output: [String].
     * Changelog: 2026-08-16 — Moved here from `TransactionLabels.dayHeader`, behaviour unchanged.
     */
    fun day(isoDate: String): String =
        try {
            LocalDate.parse(isoDate).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        } catch (_: DateTimeParseException) {
            isoDate
        }

    /**
     * Whether a string names a day that exists.
     * Why:    `LocalDate.parse` signals failure by throwing, and a `require` needs a boolean.
     *         Keeping the `try` here rather than inline in a constructor keeps that constructor
     *         readable and gives one place that decides what "a date" means for the whole model
     *         layer. It was a private helper in `Loan.kt` until issue 6.3 needed the identical
     *         check on two more dated types; duplicating it would have been three chances to
     *         disagree about whether `2026-02-30` is a date.
     * Result: `true` for an ISO `yyyy-MM-dd` naming a day that exists — so `2028-02-29` passes and
     *         `2026-02-30` does not — `false` otherwise, including for a different format.
     * Input:  [isoDate] — the candidate string (TIM-002). Output: [Boolean].
     * Changelog: 2026-08-24 — Moved here from `Loan.kt`'s private `isCalendarDate` for issue 6.3,
     *            behaviour unchanged.
     */
    fun isCalendarDate(isoDate: String): Boolean =
        try {
            LocalDate.parse(isoDate)
            true
        } catch (_: DateTimeParseException) {
            false
        }
}
