package com.aicfo.domain.engines.tax

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
 * [TaxEngine]'s decisions, one at a time (issue 13.4; §38, AC1, AC2).
 *
 * Why:  the golden file asserts whole households and is the stronger gate, but a household asserts
 *       twenty figures at once — when it goes red it says *that* something moved, not *which* rule.
 *       These are the rules: what each regime allows, where the rebate stops, what makes a
 *       limitation appear, and the two guarantees that are structural rather than numeric — the
 *       estimate never files, and an alert never names an instrument.
 * What: the deduction rules, the rebate cliff, the break-even, the limitations, TAX-001's alerts,
 *       the refusals, the FY stamp and determinism.
 * Result: every branch in `SlabTaxEngine` has a test that fails if it is removed.
 * Changelog: 2026-10-09 — Created for issue 13.4.
 */
class TaxEngineTest {
    private val engine = TaxEngineFactory.create()

    // --- the flag ------------------------------------------------------------------------------

    /**
     * Input:  the shipped constant.
     * Output: asserts the tax surface is off in v1 (AC2/AC3). A number labelled "tax" reads as
     *         authoritative, and this one omits surcharge — the label has to be designed before the
     *         figure appears on a screen.
     */
    @Test
    fun `the tax surface is off in v1`() {
        assertFalse(TaxMode.IS_ENABLED)
    }

    // --- what each regime allows ------------------------------------------------------------------

    /**
     * Input:  a household claiming 80C, 80CCD(1B) and 80D.
     * Output: asserts the new regime allows **none** of them — only its standard deduction. This is
     *         the fact the whole comparison turns on, and the one a user is most likely to doubt.
     */
    @Test
    fun `the new regime allows no itemised deductions`() {
        val claimed =
            Deductions(
                section80C = rupees(1_50_000),
                section80CcdOneB = rupees(50_000),
                section80D = rupees(25_000),
            )
        val estimate = estimate(salary = rupees(15_00_000), deductions = claimed)

        assertEquals(rupees(75_000), estimate.new.deductionsAllowed)
        assertEquals(rupees(50_000) + rupees(2_25_000), estimate.old.deductionsAllowed)
    }

    /**
     * Input:  deductions claimed far above their caps.
     * Output: asserts each is capped at the knowledge base's figure. A user may type what they
     *         invested rather than what is deductible, and an uncapped figure would promise a
     *         refund that will not arrive.
     */
    @Test
    fun `each old-regime deduction is capped at the rulebook's figure`() {
        val overclaimed =
            Deductions(
                section80C = rupees(3_00_000),
                section80CcdOneB = rupees(2_00_000),
                section80D = rupees(1_00_000),
            )
        val estimate = estimate(salary = rupees(15_00_000), deductions = overclaimed)

        // 50,000 standard + 1,50,000 + 50,000 + 25,000 — the caps, not the claims.
        assertEquals(rupees(2_75_000), estimate.old.deductionsAllowed)
    }

    /**
     * Input:  an employer NPS contribution.
     * Output: asserts it reduces tax in **both** regimes — §38.1 says 80CCD(2) is the one deduction
     *         that survives the new regime, and missing that would overstate the new regime's tax
     *         for every salaried person whose employer offers NPS.
     */
    @Test
    fun `the employer's NPS contribution is deductible in both regimes`() {
        val without = estimate(salary = rupees(20_00_000))
        val with =
            (
                engine.estimate(
                    input(salary = rupees(20_00_000)).let {
                        it.copy(salary = it.salary.copy(employerNpsContribution = rupees(1_60_000)))
                    },
                ) as Ok
            ).value

        assertTrue("the new regime must benefit too", with.new.totalTax < without.new.totalTax)
        assertTrue("and so must the old", with.old.totalTax < without.old.totalTax)
    }

    // --- the rebate cliff ---------------------------------------------------------------------------

