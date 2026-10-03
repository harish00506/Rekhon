package com.aicfo.domain.engines.insurance

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ProtectionEngine]'s decisions, one at a time (issue 13.3; §39.1, §39.3, AC1).
 *
 * Why:  the golden file asserts whole households and is the stronger gate, but a household asserts
 *       a dozen things at once — when it goes red it says *that* something moved, not *which* rule.
 *       These are the boundaries: the ages where an IRDAI band changes, the point where one arm of
 *       §39.1's `max` overtakes the other, the premium at which a policy stops being protection.
 *       Each is one assertion about one rule.
 * What: the band edges, the arm switch, §39.3's single-income nudge, the four refusals, what is
 *       never flagged, provenance and determinism.
 * Result: every branch in `RuleProtectionEngine` has a test that fails if it is removed.
 * Changelog: 2026-10-03 — Created for issue 13.3.
 */
class ProtectionEngineTest {
    private val engine = ProtectionEngineFactory.create()

    // --- the flag ----------------------------------------------------------------------------------

    /**
     * Input:  the shipped constant.
     * Output: asserts the protection surface is off in v1 (AC2). The engine is inert; what the flag
     *         holds back is showing somebody the largest number this app can produce.
     */
    @Test
    fun `the protection surface is off in v1`() {
        assertFalse(ProtectionMode.IS_ENABLED)
    }

    // --- the IRDAI bands ----------------------------------------------------------------------------

    /**
     * Input:  the last age of one band and the first of the next.
     * Output: asserts the multiple changes exactly there. §39.1's bands are 25× (18–35), 20×
     *         (36–45), 15× (46–50), 10× (51–60), and an off-by-one at a boundary is a fifth of
     *         somebody's cover.
     */
    @Test
    fun `the HLV multiple changes exactly at each band edge`() {
        // No liabilities and the minimum multiple, so the HLV arm always wins and the figure is
        // simply multiple x income.
        assertEquals(income * 25, neededAt(35))
        assertEquals(income * 20, neededAt(36))
        assertEquals(income * 20, neededAt(45))
        assertEquals(income * 15, neededAt(46))
        assertEquals(income * 15, neededAt(50))
        assertEquals(income * 10, neededAt(51))
    }

    /**
     * Input:  ages outside §39.1's four bands.
     * Output: asserts a refusal. The rulebook has no multiple there, so assessing anyway would mean
     *         inventing one — and it would look exactly as authoritative as a real figure (P-03).
     */
    @Test
    fun `an age outside the rulebook's bands is refused, not guessed`() {
        assertEquals(Err(AppError.Validation("ageYears")), engine.assess(input(household(ageYears = 17))))
        assertEquals(Err(AppError.Validation("ageYears")), engine.assess(input(household(ageYears = 61))))
    }

    // --- which arm of the max wins ------------------------------------------------------------------

    /**
     * Input:  a 30-year-old (25× band) with no liabilities, then with liabilities large enough to
     *         carry the income arm past the HLV arm.
     * Output: asserts the basis flips, and that the needed figure follows. Which arm won is the
     *         most useful thing the screen can say, and it is the field a reader uses to check the
     *         app's reasoning (P-02).
     */
    @Test
    fun `the basis says which arm of the max produced the figure`() {
        val withoutLoan = assessment(household(liabilities = Money.ZERO))
        assertEquals(CoverBasis.HUMAN_LIFE_VALUE, withoutLoan.term!!.basis)
        assertEquals(income * 25, withoutLoan.term!!.needed)

        // 10x income + a loan bigger than 15x income clears the 25x band.
        val hugeLoan = income * 16
        val withLoan = assessment(household(liabilities = hugeLoan))
        assertEquals(CoverBasis.INCOME_MULTIPLE_PLUS_LIABILITIES, withLoan.term!!.basis)
        assertEquals(income * 10 + hugeLoan, withLoan.term!!.needed)
    }

    /**
     * Input:  the same household with one income and with two.
     * Output: asserts §39.3's nudge — one income takes the multiple to the top of the band. Checked
     *         at an age whose HLV multiple is low enough that the income arm decides, or the nudge
     *         would be invisible.
     */
    @Test
    fun `a single-income household is assessed at the top of the band`() {
        val dual = assessment(household(ageYears = 55, isSingleIncome = false)).term!!
        val single = assessment(household(ageYears = 55, isSingleIncome = true)).term!!

        // 51-60 is the 10x HLV band, so 10x income (dual) ties HLV and 15x (single) beats it.
        assertEquals(income * 10, dual.needed)
        assertEquals(income * 15, single.needed)
    }

    // --- the answers that are not numbers -----------------------------------------------------------

