package com.aicfo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Locks [InvestmentHolding] and [InvestmentLot] validation to what the XIRR engine may assume
 * (issue 6.3; §11, MNY-001, TIM-002).
 *
 * Why:  `InvestmentEngine` refuses exactly four things — too few flows, flows all of one sign, a
 *       zero-day span, and a rate outside its bracket — and checks nothing else, because these two
 *       constructors have already refused everything unusable. That division of labour only holds
 *       if the refusals are proven here. The subtle one is the price/date pair: a unit price with no
 *       date has no terminal flow date, so XIRR would silently fall back to "today" and give a
 *       different answer tomorrow for a holding nobody touched — a P-08 violation that no example
 *       test would ever catch, because each run looks self-consistent.
 * What: every `require` path on both types, the accepted boundaries either side, the legitimate
 *       absences, and [LotKind]'s persisted contract and cash-flow direction.
 * Result: both models are proven fail-fast at their edges, or the build goes red.
 * Changelog: 2026-08-24 — Created for issue 6.3 (written red before Investment.kt existed).
 */
class InvestmentTest {
    /**
     * A valid holding — the base every case below deviates from by exactly one field via `copy`.
     *
     * Why:    `copy` rather than a helper with six defaulted parameters, for the reason
     *         `LoanTest.loan()` gives: the parameter list would be the whole constructor again,
     *         while `copy` re-runs `init`, which is the very thing under test.
     * Result: 'Parag Parikh Flexi Cap', equity, last priced at ₹78.4321 per unit on 2026-08-20.
     * Input:  none. Output: [InvestmentHolding].
     */
    private fun holding() =
        InvestmentHolding(
            id = "holding:ppfas",
            accountId = "account:zerodha",
            name = "Parag Parikh Flexi Cap",
            assetClass = AssetClass.EQUITY,
            unitPrice = Money(7_843),
            pricedOnIsoDate = "2026-08-20",
        )

    /**
     * A valid lot — the base every lot case deviates from by one field.
     * Result: 100 units bought for ₹7,500.00 on 2026-01-15.
     * Input:  none. Output: [InvestmentLot].
     */
    private fun lot() =
        InvestmentLot(
            id = "lot:ppfas-1",
            holdingId = "holding:ppfas",
            kind = LotKind.BUY,
            transactedOnIsoDate = "2026-01-15",
            quantity = Quantity(100 * Quantity.SCALE),
            amount = Money(750_000),
        )

    /** Input: the base holding. Output: asserts every field survives construction unchanged. */
    @Test
    fun `an ordinary holding keeps every field it was given`() {
        val subject = holding()

        assertEquals("holding:ppfas", subject.id)
        assertEquals("account:zerodha", subject.accountId)
        assertEquals("Parag Parikh Flexi Cap", subject.name)
        assertEquals(AssetClass.EQUITY, subject.assetClass)
        assertEquals(Money(7_843), subject.unitPrice)
        assertEquals("2026-08-20", subject.pricedOnIsoDate)
    }

    /**
     * Input: a holding with neither price nor date.
     * Output: asserts it is legal. This is the ordinary state of a holding the user has just
     * created and not yet priced, and 6.5 will fill it from the market API. It is not an error, so
     * XIRR reports it as unavailable rather than refusing to build the row (P-03: absent is null,
     * never zero).
     */
    @Test
    fun `a holding with no price yet is legal`() {
        val subject = holding().copy(unitPrice = null, pricedOnIsoDate = null)

        assertNull(subject.unitPrice)
        assertNull(subject.pricedOnIsoDate)
    }

