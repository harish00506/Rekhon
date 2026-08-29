package com.aicfo.domain.engines.investment

import com.aicfo.core.model.InvestmentHolding
import com.aicfo.core.model.InvestmentLot
import com.aicfo.core.model.LotKind
import com.aicfo.core.model.Money
import com.aicfo.core.model.Quantity
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Turns a holding's stored rows into the numbers the solver and the screen need (issue 6.3; §11).
 *
 * Why:  the storage shape and the arithmetic shape differ on purpose. A lot stores a magnitude and
 *       a [LotKind] so a row cannot disagree with itself; XIRR needs signed flows. Converting in
 *       exactly one place is what keeps the sign convention from being re-decided at each call
 *       site — and a sign convention applied inconsistently produces returns that are wrong in a
 *       way that still looks plausible.
 * What: the position, the value, the cost, what came back, and the flow series.
 * Result: everything [DefaultInvestmentEngine] assembles a [HoldingPerformance] from.
 * Changelog: 2026-08-24 — Created for issue 6.3 (§11).
 */
internal object CashFlows {
    /**
     * Units still held: everything bought, less everything sold.
     * Why:    income lots move no units, so they must not count — a dividend does not enlarge a
     *         position, and treating it as a zero-unit buy would still be one branch too many.
     * Result: the net position, which may be zero after a full exit.
     * Input:  [lots] — the holding's rows, in any order. Output: [Quantity].
     * Changelog: 2026-08-24 — Created for issue 6.3.
     */
    fun netQuantity(lots: List<InvestmentLot>): Quantity =
        lots.fold(Quantity.ZERO) { running, lot ->
            when (lot.kind) {
                LotKind.BUY -> running + lot.quantity
                LotKind.SELL -> running - lot.quantity
                LotKind.INCOME -> running
            }
        }

    /**
     * What the position is worth at the holding's last observed unit price.
     * Why:    `units x price` is the one place a `BigDecimal` is unavoidable — units are scaled by
     *         10⁹ and the price is in paise, so the product must be divided by the scale, and doing
     *         that in `Long` would truncate a fraction of a paise per holding on every read. One
     *         `HALF_EVEN` rounding, at the end, on the paise (MNY-001).
     * Result: the value, or `null` when the holding has never been priced **and** still holds
     *         units — absent, never zero (P-03). A fully exited position is worth exactly zero and
     *         needs no price to say so.
     * Input:  [holding] — for its unit price; [units] — the net position from [netQuantity].
     * Output: [Money] or `null`.
     * Changelog: 2026-08-24 — Created for issue 6.3.
     */
    fun currentValue(
        holding: InvestmentHolding,
        units: Quantity,
    ): Money? {
        if (units == Quantity.ZERO) return Money.ZERO
        val perUnit = holding.unitPrice ?: return null
        val exact =
            BigDecimal.valueOf(units.nano)
                .multiply(BigDecimal.valueOf(perUnit.minor))
                .divide(BigDecimal.valueOf(Quantity.SCALE), 0, RoundingMode.HALF_EVEN)
        return Money(exact.longValueExact())
    }

    /**
     * What the user put in: the purchases.
     * Result: the sum of every BUY lot's cash, as a positive amount.
     * Input:  [lots]. Output: [Money].
     * Changelog: 2026-08-24 — Created for issue 6.3.
     */
    fun invested(lots: List<InvestmentLot>): Money =
        lots.filter { it.kind == LotKind.BUY }
            .fold(Money.ZERO) { running, lot -> running + lot.amount }

    /**
     * What has already come back: sales and payouts.
     * Why:    income counts here rather than reducing the cost, because a dividend is a return the
     *         user received and not a discount on what they paid. Netting it off the cost would
     *         flatter the cost basis and understate the money that was actually at risk.
     * Result: the sum of every SELL and INCOME lot's cash, as a positive amount.
     * Input:  [lots]. Output: [Money].
     * Changelog: 2026-08-24 — Created for issue 6.3.
     */
    fun realised(lots: List<InvestmentLot>): Money =
        lots.filter { it.kind != LotKind.BUY }
            .fold(Money.ZERO) { running, lot -> running + lot.amount }

    /**
     * The dated, signed series XIRR runs over.
     * Why:    the terminal flow is dated by the day the price was observed, never by today. That is
     *         what makes the answer a function of stored facts alone, so the same holding reports
     *         the same return tomorrow (P-08). A holding with no price contributes no terminal flow
     *         at all, which the solver then reports as [XirrUnavailable.SAME_SIGN] — honest, and
     *         better than inventing a valuation.
     * What:   one flow per lot, signed by [LotKind.cashFlowSign], plus the closing value when there
     *         is one to add.
     * Result: the flows, in lot order; the solver sorts and coalesces them itself.
     * Input:  [holding]; [lots]; [value] — the result of [currentValue].
     * Output: the list.
     * Changelog: 2026-08-24 — Created for issue 6.3.
     */
    fun of(
        holding: InvestmentHolding,
        lots: List<InvestmentLot>,
        value: Money?,
    ): List<CashFlow> {
        val fromLots =
            lots.map { lot ->
                CashFlow(lot.transactedOnIsoDate, Money(lot.amount.minor * lot.kind.cashFlowSign))
            }
        val closingDate = holding.pricedOnIsoDate
        // A zero closing value adds nothing to the series and would only inflate the flow count the
        // drill-down shows, so a fully exited holding contributes its sales and stops there.
        return if (value == null || value == Money.ZERO || closingDate == null) {
            fromLots
        } else {
            fromLots + CashFlow(closingDate, value)
        }
    }
}
