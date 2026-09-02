package com.aicfo.domain.engines.emergencyfund

import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The cases the golden file cannot carry (issue 7.2; §10.1, AI-EMF).
 *
 * Why:  `EmergencyFundGoldenTest` fixes the arithmetic against the **shipped** rulebook, which is
 *       exactly what it should do — and that is why it cannot exercise `RULE-RUNWAY-M`'s clamp,
 *       which the shipped params never reach, nor the input guards, which reject a value before any
 *       figure exists. Those live here, along with the provenance and the citation discipline.
 * What: the clamp in both directions, every `require` on the input and the rules, the provenance
 *       contract, and the overflow path.
 * Result: the branches a fixture file cannot describe are still covered.
 * Changelog: 2026-09-02 — Created for issue 7.2.
 */
class EmergencyFundEngineTest {
    private val engine = EmergencyFundEngineFactory.create()

    // --- the clamp ------------------------------------------------------------------------------

    /**
     * Input:  rules whose base already exceeds `clamp_months[1]`.
     * Output: asserts M is held at the ceiling and `RULE-RUNWAY-M` is cited.
     *
     * **The shipped params never reach this.** 6 base months plus at most a 3-month bump is 9,
     * inside [3, 12] — so the clamp is dead code against today's rulebook and would be untested if
     * this file did not move a threshold. That is the whole reason [EmergencyFundRules] is an
     * injected value rather than a set of constants.
     */
    @Test
    fun `the clamp holds the multiplier at its ceiling and says that it fired`() {
        val rules = EmergencyFundRules(baseMonths = 24)
        val plan = assess(rules = rules, incomes = STEADY)

        assertEquals(rules.runwayMaxMonths, plan.multiplierMonths)
        assertTrue("the clamp changed the answer but the plan does not say so", plan.multiplierWasClamped)
        assertTrue(
            "RULE-RUNWAY-M shaped this number and is not cited",
            EmergencyFundRules.RUNWAY_CLAMP in plan.provenance.evidence,
        )
    }

    /**
     * Input:  rules whose base is below `clamp_months[0]`.
     * Output: asserts M is lifted to the floor. The clamp is a range, and a test of only its top
     *         would leave a one-sided `coerceIn` looking correct.
     */
    @Test
    fun `the clamp lifts a multiplier that is below its floor`() {
        val rules = EmergencyFundRules(baseMonths = 1)
        val plan = assess(rules = rules, incomes = STEADY)

        assertEquals(rules.runwayMinMonths, plan.multiplierMonths)
        assertTrue(plan.multiplierWasClamped)
    }

    /**
     * Input:  the shipped rules.
     * Output: asserts `RULE-RUNWAY-M` is **not** cited when the clamp did not change the answer.
     *
     * Citing a rule that did not fire is the quiet kind of wrong: it survives every test that looks
     * at amounts, and it tells the user a threshold shaped their number when it did not (P-02).
     */
    @Test
    fun `a clamp that did not fire is not cited`() {
        val plan = assess(incomes = STEADY)

        assertFalse(plan.multiplierWasClamped)
        assertFalse(
            "RULE-RUNWAY-M is cited on an assessment it did not shape",
            EmergencyFundRules.RUNWAY_CLAMP in plan.provenance.evidence,
        )
        assertEquals(
            listOf(EmergencyFundRules.MULTIPLIER, EmergencyFundRules.COACH),
            plan.provenance.evidence,
        )
    }

    // --- provenance -----------------------------------------------------------------------------

    /**
     * Input:  an ordinary assessment.
     * Output: asserts the provenance AI-ARC-003 requires, and that confidence is deliberately absent.
     */
    @Test
    fun `the assessment carries the provenance every engine result carries`() {
        // Built inline rather than through the helper: the instant is the one field this test is
        // about, and threading it through a shared builder would push that builder past detekt's
        // parameter ceiling for the sake of a single call site.
        val result =
            engine.assess(input(incomes = STEADY).copy(nowUtcMillis = 1_756_000_000_000L))
        val plan = (result as Ok).value

        assertEquals("AI-EMF", plan.provenance.engineId)
        assertEquals("1.0", plan.provenance.engineVersion)
        assertEquals(1_756_000_000_000L, plan.provenance.computedAtUtcMillis)
        assertEquals(TODAY.toString(), plan.provenance.inputWindow)
        // Not an omission: this is arithmetic, not an inference. How much history it rests on is
        // said by essentialsBasis and by a null cv, which are facts rather than a score.
        assertNull(
            "a confidence score would imply an inference this engine does not make",
            plan.provenance.confidenceBps,
        )
    }

