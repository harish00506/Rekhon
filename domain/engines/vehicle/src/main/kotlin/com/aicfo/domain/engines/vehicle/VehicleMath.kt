package com.aicfo.domain.engines.vehicle

import com.aicfo.core.model.Money
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The arithmetic AI-VEH runs on (issue 10.4; MNY-001/002, P-08).
 *
 * Why:  two calculations decide everything this engine says, and both are easy to get subtly
 *       wrong. The first turns odometer readings into a rate: a mean of the deltas lets one
 *       mistyped reading rewrite a household's whole maintenance plan, so the rate is the **median
 *       of the pairwise slopes** (Theil–Sen). The second turns a service history into a price: a
 *       mean again would let one clutch replacement predict the next oil change, so it is a median
 *       ratio, clamped.
 * What: the slope, the median, the personal cost index, and the day-count conversions the two need.
 * Result: whole kilometres per month and integer basis points — no floating-point value touches a
 *         monetary or rate path (MNY-001, MNY-002).
 * Changelog: 2026-09-26 — Created for issue 10.4.
 *
 * **A month here is thirty days.** Readings arrive whenever the user happens to look at the dial,
 * so a rate has to come from a day count; thirty is the conversion, declared once, used everywhere,
 * and stated on screen wherever a distance-based date is shown.
 */
internal object VehicleMath {
    /** The day count one "month per" rate is expressed in. */
    const val DAYS_PER_MONTH = 30L

    /**
     * The distance this vehicle covers in a month, as the median of every pairwise slope.
     * Why:    with n readings there are n(n−1)/2 slopes between them. A typo poisons the ones it
     *         takes part in and leaves the rest untouched, so their median survives it — which a
     *         mean, or a first-to-last difference, does not.
     * Result: whole km per month, or `0` when fewer than two readings fall inside the window —
     *         zero meaning "not enough to say", never "it does not move".
     * Input:  [readings] — date-and-distance pairs, any order; [windowStart] — the oldest date that
     *         still counts; [minimumReadings] — the KB's threshold.
     * Output: [Long].
     */
    fun kmPerMonth(
        readings: List<DatedReading>,
        windowStart: LocalDate,
        minimumReadings: Int,
    ): Long {
        val inWindow = readings.filter { !it.date.isBefore(windowStart) }.sortedBy { it.date }
        if (inWindow.size < minimumReadings || inWindow.size < 2) return 0L
        val slopes =
            inWindow.indices.flatMap { i ->
                (i + 1 until inWindow.size).mapNotNull { j -> slopeBetween(inWindow[i], inWindow[j]) }
            }
        return if (slopes.isEmpty()) 0L else median(slopes)
    }

    /**
     * Result: the km-per-month slope between two readings, or `null` when they share a day (which
     *         would divide by zero rather than describe a rate).
     * Input:  [from]; [to]. Output: `Long?`.
     */
    private fun slopeBetween(
        from: DatedReading,
        to: DatedReading,
    ): Long? {
        val days = ChronoUnit.DAYS.between(from.date, to.date)
        return if (days <= 0L) null else (to.km - from.km) * DAYS_PER_MONTH / days
    }

    /**
     * The middle value, or the mean of the middle two.
     * Why:    a median is the whole point of this file; having it in one place means the slope and
     *         the cost index cannot disagree about what "middle" means.
     * Result: the median. Input: [values] — non-empty. Output: [Long].
     */
    fun median(values: List<Long>): Long {
        require(values.isNotEmpty()) { "the median of nothing is not a number" }
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }

    /**
     * How this household's service prices compare with the knowledge base's range.
     * Why:    the KB's ranges are national starting estimates. A household that services at an
     *         authorised workshop in Mumbai and one that uses a local garage in Nashik are not
     *         paying the same money, and after two services the app knows which it is dealing with.
     * Result: the median ratio in basis points (10 000 = exactly the book midpoint), clamped to the
     *         KB's floor and ceiling; `null` when there are too few services to say — in which case
     *         the caller shows the book range unchanged rather than a one-sample guess.
     * Input:  [services] — what was actually paid; [classMidpoint] — the KB midpoint for this class;
     *         [spec] — the KB's prediction parameters.
     * Output: `Int?`.
     */
    fun personalIndexBps(
        services: List<Money>,
        classMidpoint: Money,
        spec: PredictionSpec,
    ): Int? {
        if (services.size < spec.minServicesForPersonalIndex || classMidpoint.minor <= 0L) return null
        val ratios = services.map { it.minor * BPS_FULL / classMidpoint.minor }
        return median(ratios).coerceIn(spec.personalIndexFloorBps.toLong(), spec.personalIndexCeilingBps.toLong())
            .toInt()
    }

    /**
     * Result: how many days of driving it takes to cover [km] at [kmPerMonth], or `null` when the
     *         rate is unknown. Input: [km] — may be negative when the point is already past;
     *         [kmPerMonth]. Output: `Long?`.
     */
    fun daysToCover(
        km: Long,
        kmPerMonth: Long,
    ): Long? = if (kmPerMonth <= 0L) null else km * DAYS_PER_MONTH / kmPerMonth

    /** 10 000 basis points is one whole (MNY-002). */
    private const val BPS_FULL = 10_000L
}

/**
 * An odometer reading with its date already parsed.
 * Input:  [date]; [km]. Output: an immutable value.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
internal data class DatedReading(
    val date: LocalDate,
    val km: Long,
)
