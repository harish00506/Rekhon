package com.aicfo.domain.engines.guardrail

/**
 * Finds every numeric claim in a piece of text (issue 9.7; `ai/chat/guardrail.md` step 1).
 *
 * Why:  a figure the extractor never sees is a figure nobody checks, so this is deliberately
 *       greedy about *shape* and leaves judgement to [AllowedRenderings]. It reads in a fixed
 *       order — names, then amounts, then percentages, then dates, then whatever digits are left —
 *       because the order is what stops one claim being read twice. Without it the "1" of
 *       "₹1,240" and the "40" of "40%" would come back as counts, and a caller would have to allow
 *       them to get its own message through, turning the number check into a rubber stamp for the
 *       digits it exists to police.
 *
 *       **Each claim is blanked with spaces rather than deleted**, so later patterns see the same
 *       offsets and two separated digit runs are never welded into a number neither claim
 *       contained.
 * What: the claims, in reading order, each with what it is and where it starts.
 * Result: what the gate then resolves.
 * Changelog: 2026-09-23 — Created for issue 9.7, widening `NumericGuardrail`'s three patterns with
 *            lakh/crore wording, paise, dates and numbers of any length (ADR-0048).
 *
 * Input:  [rules] — `allowMinorUnits` decides whether "15000000 paise" is read as one amount or as
 *         a bare number, so that switching the transform off makes the claim *stricter* rather
 *         than invisible.
 * Output: an extractor.
 */
internal class ClaimExtractor(
    private val rules: GuardrailRules,
) {
    /**
     * Reads one text.
     * Result: every claim, in reading order. Input: [text] — the composed, user-facing string;
     *         [names] — verbatim strings the caller interpolated that are names, not claims.
     * Output: `List<GuardrailClaim>`.
     */
    fun extract(
        text: String,
        names: List<String>,
    ): List<GuardrailClaim> {
        val claims = mutableListOf<GuardrailClaim>()
        var remaining = blankNames(text, names)
        patterns().forEach { (pattern, kind) ->
            pattern.findAll(remaining).forEach { match ->
                claims += GuardrailClaim(match.value.trim(), kind, match.range.first)
            }
            remaining = pattern.replace(remaining) { blanks(it.value.length) }
        }
        return claims.sortedBy { it.index }
    }

    /**
     * The patterns, in the order they are read.
     * Why:    amounts before percentages before dates before bare numbers, so the most specific
     *         shape claims its digits first. Paise is part of the amount pass only when the
     *         transform is enabled — otherwise the digits fall through and are judged as a bare
     *         number, which is what "this rendering is not allowed" should mean.
     * Result: pattern and kind, in order. Input: none. Output: a list of pairs.
     */
    private fun patterns(): List<Pair<Regex, ClaimKind>> =
        buildList {
            add(RUPEES to ClaimKind.AMOUNT)
            if (rules.allowMinorUnits) add(PAISE to ClaimKind.AMOUNT)
            add(PERCENT to ClaimKind.PERCENT)
            add(ISO_DATE to ClaimKind.DATE)
            add(DAY_MONTH_YEAR to ClaimKind.DATE)
            add(MONTH_DAY_YEAR to ClaimKind.DATE)
            add(MONTH_YEAR to ClaimKind.DATE)
            add(NUMBER to ClaimKind.NUMBER)
        }

    /**
     * Strikes out the caller's own names before any digit is read.
     * Why:    a user's category may be called "Zone 3 parking", and without this the gate would
     *         refuse the app's own correct message. It is a hole by construction, so it is only
     *         ever the verbatim value the caller interpolated, never a fragment chosen to make a
     *         failing check pass.
     * Result: the text with each name blanked. Input: [text]; [names]. Output: [String].
     */
    private fun blankNames(
        text: String,
        names: List<String>,
    ): String =
        names.filter { it.isNotBlank() }
            .fold(text) { carried, name -> carried.replace(name, blanks(name.length)) }

    /** Result: [length] spaces, which keeps every later offset where it was. */
    private fun blanks(length: Int): String = " ".repeat(length)

    private companion object {
        /**
         * A rupee figure, with optional grouping, decimals, and Indian magnitude wording.
         *
         * It must **end on a digit**: "₹1,23,457, which is" is one figure followed by the
         * sentence's comma, and a pattern that swallowed the comma would report the app's own
         * correct amount as unverifiable. The golden oracle caught exactly that.
         */
        val RUPEES =
            Regex("""-?₹\s?\d(?:[\d,]*\d)?(?:\.\d+)?(?:\s?(?:lakhs?|crores?))?""", RegexOption.IGNORE_CASE)

        /** An amount spelled in minor units: "15000000 paise". */
        val PAISE = Regex("""\b\d(?:[\d,]*\d)?\s?paise\b""", RegexOption.IGNORE_CASE)

        /** A percentage, decimals included so "79.5%" is judged rather than silently allowed. */
        val PERCENT = Regex("""\d+(?:\.\d+)?\s?%""")

        /** `2027-03-31` (TIM-002's own format). */
        val ISO_DATE = Regex("""\b\d{4}-\d{2}-\d{2}\b""")

        /** "31 Mar 2027", "31 March 2027". */
        val DAY_MONTH_YEAR = Regex("""\b\d{1,2}\s[A-Za-z]{3,9}\.?\s\d{4}\b""")

        /** "Mar 31, 2027" — what a US-locale medium date looks like. */
        val MONTH_DAY_YEAR = Regex("""\b[A-Za-z]{3,9}\.?\s\d{1,2},\s?\d{4}\b""")

        /** "March 2027". */
        val MONTH_YEAR = Regex("""\b[A-Za-z]{3,9}\.?\s\d{4}\b""")

        /**
         * Whatever digits are left: a count, a score, a quantity, or a year standing alone.
         *
         * **Any length**, which is the limitation `NumericGuardrail` wrote down and could not fix:
         * it stopped at three digits because a year was indistinguishable from a count. Dates are
         * read before this now, so a four-digit number that is not a date is judged like any other.
         */
        val NUMBER = Regex("""(?<![\d.])-?\d+(?:\.\d+)?(?![\d%])""")
    }
}
