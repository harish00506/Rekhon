package com.aicfo.domain.engines.insurance

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation

/**
 * AI-INS's implementation — §39.1's rules applied to one household (issue 13.3).
 *
 * Why:  `internal` per ARC-003, reached through [ProtectionEngineFactory]. Every number it
 *       publishes comes from a rulebook row and every row it used is cited back (P-02, P-03): the
 *       engine decides *which* rule applies, and `rules-kb.json` decides *how much*.
 * What: validates, then answers §39.1's three questions — how much life cover is needed and on
 *       which arm, what the health floor is, and which policies are investments wearing an
 *       insurance label.
 * Result: a [ProtectionAssessment], or an `Err` naming the field that made one impossible.
 * Changelog: 2026-10-03 — Created for issue 13.3.
 *
 * **Nothing here recommends an action** (P-07). There is no "buy this", no "surrender that", and no
 * ranking of products. The engine publishes a gap and an arithmetic comparison; what to do about
 * either depends on health, surrender value and tax already paid — facts the app does not hold.
 */
internal class RuleProtectionEngine(
    private val rules: ProtectionRules = ProtectionRules.BUNDLED,
) : ProtectionEngine {
    override fun assess(input: ProtectionInput): Result<ProtectionAssessment, AppError> {
        validate(input)?.let { return Err(it) }

        return Ok(
            ProtectionAssessment(
                term = termGap(input.household, input.policies),
                health = healthGap(input.household, input.policies),
                investmentLinked = investmentLinked(input.policies),
                provenance = provenance(input),
            ),
        )
    }

    /**
     * Rejects an input no assessment can honestly be made from.
     * Why:    an age outside the rulebook's bands is the important one. §39.1 gives four bands and
     *         no rule beyond them, so assessing a 16- or 70-year-old would mean inventing a
     *         multiple — and the figure would look exactly as authoritative as a real one (P-03).
     * Result: the [AppError] to return, or `null`.
     * Input:  [input]. Output: `AppError?`.
     */
    private fun validate(input: ProtectionInput): AppError? {
        val household = input.household
        val broken =
            listOf<Pair<Boolean, String>>(
                (rules.hlvMultipleFor(household.ageYears) == null) to "ageYears",
                (household.annualIncome < Money.ZERO) to "annualIncome",
                (household.outstandingLiabilities < Money.ZERO) to "outstandingLiabilities",
                input.policies.any { it.cover < Money.ZERO } to "cover",
                input.policies.any { it.annualPremium < Money.ZERO } to "annualPremium",
            ).firstOrNull { (isBroken, _) -> isBroken }

        return broken?.let { (_, field) -> AppError.Validation(field) }
    }

    /**
     * §39.1's headline: `max(income multiple + liabilities, HLV by age) − existing term cover`.
     *
     * Why:    the two arms miss different households, which is why §39.1 takes the higher. The
     *         income multiple under-covers a young earner with decades of income ahead; the HLV
     *         multiple alone ignores the loan somebody would inherit. Taking the larger means a
     *         household is covered against whichever reading of its own future is worse.
     *
     *         **No dependents, no assessment.** `RULE-TERM-10X` carries `requires_dependents`, and
     *         returning `null` rather than a gap of zero is the honest answer: term cover replaces
     *         income for people who depend on it, and "you need none" is a different statement from
     *         "you need some and have it".
     * Result: the [CoverGap], or `null` when the rule does not apply.
     * Input:  [household]; [policies]. Output: `CoverGap?`.
     */
    private fun termGap(
        household: Household,
        policies: List<Policy>,
    ): CoverGap? {
        if (rules.requiresDependents && !household.hasDependents) return null

        // §39.3: with one income, the whole household rests on one life, so the multiple goes to
        // the top of the band rather than the bottom.
        val multiple =
            if (household.isSingleIncome && rules.singleIncomeUsesMaxMultiple) {
                rules.incomeMultipleMax
            } else {
                rules.incomeMultipleMin
            }
        val liabilities =
            if (rules.includeOutstandingLiabilities) household.outstandingLiabilities else Money.ZERO
        val byIncome = ProtectionMath.timesIncome(household.annualIncome, multiple) + liabilities

        // hlvMultipleFor cannot be null here: validate() already refused an age outside the bands.
        val hlvMultiple = requireNotNull(rules.hlvMultipleFor(household.ageYears))
        val byHlv = ProtectionMath.timesIncome(household.annualIncome, hlvMultiple)

        val needed = if (byIncome >= byHlv) byIncome else byHlv
        val basis =
            if (byIncome >= byHlv) {
                CoverBasis.INCOME_MULTIPLE_PLUS_LIABILITIES
            } else {
                CoverBasis.HUMAN_LIFE_VALUE
            }
        val existing = policies.filter { it.kind == PolicyKind.TERM }.sumCover()
        return CoverGap(
            needed = needed,
            existing = existing,
            gap = ProtectionMath.gap(needed, existing),
            basis = basis,
            citation = "${ProtectionRules.TERM_RULE}@${ProtectionRules.TERM_RULE_VERSION}",
        )
    }

    /**
     * §39.1's health floor against what the household holds.
     * Why:    a floor, not a target — the rule's own wording. It is reported for every household,
     *         dependants or not, because a hospital bill does not ask who depends on you.
     * Result: the [CoverGap].
     * Input:  [household]; [policies]. Output: [CoverGap].
     */
    private fun healthGap(
        household: Household,
        policies: List<Policy>,
    ): CoverGap {
        val needed = Money(rules.healthFloorPaise(household.isMetro))
        val existing = policies.filter { it.kind == PolicyKind.HEALTH }.sumCover()
        return CoverGap(
            needed = needed,
            existing = existing,
            gap = ProtectionMath.gap(needed, existing),
            basis = CoverBasis.HEALTH_FLOOR,
            citation = "${ProtectionRules.HEALTH_RULE}@${ProtectionRules.HEALTH_RULE_VERSION}",
        )
    }

    /**
     * INS-002: the policies whose premium per lakh says they are investments, with the workings.
     *
     * Why:    **only [PolicyKind.OTHER] is examined.** A term or health policy the user has labelled
     *         as such is never flagged, however dear — an older person's genuine term plan is the
     *         false positive that would cost the most trust, and the rule's threshold is set above
     *         real term pricing precisely so it cannot happen by accident either.
     * Result: one row per flagged policy, dearest per lakh first, each carrying the comparison
     *         §39.1 asks to be *shown* rather than acted on (P-07).
     * Input:  [policies]. Output: the flagged rows.
     */
    private fun investmentLinked(policies: List<Policy>): List<InvestmentLinkedPolicy> =
        policies
            .asSequence()
            .filter { it.kind == PolicyKind.OTHER }
            .mapNotNull { policy ->
                val perLakh = ProtectionMath.premiumPerLakh(policy.annualPremium, policy.cover)
                if (perLakh == null || perLakh < rules.flagPremiumPerLakhPaiseMin) null else policy to perLakh
            }
            .map { (policy, perLakh) -> comparisonFor(policy, perLakh) }
            .sortedByDescending { it.premiumPerLakhPaise }
            .toList()

    /**
     * The buy-term-invest-the-rest arithmetic for one flagged policy.
     * Result: an [InvestmentLinkedPolicy] carrying what the same cover costs as term, what the
     *         difference would grow to at the rulebook's equity rate, and what the policy itself
     *         would return at the rulebook's endowment band — three figures the reader can check.
     * Input:  [policy]; [perLakh] — already computed. Output: [InvestmentLinkedPolicy].
     */
    private fun comparisonFor(
        policy: Policy,
        perLakh: Long,
    ): InvestmentLinkedPolicy {
        val lakhsOfCover =
            java.math.BigDecimal.valueOf(policy.cover.minor)
                .divide(
                    java.math.BigDecimal.valueOf(ProtectionRules.PAISE_PER_LAKH),
                    LAKH_SCALE,
                    java.math.RoundingMode.HALF_EVEN,
                )
        val termEquivalent =
            Money(
                lakhsOfCover
                    .multiply(java.math.BigDecimal.valueOf(rules.termPremiumPerLakhPaiseMax))
                    .setScale(0, java.math.RoundingMode.HALF_EVEN)
                    .longValueExact(),
            )
        val difference = ProtectionMath.gap(policy.annualPremium, termEquivalent)
        val years = rules.comparisonHorizonYears
        return InvestmentLinkedPolicy(
            policyId = policy.id,
            label = policy.label,
            premiumPerLakhPaise = perLakh,
            termEquivalentPremium = termEquivalent,
            annualDifference = difference,
            investedValue = ProtectionMath.futureValueOfYearlyInvestment(difference, rules.equitySipReturnBps, years),
            policyValueLow =
                ProtectionMath.futureValueOfYearlyInvestment(policy.annualPremium, rules.endowmentReturnBpsLow, years),
            policyValueHigh =
                ProtectionMath.futureValueOfYearlyInvestment(policy.annualPremium, rules.endowmentReturnBpsHigh, years),
            horizonYears = years,
            citation = "${ProtectionRules.ENDOWMENT_RULE}@${ProtectionRules.ENDOWMENT_RULE_VERSION}",
        )
    }

    /**
     * The provenance every assessment carries (AI-ARC-003/006).
     * Result: the engine's id and version, when it ran, and the three rows it read **with their own
     *         versions**, so an insight stored today still says which thresholds produced it.
     * Input:  [input]. Output: [EngineProvenance].
     */
    private fun provenance(input: ProtectionInput): EngineProvenance =
        EngineProvenance(
            engineId = ENGINE_ID,
            engineVersion = ENGINE_VERSION,
            computedAtUtcMillis = input.nowUtcMillis,
            evidence =
                listOf(
                    RuleCitation(ProtectionRules.TERM_RULE, ProtectionRules.TERM_RULE_VERSION),
                    RuleCitation(ProtectionRules.HEALTH_RULE, ProtectionRules.HEALTH_RULE_VERSION),
                    RuleCitation(ProtectionRules.ENDOWMENT_RULE, ProtectionRules.ENDOWMENT_RULE_VERSION),
                ),
        )

    private companion object {
        /**
         * Decimal places kept when expressing a cover in lakhs, before it is multiplied by a
         * per-lakh price. Four is enough that a cover of a few thousand rupees still prices
         * correctly, and the result is rounded to the paise immediately afterwards anyway.
         */
        const val LAKH_SCALE = 4

        const val ENGINE_ID = "AI-INS"
        const val ENGINE_VERSION = "1.0"
    }
}

/** Result: the total cover of a list of policies, in paise (MNY-001). */
private fun List<Policy>.sumCover(): Money = fold(Money.ZERO) { running, policy -> running + policy.cover }
