package com.aicfo.core.model

import java.math.BigInteger

/**
 * Renders a [Money] in Indian rupee notation: ₹1,23,456.78.
 *
 * Why:  CLAUDE.md §5 and issue 1.2 AC3 require Indian digit grouping, which is **2,2,3** — one
 *       lakh is `1,00,000`, never `100,000`. Two obvious approaches do not work:
 *       `java.text.DecimalFormat` supports only a *single* grouping size, so the pattern
 *       `#,##,##0.00` silently degrades to `1,23,456` → `123,456` (verified, not assumed);
 *       and `NumberFormat.getCurrencyInstance(en-IN)` gets the grouping right but hands the
 *       output to platform locale data, which differs between JDK versions and Android releases
 *       (symbol spacing especially) — so screenshot and unit tests would depend on where they run.
 * What: splits the paise into rupees + two fraction digits as text, then inserts separators —
 *       three digits, then twos — so the rule is written down rather than delegated.
 * Result: one canonical amount string, identical on every JVM and device, safe to assert on.
 * Changelog: 2026-07-25 — Created for issue 1.2 (AC3).
 *
 * Lives in `:core:model` rather than the UI layer because this uses only Kotlin/JVM stdlib — no
 * Android import, so ARC-002 holds and engines can format for logs and exports too.
 */
object MoneyFormatter {
    private const val RUPEE_SIGN = "₹"
    private const val PAISE_DIGITS = 2
    private const val FIRST_GROUP = 3
    private const val LATER_GROUPS = 2

    /**
     * Formats an amount for display.
     * Why:    amounts reach the user in exactly one shape; call sites must never hand-roll it.
     * What:   takes the magnitude as an exact digit string ([BigInteger], because `Math.abs` of
     *         `Long.MIN_VALUE` overflows and no `Double` may touch an amount), peels off the two
     *         paise digits, groups the rupees, and puts any minus sign before the ₹.
     * Result: a string like `₹1,23,456.78`; refunds render as `-₹1,23,456.78`; zero as `₹0.00`.
     * Input:  [amount] — any [Money], positive, zero or negative.
     * Output: the display string, always with exactly two paise digits.
     */
    fun format(amount: Money): String {
        val digits = BigInteger.valueOf(amount.minor).abs().toString().padStart(FIRST_GROUP, '0')
        val rupees = groupIndian(digits.dropLast(PAISE_DIGITS))
        val paise = digits.takeLast(PAISE_DIGITS)
        val sign = if (amount.minor < 0L) "-" else ""
        return "$sign$RUPEE_SIGN$rupees.$paise"
    }

    /**
     * Inserts Indian thousands separators into a run of digits.
     * Why:    the 2,2,3 rule is the one piece of formatting no stdlib class will do for us.
     * What:   keeps the last three digits together, then groups everything to their left in twos.
     * Result: `123456` → `1,23,456`; `999` → `999`; `9223372036854775807` → `92,23,37,…,758`.
     * Input:  [digits] — the rupee digits, no sign and no separators (may be a single `0`).
     * Output: the same digits with `,` separators.
     */
    private fun groupIndian(digits: String): String {
        if (digits.length <= FIRST_GROUP) return digits
        val lead = digits.dropLast(FIRST_GROUP)
        val pairs = lead.reversed().chunked(LATER_GROUPS) { it.reversed() }.reversed()
        return pairs.joinToString(separator = ",") + "," + digits.takeLast(FIRST_GROUP)
    }
}
