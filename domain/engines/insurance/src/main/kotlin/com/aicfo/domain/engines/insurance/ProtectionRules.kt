package com.aicfo.domain.engines.insurance

/**
 * The rulebook rows AI-INS applies (issue 13.3; CLAUDE.md §6, SRS §39.1).
 *
 * Why:  every number §39.1 names is a financial threshold, and §6 says those live in
 *       `ai/rules/rules-kb.json` rather than in an engine. The engine is pure Kotlin and cannot
 *       read the file (ARC-002), so it reads this mirror — and `ProtectionRulebookDriftTest` fails
 *       the build the moment the two disagree, which is the only thing that makes a mirror safe.
 * What: the term-cover rule's multiples and IRDAI bands, the health floor, and the endowment
 *       detector's threshold and comparison assumptions.
 * Result: changing what the app considers adequate cover is a JSON edit plus this file, reviewed
 *         together, and every figure the engine publishes cites the row it came from (P-02).
 * Changelog: 2026-10-03 — Created for issue 13.3 from rules-kb.json 1.24.0.
 *
 * Input:  see each property. Output: an immutable value; [BUNDLED] is the one every caller uses.
 */
data class ProtectionRules(
    val incomeMultipleMin: Int,
    val incomeMultipleMax: Int,
    val requiresDependents: Boolean,
    val includeOutstandingLiabilities: Boolean,
    val hlvAgeMultipliers: List<HlvBand>,
    val singleIncomeUsesMaxMultiple: Boolean,
    val baseFloorInrLakh: Int,
    val metroFloorInrLakh: Int,
    val healthcareInflationBps: Int,
    val termPremiumPerLakhPaiseMax: Long,
    val flagPremiumPerLakhPaiseMin: Long,
    val equitySipReturnBps: Int,
    val endowmentReturnBpsLow: Int,
    val endowmentReturnBpsHigh: Int,
    val comparisonHorizonYears: Int,
) {
    init {
        require(incomeMultipleMin <= incomeMultipleMax) {
            "the income-multiple band must not run backwards: $incomeMultipleMin..$incomeMultipleMax"
        }
        require(hlvAgeMultipliers.isNotEmpty()) { "RULE-TERM-10X needs at least one HLV band" }
        require(flagPremiumPerLakhPaiseMin > termPremiumPerLakhPaiseMax) {
            "the flag threshold must sit ABOVE the dearest real term plan, or genuine term cover " +
                "gets flagged as an investment — the false positive that matters most"
        }
        require(endowmentReturnBpsLow <= endowmentReturnBpsHigh) {
            "the endowment return band must not run backwards"
        }
        require(comparisonHorizonYears > 0) { "a comparison needs a horizon" }
    }

    /**
     * The IRDAI human-life-value multiple for an age.
     * Why:    §39.1 gives four bands and **no rule for an age outside them**. Returning `null`
     *         rather than guessing is what lets the engine refuse the input instead of quietly
     *         assessing a 14-year-old against the 18–35 band.
     * Result: the multiple, or `null` when no band covers [ageYears].
     * Input:  [ageYears]. Output: `Int?`.
     */
    fun hlvMultipleFor(ageYears: Int): Int? =
        hlvAgeMultipliers.firstOrNull { ageYears in it.minAge..it.maxAge }?.multiple

    /**
     * The health-cover floor in paise.
     * Result: the metro or base figure, converted from lakh to paise (MNY-001).
     * Input:  [isMetro]. Output: paise as [Long].
     */
    fun healthFloorPaise(isMetro: Boolean): Long =
        (if (isMetro) metroFloorInrLakh else baseFloorInrLakh).toLong() * PAISE_PER_LAKH

    companion object {
        /** 1 lakh rupees = 100 000 rupees = 10 000 000 paise (MNY-001). */
        const val PAISE_PER_LAKH: Long = 1_00_000L * 100L

        /** The rulebook file these values were copied from, as `_meta.version`. */
        const val RULEBOOK_VERSION = "1.24.0"

        /** `RULE-TERM-10X` — how much life cover, and on which of §39.1's two arms. */
        const val TERM_RULE = "RULE-TERM-10X"

        /** Its version, cited beside the id so an old insight stays reproducible (AI-ARC-006). */
        const val TERM_RULE_VERSION = "1.1"

        /** `RULE-HEALTH-COVER` — the family floor, and the inflation it is sized against. */
        const val HEALTH_RULE = "RULE-HEALTH-COVER"

        /** Its version. */
        const val HEALTH_RULE_VERSION = "1.1"

        /** `RULE-TERM-VS-ENDOW` — INS-002's detector and its comparison assumptions. */
        const val ENDOWMENT_RULE = "RULE-TERM-VS-ENDOW"

        /** Its version. */
        const val ENDOWMENT_RULE_VERSION = "1.0"

        /**
         * The bundled rules, mirroring `rules-kb.json` 1.24.0.
         * Result: the values the app ships with. Input: none. Output: [ProtectionRules].
         */
        val BUNDLED =
            ProtectionRules(
                incomeMultipleMin = 10,
                incomeMultipleMax = 15,
                requiresDependents = true,
                includeOutstandingLiabilities = true,
                hlvAgeMultipliers =
                    listOf(
                        HlvBand(18, 35, 25),
                        HlvBand(36, 45, 20),
                        HlvBand(46, 50, 15),
                        HlvBand(51, 60, 10),
                    ),
                singleIncomeUsesMaxMultiple = true,
                baseFloorInrLakh = 5,
                metroFloorInrLakh = 10,
                healthcareInflationBps = 1_400,
                termPremiumPerLakhPaiseMax = 1_40_000L,
                flagPremiumPerLakhPaiseMin = 3_00_000L,
                equitySipReturnBps = 1_200,
                endowmentReturnBpsLow = 400,
                endowmentReturnBpsHigh = 600,
                comparisonHorizonYears = 30,
            )
    }
}

/**
 * One IRDAI human-life-value band: an age range and the income multiple it carries.
 *
 * Why:    a typed row rather than a nested list, so the drift test compares fields with names and a
 *         band cannot be read in the wrong order.
 * Input:  [minAge]; [maxAge] — both inclusive; [multiple] — times annual income.
 * Output: an immutable value.
 * Changelog: 2026-10-03 — Created for issue 13.3.
 */
data class HlvBand(
    val minAge: Int,
    val maxAge: Int,
    val multiple: Int,
) {
    init {
        require(minAge <= maxAge) { "an age band must not run backwards: $minAge..$maxAge" }
        require(multiple > 0) { "an HLV multiple must be positive, was $multiple" }
    }
}
