package com.aicfo.domain.engines.marketsignal

import com.aicfo.core.model.Money

/**
 * The arithmetic AI-MKT runs on (issue 10.7; MNY-002, P-08).
 *
 * Why:  five of the seven signals are computed from a price series, and each is a small, standard
 *       calculation that is easy to get subtly wrong — an RSI whose first average uses the wrong
 *       window, a percentile that counts the value against itself, a moving average that silently
 *       uses whatever days it has. They live here, separately, so each can be tested on its own and
 *       so the engine reads as a list of signals rather than a page of arithmetic.
 * What: proportions in basis points, percentiles, the moving average, RSI and the down-day streak.
 * Result: integers. **No floating-point value touches any of this** (MNY-001/002): a proportion is
 *         basis points, a percentile is a whole number, and a price is paise.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 */
internal object MarketMath {
    /** 10 000 basis points is one whole (MNY-002). */
    const val BPS_FULL = 10_000L

    /** One hundred percent. */
    private const val PERCENT_FULL = 100L

    /** Two halves, so the mid-rank percentile stays in whole numbers. */
    private const val HALVES = 2

    /**
     * Result: how far [value] sits below [reference], in basis points — negative below, positive
     *         above, `null` when the reference is zero and the proportion is meaningless.
     * Input:  [value]; [reference]. Output: `Int?`.
     */
    fun deviationBps(
        value: Money,
        reference: Money,
    ): Int? =
        if (reference.minor == 0L) {
            null
        } else {
            ((value.minor - reference.minor) * BPS_FULL / reference.minor).toInt()
        }

    /**
     * Result: the highest close in [window], or `null` when it is empty.
     * Input: [window]. Output: `Money?`.
     */
    fun highest(window: List<DailyClose>): Money? = window.maxByOrNull { it.close.minor }?.close

    /**
     * Result: the mean close of [window] in paise, or `null` when it is empty.
     * Why:    integer division, truncating — a moving average of prices does not need a fraction of
     *         a paise, and rounding it would be a second convention nobody could reproduce.
     * Input:  [window]. Output: `Money?`.
     */
    fun average(window: List<DailyClose>): Money? =
        if (window.isEmpty()) null else Money(window.sumOf { it.close.minor } / window.size)

    /**
     * Where a value sits in a window, as a whole percentile.
     * Why:    "bottom decile" has to mean something exact, and **ties are the whole problem**. The
     *         obvious definition — the fraction strictly below — puts a perfectly flat series at
     *         the 0th percentile, which reads as "today is the cheapest day of the year" when it is
     *         the most ordinary one. The first draft did exactly that and scored a flat market five
     *         points for rarity. This is the mid-rank: everything below, plus half of everything
     *         equal. A flat series comes out at 50, and a genuine all-time low still comes out at 0.
     * Result: 0..100, or `null` for an empty window. Input: [value]; [window]. Output: `Int?`.
     */
    fun percentile(
        value: Money,
        window: List<DailyClose>,
    ): Int? {
        if (window.isEmpty()) return null
        val below = window.count { it.close.minor < value.minor }
        val equal = window.count { it.close.minor == value.minor }
        // Everything below, plus half of everything equal — expressed in halves so it stays whole.
        return (((below * HALVES + equal) * PERCENT_FULL) / (window.size * HALVES)).toInt()
    }

    /**
     * Wilder's RSI over [period] closes, as a whole number.
     * Why:    the KB names RSI(14) and RSI has one definition worth having: an exponential average
     *         of gains against losses, seeded by the simple average of the first window. The naive
     *         "average of the last 14 changes" is a different indicator that happens to look like
     *         this one, and it is the one people reach for by accident.
     * Result: 0..100, or `null` when there are fewer than `period + 1` closes.
     * Input:  [closes] — ascending by date; [period]. Output: `Int?`.
     */
    fun rsi(
        closes: List<DailyClose>,
        period: Int,
    ): Int? {
        if (closes.size <= period) return null
        val changes = closes.zipWithNext { previous, next -> next.close.minor - previous.close.minor }
        var gain = changes.take(period).filter { it > 0 }.sum() / period
        var loss = changes.take(period).filter { it < 0 }.sumOf { -it } / period
        changes.drop(period).forEach { change ->
            val up = if (change > 0) change else 0L
            val down = if (change < 0) -change else 0L
            gain = (gain * (period - 1) + up) / period
            loss = (loss * (period - 1) + down) / period
        }
        // No losses at all is the textbook 100: everything that moved, moved up.
        return if (loss == 0L) PERCENT_FULL.toInt() else (PERCENT_FULL - (PERCENT_FULL * loss) / (gain + loss)).toInt()
    }

    /**
     * Result: how many consecutive days to the end of [closes] closed lower than the day before.
     * Input:  [closes] — ascending by date. Output: [Int].
     */
    fun downStreak(closes: List<DailyClose>): Int =
        closes
            .zipWithNext { previous, next -> next.close.minor < previous.close.minor }
            .reversed()
            .takeWhile { it }
            .size
}