    /**
     * Input:  a household with no dependents.
     * Output: asserts term cover is **not assessed** rather than assessed at zero. "You need none"
     *         and "you need some and have it" are different statements, and a gap of zero would say
     *         the second.
     */
    @Test
    fun `without dependents there is no term assessment at all`() {
        val assessment = assessment(household(hasDependents = false))

        assertNull(assessment.term)
        // Health is still assessed: a hospital bill does not ask who depends on you.
        assertTrue(assessment.health.needed > Money.ZERO)
    }

    /**
     * Input:  a household with far more cover than it needs.
     * Output: asserts the gap floors at zero while `needed` and `existing` still report honestly,
     *         so the screen can say "you are covered" with the figures behind it.
     */
    @Test
    fun `over-cover floors the gap without hiding the figures`() {
        val term = assessment(household(ageYears = 55), existingTermCover = income * 50).term!!

        assertEquals(Money.ZERO, term.gap)
        assertEquals(income * 50, term.existing)
        assertTrue(term.needed > Money.ZERO)
    }

    // --- the health floor ---------------------------------------------------------------------------

    /**
     * Input:  a metro and a non-metro household.
     * Output: asserts ₹10L and ₹5L respectively — the rulebook's floors, in paise.
     */
    @Test
    fun `the health floor follows the metro flag`() {
        assertEquals(Money(10_00_000_00L), assessment(household(isMetro = true)).health.needed)
        assertEquals(Money(5_00_000_00L), assessment(household(isMetro = false)).health.needed)
    }

    // --- the endowment detector ---------------------------------------------------------------------

    /**
     * Input:  a policy priced one paise below the rulebook's threshold and one paise at it.
     * Output: asserts the flag fires **at** the threshold and not below. The detector's whole
     *         credibility is this boundary.
     */
    @Test
    fun `the endowment flag fires at the threshold and not below`() {
        // Cover of exactly 1 lakh makes the premium in paise equal to the price per lakh.
        val oneLakh = Money(1_00_000_00L)
        val atThreshold = flaggedCount(Policy("p", "p", PolicyKind.OTHER, oneLakh, Money(3_00_000L)))
        val belowIt = flaggedCount(Policy("p", "p", PolicyKind.OTHER, oneLakh, Money(2_99_999L)))

        assertEquals(1, atThreshold)
        assertEquals(0, belowIt)
    }

    /**
     * Input:  a term policy priced far above the endowment threshold, and a health one.
     * Output: asserts neither is flagged, **whatever** they cost. Only `OTHER` is examined — an
     *         older person's genuine term plan is the false positive that would cost the most
     *         trust, and the kind filter is what makes it impossible rather than unlikely.
     */
    @Test
    fun `a term or health policy is never flagged, however dear`() {
        val oneLakh = Money(1_00_000_00L)
        assertEquals(0, flaggedCount(Policy("t", "t", PolicyKind.TERM, oneLakh, Money(50_00_000L))))
        assertEquals(0, flaggedCount(Policy("h", "h", PolicyKind.HEALTH, oneLakh, Money(50_00_000L))))
    }

    /**
     * Input:  a flagged policy.
     * Output: asserts the comparison is complete — a term-equivalent premium, the difference, and
     *         three projected values. §39.1 asks for this to be **shown as math**, and a row with
     *         a flag but no workings would be the verdict without the reasoning (P-02, P-07).
     */
    @Test
    fun `a flagged policy carries the whole comparison, not a verdict`() {
        val assessment =
            assessment(
                policies = listOf(Policy("e", "Endowment", PolicyKind.OTHER, Money(10_00_000_00L), Money(80_000_00L))),
            )
        val flagged = assessment.investmentLinked.single()

        assertEquals(Money(14_000_00L), flagged.termEquivalentPremium)
        assertEquals(Money(66_000_00L), flagged.annualDifference)
        assertEquals(30, flagged.horizonYears)
        assertTrue(
            "the invested value must exceed the policy's own low projection",
            flagged.investedValue > flagged.policyValueLow,
        )
        assertTrue("the policy's band must not run backwards", flagged.policyValueHigh >= flagged.policyValueLow)
        assertEquals("RULE-TERM-VS-ENDOW@1.0", flagged.citation)
    }

    /**
     * Input:  two flagged policies at different prices per lakh.
     * Output: asserts the dearest per lakh comes first, so a screen showing one shows the worst.
     */
    @Test
    fun `flagged policies are ordered dearest per lakh first`() {
        val cheaper = Policy("a", "a", PolicyKind.OTHER, Money(10_00_000_00L), Money(40_000_00L))
        val dearer = Policy("b", "b", PolicyKind.OTHER, Money(10_00_000_00L), Money(90_000_00L))

        val order = assessment(policies = listOf(cheaper, dearer)).investmentLinked.map { it.policyId }

        assertEquals(listOf("b", "a"), order)
    }

