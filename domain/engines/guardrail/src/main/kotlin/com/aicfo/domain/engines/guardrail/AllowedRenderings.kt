package com.aicfo.domain.engines.guardrail

import com.aicfo.core.model.DateFormatter
import com.aicfo.core.model.Money
import com.aicfo.core.model.MoneyFormatter
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Every way an evidence value may be written (issue 9.7; `ai/chat/guardrail.md` GRD-002/003/004).
 *
 * Why:  this set *is* the gate. A claim resolves when it equals one of these strings and not
 *       otherwise, so "the same number written for a reader" and "a new number" are separated by an
 *       explicit allowlist rather than by a tolerance. Arithmetic therefore fails by construction
 *       (GRD-003): the product of two evidence values is not a rendering of either.
 * What: the renderings of every amount, percentage, date and plain number the engines produced.
 * Result: what [VerifyingGuardrailEngine] checks each claim against.
 * Changelog: 2026-09-23 — Created for issue 9.7.
 *
 * **Rounding stops at the rupee.** An amount may be shown to the rupee or finer — ₹1,23,456.78 as
 * "₹1,23,457" hides less than a rupee and reads better. It may **not** be rounded at lakh or crore
 * granularity: "₹2 lakh" for ₹1.5 lakh is a third of the figure, a difference the reader cannot see
 * and no engine produced. So lakh and crore wording is offered only where it is **exact**.
 *
 * Input:  [evidence] — what the engines produced; [rules] — which transforms are on.
 * Output: the allowlist.
 */
