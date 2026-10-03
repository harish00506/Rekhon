package com.aicfo.domain.engines.appliance

import com.aicfo.core.model.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * The arithmetic AI-APP is made of (issue 13.2; §12, MNY-001, TIM-002).
 *
 * Why:  kept apart from [KbApplianceEngine] so the two things that can be wrong here can be tested
 *       separately: the *calendar*, where a month added to 31 January has to land somewhere
 *       defensible, and the *money*, where a running cost is four multiplications and one division
 *       and the division is the one that can lose paise. Both are pure functions of their
 *       arguments, so each has a property test rather than a worked example.
 * What: date shifting, day counting, the seasonal anchor, the midpoint of a range, and the running
 *       cost.
 * Result: the engine above reads as a sequence of decisions rather than as arithmetic.
 * Changelog: 2026-10-03 — Created for issue 13.2.
 */
internal object ApplianceMath {
    /**
     * Adds whole months to an ISO date.
     * Why:    `LocalDate.plusMonths` clamps 31 January + 1 month to 28 February rather than
     *         overflowing into March, which is the behaviour a service schedule wants: a machine
     *         serviced on the 31st is due at the end of the next month, not on the 3rd of the one
     *         after. Stated here because it is a decision, not an accident of the library.
     * Result: the shifted date as ISO `yyyy-MM-dd` (TIM-002).
     * Input:  [isoDate] — `yyyy-MM-dd`; [months] — may be zero or negative.
     * Output: [String].
     */
    fun plusMonths(
        isoDate: String,
        months: Int,
    ): String = LocalDate.parse(isoDate).plusMonths(months.toLong()).toString()

    /**
     * Counts whole days from [fromIsoDate] to [toIsoDate].
     * Result: positive when [toIsoDate] is later, negative when it has passed, 0 on the day.
     * Input:  two ISO dates. Output: [Long].
     */
    fun daysBetween(
        fromIsoDate: String,
        toIsoDate: String,
    ): Long = ChronoUnit.DAYS.between(LocalDate.parse(fromIsoDate), LocalDate.parse(toIsoDate))

    /**
     * The next occurrence of a seasonal anchor month, on or after a date.
     *
     * Why:    an AC is serviced before summer, every year, whatever happened last year — §12's
     *         "AC service pre-summer". So the date is a point in the calendar rather than an
     *         interval from the last visit, and the rule has to answer "which March?" the same way
     *         every time it is asked. **On or after**, not strictly after: asked on the 1st of
     *         March, the answer is today, because a service due today is due today rather than in
     *         a year.
     * Result: ISO `yyyy-MM-dd` for day [dayOfMonth] of the next [month].
     * Input:  [onOrAfterIsoDate]; [month] — 1..12; [dayOfMonth] — the KB's
     *   `seasonal_due_day_of_month`.
     * Output: [String].
     */
    fun nextSeasonal(
        onOrAfterIsoDate: String,
        month: Int,
        dayOfMonth: Int,
    ): String {
        val from = LocalDate.parse(onOrAfterIsoDate)
        val thisYear = LocalDate.of(from.year, month, dayOfMonth)
        return if (thisYear < from) thisYear.plusYears(1).toString() else thisYear.toString()
    }

    /**
     * The midpoint of a cost range.
     * Why:    a forecast line needs one number, and §12's own example quotes a range. The low end
     *         would flatter the forecast and the high end would frighten it, so the midpoint is the
     *         honest single figure — the same choice AI-VEH made.
     * Result: `(low + high) / 2`, rounded **half-even** so a run of midpoints does not drift upward
     *         the way half-up would (MNY-001).
     * Input:  [range]. Output: [Money].
     */
    fun midpoint(range: ApplianceCostRange): Money =
        Money(
            BigDecimal.valueOf(range.low.minor)
                .add(BigDecimal.valueOf(range.high.minor))
                .divide(BigDecimal.valueOf(2L), 0, RoundingMode.HALF_EVEN)
                .longValueExact(),
        )

    /**
     * What an appliance costs to run for a month.
     *
     * Why:    this is the figure nobody budgets, because it never arrives as a bill of its own —
     *         it is inside the electricity bill with everything else. Putting a rupee number on one
     *         appliance is what makes "the geyser costs more than the fridge" a thing a household
     *         can act on.
     * What:   `watts × minutes/day × days/month ÷ 60 000` is kWh for the month; multiplied by the
     *         tariff in paise per kWh it is paise. Done as **one** division at the end rather than
     *         converting to kWh first, so the intermediate is exact and only the final paise are
     *         rounded (MNY-001). `BigDecimal` with `HALF_EVEN`, matching `Money.percentOf`.
     * Result: paise per month; `Money.ZERO` when the appliance is never switched on.
     * Input:  [watts] — positive; [minutesPerDay] — 0..1440; [daysPerMonth] — the KB's 30;
     *   [tariffPaisePerKwh] — positive.
     * Output: [Money].
     */
    fun runningCostPerMonth(
        watts: Int,
        minutesPerDay: Int,
        daysPerMonth: Int,
        tariffPaisePerKwh: Int,
    ): Money {
        if (minutesPerDay == 0) return Money.ZERO
        val paise =
            BigDecimal.valueOf(watts.toLong())
                .multiply(BigDecimal.valueOf(minutesPerDay.toLong()))
                .multiply(BigDecimal.valueOf(daysPerMonth.toLong()))
                .multiply(BigDecimal.valueOf(tariffPaisePerKwh.toLong()))
                .divide(WATT_MINUTES_PER_KWH, 0, RoundingMode.HALF_EVEN)
        return Money(paise.longValueExact())
    }

    /**
     * Result: true when [value] parses as an ISO `yyyy-MM-dd` date (TIM-002).
     * Why:    here rather than in the engine because it is a calendar question, and because keeping
     *         `try`/`catch` out of the engine leaves its `validate` reading as a list of rules.
     * Input:  [value]. Output: [Boolean].
     */
    fun isIsoDate(value: String): Boolean =
        try {
            LocalDate.parse(value)
            true
        } catch (expected: DateTimeParseException) {
            false
        }

    /** 1 kWh = 1 000 W for 60 minutes. Written out so the divisor below is not a bare literal. */
    private const val WATT_MINUTES_IN_A_KWH = 1_000L * 60L

    /** The one divisor in the running cost (MNY-001). */
    private val WATT_MINUTES_PER_KWH = BigDecimal.valueOf(WATT_MINUTES_IN_A_KWH)
}
