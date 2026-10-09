package com.aicfo.domain.engines.tax

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation

/**
 * AI-TAX's implementation — §38's parameters applied to one year (issue 13.4).
 *
 * Why:  `internal` per ARC-003, reached through [TaxEngineFactory]. Every rate and cap comes from
 *       the knowledge base and the FY rules version travels on the result (TAX-002): the engine
 *       decides *which* rule applies, and `tax-kb-fy2025-26.json` decides *how much*.
 * What: computes both regimes, picks the winner, finds the break-even, taxes the realised gains,
 *       raises TAX-001's alerts, and lists what it did not model.
 * Result: a [TaxEstimate], or an `Err` naming the field that made one impossible.
 * Changelog: 2026-10-09 — Created for issue 13.4.
 *
 * **It never files, and it never names an instrument** (P-07, TAX-001). Those are properties of the
 * result types — there is nothing in [TaxEstimate] to put a filing action in, and nothing in
 * [TaxAlert] to put a fund name in.
 */
internal class SlabTaxEngine(
    private val knowledge: TaxKnowledge = TaxKnowledge.BUNDLED,
) : TaxEngine {
    override fun estimate(input: TaxInput): Result<TaxEstimate, AppError> {
        validate(input)?.let { return Err(it) }

        val gains = capitalGains(input.realisedGains)
        val old = compute(Regime.OLD, input, gains.slabTaxedGains)
        val new = compute(Regime.NEW, input, gains.slabTaxedGains)
        val winner = if (old.totalTax <= new.totalTax) Regime.OLD else Regime.NEW
        val margin = if (old.totalTax <= new.totalTax) new.totalTax - old.totalTax else old.totalTax - new.totalTax

        return Ok(
            TaxEstimate(
                old = old,
                new = new,
                winner = winner,
                margin = margin,
                breakEvenDeductions = breakEven(input, new.totalTax, gains.slabTaxedGains),
                capitalGains = gains,
                alerts = alerts(input, gains),
                limitations = limitations(input, old, new),
                fyRulesVersion = knowledge.fyRulesVersion,
                provenance = provenance(input),
            ),
        )
    }

    /**
     * Rejects an input no estimate can honestly be made from.
     * Result: the [AppError], or `null`. Input: [input]. Output: `AppError?`.
     */
    private fun validate(input: TaxInput): AppError? {
        val d = input.deductions
        val broken =
            listOf<Pair<Boolean, String>>(
                !TaxMath.isIsoDate(input.todayIsoDate) to "todayIsoDate",
                (input.salary.grossAnnual < Money.ZERO) to "grossAnnual",
                (input.salary.employerNpsContribution < Money.ZERO) to "employerNpsContribution",
                listOf(d.section80C, d.section80CcdOneB, d.section80D, d.homeLoanInterest, d.hraExempt)
                    .any { it < Money.ZERO } to "deductions",
                input.realisedGains.any { it.heldMonths < 0 } to "heldMonths",
                input.openPositions.any { it.heldMonths < 0 } to "heldMonths",
            ).firstOrNull { (isBroken, _) -> isBroken }

        return broken?.let { (_, field) -> AppError.Validation(field) }
    }

    /**
     * One regime, from gross salary to total tax.
     *
     * Why:    `slabTaxedGains` are added to salary rather than taxed separately, because that is
     *         what "slab rate always" means for a post-April-2023 debt fund — the gain stacks on
     *         top of income and is taxed at whatever marginal band it lands in (§38.2).
     * Result: the full [RegimeComputation], bands included (P-02).
     * Input:  [regime]; [input]; [slabTaxedGains]. Output: [RegimeComputation].
     */
    private fun compute(
        regime: Regime,
        input: TaxInput,
        slabTaxedGains: Money,
    ): RegimeComputation {
        val rules = knowledge.rulesFor(regime)
        val gross = input.salary.grossAnnual + slabTaxedGains
        val allowed = deductionsAllowed(rules, input)
        val taxable = if (gross > allowed) gross - allowed else Money.ZERO
        val bands = TaxMath.walk(taxable, rules.slabs)
        val before = bands.fold(Money.ZERO) { running, band -> running + band.taxHere }
        val rebate = if (taxable <= rules.rebateTaxableUpto) TaxMath.cappedAt(before, rules.rebateMax) else Money.ZERO
        val afterRebate = before - rebate
        val cess = TaxMath.percentOf(afterRebate, knowledge.cessBps)

        return RegimeComputation(
            regime = regime,
            grossIncome = gross,
            deductionsAllowed = allowed,
            taxableIncome = taxable,
            bands = bands,
            taxBeforeRebate = before,
            rebate = rebate,
            cess = cess,
            totalTax = afterRebate + cess,
        )
    }

    /**
     * What a regime actually permits of what was claimed.
     *
     * Why:    the new regime allows the standard deduction and the employer's NPS contribution and
     *         **nothing else** — 80C, 80D and 80CCD(1B) are worth zero there, which is the whole
     *         reason the comparison is interesting. Each old-regime deduction is capped at the
     *         knowledge base's figure rather than taken as claimed, because a user may enter what
     *         they invested rather than what is deductible.
     * Result: the total allowed. Input: [rules]; [input]. Output: [Money].
     */
    private fun deductionsAllowed(
        rules: RegimeRules,
        input: TaxInput,
    ): Money {
        // 80CCD(2), the employer's NPS share, is deductible in BOTH regimes (§38.1).
        val base = rules.standardDeduction + input.salary.employerNpsContribution
        if (!rules.allowsItemisedDeductions) return base

        val d = input.deductions
        return base +
            TaxMath.cappedAt(d.section80C, rules.section80CCap) +
            TaxMath.cappedAt(d.section80CcdOneB, rules.section80CcdOneBCap) +
            TaxMath.cappedAt(d.section80D, rules.section80DCap) +
            d.homeLoanInterest +
            d.hraExempt
    }

    /**
     * How much **more** deduction the old regime would need to start winning (§38.1, "shown in
     * rupees").
     *
     * Why:    relative to what is already claimed, not from zero. The first version probed by
     *         *replacing* the household's deductions, so "does the old regime already win?" was
     *         answered for a household with none of them — and a household that was already
     *         winning got a break-even figure anyway. Measured against the actual input, `null`
     *         means "you already win", which is what the field has to mean to be read correctly.
     * Result: the extra deductions needed, or `null` when the old regime already wins, or when no
     *         amount within the household's income would be enough.
     * Input:  [input]; [newTotal]; [slabTaxedGains]. Output: `Money?`.
     */
    private fun breakEven(
        input: TaxInput,
        newTotal: Money,
        slabTaxedGains: Money,
    ): Money? =
        TaxMath.breakEvenDeductions(
            oldTaxAt = { extra ->
                // Probed as home-loan interest, the one old-regime deduction the knowledge base
                // does not cap — so the search is not pinned by a ceiling before it converges.
                val claimed = input.deductions.homeLoanInterest
                val probed = input.copy(deductions = input.deductions.copy(homeLoanInterest = claimed + extra))
                compute(Regime.OLD, probed, slabTaxedGains).totalTax
            },
            newTax = newTotal,
            maxDeductions = input.salary.grossAnnual,
        )

    /**
     * §38.2's treatment, by asset class.
     * Result: the [CapitalGainsSummary]. Input: [gains]. Output: [CapitalGainsSummary].
     */
    private fun capitalGains(gains: List<RealisedGain>): CapitalGainsSummary {
        var short = Money.ZERO
        var slabTaxed = Money.ZERO
        var exempt = Money.ZERO
        var equityLong = Money.ZERO

        gains.forEach { gain ->
            when (gain.assetClass) {
                AssetClass.SGB_HELD_TO_MATURITY -> exempt += gain.gain
                AssetClass.DEBT_POST_2023 -> slabTaxed += gain.gain
                AssetClass.EQUITY ->
                    if (gain.heldMonths > knowledge.equityLongTermAfterMonths) {
                        equityLong += gain.gain
                    } else {
                        short += TaxMath.percentOf(gain.gain, knowledge.equityShortTermRateBps)
                    }
                AssetClass.DEBT_PRE_2023_OR_GOLD ->
                    if (gain.heldMonths > knowledge.debtPre2023LongTermAfterMonths) {
                        short += TaxMath.percentOf(gain.gain, knowledge.debtPre2023LongTermRateBps)
                    } else {
                        slabTaxed += gain.gain
                    }
            }
        }

        // The equity exemption is annual and applies to the NET long-term equity gain, so it is
        // taken once over the total rather than per lot — taking it per lot would multiply it.
        val exemption = knowledge.equityLtcgAnnualExemption
        val used = if (equityLong > Money.ZERO) TaxMath.cappedAt(equityLong, exemption) else Money.ZERO
        val taxableLong = if (equityLong > used) equityLong - used else Money.ZERO

        return CapitalGainsSummary(
            shortTermTax = short,
            longTermTax = TaxMath.percentOf(taxableLong, knowledge.equityLongTermRateBps),
            exemptionUsed = used,
            exemptionRemaining = exemption - used,
            slabTaxedGains = slabTaxed,
            exemptGains = exempt,
        )
    }

    /**
     * TAX-001's alerts — generic, never naming an instrument.
     * Result: the alerts, soonest first. Input: [input]; [gains]. Output: the list.
     */
    private fun alerts(
        input: TaxInput,
        gains: CapitalGainsSummary,
    ): List<TaxAlert> {
        val daysLeft = TaxMath.daysToFinancialYearEnd(input.todayIsoDate)
        val raised = mutableListOf<TaxAlert>()

        if (gains.exemptionRemaining > Money.ZERO && daysLeft <= knowledge.exemptionAlertDaysBeforeFyEnd) {
            raised += TaxAlert(TaxAlertKind.UNUSED_LTCG_EXEMPTION, gains.exemptionRemaining, daysLeft, CITE_TAX_001)
        }

        val losses = input.openPositions.filter { it.unrealisedGain < Money.ZERO }
        if (losses.isNotEmpty() && daysLeft <= knowledge.exemptionAlertDaysBeforeFyEnd) {
            val total = losses.fold(Money.ZERO) { running, position -> running + position.unrealisedGain }
            raised += TaxAlert(TaxAlertKind.HARVESTABLE_LOSSES, total, daysLeft, CITE_TAX_001)
        }

        // A holding one month short of long-term: selling now costs 20% instead of 12.5%.
        val turning =
            input.openPositions.filter {
                it.assetClass == AssetClass.EQUITY && it.heldMonths == knowledge.equityLongTermAfterMonths
            }
        if (turning.isNotEmpty()) {
            val total = turning.fold(Money.ZERO) { running, position -> running + position.unrealisedGain }
            raised +=
                TaxAlert(
                    TaxAlertKind.SHORT_TERM_TURNING_LONG,
                    total,
                    knowledge.stcgToLtcgCountdownDays.toLong(),
                    CITE_TAX_001,
                )
        }
        return raised.sortedBy { it.daysAway }
    }

    /**
     * What this estimate left out, travelling with the figure rather than in a comment.
     * Result: the limitations. Input: [input]; [old]; [new]. Output: the list.
     */
    private fun limitations(
        input: TaxInput,
        old: RegimeComputation,
        new: RegimeComputation,
    ): List<TaxLimitation> {
        val found = mutableListOf<TaxLimitation>()
        val highest = if (old.taxableIncome > new.taxableIncome) old.taxableIncome else new.taxableIncome
        if (!knowledge.surchargeModelled && highest > knowledge.surchargeAppliesAboveTaxable) {
            found += TaxLimitation(LimitationKind.SURCHARGE_NOT_MODELLED)
        }
        if (input.deductions.hraExempt > Money.ZERO) {
            found += TaxLimitation(LimitationKind.HRA_TAKEN_AS_GIVEN)
        }
        return found
    }

    /**
     * The provenance every estimate carries (AI-ARC-003/006, TAX-002).
     * Result: the engine, its version, and the knowledge base's FY rules version.
     * Input:  [input]. Output: [EngineProvenance].
     */
    private fun provenance(input: TaxInput): EngineProvenance =
        EngineProvenance(
            engineId = ENGINE_ID,
            engineVersion = ENGINE_VERSION,
            computedAtUtcMillis = input.nowUtcMillis,
            evidence =
                listOf(
                    RuleCitation(CITE_SLABS, knowledge.fyRulesVersion),
                    RuleCitation(CITE_CAPITAL_GAINS, knowledge.fyRulesVersion),
                    RuleCitation(CITE_TAX_001, knowledge.fyRulesVersion),
                ),
            inputWindow = "FY${knowledge.fyRulesVersion.substringBefore('.')}",
        )

    private companion object {
        const val ENGINE_ID = "AI-TAX"
        const val ENGINE_VERSION = "1.0"
        const val CITE_SLABS = "TAX-KB.regimes"
        const val CITE_CAPITAL_GAINS = "TAX-KB.capital_gains"
        const val CITE_TAX_001 = "TAX-001"
    }
}
