package com.aicfo.data.repository

import com.aicfo.domain.engines.investment.HoldingPerformance
import com.aicfo.domain.engines.investment.PriceFreshness

/**
 * A holding's figures, and how much to trust the price behind them (issue 6.5; §16 EXT-002, P-02).
 *
 * Why:  the two come from different engine operations for a reason that is not arbitrary.
 *       `HoldingPerformance` is a function of stored facts alone — the same holding reports the
 *       same return tomorrow, which is what P-08 requires. Freshness is definitionally a function
 *       of *today*. Folding the second into the first would have made the first move with the
 *       calendar, so they are computed separately and joined here, where a clock legitimately
 *       exists.
 *
 *       That is also why this is a wrapper in `:data:repository` rather than two more fields on
 *       `HoldingPerformance`: adding them would have changed the engine's public surface and every
 *       golden file that pins it, to save one type.
 * What: the performance, and the verdict on the price it was computed from.
 * Result: what `:feature:accounts` renders — a value, and beneath it how old the number is.
 * Changelog: 2026-08-29 — Created for issue 6.5.
 *
 * @property performance the value, cost, gain and money-weighted return (issue 6.3).
 * @property freshness whether the price behind them is fresh, stale, or absent. **Never null** —
 *   "never priced" is a verdict, not an absence, so the screen always has something to say (P-02),
 *   the same way `XirrUnavailable` gives it a reason rather than an empty cell.
 */
data class PricedHolding(
    val performance: HoldingPerformance,
    val freshness: PriceFreshness,
)