    /**
     * Input:  a plan constructed with no evidence.
     * Output: asserts it cannot be built at all (P-02, AI-ARC-006).
     */
    @Test
    fun `a plan that cannot name its rules cannot be constructed`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                assess(incomes = STEADY).copy(
                    provenance = assess(incomes = STEADY).provenance.copy(evidence = emptyList()),
                )
            }

        assertTrue(error.message.orEmpty().contains("names the rules"))
    }

    // --- the unknown branch ---------------------------------------------------------------------

    /**
     * Input:  no essentials at all.
     * Output: asserts UNKNOWN, a zero target, a null runway — and that this is an `Ok`, not an `Err`.
     *
     * A user the app has never watched spend has an unanswerable question, not a broken one. An
     * `Err` here would put an error screen in front of every fresh install (P-04).
     */
    @Test
    fun `no essentials is an answer, not an error`() {
        val plan = assess(essentials = null, basis = EssentialsBasis.NONE, incomes = STEADY)

        assertEquals(EmergencyStatus.UNKNOWN, plan.status)
        assertEquals(Money.ZERO, plan.target)
        assertEquals(Money.ZERO, plan.topUpMonthly)
        assertEquals(0, plan.fundedRatioBps)
        assertNull(plan.runwayMonthsBps)
        assertFalse("an unanswered question is not a funded fund", plan.isFunded)
    }

    /**
     * Input:  essentials of exactly zero.
     * Output: asserts UNKNOWN rather than a division by zero or an infinite runway.
     *
     * Distinct from the null case above: zero passes the input's `require`, reaches the arithmetic,
     * and is the value that would divide by zero if the guard were missing.
     */
    @Test
    fun `zero essentials is unknown, not an infinite runway`() {
        val plan = assess(essentials = Money.ZERO, incomes = STEADY, liquid = Money(50_000_00L))

        assertEquals(EmergencyStatus.UNKNOWN, plan.status)
        assertNull(plan.runwayMonthsBps)
    }

    // --- the volatility reading -------------------------------------------------------------------

    /**
     * Input:  no income history at all.
     * Output: asserts a null cv and the **base** multiplier.
     *
     * The direction matters. Treating "unmeasured" as "lumpy" would inflate a stranger's target on
     * no evidence, which is the app inventing a number about them (P-03).
     */
    @Test
    fun `an unmeasurable income adds nothing to the multiplier`() {
        val plan = assess(incomes = emptyList())

        assertNull(plan.incomeCvBps)
        assertEquals(EmergencyFundRules().baseMonths, plan.multiplierMonths)
    }

    /**
     * Input:  a variance that is not a perfect square.
     * Output: asserts the cv is the floor of the true value, never the ceiling.
     *
     * The standard deviation is an integer square root (no `Double` — MNY-002, P-08). Flooring
     * understates volatility, so a rounding change can only ever shrink a target, never inflate one.
     */
    @Test
    fun `the standard deviation floors rather than rounds`() {
        // 4,000,000 / 5,000,000 / 6,000,000: variance 666,666,666,666, whose root is 816,496.58.
        val plan = assess(incomes = listOf(Money(40_000_00L), Money(50_000_00L), Money(60_000_00L)))

        assertEquals(1_632, plan.incomeCvBps)
    }

    /**
     * Input:  a perfect square variance.
     * Output: asserts the root is exact, so the floor above is not hiding an off-by-one.
     */
    @Test
    fun `an exact square root is exact`() {
        val plan = assess(incomes = listOf(Money(45_000_00L), Money(45_000_00L), Money(55_000_00L), Money(55_000_00L)))

        assertEquals(1_000, plan.incomeCvBps)
    }

    /**
     * Input:  each of the three bands, through the rules directly.
     * Output: asserts the bump at and around both edges.
     *
     * The edges are asserted here as well as in the golden file because the golden file proves them
     * end to end and this proves them in isolation — a band that moved would otherwise be one
     * failure with two possible causes.
     */
    @Test
    fun `the volatility bands are inclusive at the top and exclusive at the bottom`() {
        val rules = EmergencyFundRules()

        assertEquals(0, rules.volatilityBumpFor(rules.cvLowBps - 1))
        assertEquals(rules.cvMidBump, rules.volatilityBumpFor(rules.cvLowBps))
        assertEquals(rules.cvMidBump, rules.volatilityBumpFor(rules.cvHighBps))
        assertEquals(rules.cvHighBump, rules.volatilityBumpFor(rules.cvHighBps + 1))
        assertEquals(0, rules.volatilityBumpFor(null))
    }

    // --- input guards -----------------------------------------------------------------------------

    /** Input: each malformed input. Output: asserts the guard fires with a message that explains. */
    @Test
    fun `the input refuses values that would make a figure meaningless`() {
        assertGuard("negative cost of living") {
            input(essentials = Money(-1L))
        }
        assertGuard("overdrawn") {
            input(liquid = Money(-1L))
        }
        assertGuard("classification bug upstream") {
            input(incomes = listOf(Money(-1L)))
        }
        // The basis and the figure are two halves of one fact; a screen must never be able to say
        // "based on your last six months" beside a target built from nothing at all.
        assertGuard("must be NONE exactly when") {
            input(essentials = null, basis = EssentialsBasis.OBSERVED_MEDIAN)
        }
        assertGuard("must be NONE exactly when") {
            input(essentials = Money(1L), basis = EssentialsBasis.NONE)
        }
    }

    /** Input: each unsatisfiable rule set. Output: asserts construction fails rather than the maths. */
    @Test
    fun `the rules refuse a set of thresholds that cannot be satisfied`() {
        assertGuard("must be positive") { EmergencyFundRules(baseMonths = 0) }
        assertGuard("ordered basis points") { EmergencyFundRules(cvLowBps = 4_000, cvHighBps = 3_000) }
        assertGuard("bumps are ordered") { EmergencyFundRules(cvMidBump = 4, cvHighBump = 3) }
        assertGuard("at least one month") { EmergencyFundRules(minMonthsObserved = 0) }
        assertGuard("can never satisfy it") { EmergencyFundRules(essentialsLookbackMonths = 2, minMonthsObserved = 3) }
        assertGuard("positive, ordered range") { EmergencyFundRules(runwayMinMonths = 13, runwayMaxMonths = 12) }
        // The *other* half of each range check. `x in 0..y` is two comparisons, and asserting only
        // the upper one leaves a guard that would accept a negative band edge — which would make
        // every cv fall in the middle band and quietly add a month to everyone's target.
        assertGuard("ordered basis points") { EmergencyFundRules(cvLowBps = -1) }
        assertGuard("bumps are ordered") { EmergencyFundRules(cvMidBump = -1) }
        assertGuard("positive, ordered range") { EmergencyFundRules(runwayMinMonths = 0) }
        assertGuard("months floor") { EmergencyFundRules(urgentBelowMonths = -1) }
        assertGuard("margin above the target") { EmergencyFundRules(surplusAboveTargetMonths = -1) }
    }

    // --- overflow ---------------------------------------------------------------------------------

    /**
     * Input:  essentials whose target will not fit in a `Long`.
     * Output: asserts an `Err`, not a wrapped negative target.
     *
     * MNY-001: `Money` raises rather than wrapping, and an engine that let that escape would crash a
     * screen instead of degrading it (§21.6). The figure is absurd; the failure mode is not.
     */
    @Test
    fun `an overflowing target degrades to an error rather than wrapping`() {
        val result =
            engine.assess(
                input(essentials = Money(Long.MAX_VALUE / 2), basis = EssentialsBasis.OBSERVED_MEDIAN),
            )

        assertTrue("expected an Err for a target that cannot fit in a Long, got $result", result is Err)
    }

    // --- helpers ----------------------------------------------------------------------------------

    /** Result: an input with the shipped defaults, overridden per test. Output: [EmergencyFundInput]. */
    private fun input(
        essentials: Money? = Money(40_000_00L),
        basis: EssentialsBasis = if (essentials == null) EssentialsBasis.NONE else EssentialsBasis.OBSERVED_MEDIAN,
        incomes: List<Money> = emptyList(),
        liquid: Money = Money.ZERO,
        rules: EmergencyFundRules = EmergencyFundRules(),
    ) = EmergencyFundInput(
        monthlyEssentials = essentials,
        essentialsBasis = basis,
        monthlyIncomes = incomes,
        liquidFunds = liquid,
        today = TODAY,
        rules = rules,
    )

    /** Result: the assessment, unwrapped. Output: [EmergencyFundPlan]; fails loudly on an `Err`. */
    private fun assess(
        essentials: Money? = Money(40_000_00L),
        basis: EssentialsBasis = if (essentials == null) EssentialsBasis.NONE else EssentialsBasis.OBSERVED_MEDIAN,
        incomes: List<Money> = emptyList(),
        liquid: Money = Money.ZERO,
        rules: EmergencyFundRules = EmergencyFundRules(),
    ): EmergencyFundPlan {
        val result = engine.assess(input(essentials, basis, incomes, liquid, rules))
        assertNotNull("expected an Ok, got $result", result as? Ok)
        return (result as Ok).value
    }

    /**
     * Asserts a `require` fires and that its message says **why**.
     * Why:    asserting only that something threw would pass on the wrong guard firing, which is how
     *         a validation test quietly stops testing the thing it names.
     * Input:  [fragment] — text the message must contain; [block]. Output: none.
     */
    private fun assertGuard(
        fragment: String,
        block: () -> Any?,
    ) {
        val error = assertThrows(IllegalArgumentException::class.java) { block() }
        assertTrue(
            "guard fired, but with the wrong message: '${error.message}' does not mention '$fragment'",
            error.message.orEmpty().contains(fragment),
        )
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.parse("2026-09-02")

        /** Three identical months: a cv of exactly zero, so no bump and no clamp. */
        val STEADY = listOf(Money(50_000_00L), Money(50_000_00L), Money(50_000_00L))
    }
}
