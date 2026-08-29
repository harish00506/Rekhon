package com.aicfo.core.model

/**
 * How many units of a holding, exactly (issue 6.3; MNY-001's argument applied to units).
 *
 * Why:  units are a unit-of-measure exactly like paise, and a bare `Long` invites the precise
 *       confusion [Money] exists to prevent — nano-units added to whole units, or a `Double`
 *       creeping in because "0.001 units of a fund" reads like a decimal. Mutual funds quote three
 *       decimals and crypto quotes eight, so a fixed scale of 10^9 covers both with room, and a
 *       value class makes the scale impossible to forget at a call site.
 * What: a signed count of nano-units, with exact addition and subtraction that throw rather than
 *       wrap, and an ordering.
 * Result: unit arithmetic that cannot silently lose or invent a fraction of a unit.
 * Changelog: 2026-08-24 — Created for issue 6.3 (§11).
 *
 * **The surface is deliberately minimal.** There is no `times` or `div`: multiplying units by a
 * price is issue 6.5's job and belongs where the rounding can be argued about, not on this type.
 *
 * **Signed, though a stored lot is not.** A running `netQuantity` is a difference and may go
 * negative while it is being accumulated; it is [InvestmentLot] that requires a non-negative
 * magnitude, because direction there comes from [LotKind].
 */
@JvmInline
value class Quantity(val nano: Long) : Comparable<Quantity> {
    /**
     * Adds two quantities.
     * Why:    `Math.addExact`, not `+`, for the reason [Money.plus] gives — a silently negative
     *         portfolio is worse than a crash that names the row.
     * Result: the sum, or [ArithmeticException] on overflow.
     * Input:  [other] — the quantity to add. Output: a new [Quantity].
     * Changelog: 2026-08-24 — Created for issue 6.3.
     */
    operator fun plus(other: Quantity): Quantity = Quantity(Math.addExact(nano, other.nano))

    /**
     * Subtracts a quantity, as a sale reduces a position.
     * Result: the difference, which may be negative, or [ArithmeticException] on underflow.
     * Input:  [other] — the quantity to remove. Output: a new [Quantity].
     * Changelog: 2026-08-24 — Created for issue 6.3.
     */
    operator fun minus(other: Quantity): Quantity = Quantity(Math.subtractExact(nano, other.nano))

    /**
     * Orders two quantities by their units.
     * Result: the usual negative/zero/positive comparison.
     * Input:  [other] — the quantity to compare against. Output: [Int].
     * Changelog: 2026-08-24 — Created for issue 6.3.
     */
    override fun compareTo(other: Quantity): Int = nano.compareTo(other.nano)

    companion object {
        /** No units — the identity for [plus] and the starting point of every running total. */
        val ZERO: Quantity = Quantity(0)

        /**
         * Nano-units in one whole unit.
         * 10^9 covers crypto's eight decimals and a mutual fund's three. It is the divisor every
         * persisted `quantity_nano` is read through, and the one constant a migration could never
         * repair if it changed.
         */
        const val SCALE: Long = 1_000_000_000L
    }
}

/**
 * What a lot did — the direction of its cash, without a signed amount (issue 6.3; §11).
 *
 * Why:  a lot needs to say whether money left the user or arrived, and there are two ways to spell
 *       that: a signed `amount`, or a kind. Storing the sign would give two spellings of "sold ten
 *       units" and let a row disagree with itself. This is the argument [AccountType.isLiability]'s
 *       doc makes — classification is by type, never by sign — applied to cash direction.
 * What: the closed set of things a lot can be, each with its persisted string and the sign XIRR
 *       gives its cash flow.
 * Result: a lot's direction is a compile-time value, and the stored amount is always a magnitude.
 * Changelog: 2026-08-24 — Created for issue 6.3.
 */
enum class LotKind(val storedValue: String, val cashFlowSign: Int) {
    /** Units acquired. Money leaves the user, so the flow is negative. */
    BUY("buy", -1),

