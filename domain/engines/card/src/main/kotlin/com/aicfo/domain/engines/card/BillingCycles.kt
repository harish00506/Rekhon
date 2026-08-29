package com.aicfo.domain.engines.card

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Turns two days-of-month into the dates a card actually bills on (issue 6.1; FR-ACC-002).
 *
 * Why:  FR-ACC-002 stores a *statement day* and a *due day*, not dates, because a card bills on the
 *       same day every month. Resolving those into real dates has three traps, and all three are
 *       silent — each produces a plausible date that is simply the wrong one:
 *
 *       **A day that does not exist.** A card billing on the 31st has no statement date in
 *       February. `LocalDate.of(2026, 2, 31)` throws; naively rolling to March 3rd would move the
 *       statement into the next cycle. It clamps to the last day of the month, which is what every
 *       issuer does.
 *
 *       **A due day before the statement day.** Statement on the 25th, due on the 5th is the
 *       ordinary case, not an odd one — the payment belongs to the *following* month. Reading them
 *       as two days in one month would produce a due date twenty days before the statement was cut.
 *
 *       **The boundary itself.** On the statement day, that day's statement is the current one; on
 *       the due date, the payment is due today, not overdue. Both are `>=`/`<=` decisions that a
 *       user only notices when they are wrong at exactly the moment they matter.
 * What: the most recent statement on or before a date, the due date that closes it, the next
 *       statement date, and the whole days between.
 * Result: a [BillingCycle], reproducible from its inputs alone (P-08).
 * Changelog: 2026-08-17 — Created for issue 6.1.
 *
 * `internal` — the public seam is [CardEngine.cycle] (ARC-003). **No clock** (TIM-001): the date is
 * the caller's. Calendar arithmetic on that date is deliberate and has precedent in
 * `DefaultRecurringEngine`.
 */
internal object BillingCycles {
    /**
     * Places [today] inside the cycle.
     *
     * Why:    walks back to the statement that has already been cut, rather than forward to the
     *         next one, because everything a user is told about a card concerns money they have
     *         already spent. The due date is then derived from *that* statement, so a cycle is
     *         always statement-then-payment and never the other way round.
     * Result: the [BillingCycle] containing [today].
     * Input:  [today]; [statementDay], [dueDay] — 1..31, already validated by `CreditCard`.
     * Output: [BillingCycle].
     */
    fun of(
        today: LocalDate,
        statementDay: Int,
        dueDay: Int,
    ): BillingCycle {
        val thisMonth = today.withDayClamped(statementDay)
        // `!isAfter`, not `isBefore`: on the statement day itself, that day's statement is the
        // current one. Using isBefore would send the user back a whole month every 5th.
        val statementDate =
            if (!thisMonth.isAfter(today)) thisMonth else today.minusMonths(1).withDayClamped(statementDay)
        val dueDate = dueDateFor(statementDate, dueDay)

        return BillingCycle(
            statementDate = statementDate,
            dueDate = dueDate,
            nextStatementDate = statementDate.plusMonths(1).withDayClamped(statementDay),
            // Signed and from `today`, so "due in 3 days" and "4 days overdue" are the same
            // subtraction. ChronoUnit.DAYS on two LocalDates is a whole-day count with no time zone
            // in it — the zone was already applied when the caller produced `today`.
            daysUntilDue = ChronoUnit.DAYS.between(today, dueDate).toInt(),
        )
    }

    /**
     * Finds when a given statement must be paid.
     * Why:    the due day belongs to the statement, not to the calendar month. When the due day is
     *         at or before the statement day the payment falls in the next month — the common
     *         "statement on the 25th, pay by the 5th" card. Comparing the resolved *dates* rather
     *         than the raw day numbers keeps the clamp honest: a statement clamped to Feb 28 with a
     *         due day of 28 is due in March, not the same afternoon.
     * Result: the payment date, always strictly after [statementDate].
     * Input:  [statementDate]; [dueDay] — 1..31. Output: [LocalDate].
     */
    private fun dueDateFor(
        statementDate: LocalDate,
        dueDay: Int,
    ): LocalDate {
        val sameMonth = statementDate.withDayClamped(dueDay)
        return if (sameMonth.isAfter(statementDate)) sameMonth else statementDate.plusMonths(1).withDayClamped(dueDay)
    }

    /**
     * Sets the day of month, clamping to the month's length.
     * Why:    the one line that stops a day-31 card throwing every February. `lengthOfMonth()` is
     *         leap-year aware, so 2028-02-29 resolves where 2026-02-28 does.
     * Result: a date in the receiver's month. Input: [day] — 1..31. Output: [LocalDate].
     */
    private fun LocalDate.withDayClamped(day: Int): LocalDate = withDayOfMonth(minOf(day, lengthOfMonth()))
}