    // --- refusals ------------------------------------------------------------------------------------

    /** Input: a negative income. Output: asserts a refusal naming the field. */
    @Test
    fun `a negative income is refused`() {
        assertEquals(
            Err(AppError.Validation("annualIncome")),
            engine.assess(input(Household(ageYears = 30, annualIncome = Money(-1L)))),
        )
    }

    /** Input: negative liabilities. Output: asserts a refusal — a debt owed to you is not a liability. */
    @Test
    fun `negative liabilities are refused`() {
        assertEquals(
            Err(AppError.Validation("outstandingLiabilities")),
            engine.assess(input(household(liabilities = Money(-1L)))),
        )
    }

    /** Input: a policy with negative cover. Output: asserts a refusal. */
    @Test
    fun `a policy with negative cover is refused`() {
        val bad = Policy("p", "p", PolicyKind.TERM, Money(-1L), Money(100L))

        assertEquals(Err(AppError.Validation("cover")), engine.assess(input(policies = listOf(bad))))
    }

    /** Input: a policy with a negative premium. Output: asserts a refusal. */
    @Test
    fun `a policy with a negative premium is refused`() {
        val bad = Policy("p", "p", PolicyKind.TERM, Money(100L), Money(-1L))

        assertEquals(Err(AppError.Validation("annualPremium")), engine.assess(input(policies = listOf(bad))))
    }

    // --- provenance and determinism -------------------------------------------------------------------

    /**
     * Input:  an assessment.
     * Output: asserts it names the engine and the three rows **with their versions**, so an insight
     *         stored today still says which thresholds produced it (AI-ARC-003/006).
     */
    @Test
    fun `every assessment cites the three rules and their versions`() {
        val provenance = assessment().provenance

        assertEquals("AI-INS", provenance.engineId)
        assertEquals("1.0", provenance.engineVersion)
        assertEquals(
            listOf("RULE-TERM-10X", "RULE-HEALTH-COVER", "RULE-TERM-VS-ENDOW"),
            provenance.evidence.map { it.ruleId },
        )
        assertEquals(listOf("1.1", "1.1", "1.0"), provenance.evidence.map { it.ruleVersion })
    }

    /**
     * Input:  the gaps and the flagged rows.
     * Output: asserts every citation names a rule **and** a version. A gap whose citation lost its
     *         version could not be reproduced once the threshold moved (AI-ARC-006).
     */
    @Test
    fun `every published figure cites a versioned rule`() {
        val endowment = Policy("e", "e", PolicyKind.OTHER, Money(10_00_000_00L), Money(80_000_00L))
        val assessment = assessment(policies = listOf(endowment))
        val citations =
            listOf(assessment.term!!.citation, assessment.health.citation) +
                assessment.investmentLinked.map { it.citation }

        citations
            .forEach { citation ->
                assertTrue(
                    "'$citation' does not name a rule and a version",
                    Regex("^RULE-[A-Z0-9-]+@\\d+\\.\\d+$").matches(citation),
                )
            }
    }

    /** Input: the same household twice. Output: asserts identical results (P-08). */
    @Test
    fun `the same household assesses identically every time`() {
        val input = input()
        assertEquals(engine.assess(input), engine.assess(input))
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private val income = Money(10_00_000_00L)

    private fun neededAt(ageYears: Int): Money = assessment(household(ageYears = ageYears)).term!!.needed

    private fun flaggedCount(policy: Policy): Int = assessment(policies = listOf(policy)).investmentLinked.size

    /**
     * A household, defaulted to the one most tests want: 30, metro, dependants, two incomes.
     *
     * Why a builder and not six parameters on [assessment]: detekt caps a function at six, and
     * `Household` is a constructor — which is also the honest shape, since these six fields travel
     * together everywhere else in the engine.
     */
    private fun household(
        ageYears: Int = 30,
        liabilities: Money = Money.ZERO,
        hasDependents: Boolean = true,
        isSingleIncome: Boolean = false,
        isMetro: Boolean = true,
    ) = Household(ageYears, income, liabilities, hasDependents, isSingleIncome, isMetro)

    private fun assessment(
        household: Household = household(),
        existingTermCover: Money = Money.ZERO,
        policies: List<Policy> = emptyList(),
    ): ProtectionAssessment {
        val all =
            if (existingTermCover > Money.ZERO) {
                policies + Policy("term", "term", PolicyKind.TERM, existingTermCover, Money(1_000L))
            } else {
                policies
            }
        return (engine.assess(input(household, all)) as Ok).value
    }

    private fun input(
        household: Household = household(),
        policies: List<Policy> = emptyList(),
    ) = ProtectionInput(
        household = household,
        todayIsoDate = "2026-10-03",
        nowUtcMillis = 1_790_000_000_000L,
        policies = policies,
    )
}