internal class AllowedRenderings(
    private val evidence: GuardrailEvidence,
    private val rules: GuardrailRules,
) {
    /** Renderings of an amount: the formatter's output, display rounding, lakh/crore, paise. */
    private val amounts: Set<String> by lazy { evidence.amounts.flatMapTo(mutableSetOf(), ::amountRenderings) }

    /** Renderings of a percentage, including the basis points an engine published (MNY-002). */
    private val percents: Set<String> by lazy {
        buildSet {
            evidence.percents.forEach { add(normalise("$it%")) }
            if (rules.allowBpsAsPercent) {
                evidence.percentsBps.forEach { bps ->
                    decimalRenderings(BigDecimal(bps).movePointLeft(BPS_DECIMALS)).forEach { add(normalise("$it%")) }
                }
            }
        }
    }

    /** Renderings of a date an engine produced (GRD-004). */
    private val dates: Set<String> by lazy { evidence.dates.flatMapTo(mutableSetOf(), ::dateRenderings) }

    /** Renderings of a bare number: counts and scores, quantities, and a year standing alone. */
    private val numbers: Set<String> by lazy {
        buildSet {
            evidence.counts.forEach { add(normalise("$it")) }
            evidence.quantities.forEach { quantity -> decimalRenderings(quantity).forEach { add(normalise(it)) } }
            if (rules.allowYearAlone) evidence.dates.forEach { add(normalise("${it.year}")) }
        }
    }

    /**
     * Whether one claim resolves.
     * Result: `true` when the span is a permitted rendering of some evidence value of its kind.
     * Input:  [claim]. Output: [Boolean].
     */
    fun permits(claim: GuardrailClaim): Boolean {
        val written = normalise(claim.span)
        return when (claim.kind) {
            ClaimKind.AMOUNT -> written in amounts
            ClaimKind.PERCENT -> written in percents
            ClaimKind.DATE -> written in dates
            ClaimKind.NUMBER -> written in numbers
        }
    }

    /**
     * Every way one amount may be written.
     * Result: the renderings. Input: [money]. Output: `List<String>`, normalised.
     */
    private fun amountRenderings(money: Money): List<String> =
        buildList {
            val exact = MoneyFormatter.format(money)
            add(exact)
            if (exact.endsWith(WHOLE_RUPEES)) add(exact.dropLast(WHOLE_RUPEES.length))
            if (rules.allowRoundedDisplay) {
                for (decimals in 0..rules.maxDisplayDecimals) add(roundedToRupees(money, decimals))
            }
            if (rules.allowLakhCroreWords) addAll(magnitudeWords(money))
            if (rules.allowMinorUnits) add("${money.minor} $PAISE")
        }.map(::normalise)

    /**
     * The amount rounded for display, as the app itself would write it.
     * Why:    built from [MoneyFormatter]'s own output rather than from a second formatter, so the
     *         allowlist cannot drift into disagreeing with the screen about what ₹1,23,456.78 looks
     *         like. Half-even, because that is the app's rounding everywhere else (MNY-001).
     * Result: e.g. "₹1,23,457" at 0 decimals. Input: [money]; [decimals] — 0..maxDisplayDecimals.
     * Output: [String].
     */
    private fun roundedToRupees(
        money: Money,
        decimals: Int,
    ): String {
        // `BigDecimal` here is a *rendering* step, not money: MNY-001's amount stays the Long paise
        // in [money], and this only decides which digits of it are shown. Exact by construction —
        // scaling a Long by powers of ten, never a binary float.
        val shownDigits =
            BigDecimal(
                money.minor,
            ).movePointLeft(MONEY_DECIMALS).setScale(decimals, RoundingMode.HALF_EVEN)
        val formatted = MoneyFormatter.format(Money(shownDigits.movePointRight(MONEY_DECIMALS).toLong()))
        val dot = formatted.lastIndexOf('.')
        return if (decimals == 0) formatted.take(dot) else formatted.take(dot + 1 + decimals)
    }

    /**
     * "₹1.5 lakh" and "₹2.5 crore" — **only where they are exact**, per the class doc.
     * Result: the renderings, or empty when the figure cannot be written that way without losing
     *         rupees. Input: [money]. Output: `List<String>`.
     */
    private fun magnitudeWords(money: Money): List<String> =
        MAGNITUDES.flatMap { (word, decimals) ->
            val value = BigDecimal(money.minor).movePointLeft(decimals)
            val stripped = value.stripTrailingZeros()
            if (stripped.scale() > rules.maxDisplayDecimals) {
                emptyList()
            } else {
                (maxOf(stripped.scale(), 0)..rules.maxDisplayDecimals)
                    .map { scale -> "$RUPEE${value.setScale(scale, RoundingMode.UNNECESSARY).toPlainString()} $word" }
            }
        }

    /**
     * Every way one date may be written (GRD-004).
     * Why:    ISO is how the app stores it, and the rest are how a person writes it. Both the
     *         device locale and English are offered: the model writes English today, and a
     *         localised reply must not be refused for saying "मार्च" the moment it can.
     * Result: the renderings. Input: [date]. Output: `List<String>`, normalised.
     */
    private fun dateRenderings(date: LocalDate): List<String> =
        buildList {
            add(date.toString())
            add(DateFormatter.day(date.toString()))
            locales().forEach { locale ->
                DATE_PATTERNS.forEach { pattern ->
                    add(date.format(DateTimeFormatter.ofPattern(pattern, locale)))
                }
            }
        }.map(::normalise)

    /**
     * A non-money figure as it may be shown: exactly, and rounded for display.
     * Result: the renderings. Input: [value]. Output: `List<String>`.
     */
    private fun decimalRenderings(value: BigDecimal): List<String> =
        buildList {
            add(value.toPlainString())
            add(value.stripTrailingZeros().toPlainString())
            if (rules.allowRoundedDisplay) {
                for (decimals in 0..rules.maxDisplayDecimals) {
                    val rounded = value.setScale(decimals, RoundingMode.HALF_EVEN)
                    add(rounded.toPlainString())
                    add(rounded.stripTrailingZeros().toPlainString())
                }
            }
        }

    /**
     * The locales a date may be written in.
     * Why:    read per call, never cached in a constant: the user can change the device language
     *         while the app is running, and a gate that had memorised the old one would start
     *         refusing correct dates (lint's `ConstantLocale`).
     * Result: English and the device's own. Input: none. Output: `List<Locale>`.
     */
    private fun locales(): List<Locale> = listOf(Locale.ENGLISH, Locale.getDefault())

    /**
     * What two claims must agree on to be the same claim.
     * Why:    whitespace inside a figure is a writer's habit — "₹ 1,000.00" is the same claim as
     *         "₹1,000.00", and a gate that refused the first is one the team learns to route
     *         around. Case follows, so "1.5 Lakh" reads as "1.5 lakh". **Grouping is not
     *         normalised**: the allowlist is the formatter's output, so ₹1,23,456.78 is Indian
     *         grouping and "₹123,456.78" is not a rendering this app produces.
     * Result: the comparable form. Input: [written]. Output: [String].
     */
    private fun normalise(written: String): String = written.filterNot { it.isWhitespace() }.lowercase(Locale.ROOT)

    private companion object {
        const val RUPEE = "₹"
        const val PAISE = "paise"
        const val WHOLE_RUPEES = ".00"
        const val MONEY_DECIMALS = 2
        const val BPS_DECIMALS = 2

        /** Paise to lakh is seven decimal places; to crore, nine. */
        val MAGNITUDES = listOf("lakh" to 7, "crore" to 9)

        val DATE_PATTERNS = listOf("d MMM uuuu", "d MMMM uuuu", "MMM d, uuuu", "MMMM d, uuuu", "MMM uuuu", "MMMM uuuu")
    }
}
