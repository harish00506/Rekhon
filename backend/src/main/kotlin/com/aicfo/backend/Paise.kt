package com.aicfo.backend

import com.aicfo.core.model.Money
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The one place a vendor's decimal price becomes paise (issue 6.7; MNY-001).
 *
 * Why:  every upstream — CoinGecko, AMFI, a metals API, RBI — publishes rupees as a decimal, and
 *       every one of them is a chance to write `.toDouble()` and corrupt the wire silently. Funnel
 *       all four through one function and the rule has one place to be right, one place to be
 *       reviewed, and one place to be tested exhaustively.
 * What: `BigDecimal` in, [Money] out, rounded HALF_EVEN like every other division in this project.
 * Result: an exact `Long` count of paise, or a thrown [ArithmeticException] if the value cannot be
 *         one — never a quietly wrong number.
 * Changelog: 2026-08-30 — Created for issue 6.7.
 */
object Paise {
    /**
     * Converts a rupee amount to paise.
     *
     * Why:    MNY-001 — money is `Long` minor units end to end, and the rounding mode is HALF_EVEN
     *         throughout this project so repeated conversions do not drift upward.
     * What:   scales to two decimals, shifts the point, and demands an exact `Long`.
     * Result: the paise value. [java.math.BigDecimal.longValueExact] **throws rather than wraps** on
     *         overflow: a silently negative price would be dropped by the client as non-positive,
     *         and a dropped quote is a stale price nobody can explain. Loud beats plausible.
     * Input:  [rupees] — a rupee amount at any scale, e.g. AMFI's four-decimal NAV `123.4567`.
     * Output: [Money] in paise.
     */
    fun fromRupees(rupees: BigDecimal): Money =
        Money(rupees.setScale(PAISE_SCALE, RoundingMode.HALF_EVEN).movePointRight(PAISE_SCALE).longValueExact())

    /**
     * Parses a vendor's price **from its raw text**, never from a parsed floating-point number.
     *
     * Why:    this is the guard the whole module exists to hold. `JsonPrimitive.double` and
     *         `String.toDouble()` both look harmless and both lose exactness before [fromRupees] ever
     *         sees the value, which puts the error *upstream* of the one function that was supposed
     *         to prevent it. Taking the text and handing it to `BigDecimal` means no binary floating
     *         point is constructed at any point in the pipeline.
     * What:   trims and parses; refuses anything that is not a decimal number, and refuses zero and
     *         negatives — a free instrument is a vendor bug, not a price.
     * Result: the paise value, or **null** when the text was not a usable price. Null means "no
     *         quote for this instrument", which the client already handles by keeping its cached
     *         price (P-04). A vendor changing its payload shape therefore degrades rather than
     *         throws.
     * Input:  [raw] — the vendor's own characters, e.g. `"7890.12"`, `"123.4567"`, `"N.A."`.
     * Output: [Money] in paise, or null.
     */
    fun parseRupees(raw: String?): Money? =
        runCatching { BigDecimal(raw?.trim().orEmpty()) }
            .getOrNull()
            ?.takeIf { it > BigDecimal.ZERO }
            ?.let { rupees -> runCatching { fromRupees(rupees) }.getOrNull() }

    /** Two decimal places: a rupee is a hundred paise. */
    private const val PAISE_SCALE = 2
}
