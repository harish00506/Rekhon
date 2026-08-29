package com.aicfo.data.repository

import java.time.LocalDate

/**
 * A calendar month, as the pair of ISO dates every date-windowed query in this module takes.
 *
 * Why:  three features now ask the same question — "which rows belong to this month?" — and each one
 *       that computes `withDayOfMonth(1)` and `lengthOfMonth()` inline is another place the month
 *       can be defined slightly differently. Issue 4.3 inlined it in `observeNatureBreakdown`, and
 *       its tracker flagged that issue 4.4 would want the same window; two copies is the point at
 *       which the third becomes inevitable, and a budget that disagrees with the 50/30/20 rings
 *       about where March ends is a bug nobody would think to look for.
 * What: the month's first and last day as ISO `yyyy-MM-dd` strings (TIM-002), plus the day count and
 *       elapsed-day count `BudgetEngine` needs to compute pace without a clock of its own.
 * Result: one definition of a month, shared by every caller that needs one.
 * Changelog: 2026-08-11 — Created for issue 4.4, extracting the window
 *            `TransactionRepository.observeNatureBreakdown` had inlined since issue 4.3.
 *
 * **Built from a `LocalDate` the caller already resolved in the profile zone** (TIM-001). Nothing
 * here reads a clock; `Clock.today()` is the only sanctioned source and it lives one layer up.
 *
 * Input:  [startIsoDate] — the first of the month; [endIsoDate] — the last day; [daysInMonth];
 *         [daysElapsed] — days gone including today, or [daysInMonth] for a month already past.
 * Output: an immutable value.
 */
internal data class MonthWindow(
    val startIsoDate: String,
    val endIsoDate: String,
    val daysInMonth: Int,
    val daysElapsed: Int,
) {
    /**
     * The last day that counts as **actual** spending.
     * Why:    FR-TXN-010 says future-dated transactions are excluded from actuals but included in
     *         forecasts, and a budget's "spent" is an actual. Capping the query's upper bound at
     *         today is how that exclusion happens — cheaper and more honest than a `date('now')` in
     *         SQL, which would be a wall-clock read inside a statement (TIM-001).
     * Result: today's date for the current month, the month's end for one already closed.
     * Input:  none. Output: an ISO `yyyy-MM-dd` string.
     */
    val actualsEndIsoDate: String
        get() = if (daysElapsed >= daysInMonth) endIsoDate else startIsoDate.replaceDayOfMonth(daysElapsed)

    private fun String.replaceDayOfMonth(day: Int): String =
        "${substring(0, ISO_MONTH_LENGTH)}-${day.toString().padStart(2, '0')}"

    companion object {
        /** `yyyy-MM` — the prefix a month key and a day substitution both work from (TIM-002). */
        const val ISO_MONTH_LENGTH = 7

        /**
         * The month containing [today], seen from [today].
         * Why:    the live month is the only one where "elapsed" is not the whole month, and getting
         *         that wrong in the generous direction would make every safe-pace figure look
         *         achievable on the 1st.
         * Result: the window, with `daysElapsed` counted inclusively — the 1st is one day gone, not
         *         zero, because a run rate cannot divide by zero and because the day the user is
         *         living through is a day their budget is being spent in.
         * Input:  [today] — resolved in the profile zone by the caller. Output: [MonthWindow].
         */
        fun current(today: LocalDate): MonthWindow = of(today.withDayOfMonth(1), elapsedAsAt = today)

        /**
         * A month that has already ended.
         * Why:    the suggestion's history is whole months; treating them as partially elapsed would
         *         make a completed March look like it was still being spent in.
         * Result: the window, fully elapsed.
         * Input:  [monthStart] — the first of that month. Output: [MonthWindow].
         */
        fun closed(monthStart: LocalDate): MonthWindow = of(monthStart, elapsedAsAt = null)

        private fun of(
            monthStart: LocalDate,
            elapsedAsAt: LocalDate?,
        ): MonthWindow {
            val start = monthStart.withDayOfMonth(1)
            val length = start.lengthOfMonth()
            return MonthWindow(
                startIsoDate = start.toString(),
                endIsoDate = start.withDayOfMonth(length).toString(),
                daysInMonth = length,
                daysElapsed = elapsedAsAt?.dayOfMonth ?: length,
            )
        }
    }
}
