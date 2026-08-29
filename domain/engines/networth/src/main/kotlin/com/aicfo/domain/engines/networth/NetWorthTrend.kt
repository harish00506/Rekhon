package com.aicfo.domain.engines.networth

import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money

/**
 * One stored day of the series (issue 6.6; FR-ACC-005, TIM-002).
 *
 * Why:  deliberately **not** [NetWorthResult]. A chart needs a date and a figure; carrying the
 *       assets/liabilities subtotals and a provenance stamp for every one of up to 730 days would
 *       be hundreds of objects of ballast to draw one line. It is also the narrower type: a point
 *       cannot be mistaken for something the engine computed, because a point is something the
 *       engine *read*.
 * What: a day and what net worth was on it.
 * Result: the element type of a trend.
 * Changelog: 2026-08-29 — Created for issue 6.6.
 *
 * Input:  [asOfIsoDate] — the day, ISO `yyyy-MM-dd` in the profile zone (TIM-002); [netWorth] —
 *         **signed** paise (MNY-001), negative for a user who owes more than they hold.
 * Output: an immutable value.
 */
data class NetWorthPoint(
    val asOfIsoDate: String,
    val netWorth: Money,
)

/**
 * The four windows FR-ACC-005 names (issue 6.6).
 *
 * Why:  four, and exactly the four the requirement lists — *"with 1M/6M/1Y/All charts"*. This is an
 *       enum rather than a day count because the requirement is written in calendar terms, and
 *       "one month" is not thirty days: resolving it needs `LocalDate.minusMonths` on the profile's
 *       calendar, which is the repository's job because it needs a clock (TIM-001).
 *
 *       **Not a rulebook row** (CLAUDE.md §6). These are the SRS's own UI ranges, not financial
 *       thresholds — nothing about a chart's window decides money. Putting them in `ai/rules/` would
 *       dress an affordance as a policy.
 * What: the window the user picked.
 * Result: carried into provenance as the input window (AI-ARC-003), so a trend says what it covers.
 * Changelog: 2026-08-29 — Created for issue 6.6.
 */
enum class NetWorthRange {
    /** The last calendar month. */
    ONE_MONTH,

    /** The last six calendar months. */
    SIX_MONTHS,

    /** The last calendar year. */
    ONE_YEAR,

    /** Every stored snapshot, however far back it goes. */
    ALL,
}

/**
 * Everything the trend is a function of (issue 6.6).
 *
 * Why:  **the points arrive already bounded to [range], and no clock is read here** (TIM-001,
 *       P-08). Selecting the window needs today's date in the profile zone, which is a storage-layer
 *       question; measuring what is in the window is arithmetic. Splitting it that way is what lets
 *       every case in the golden file be reproducible to the byte.
 *
 *       [range] is therefore **not used to filter** — it is carried so the result can say which
 *       window it describes. An engine that re-filtered would be doing the caller's job with worse
 *       information.
 * What: the series, the window it came from, and the instant for provenance.
 * Result: the argument to [NetWorthEngine.trend].
 * Changelog: 2026-08-29 — Created for issue 6.6.
 *
 * Input:  [points] — the stored snapshots, oldest first; empty is legitimate; [range] — the window
 *         the caller applied; [nowUtcMillis] — stamped into provenance, never read as "now".
 * Output: an immutable value.
 */
data class NetWorthTrendInput(
    val points: List<NetWorthPoint>,
    val range: NetWorthRange,
    val nowUtcMillis: Long,
)

/**
 * Which way net worth has gone, and by how much (issue 6.6; FR-ACC-005, P-02, P-03).
 *
 * Why:  **every summary figure here is nullable, and that is the design rather than an oversight.**
 *       A series of nothing has no first day; a series of one reading has no change. P-03 draws a
 *       hard line between a figure that is absent and one that is zero, and the two are easiest to
 *       confuse exactly here — a chart showing "₹0 change" for a profile with one snapshot would be
 *       reporting stability that nobody measured.
 * What: the series itself, its endpoints, its extremes, and the change between the endpoints.
 * Result: what the history screen renders — the line from [points], the figures from the rest.
 * Changelog: 2026-08-29 — Created for issue 6.6.
 *
 * @property points every stored day in the window, oldest first, exactly as read.
 * @property first the earliest point, or `null` when the window holds nothing.
 * @property last the latest point, or `null` when the window holds nothing. Equal to [first] when
 *   there is exactly one reading.
 * @property change [last] minus [first] in paise, or `null` when there are fewer than two readings —
 *   a change measured over a zero-length interval is unknown, not zero.
 * @property changeBps the same change as integer basis points of the starting value (MNY-002), or
 *   `null` when a percentage would mislead: see [Trend.changeBps] for why that is most of the time.
 * @property high the largest value in the window, earliest on a tie, or `null` when empty.
 * @property low the smallest value in the window, earliest on a tie, or `null` when empty.
 * @property provenance which engine and version decided this, and over which window (AI-ARC-003).
 */
