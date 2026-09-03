package com.aicfo.data.repository

import com.aicfo.core.model.Money
import com.aicfo.domain.engines.nature.NatureBreakdown

/**
 * One closed month of the ledger, classified (issue 7.2; §8.3, §10.1).
 *
 * Why:  everything in this app that sizes a *habit* rather than reporting a *moment* needs several
 *       months at once, and until issue 7.2 nothing produced them. `observeNatureBreakdown` answers
 *       "this month", which is the right window for a dashboard ring and the wrong one for an
 *       emergency-fund target: a single month carries whatever annual bill happened to land in it.
 * What: the month's key, its nature breakdown, and its income.
 * Result: the shape `EmergencyFundRepository` takes a median and a volatility reading from — and the
 *         shape 9.x's forecast backtests will want when the forecast engine stops being a stub.
 * Changelog: 2026-09-02 — Created for issue 7.2.
 *
 * **Income is a field of its own rather than a sixth total on [nature].** §8.3 asks what money
 * *became*, and `natureBreakdown` excludes `INCOME` and `ADJUSTMENT` outright because neither is
 * money leaving. Adding an income term to `NatureBreakdown` would make `trueSpend` and the 50/30/20
 * rings answerable from a type that no longer means only "what was spent".
 *
 * @property monthKey the month as ISO `yyyy-MM` (TIM-002) — a date-only key, never a timestamp, and
 *   built the same way [MonthWindow] builds its own so the two cannot drift.
 * @property nature what the month's outflows became: needs, wants, invested, assets, liabilities.
 * @property income what arrived from outside that month, as a positive magnitude.
 */
data class MonthlyLedger(
    val monthKey: String,
    val nature: NatureBreakdown,
    val income: Money,
)