    /** Units disposed of. Money arrives. */
    SELL("sell", 1),

    /** A dividend, coupon or interest credit. Money arrives without units moving. */
    INCOME("income", 1),
    ;

    companion object {
        /**
         * Resolves a stored string back to a kind.
         * Why:    the same forward compatibility [AssetClass.fromStored] keeps — a row from a newer
         *         build is skipped, never a crash.
         * Result: the matching kind, or `null` when this build does not know the value.
         * Input:  [stored] — the raw `kind` column value. Output: [LotKind] or `null`.
         * Changelog: 2026-08-24 — Created for issue 6.3.
         */
        fun fromStored(stored: String): LotKind? = entries.firstOrNull { it.storedValue == stored }
    }
}

/**
 * One thing the user owns inside an investment account (issue 6.3; §11, FR-ACC-001).
 *
 * Why:  §20.1 names a `holdings` table separate from `accounts` because a single broker account
 *       holds many instruments, each with its own asset class and its own return. Everything a
 *       holding is *worth* is derived from its lots and this price, never stored — the argument
 *       ADR-0007 makes for account balances and ADR-0026 makes for amortisation schedules: a cached
 *       total is a second source of truth that goes stale the first time a lot is corrected.
 * What: the identity, the account it sits in, the user's label, its [AssetClass], and the latest
 *       price per unit with the day that price was observed.
 * Result: the fixed facts about a holding, validated so `InvestmentEngine` can assume them.
 * Changelog: 2026-08-24 — Created for issue 6.3 (§11).
 *
 * **The price is stored though everything else is derived**, and the distinction is the point: a
 * market price is an observation the device cannot compute from anything it holds. Issue 6.5
 * replaced only where it comes from — the column and every figure derived from it are unchanged.
 *
 * **[unitPrice] and [pricedOnIsoDate] are both-or-neither.** A price with no date has no terminal
 * flow date, so XIRR would fall back to "today" and give a different answer tomorrow for a holding
 * nobody touched — a P-08 violation invisible to any single run.
 *
 * **Two dates, deliberately, and they answer different questions** (issue 6.5). [pricedOnIsoDate] is
 * the day the *market* priced the instrument; [priceFetchedAtUtcMillis] is when *this device* last
 * heard about it. They are usually minutes apart and occasionally days: a Monday fetch of Friday's
 * closing gold price has a Friday price date and a Monday fetch time. One decides whether the number
 * shown to the user is old (in days); the other decides whether it is worth spending a network call
 * (in minutes, because crypto moves that fast — SRS §16.1). A single column could not do both.
 *
 * @property id stable identifier, minted by the injected id source.
 * @property accountId the account this sits in; 1:N, unlike `loan` and `credit_card`.
 * @property name the user's own label, e.g. "Parag Parikh Flexi Cap".
 * @property assetClass what kind of thing this is, for issue 6.4's allocation.
 * @property unitPrice paise per unit as last observed, or `null` when never priced (P-03: absent is
 *   null, never zero).
 * @property pricedOnIsoDate ISO `yyyy-MM-dd` day [unitPrice] was observed (TIM-002), or `null`.
 * @property priceKey the instrument identifier a market-data proxy resolves, or `null` when this
 *   holding is priced by hand. **Null is also the opt-out**: a holding with no key is never touched
 *   by a refresh, so the user keeps whatever they typed (issue 6.5).
 * @property priceFetchedAtUtcMillis when a fetched price was last written, UTC epoch millis
 *   (TIM-001), or `null` when the current price was typed by hand rather than fetched.
 */
