package com.aicfo.domain.engines.tax

import com.aicfo.core.model.Money

/**
 * The tax knowledge base, as typed values the engine can read (issue 13.4; §38, §6, TAX-002).
 *
 * Why:  §38.1's whole point is that tax parameters change every Budget, so they are a **versioned
 *       data file** and never hardcoded. The engine is pure Kotlin and cannot read the file
 *       (ARC-002), so it reads this mirror — and `TaxKbDriftTest` fails the build the moment the
 *       two disagree. Without that, the Budget edit lands in the JSON, the mirror keeps last year's
 *       rates, and every figure the app shows is quietly a year out of date.
 * What: both regimes' slabs, standard deductions, rebates and caps; the cess; the capital-gains
 *       rules; and the harvesting thresholds.
 * Result: changing a slab is a JSON edit plus this file, reviewed together, and every estimate
 *         carries [fyRulesVersion] (TAX-002).
 * Changelog: 2026-10-09 — Created for issue 13.4 from tax-kb-fy2025-26.json 1.1.
 *
 * Input:  see each property. Output: an immutable value; [BUNDLED] is the one every caller uses.
 */
data class TaxKnowledge(
    val fyRulesVersion: String,
    val old: RegimeRules,
    val new: RegimeRules,
    val cessBps: Int,
    val surchargeModelled: Boolean,
    val surchargeAppliesAboveTaxable: Money,
    val equityLongTermAfterMonths: Int,
    val equityShortTermRateBps: Int,
    val equityLongTermRateBps: Int,
    val equityLtcgAnnualExemption: Money,
    val debtPre2023LongTermAfterMonths: Int,
    val debtPre2023LongTermRateBps: Int,
    val exemptionAlertDaysBeforeFyEnd: Int,
    val stcgToLtcgCountdownDays: Int,
) {
    /** Result: the rules for a regime. Input: [regime]. Output: [RegimeRules]. */
    fun rulesFor(regime: Regime): RegimeRules = if (regime == Regime.OLD) old else new

    companion object {
        /** 1 rupee = 100 paise (MNY-001). Rupee figures in the KB become paise here, once. */
        const val PAISE_PER_RUPEE: Long = 100L

        /** The KB file these values were copied from, as `_meta.version`. */
        const val KB_VERSION = "1.1"

        /** `_meta.fy_rules_version` — printed on every result (TAX-002). */
        const val FY_RULES_VERSION = "2025-26.1"

        /** Result: rupees as paise. Input: [rupees]. Output: [Money]. */
        private fun rupees(rupees: Long) = Money(rupees * PAISE_PER_RUPEE)

        /**
         * The bundled knowledge base, mirroring `tax-kb-fy2025-26.json` 1.1.
         * Result: the values the app ships with. Input: none. Output: [TaxKnowledge].
         */
        val BUNDLED =
            TaxKnowledge(
                fyRulesVersion = FY_RULES_VERSION,
                old =
                    RegimeRules(
                        regime = Regime.OLD,
                        slabs =
                            listOf(
                                Slab(rupees(2_50_000), 0),
                                Slab(rupees(5_00_000), 500),
                                Slab(rupees(10_00_000), 2_000),
                                Slab(null, 3_000),
                            ),
                        standardDeduction = rupees(50_000),
                        rebateMax = rupees(12_500),
                        rebateTaxableUpto = rupees(5_00_000),
                        section80CCap = rupees(1_50_000),
                        section80CcdOneBCap = rupees(50_000),
                        section80DCap = rupees(25_000),
                        allowsItemisedDeductions = true,
                    ),
                new =
                    RegimeRules(
                        regime = Regime.NEW,
                        slabs =
                            listOf(
                                Slab(rupees(4_00_000), 0),
                                Slab(rupees(8_00_000), 500),
                                Slab(rupees(12_00_000), 1_000),
                                Slab(rupees(16_00_000), 1_500),
                                Slab(rupees(20_00_000), 2_000),
                                Slab(rupees(24_00_000), 2_500),
                                Slab(null, 3_000),
                            ),
                        standardDeduction = rupees(75_000),
                        rebateMax = rupees(60_000),
                        rebateTaxableUpto = rupees(12_00_000),
                        section80CCap = Money.ZERO,
                        section80CcdOneBCap = Money.ZERO,
                        section80DCap = Money.ZERO,
                        allowsItemisedDeductions = false,
                    ),
                cessBps = 400,
                surchargeModelled = false,
                surchargeAppliesAboveTaxable = rupees(50_00_000),
                equityLongTermAfterMonths = 12,
                equityShortTermRateBps = 2_000,
                equityLongTermRateBps = 1_250,
                equityLtcgAnnualExemption = rupees(1_25_000),
                debtPre2023LongTermAfterMonths = 24,
                debtPre2023LongTermRateBps = 1_250,
                exemptionAlertDaysBeforeFyEnd = 60,
                stcgToLtcgCountdownDays = 30,
            )
    }
}

/**
 * One regime's parameters.
 * Input:  [regime]; [slabs] — in ascending order, the last one open-ended; [standardDeduction];
 *   [rebateMax] and [rebateTaxableUpto] — §87A; the three caps; [allowsItemisedDeductions] —
 *   false for the new regime, which is what makes 80C and 80D worth nothing there.
 * Output: an immutable value.
 * Changelog: 2026-10-09 — Created for issue 13.4.
 */
data class RegimeRules(
    val regime: Regime,
    val slabs: List<Slab>,
    val standardDeduction: Money,
    val rebateMax: Money,
    val rebateTaxableUpto: Money,
    val section80CCap: Money,
    val section80CcdOneBCap: Money,
    val section80DCap: Money,
    val allowsItemisedDeductions: Boolean,
) {
    init {
        require(slabs.isNotEmpty()) { "a regime needs at least one slab" }
        require(slabs.last().uptoExclusive == null) {
            "the top slab must be open-ended, or income above it would be untaxed"
        }
        require(slabs.dropLast(1).all { it.uptoExclusive != null }) {
            "only the top slab may be open-ended"
        }
        require(
            slabs.dropLast(1).map { it.uptoExclusive!!.minor } ==
                slabs.dropLast(1).map { it.uptoExclusive!!.minor }.sorted(),
        ) {
            "slabs must ascend, or the band walk would tax the wrong income at the wrong rate"
        }
    }
}

/**
 * One slab band.
 * Input:  [uptoExclusive] — the top of the band in paise, `null` on the open-ended one;
 *   [rateBps] — basis points (MNY-002).
 * Output: an immutable value.
 * Changelog: 2026-10-09 — Created for issue 13.4.
 */
data class Slab(
    val uptoExclusive: Money?,
    val rateBps: Int,
) {
    init {
        require(rateBps in 0..BPS_IN_FULL) { "a slab rate is 0..10000 bps (MNY-002), was $rateBps" }
        require(uptoExclusive == null || uptoExclusive > Money.ZERO) {
            "a slab's upper bound must be positive, was $uptoExclusive"
        }
    }

    private companion object {
        const val BPS_IN_FULL = 10_000
    }
}
