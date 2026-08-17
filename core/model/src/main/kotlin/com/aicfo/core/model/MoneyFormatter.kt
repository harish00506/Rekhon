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
    private const val RUPEE_CHAR = '₹'
    private const val PAISE_DIGITS = 2
    private const val FIRST_GROUP = 3
    private const val LATER_GROUPS = 2

    /**
     * The mask body. Seven dots: wide enough that a masked ₹1,23,456.78 does not read as a short
     * number, narrow enough not to wrap at 200% font, where `CfoAmountText` renders with
     * `softWrap = false`.
     */
    private const val MASK_DOTS = "•••••••"

    private const val PAISE_IN_A_RUPEE = 100L

    /** Only `0`–`9`. `Char.isDigit()` is deliberately not used — see [parse]. */
    private val ASCII_DIGITS = '0'..'9'
    private val PAISE_PER_RUPEE = BigInteger.valueOf(PAISE_IN_A_RUPEE)
    private val LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE)
    private val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)

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
     * The masked form of an amount, for the privacy blur (issue 5.3; §23, P-01).
     *
     * Why:  two decisions, and both are the difference between a blur that works and one that looks
     *       like it does.
     *
     *       **The run of dots is a fixed length, never the number's own.** Masking ₹450 as `₹•••`
     *       and ₹4,50,000 as `₹•,••,•••` would hide the digits and leak the order of magnitude —
     *       which is most of what someone reading over a shoulder wants, and all of what makes a
     *       salary or a balance sensitive. Every amount masks to the same width, so a column of
     *       them says nothing about any of them.
     *
     *       **The sign survives.** Direction is not the secret — nobody is embarrassed that a
     *       transaction was an outflow — and `CfoAmountText` colours by sign, so dropping it would
     *       leave colour as the only signal of direction (P-02, and greyscale/colour-blind users).
     *       The currency symbol stays for the same reason: it keeps the masked value legible *as an
     *       amount* rather than as an error.
     *
     *       **Here rather than in `:core:designsystem`, where issue 5.3 first wrote it.** The
     *       home-screen widget (issue 5.5) masks the same two amounts and is a Glance module: it
     *       cannot see a Compose `CompositionLocal`, and making it depend on the whole Material3
     *       design system for three lines would buy nothing. This is text formatting with no
     *       Android in it — the same argument [format] gives for living here — so both callers now
     *       read one definition and the mask cannot drift to two widths.
     * Result: `₹•••••••` or `-₹•••••••` — identical for every magnitude.
     * Input:  [amount] — read only for its sign, never its digits.
     * Output: the mask.
     * Changelog: 2026-08-16 — Created for issue 5.3 as `maskOf` in `:core:designsystem`.
     *            2026-08-17 — Issue 5.5: moved here so `:widget` can mask without Compose.
     */
    fun mask(amount: Money): String {
        val sign = if (amount.minor < 0L) "-" else ""
        return "$sign$RUPEE_SIGN$MASK_DOTS"
    }

    /**
     * Reads an amount a user typed.
     *
     * Why:    onboarding's quick-setup step (FR-ONB-002) is the first screen that takes a typed
     *         amount, and every later one will reuse this. The obvious implementation —
     *         `text.toDouble() * 100` — is exactly what MNY-001 forbids: `"0.07".toDouble() * 100`
     *         is `7.000000000000001`. So the whole path stays on text and [BigInteger], the same
     *         reason [format] uses it, and the two are proved to round-trip.
     * What:   strips the decoration [format] adds (₹, grouping commas, spaces), splits on the
     *         decimal point, pads the paise, and refuses anything it cannot represent exactly.
     * Result: the amount, or `null` for input that is empty, malformed, more precise than paise, or
     *         outside the [Long] range. Never a rounded or wrapped approximation — a parser that
     *         guesses is worse than one that declines, because the user cannot see it guessing.
     * Input:  [text] — anything the user typed, e.g. `45000`, `1.5`, `₹1,23,456.78`, `-100`.
     * Output: [Money], or `null`.
     * Changelog: 2026-07-25 — Created for issue 2.1.
     */
    fun parse(text: String): Money? {
        val cleaned = text.filterNot { it == ',' || it.isWhitespace() || it == RUPEE_CHAR }
        val negative = cleaned.startsWith('-')
        val parts = cleaned.removePrefix("-").split('.')
        val rupees = parts.first()
        // padEnd, not padStart: a single fraction digit is tens of paise — "1.5" is ₹1.50.
        val paise = parts.getOrElse(1) { "" }.padEnd(PAISE_DIGITS, '0')
        if (!isExactlyRepresentable(parts.size, rupees, paise)) return null

        val magnitude = BigInteger(rupees).multiply(PAISE_PER_RUPEE).add(BigInteger(paise))
        val minor = if (negative) magnitude.negate() else magnitude
        // Refuse rather than wrap: a wrapped amount turns a fortune into a debt, silently.
        return if (minor in LONG_MIN..LONG_MAX) Money(minor.toLong()) else null
    }

    /**
     * Decides whether cleaned input can become an exact paise amount.
     * Why:    split out so [parse] has one rejection point rather than a ladder of returns, and so
     *         each condition is named where it is easy to read against the tests.
     * What:   at most one decimal point, a rupee part present, no more precision than paise, and
     *         ASCII digits only — `Char.isDigit()` accepts Devanagari `१`, which then throws inside
     *         [BigInteger]: a validation that reads as correct and crashes. This app is India-first,
     *         so that input is plausible rather than exotic.
     * Result: `true` when [parse] may safely convert, `false` when it must return `null`.
     * Input:  [partCount] — how many pieces the decimal point produced; [rupees]; [paise] — already
     *         padded to [PAISE_DIGITS].
     * Output: `Boolean`.
     * Changelog: 2026-07-25 — Created for issue 2.1.
     */
    private fun isExactlyRepresentable(
        partCount: Int,
        rupees: String,
        paise: String,
    ): Boolean =
        partCount <= 2 &&
            rupees.isNotEmpty() &&
            paise.length <= PAISE_DIGITS &&
            rupees.all { it in ASCII_DIGITS } &&
            paise.all { it in ASCII_DIGITS }

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