    /**
     * Input: a price with no date, then a date with no price.
     * Output: asserts both are refused. A price without a date has no terminal flow date, so the
     * answer would drift day to day (P-08); a date without a price says a valuation happened and
     * then omits it.
     */
    @Test
    fun `a price and its date are both-or-neither`() {
        assertThrows(IllegalArgumentException::class.java) {
            holding().copy(unitPrice = Money(7_843), pricedOnIsoDate = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            holding().copy(unitPrice = null, pricedOnIsoDate = "2026-08-20")
        }
    }

    /** Input: blank identifiers and name. Output: asserts each is refused. */
    @Test
    fun `a holding must name itself and its account`() {
        assertThrows(IllegalArgumentException::class.java) { holding().copy(id = "") }
        assertThrows(IllegalArgumentException::class.java) { holding().copy(accountId = " ") }
        assertThrows(IllegalArgumentException::class.java) { holding().copy(name = "  ") }
    }

    /**
     * Input: a zero and a negative unit price.
     * Output: asserts both are refused. A zero price is not "worthless" — it is an unentered price,
     * which the nullable field already expresses; allowing both spellings would let a portfolio
     * read as ₹0 when it is merely unpriced.
     */
    @Test
    fun `a unit price that is present must be positive`() {
        assertThrows(IllegalArgumentException::class.java) {
            holding().copy(unitPrice = Money.ZERO, pricedOnIsoDate = "2026-08-20")
        }
        assertThrows(IllegalArgumentException::class.java) {
            holding().copy(unitPrice = Money(-1), pricedOnIsoDate = "2026-08-20")
        }
    }

    /** Input: strings that are not resolvable calendar days. Output: asserts each is refused. */
    @Test
    fun `a pricing date must be a real calendar day`() {
        assertThrows(IllegalArgumentException::class.java) {
            holding().copy(pricedOnIsoDate = "2026-02-30")
        }
        assertThrows(IllegalArgumentException::class.java) {
            holding().copy(pricedOnIsoDate = "20-08-2026")
        }
        assertThrows(IllegalArgumentException::class.java) {
            holding().copy(pricedOnIsoDate = "yesterday")
        }
    }

    /** Input: a leap day. Output: asserts a real 29 February is accepted, unlike 30 February. */
    @Test
    fun `a leap day is a real calendar day`() {
        assertEquals("2028-02-29", holding().copy(pricedOnIsoDate = "2028-02-29").pricedOnIsoDate)
    }

    /** Input: the base lot. Output: asserts every field survives construction unchanged. */
    @Test
    fun `an ordinary lot keeps every field it was given`() {
        val subject = lot()

        assertEquals("lot:ppfas-1", subject.id)
        assertEquals("holding:ppfas", subject.holdingId)
        assertEquals(LotKind.BUY, subject.kind)
        assertEquals("2026-01-15", subject.transactedOnIsoDate)
        assertEquals(Quantity(100 * Quantity.SCALE), subject.quantity)
        assertEquals(Money(750_000), subject.amount)
    }

    /** Input: blank identifiers. Output: asserts each is refused. */
    @Test
    fun `a lot must name itself and its holding`() {
        assertThrows(IllegalArgumentException::class.java) { lot().copy(id = "") }
        assertThrows(IllegalArgumentException::class.java) { lot().copy(holdingId = " ") }
    }

    /** Input: an unparseable transaction date. Output: asserts it is refused. */
    @Test
    fun `a lot date must be a real calendar day`() {
        assertThrows(IllegalArgumentException::class.java) {
            lot().copy(transactedOnIsoDate = "2026-13-01")
        }
    }

    /**
     * Input: a negative quantity.
     * Output: asserts it is refused. Direction comes from [LotKind], never from a stored sign —
     * the argument [AccountType.isLiability]'s doc makes about classification by type. Two ways to
     * spell "sold 10 units" is one way too many.
     */
    @Test
    fun `a stored quantity is a magnitude, never a signed direction`() {
        assertThrows(IllegalArgumentException::class.java) { lot().copy(quantity = Quantity(-1)) }
    }

    /**
     * Input: a zero quantity.
     * Output: asserts it is accepted — an INCOME lot (a dividend, a coupon) moves cash without
     * moving units, and refusing it would force a fake unit count onto every payout.
     */
    @Test
    fun `an income lot may move cash without moving units`() {
        val subject = lot().copy(kind = LotKind.INCOME, quantity = Quantity.ZERO)

        assertEquals(Quantity.ZERO, subject.quantity)
    }

    /**
     * Input: a zero and a negative amount.
     * Output: asserts both are refused. Every lot is a cash movement; one that moved no cash is a
     * row with nothing to contribute to XIRR and no reason to exist.
     */
    @Test
    fun `a lot amount must be positive`() {
        assertThrows(IllegalArgumentException::class.java) { lot().copy(amount = Money.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { lot().copy(amount = Money(-750_000)) }
    }

    /** Input: none. Output: asserts each kind carries the exact string the column stores. */
    @Test
    fun `every lot kind stores the string the schema expects`() {
        assertEquals("buy", LotKind.BUY.storedValue)
        assertEquals("sell", LotKind.SELL.storedValue)
        assertEquals("income", LotKind.INCOME.storedValue)
    }

    /** Input: every kind's own stored value. Output: asserts the round trip is total. */
    @Test
    fun `every lot kind parses back from what it stored`() {
        for (kind in LotKind.entries) {
            assertEquals(kind, LotKind.fromStored(kind.storedValue))
        }
        assertNull(LotKind.fromStored("dividend"))
    }

    /**
     * Input: each kind.
     * Output: asserts the sign XIRR gives its cash flow. A buy is money leaving the user
     * (negative); a sale and a payout are money arriving (positive). Getting this backwards
     * inverts every return in the app, and inverts it *consistently*, which is exactly the kind of
     * wrong that looks right.
     */
    @Test
    fun `a buy is money out and a sale or payout is money in`() {
        assertEquals(-1, LotKind.BUY.cashFlowSign)
        assertEquals(1, LotKind.SELL.cashFlowSign)
        assertEquals(1, LotKind.INCOME.cashFlowSign)
    }
}
