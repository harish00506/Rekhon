package com.aicfo.domain.engines.card

import com.aicfo.core.model.Money

/**
 * How much of a credit limit is in use (issue 6.1; FR-ACC-002, MNY-002).
 *
 * Why:  one ratio, computed one way, used by both bases and by the alert — so the number on the
 *       screen and the number that decided to interrupt someone are provably the same arithmetic.
 *       Kept apart from [BillingCycles] because dates and ratios fail differently and a reader
 *       chasing one should not have to read the other.
 * What: integer basis points, and the derived amounts a card screen shows beside them.
 * Result: values reproducible from their inputs alone (P-08).
 * Changelog: 2026-08-17 — Created for issue 6.1.
 *
 * `internal` — the public seam is [CardEngine.status] (ARC-003). **No `Double`** (MNY-001/002).
 */
internal object CardUtilisations {
    /**
     * The ratio of used to limit, in basis points.
     *
     * Why:    scaled by 10 000 **before** dividing so integer division truncates exactly once.
     *         Truncation is the honest direction here for the same reason it is in
     *         `BudgetAlertBands`: 29.99% of a limit is not 30% of it, so a user is never told they
     *         crossed a line a rupee early.
     * Result: 0 or more; 10 000 at exactly the limit; `null` when [used] is unknown, which is not
     *         the same as zero — a card with no statement recorded has no statement utilisation, and
     *         rendering that as 0% would tell the user they owe nothing.
     * Input:  [used] — the numerator, a positive magnitude, or `null`; [limit] — positive, which
     *         `CreditCard` guarantees.
     * Output: [Int]?, saturated at [Int.MAX_VALUE]. A ₹1 limit with ₹10 crore against it genuinely
     *         overflows an `Int` of basis points; saturating keeps the band right (it is over
     *         either way) rather than wrapping negative and reporting no alert at all.
     */
    fun ratioBps(
        used: Money?,
        limit: Money,
    ): Int? {
        if (used == null || limit <= Money.ZERO) return null
        // A credit balance — a refund landed after the statement — is not negative utilisation.
        // It is nothing used.
        if (used <= Money.ZERO) return 0
        return (used.minor * BPS_FULL / limit.minor).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * What has been spent since the last statement was cut (FR-ACC-002's "current unbilled").
     *
     * Why:    derived, never stored. The ledger already knows the outstanding total and the card
     *         row already knows the last statement; storing their difference would be a second
     *         version of the same truth, which is the argument `budget_alert` makes for storing no
     *         amounts at all.
     * Result: the difference, floored at zero. **The floor is doing real work and is an
     *         approximation this engine owns**: once the user pays the statement, outstanding drops
     *         below it and the subtraction goes negative, which would render as "you have spent
     *         −₹40,000 this cycle". Zero is the honest answer the app can defend without importing
     *         a payment history it does not have. With no statement recorded, the whole outstanding
     *         balance is unbilled, which is exactly right for a new card.
     * Input:  [outstanding] — a positive magnitude; [lastStatement] — or `null`.
     * Output: [Money].
     */
    fun unbilled(
        outstanding: Money,
        lastStatement: Money?,
    ): Money = (outstanding - (lastStatement ?: Money.ZERO)).coerceAtLeast(Money.ZERO)

    /**
     * How much of the limit is still spendable.
     * Why:    the figure a user actually acts on at a till, and the one the limit exists to express.
     * Result: limit less outstanding, floored at zero — an over-limit card has nothing available,
     *         and a negative "available" would read as a credit.
     * Input:  [limit]; [outstanding]. Output: [Money].
     */
    fun available(
        limit: Money,
        outstanding: Money,
    ): Money = (limit - outstanding).coerceAtLeast(Money.ZERO)
}