    /**
     * Input:  taxable income exactly at the new regime's rebate ceiling, and one rupee over.
     * Output: asserts the rebate vanishes entirely one rupee past the ceiling. §87A is a **cliff**,
     *         not a taper, and that is a real and widely-misunderstood feature of the law — so the
     *         engine must reproduce it rather than smooth it.
     */
    @Test
    fun `the rebate is a cliff, not a taper`() {
        val atCeiling = estimate(salary = rupees(12_00_000) + rupees(75_000))
        val oneRupeeOver = estimate(salary = rupees(12_00_000) + rupees(75_000) + rupees(1))

        assertEquals("12L taxable must be fully rebated", Money.ZERO, atCeiling.new.totalTax)
        assertTrue("one rupee over, the whole rebate is gone", oneRupeeOver.new.totalTax > rupees(60_000))
    }

    // --- the break-even -------------------------------------------------------------------------------

    /**
     * Input:  a household with no deductions, where the new regime wins.
     * Output: asserts a break-even is reported, and that applying it actually flips the winner.
     *         §38.1 asks for this "shown in rupees", and a figure that did not flip the result
     *         would be worse than none.
     */
    @Test
    fun `the break-even is the deduction level at which the old regime starts winning`() {
        val estimate = estimate(salary = rupees(20_00_000))
        assertEquals(Regime.NEW, estimate.winner)

        val breakEven = checkNotNull(estimate.breakEvenDeductions) { "a break-even should exist here" }
        val withThem = estimate(salary = rupees(20_00_000), deductions = Deductions(homeLoanInterest = breakEven))

        assertEquals("applying the break-even must flip the winner", Regime.OLD, withThem.winner)
    }

    /**
     * Input:  a household already winning under the old regime.
     * Output: asserts `null` — there is nothing to reach.
     */
    @Test
    fun `there is no break-even when the old regime already wins`() {
        // 7.25L of deductions at 18L income. The first version of this test used 4.25L and failed,
        // because under FY2025-26's rates that is **not enough** — the new regime still won by
        // 67,600. The engine was right and the test's premise was wrong, which is itself the most
        // useful thing this engine has to say (ADR-0072).
        val heavy =
            Deductions(
                section80C = rupees(1_50_000),
                section80CcdOneB = rupees(50_000),
                section80D = rupees(25_000),
                homeLoanInterest = rupees(5_00_000),
            )
        val estimate = estimate(salary = rupees(18_00_000), deductions = heavy)

        assertEquals(Regime.OLD, estimate.winner)
        assertNull(estimate.breakEvenDeductions)
    }

    // --- what the estimate leaves out ------------------------------------------------------------------

    /**
     * Input:  income above the surcharge threshold.
     * Output: asserts the estimate **says** it is incomplete. This is the honesty half of P-03: not
     *         inventing a number is one thing, not hiding that one is understated is the other.
     */
    @Test
    fun `an estimate above the surcharge threshold says it is incomplete`() {
        val high = estimate(salary = rupees(60_00_000))
        val ordinary = estimate(salary = rupees(15_00_000))

        assertTrue(high.limitations.any { it.kind == LimitationKind.SURCHARGE_NOT_MODELLED })
        assertFalse(ordinary.limitations.any { it.kind == LimitationKind.SURCHARGE_NOT_MODELLED })
    }

    /** Input: an HRA exemption claimed. Output: asserts the estimate says it was taken as given. */
    @Test
    fun `claiming HRA is flagged as taken on trust`() {
        val estimate = estimate(salary = rupees(15_00_000), deductions = Deductions(hraExempt = rupees(2_00_000)))

        assertTrue(estimate.limitations.any { it.kind == LimitationKind.HRA_TAKEN_AS_GIVEN })
    }

    // --- capital gains ----------------------------------------------------------------------------------

    /**
     * Input:  a long-term equity gain inside the annual exemption, and one over it.
     * Output: asserts the exemption is taken **once over the total**, not per lot — taking it per
     *         lot would multiply a ₹1.25 lakh allowance by the number of holdings sold.
     */
    @Test
    fun `the equity exemption is annual, not per lot`() {
        val threeLots =
            listOf(
                RealisedGain(AssetClass.EQUITY, rupees(1_00_000), heldMonths = 18),
                RealisedGain(AssetClass.EQUITY, rupees(1_00_000), heldMonths = 18),
                RealisedGain(AssetClass.EQUITY, rupees(1_00_000), heldMonths = 18),
            )
        val gains = estimate(salary = rupees(12_00_000), realised = threeLots).capitalGains

        assertEquals("the exemption is used once", rupees(1_25_000), gains.exemptionUsed)
        assertEquals(Money.ZERO, gains.exemptionRemaining)
        // 3L gain - 1.25L exempt = 1.75L at 12.5%.
        assertEquals(rupees(21_875), gains.longTermTax)
    }