data class InvestmentHolding(
    val id: String,
    val accountId: String,
    val name: String,
    val assetClass: AssetClass,
    val unitPrice: Money?,
    val pricedOnIsoDate: String?,
    val priceKey: PriceKey? = null,
    val priceFetchedAtUtcMillis: Long? = null,
) {
    init {
        require(id.isNotBlank()) { "A holding must have an id" }
        require(accountId.isNotBlank()) { "A holding must name the account it sits in" }
        require(name.isNotBlank()) { "A holding must have a name the user can recognise" }
        require((unitPrice == null) == (pricedOnIsoDate == null)) {
            "A unit price and its date are both-or-neither: a price with no date has no terminal " +
                "flow date and would change answer by the day (P-08)"
        }
        require(unitPrice == null || unitPrice > Money.ZERO) {
            "A price that is present must be positive; an unentered price is null, not zero " +
                "(P-03), was ${unitPrice?.minor} paise"
        }
        require(pricedOnIsoDate == null || DateFormatter.isCalendarDate(pricedOnIsoDate)) {
            "The pricing date is an ISO yyyy-MM-dd calendar date (TIM-002), was '$pricedOnIsoDate'"
        }
        // A fetch stamp with no price is a row claiming provenance for a value that is not there —
        // it would make the screen say "fetched an hour ago" beside "not valued yet". The reverse is
        // fine and ordinary: a hand-typed price has no fetch stamp at all.
        require(priceFetchedAtUtcMillis == null || unitPrice != null) {
            "A fetch timestamp records where a price came from, so there must be a price: " +
                "priceFetchedAtUtcMillis was $priceFetchedAtUtcMillis with no unitPrice"
        }
        require(priceFetchedAtUtcMillis == null || priceFetchedAtUtcMillis > 0L) {
            "A fetch timestamp is UTC epoch millis and must be positive, was $priceFetchedAtUtcMillis"
        }
    }
}

/**
 * One dated cash movement in a holding — a purchase, a sale or a payout (issue 6.3; §11, §20.1).
 *
 * Why:  XIRR is money-weighted, so it needs every movement with its own date; a single "total
 *       invested" cannot express a SIP. §20.1 names `holding_lots` for exactly this. Storing the
 *       units alongside the cash is what lets the holding's position be a sum rather than a second
 *       stored number that can disagree with the lots beneath it.
 * What: the identity, its holding, what kind of movement it was, when, how many units and how much
 *       cash — the last two as magnitudes, with direction from [kind].
 * Result: the atom `CashFlows` turns into an XIRR input.
 * Changelog: 2026-08-24 — Created for issue 6.3 (§11).
 *
 * **Charges are folded into [amount]** — it is the cash that actually moved. A separate fee column
 * would be a second figure that can disagree with the first, and the user's bank statement shows
 * the total, not the split.
 *
 * @property id stable identifier, minted by the injected id source.
 * @property holdingId the holding this belongs to.
 * @property kind what the movement was; the only source of its cash direction.
 * @property transactedOnIsoDate ISO `yyyy-MM-dd` day it happened (TIM-002).
 * @property quantity units moved, as a magnitude; [Quantity.ZERO] for an income lot.
 * @property amount cash moved in paise, as a magnitude, charges included; always positive.
 */
data class InvestmentLot(
    val id: String,
    val holdingId: String,
    val kind: LotKind,
    val transactedOnIsoDate: String,
    val quantity: Quantity,
    val amount: Money,
) {
    init {
        require(id.isNotBlank()) { "A lot must have an id" }
        require(holdingId.isNotBlank()) { "A lot must name the holding it belongs to" }
        require(DateFormatter.isCalendarDate(transactedOnIsoDate)) {
            "The transaction date is an ISO yyyy-MM-dd calendar date (TIM-002), was " +
                "'$transactedOnIsoDate'"
        }
        require(quantity >= Quantity.ZERO) {
            "A stored quantity is a magnitude; direction comes from the lot kind, was " +
                "${quantity.nano} nano-units"
        }
        require(amount > Money.ZERO) {
            "Every lot is a cash movement, so its amount is positive, was ${amount.minor} paise"
        }
    }
}