data class NetWorthTrend(
    val points: List<NetWorthPoint>,
    val first: NetWorthPoint?,
    val last: NetWorthPoint?,
    val change: Money?,
    val changeBps: Int?,
    val high: NetWorthPoint?,
    val low: NetWorthPoint?,
    val provenance: EngineProvenance,
) {
    init {
        require(points.isEmpty() == (first == null)) {
            "A non-empty series has a first point and an empty one has none — never one without the other"
        }
        require((first == null) == (last == null)) {
            "Endpoints come as a pair: a series with a first point has a last one, even if they are the same day"
        }
        require(points.size >= MIN_POINTS_FOR_CHANGE || change == null) {
            "A change needs two readings to measure between; with ${points.size} it is unknown, not zero (P-03)"
        }
        require(change != null || changeBps == null) {
            "A percentage of a change that was never measured is not a smaller claim, it is a made-up one"
        }
    }

    companion object {
        /** Below this there is no interval, so there is no change to report. */
        const val MIN_POINTS_FOR_CHANGE = 2
    }
}

/**
 * The trend arithmetic (issue 6.6).
 *
 * Why:  kept out of [DefaultNetWorthEngine] for the reason the sibling engines keep `Xirr`,
 *       `Allocation` and `Freshness` out of theirs — the engine class assembles a result and stamps
 *       provenance, and folding integer ratio maths into that is how a sign error ends up somewhere
 *       nobody looks for it.
 * What: endpoints, extremes, the change, and the one judgement in this file.
 * Result: everything [NetWorthTrend] carries except its provenance.
 * Changelog: 2026-08-29 — Created for issue 6.6.
 */
internal object Trend {
    /**
     * The change between the endpoints, in paise.
     * Why:    `Money.minus` is checked arithmetic (MNY-001), so an overflow becomes a thrown
     *         programmer error rather than a wrapped negative fortune.
     * Result: `last − first`, or `null` when there are fewer than two readings.
     * Input:  [points] — oldest first. Output: [Money]?.
     * Changelog: 2026-08-29 — Created for issue 6.6.
     */
    fun change(points: List<NetWorthPoint>): Money? =
        if (points.size < NetWorthTrend.MIN_POINTS_FOR_CHANGE) {
            null
        } else {
            points.last().netWorth - points.first().netWorth
        }

    /**
     * The change as a share of where it started — when that means anything.
     *
     * Why:    **the one judgement in this file, and it is a refusal.** A percentage needs a
     *         denominator that is a magnitude, and net worth is not: it is routinely negative for
     *         someone with a loan and little saved, and it passes through zero on the way up. Going
     *         from −₹50,000 to −₹10,000 is an improvement of ₹40,000, and calling it "+80%" states
     *         the opposite of what happened, because dividing by a negative flips the sign of a
     *         number the user reads as progress. Starting at exactly zero is the same lie by
     *         division-by-zero. So the test is `> 0`, never `>= 0`, and the answer when it fails is
     *         **no percentage at all** rather than a signed or clamped one — P-03 would rather show
     *         the user nothing than something it cannot stand behind. The absolute change is always
     *         reported, and for these users it is the honest figure anyway.
     *
     *         Integer basis points throughout (MNY-002); no `Double` touches this. `multiplyExact`
     *         first, so precision is not lost before the division, and the division truncates toward
     *         zero — a user a hair under a percent is never rounded up into it.
     * Result: basis points, or `null` when the starting value is not strictly positive or there was
     *         no interval to measure.
     * Input:  [points] — oldest first; [change] — the value [change] returned, passed rather than
     *         recomputed so the two can never disagree.
     * Output: `Int?`.
     * Changelog: 2026-08-29 — Created for issue 6.6.
     */
    fun changeBps(
        points: List<NetWorthPoint>,
        change: Money?,
    ): Int? {
        val start = points.firstOrNull()?.netWorth?.minor ?: return null
        if (change == null || start <= 0L) return null
        return (Math.multiplyExact(change.minor, BPS_FULL) / start).toInt()
    }

    /**
     * The largest value in the window, earliest on a tie.
     * Why:    `maxByOrNull` keeps the first maximum it meets, and the points arrive oldest first, so
     *         a flat series names the day it started rather than an arbitrary one. That is what
     *         keeps the golden file reproducible to the byte (P-08).
     * Result: the point, or `null` when the series is empty.
     * Input:  [points]. Output: [NetWorthPoint]?.
     * Changelog: 2026-08-29 — Created for issue 6.6.
     */
    fun high(points: List<NetWorthPoint>): NetWorthPoint? = points.maxByOrNull { it.netWorth.minor }

    /**
     * The smallest value in the window, earliest on a tie.
     * Result: the point, or `null` when empty. Input: [points]. Output: [NetWorthPoint]?.
     * Changelog: 2026-08-29 — Created for issue 6.6.
     */
    fun low(points: List<NetWorthPoint>): NetWorthPoint? = points.minByOrNull { it.netWorth.minor }

    /** One hundred percent in basis points (MNY-002). */
    private const val BPS_FULL = 10_000L
}