    /**
     * Input:  an equity lot held exactly twelve months, and one held thirteen.
     * Output: asserts twelve months is still **short**-term. "More than 12 months" is the law's
     *         wording, and an off-by-one here is the difference between 20% and 12.5%.
     */
    @Test
    fun `twelve months is short-term, thirteen is long`() {
        val twelve = estimate(realised = listOf(RealisedGain(AssetClass.EQUITY, rupees(1_00_000), 12))).capitalGains
        val thirteen = estimate(realised = listOf(RealisedGain(AssetClass.EQUITY, rupees(1_00_000), 13))).capitalGains

        assertEquals("12 months must be taxed at the short-term rate", rupees(20_000), twelve.shortTermTax)
        assertEquals(Money.ZERO, thirteen.shortTermTax)
        assertEquals("13 months falls inside the exemption", rupees(1_00_000), thirteen.exemptionUsed)
    }

    /**
     * Input:  a post-April-2023 debt gain.
     * Output: asserts it is added to **salary** and taxed at the slab rate, not at a capital-gains
     *         rate. §38.2 is explicit, and treating it as a capital gain would under-tax it for
     *         anyone in the 30% band.
     */
    @Test
    fun `a post-2023 debt gain stacks on salary at the slab rate`() {
        val withGain =
            estimate(
                salary = rupees(11_00_000),
                realised = listOf(RealisedGain(AssetClass.DEBT_POST_2023, rupees(2_00_000), heldMonths = 40)),
            )

        assertEquals(rupees(2_00_000), withGain.capitalGains.slabTaxedGains)
        assertEquals(Money.ZERO, withGain.capitalGains.longTermTax)
        assertEquals("the gain must raise gross income", rupees(13_00_000), withGain.new.grossIncome)
    }

    /** Input: an SGB held to maturity. Output: asserts it is fully exempt and changes no tax. */
    @Test
    fun `an SGB held to maturity is fully exempt`() {
        val gain = RealisedGain(AssetClass.SGB_HELD_TO_MATURITY, rupees(5_00_000), heldMonths = 96)
        val withIt = estimate(salary = rupees(12_00_000), realised = listOf(gain))
        val without = estimate(salary = rupees(12_00_000))

        assertEquals(rupees(5_00_000), withIt.capitalGains.exemptGains)
        assertEquals("an exempt gain must change nothing", without.new.totalTax, withIt.new.totalTax)
    }

    // --- TAX-001's alerts ----------------------------------------------------------------------------------

    /**
     * Input:  unused exemption, close to the financial year's end and far from it.
     * Output: asserts the alert fires only inside the knowledge base's window.
     */
    @Test
    fun `the unused-exemption alert fires only near the year end`() {
        val near = estimate(today = "2027-03-01")
        val far = estimate(today = "2026-06-15")

        assertTrue(near.alerts.any { it.kind == TaxAlertKind.UNUSED_LTCG_EXEMPTION })
        assertFalse(far.alerts.any { it.kind == TaxAlertKind.UNUSED_LTCG_EXEMPTION })
    }

    /**
     * Input:  open positions sitting at a loss, near the year end.
     * Output: asserts the harvesting alert fires and carries the total — India has no wash-sale
     *         rule, which is why realising a loss before 31 March is worth saying.
     */
    @Test
    fun `losses near the year end raise a harvesting alert`() {
        val losses = listOf(OpenPosition(AssetClass.EQUITY, rupees(-50_000), heldMonths = 8, label = "a fund"))
        val estimate = estimate(today = "2027-03-01", open = losses)

        val alert = estimate.alerts.first { it.kind == TaxAlertKind.HARVESTABLE_LOSSES }
        assertEquals(rupees(-50_000), alert.amount)
    }

    /**
     * Input:  an open position at exactly the long-term boundary.
     * Output: asserts the countdown alert fires — selling now costs 20% instead of 12.5%.
     */
    @Test
    fun `a holding about to turn long-term raises the countdown alert`() {
        val almost = listOf(OpenPosition(AssetClass.EQUITY, rupees(2_00_000), heldMonths = 12))

        assertTrue(estimate(open = almost).alerts.any { it.kind == TaxAlertKind.SHORT_TERM_TURNING_LONG })
    }

    /**
     * Input:  positions carrying labels, in a year-end scenario that raises every alert.
     * Output: asserts **no alert carries a label**. TAX-001 says the alerts are generic and never
     *         say "sell fund X"; this is the test that the label the engine was handed never leaks
     *         into one.
     */
    @Test
    fun `no alert ever names an instrument`() {
        val named =
            listOf(
                OpenPosition(AssetClass.EQUITY, rupees(-50_000), heldMonths = 8, label = "Parag Parikh Flexi Cap"),
                OpenPosition(AssetClass.EQUITY, rupees(2_00_000), heldMonths = 12, label = "Nippon Small Cap"),
            )
        val estimate = estimate(today = "2027-03-01", open = named)

        assertTrue("the scenario must actually raise alerts", estimate.alerts.isNotEmpty())
        estimate.alerts.forEach { alert ->
            assertFalse("an alert cited '${alert.citation}' with a fund name", alert.citation.contains("Parag"))
            assertFalse("an alert cited a fund name", alert.citation.contains("Nippon"))
        }
    }

    // --- refusals, stamp and determinism -----------------------------------------------------------------

    /** Input: a negative salary. Output: asserts a refusal naming the field. */
    @Test
    fun `a negative salary is refused`() {
        assertEquals(Err(AppError.Validation("grossAnnual")), engine.estimate(input(salary = Money(-1L))))
    }

    /** Input: a malformed date. Output: asserts a refusal — the alerts all depend on it. */
    @Test
    fun `an unparseable date is refused`() {
        assertEquals(Err(AppError.Validation("todayIsoDate")), engine.estimate(input(today = "15-06-2026")))
    }

    /** Input: a gain held a negative number of months. Output: asserts a refusal. */
    @Test
    fun `a negative holding period is refused`() {
        val bad = listOf(RealisedGain(AssetClass.EQUITY, rupees(1_000), heldMonths = -1))

        assertEquals(Err(AppError.Validation("heldMonths")), engine.estimate(input(realised = bad)))
    }

    /**
     * Input:  any estimate.
     * Output: asserts the FY rules version is on the result and in the provenance. TAX-002 requires
     *         it printed on every result, and a figure without it cannot be reproduced after a
     *         Budget.
     */
    @Test
    fun `every estimate is stamped with the FY rules version`() {
        val estimate = estimate()

        assertEquals(TaxKnowledge.FY_RULES_VERSION, estimate.fyRulesVersion)
        assertEquals("AI-TAX", estimate.provenance.engineId)
        assertTrue(estimate.provenance.evidence.all { it.ruleVersion == TaxKnowledge.FY_RULES_VERSION })
    }

    /** Input: the same year twice. Output: asserts identical results (P-08). */
    @Test
    fun `the same year estimates identically every time`() {
        val input = input()
        assertEquals(engine.estimate(input), engine.estimate(input))
    }

    // --- helpers --------------------------------------------------------------------------------------

    private fun estimate(
        salary: Money = rupees(12_00_000),
        deductions: Deductions = Deductions(),
        realised: List<RealisedGain> = emptyList(),
        open: List<OpenPosition> = emptyList(),
        today: String = "2026-06-15",
    ): TaxEstimate =
        (
            engine.estimate(
                input(
                    salary = salary,
                    deductions = deductions,
                    realised = realised,
                    open = open,
                    today = today,
                ),
            ) as Ok
        ).value

    private fun input(
        salary: Money = rupees(12_00_000),
        deductions: Deductions = Deductions(),
        realised: List<RealisedGain> = emptyList(),
        open: List<OpenPosition> = emptyList(),
        today: String = "2026-06-15",
    ) = TaxInput(
        salary = SalaryIncome(salary),
        todayIsoDate = today,
        nowUtcMillis = 1_790_000_000_000L,
        deductions = deductions,
        realisedGains = realised,
        openPositions = open,
    )

    private fun rupees(amount: Long) = Money(amount * 100L)
}
