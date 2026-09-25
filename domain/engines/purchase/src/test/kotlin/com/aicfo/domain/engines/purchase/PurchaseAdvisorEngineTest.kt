package com.aicfo.domain.engines.purchase

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * AI-PA, one gate at a time (issue 10.1; SRS §13.1, P-02, P-07, P-08).
 *
 * Why:  this is the flagship answer to "can I afford this?", and the answer is only worth anything
 *       if the user can see why. So every test here pins a *reason*, not just a verdict: which gate
 *       fired, on which figures, citing which rule. The two ways it can be wrong are both
 *       expensive — telling someone a purchase is comfortable when it empties their emergency fund,
 *       and refusing a purchase they can plainly afford, which teaches them to stop asking.
 * What: each gate's pass, warn and fail; the verdict as the worst of them; what urgency may and may
 *       not soften; the impact strip; the alternatives; the refusals; provenance; the rules seam.
 * Result: a regression in any of them names itself.
 * Changelog: 2026-09-25 — Created for issue 10.1.
 */
class PurchaseAdvisorEngineTest {
    private val engine = PurchaseAdvisorEngineFactory.create()

    // --- the easy directions ----------------------------------------------------------------------

    @Test
    fun `a small purchase against healthy finances is comfortable`() {
        val card = advise(price = 2_000_00L)

        assertEquals(Verdict.COMFORTABLE, card.verdict)
        assertTrue("no gate should object", card.gates.none { it.outcome != GateOutcome.PASS })
    }

    @Test
    fun `every gate is reported, in the order §13 runs them`() {
        val card = advise(price = 2_000_00L)

        assertEquals(
            listOf(
                GateId.AFFORDABILITY,
                GateId.CASH_FLOW,
                GateId.OBLIGATIONS,
                GateId.GOAL_IMPACT,
                GateId.BUDGET_FIT,
                GateId.OPPORTUNITY_COST,
                GateId.TIMING,
            ),
            card.gates.map { it.gate },
        )
    }

    @Test
    fun `every gate cites the rule it applied, so the card can show its working`() {
        val card = advise(price = 2_000_00L)

        assertTrue("P-02: a gate that cites nothing cannot answer 'why'", card.gates.all { it.citations.isNotEmpty() })
    }

    // --- gate 1, affordability --------------------------------------------------------------------

    @Test
    fun `spending into the emergency fund is a warning, not a refusal`() {
        // §13.1: "fail if liquidAfter < emergencyFloor -> verdict <= STRETCH". The money is there;
        // what it would cost is the safety net, and that is the user's call to make (P-07).
        val card = advise(price = 80_000_00L)

        assertEquals(GateOutcome.WARN, gate(card, GateId.AFFORDABILITY).outcome)
        assertEquals(Verdict.STRETCH, card.verdict)
    }

    @Test
    fun `a purchase the money cannot cover at all is refused`() {
        val card = advise(price = 2_00_000_00L)

        assertEquals(GateOutcome.FAIL, gate(card, GateId.AFFORDABILITY).outcome)
        assertEquals(Verdict.NOT_NOW, card.verdict)
    }

    @Test
    fun `the affordability gate shows the figures it judged`() {
        val figures = gate(advise(price = 80_000_00L), GateId.AFFORDABILITY).figures

        assertEquals(Money(1_00_000_00L), figures.amount("liquidBefore"))
        assertEquals(Money(20_000_00L), figures.amount("liquidAfter"))
        assertEquals(Money(50_000_00L), figures.amount("emergencyFloor"))
    }

    // --- gate 2, cash flow ------------------------------------------------------------------------

    @Test
    fun `a purchase that pushes a day under the buffer is a warning`() {
        // A tighter month: the lowest forecast day is ₹12,000 over a ₹5,000 buffer, so a ₹5,000
        // purchase leaves the buffer intact and a ₹10,000 one does not.
        val tight = household.copy(forecastLowest = Money(12_000_00L))

        assertEquals(GateOutcome.PASS, gate(advise(price = 5_000_00L, signals = tight), GateId.CASH_FLOW).outcome)
        assertEquals(GateOutcome.WARN, gate(advise(price = 10_000_00L, signals = tight), GateId.CASH_FLOW).outcome)
    }

    @Test
    fun `a purchase that would overdraw the forecast is a refusal`() {
        val card = advise(price = 90_000_00L, signals = household.copy(forecastLowest = Money(12_000_00L)))

        assertEquals(GateOutcome.FAIL, gate(card, GateId.CASH_FLOW).outcome)
    }

    @Test
    fun `a day already under the buffer is the forecast's problem, not this purchase's`() {
        // Honesty about attribution: the gate reports the crunch days it *adds*. Blaming a purchase
        // for a crunch that was coming anyway would make every verdict NOT_NOW for someone already
        // in trouble, which is when the advice matters most.
        val already = household.copy(forecastCrunchDays = 3, forecastLowest = Money(2_000_00L))

        assertEquals(
            3,
            gate(advise(price = 100_00L, signals = already), GateId.CASH_FLOW).figures.count("crunchDaysBefore"),
        )
        assertEquals(GateOutcome.WARN, gate(advise(price = 100_00L, signals = already), GateId.CASH_FLOW).outcome)
    }

    // --- gate 3, obligations ----------------------------------------------------------------------

    @Test
    fun `an EMI that takes obligations past forty percent warns, and past fifty refuses`() {
        // RULE-EMI-40, on income of ₹1,00,000 with ₹30,000 of obligations already.
        val warn = advise(price = 1_20_000_00L, method = PaymentMethod.EMI, monthlyEmi = Money(12_000_00L))
        val fail = advise(price = 3_00_000_00L, method = PaymentMethod.EMI, monthlyEmi = Money(25_000_00L))

        assertEquals(GateOutcome.WARN, gate(warn, GateId.OBLIGATIONS).outcome)
        assertEquals(4_200, gate(warn, GateId.OBLIGATIONS).figures.bps("obligationsAfter"))
        assertEquals(GateOutcome.FAIL, gate(fail, GateId.OBLIGATIONS).outcome)
    }

    @Test
    fun `paying cash adds no obligation`() {
        val card = advise(price = 40_000_00L)

        assertEquals(GateOutcome.PASS, gate(card, GateId.OBLIGATIONS).outcome)
        assertEquals(3_000, gate(card, GateId.OBLIGATIONS).figures.bps("obligationsAfter"))
    }

    // --- gate 4, goal impact ----------------------------------------------------------------------

    @Test
    fun `spending the money the goals are fed with delays them, and the card says by how long`() {
        // ₹15,000 a month goes to goals; ₹30,000 spent is two months of that, so 60 days.
        val card = advise(price = 30_000_00L)

        assertEquals(60, gate(card, GateId.GOAL_IMPACT).figures.count("goalDelayDays"))
        assertEquals(60, card.impact.goalDelayDays)
        assertEquals(GateOutcome.WARN, gate(card, GateId.GOAL_IMPACT).outcome)
    }

    @Test
    fun `a purchase small beside the monthly contribution delays nothing worth reporting`() {
        val card = advise(price = 2_000_00L)

        assertEquals(4, gate(card, GateId.GOAL_IMPACT).figures.count("goalDelayDays"))
        assertEquals(GateOutcome.PASS, gate(card, GateId.GOAL_IMPACT).outcome)
    }

    @Test
    fun `with nothing being saved there is no goal to delay`() {
        val card = advise(price = 30_000_00L, signals = household.copy(goalContributionsMonthly = Money.ZERO))

        assertEquals(GateOutcome.PASS, gate(card, GateId.GOAL_IMPACT).outcome)
        assertEquals(0, card.impact.goalDelayDays)
    }

    // --- gate 5, budget fit -----------------------------------------------------------------------

    @Test
    fun `a purchase past what the category has left this month is a warning`() {
        assertEquals(GateOutcome.PASS, gate(advise(price = 3_000_00L), GateId.BUDGET_FIT).outcome)
        assertEquals(GateOutcome.WARN, gate(advise(price = 6_000_00L), GateId.BUDGET_FIT).outcome)
    }

    @Test
    fun `a category with no budget set is not judged`() {
        val card = advise(price = 6_000_00L, signals = household.copy(categoryRemaining = null))

        assertEquals(GateOutcome.PASS, gate(card, GateId.BUDGET_FIT).outcome)
        assertNull(gate(card, GateId.BUDGET_FIT).figures.amount("categoryRemaining"))
    }

    // --- gate 6, opportunity cost -----------------------------------------------------------------

    @Test
    fun `the card shows what the money would have become, and never fails for it`() {
        // ₹1,00,000 at 11% is ₹1,68,505.82 over five years and ₹2,83,942.10 over ten.
        val gate =
            gate(
                advise(price = 1_00_000_00L, signals = household.copy(liquidFunds = Money(5_00_000_00L))),
                GateId.OPPORTUNITY_COST,
            )

        assertEquals(GateOutcome.PASS, gate.outcome)
        assertEquals(Money(1_68_505_82L), gate.figures.amount("futureValue5y"))
        assertEquals(Money(2_83_942_10L), gate.figures.amount("futureValue10y"))
    }

    // --- gate 7, timing ---------------------------------------------------------------------------

    @Test
    fun `a month the season makes this cheaper is worth waiting for, and is named`() {
        val card =
            advise(
                price = 40_000_00L,
                signals = household.copy(cheaperMonth = CheaperMonth("2026-11", Money(6_000_00L))),
            )

        assertEquals(GateOutcome.WARN, gate(card, GateId.TIMING).outcome)
        assertEquals("2026-11", gate(card, GateId.TIMING).figures.text("cheaperMonth"))
        assertEquals(Money(6_000_00L), gate(card, GateId.TIMING).figures.amount("cheaperMonthSaving"))
    }

    @Test
    fun `with no cheaper month ahead the timing gate says so quietly`() {
        assertEquals(GateOutcome.PASS, gate(advise(price = 2_000_00L), GateId.TIMING).outcome)
    }

    // --- the verdict, and what urgency may do to it ------------------------------------------------

    @Test
    fun `the verdict is the worst of the gates`() {
        assertEquals(Verdict.COMFORTABLE, advise(price = 2_000_00L).verdict)
        assertEquals(Verdict.STRETCH, advise(price = 30_000_00L).verdict)
        assertEquals(Verdict.NOT_NOW, advise(price = 2_00_000_00L).verdict)
    }

    @Test
    fun `urgency softens one step`() {
        val routine = advise(price = 30_000_00L, urgency = Urgency.ROUTINE)
        val urgent = advise(price = 30_000_00L, urgency = Urgency.URGENT)

        assertEquals(Verdict.STRETCH, routine.verdict)
        assertEquals(Verdict.COMFORTABLE, urgent.verdict)
    }

    @Test
    fun `urgency never softens a hard fail`() {
        // The whole point of P-07 is that the user decides — but the app does not tell someone an
        // unaffordable purchase is merely a stretch because they said it was urgent.
        val urgent = advise(price = 2_00_000_00L, urgency = Urgency.URGENT)

        assertEquals(Verdict.NOT_NOW, urgent.verdict)
        assertTrue(urgent.hardFail)
    }

    // --- the impact strip and the alternatives (§13.2) ---------------------------------------------

    @Test
    fun `the impact strip carries the before and after of everything that moved`() {
        val impact = advise(price = 30_000_00L).impact

        assertEquals(Money(1_00_000_00L), impact.liquidBefore)
        assertEquals(Money(70_000_00L), impact.liquidAfter)
        assertEquals(24, impact.runwayMonthsBeforeTenths)
        assertEquals(17, impact.runwayMonthsAfterTenths)
        assertEquals(60, impact.goalDelayDays)
    }

    @Test
    fun `the alternatives name the price this would be comfortable at`() {
        // The smallest cap binds, and here it is the category's ₹5,000 rather than the ₹50,000 of
        // spare cash — which is the honest thing to say, and the useful one.
        assertEquals(Money(5_000_00L), advise(price = 80_000_00L).alternatives.comfortablePrice)
    }

    @Test
    fun `when a gate objects whatever the price is, no price is called comfortable`() {
        // Days already under the buffer, a cheaper month ahead, or obligations already past the
        // lender's line: none of them is about this purchase's size, so there is no size that fixes
        // them. A property test caught the advisor offering a price that still came back a stretch.
        val crunching = household.copy(forecastCrunchDays = 2)

        assertNull(advise(price = 2_000_00L, signals = crunching).alternatives.comfortablePrice)
        assertNull(
            advise(price = 2_000_00L, signals = household.copy(cheaperMonth = CheaperMonth("2026-11", Money(500_00L))))
                .alternatives.comfortablePrice,
        )
    }

    @Test
    fun `the alternatives name the date the answer would change, when saving would get there`() {
        val alternatives = advise(price = 80_000_00L).alternatives

        // ₹30,000 more than the spare cash above the emergency floor, at ₹15,000 saved a month.
        assertEquals(LocalDate.parse("2026-11-25"), alternatives.comfortableFrom)
    }

    @Test
    fun `a purchase big enough to deserve a night's thought says so`() {
        // RULE-COOL-OFF: more than 1% of annual income. ₹1,00,000 a month is ₹12,00,000 a year.
        assertTrue(advise(price = 15_000_00L).alternatives.coolOffSuggested)
        assertTrue(!advise(price = 8_000_00L).alternatives.coolOffSuggested)
    }

    // --- refusals and provenance -------------------------------------------------------------------

    @Test
    fun `impossible inputs are refused by field`() {
        assertEquals(AppError.Validation("purchase.price"), error(input(price = Money(-1L))))
        assertEquals(AppError.Validation("purchase.item"), error(input(item = " ")))
        assertEquals(
            AppError.Validation("purchase.monthlyEmi"),
            error(input(method = PaymentMethod.EMI, monthlyEmi = null)),
        )
    }

    @Test
    fun `provenance names the engine, its rules and the day it decided`() {
        val provenance = advise(price = 2_000_00L).provenance

        assertEquals("AI-PA", provenance.engineId)
        assertEquals("1.0", provenance.engineVersion)
        assertEquals(NOW_MILLIS, provenance.computedAtUtcMillis)
        assertTrue(PurchaseRules.GATES in provenance.evidence)
        assertTrue(PurchaseRules.OPPORTUNITY_COST in provenance.evidence)
    }

    @Test
    fun `the thresholds are the rulebook's, not the engine's`() {
        val strict = PurchaseRules(urgencySoftensOneStep = false)
        val card = adviseWith(30_000_00L, strict, Urgency.URGENT)

        assertEquals("urgency softens nothing when the rulebook says so", Verdict.STRETCH, card.verdict)
    }

    // --- fixtures ----------------------------------------------------------------------------------

    /**
     * A household with ₹1,00,000 liquid, a ₹50,000 emergency floor, ₹1,00,000 monthly income,
     * ₹30,000 of obligations, ₹15,000 a month going to goals, ₹5,000 left in the category, and a
     * forecast whose lowest day of the next ninety is ₹90,000, over a ₹5,000 buffer.
     *
     * The forecast low point tracks the same account as the liquid figure, so a fixture where the
     * two disagreed would make every large purchase overdraw it — the first draft did exactly that,
     * and four tests failed for a reason that had nothing to do with the gate they were about.
     * A test that needs a different household says so with `.copy(...)`.
     */
    private val household =
        PurchaseSignals(
            liquidFunds = Money(1_00_000_00L),
            emergencyFloor = Money(50_000_00L),
            monthlyEssentials = Money(42_000_00L),
            safeToSpend = Money(25_000_00L),
            forecastLowest = Money(90_000_00L),
            forecastBuffer = Money(5_000_00L),
            forecastCrunchDays = 0,
            monthlyIncome = Money(1_00_000_00L),
            monthlyObligations = Money(30_000_00L),
            goalContributionsMonthly = Money(15_000_00L),
            categoryRemaining = Money(5_000_00L),
            cheaperMonth = null,
        )

    private fun input(
        item: String = "Headphones",
        price: Money = Money(2_000_00L),
        method: PaymentMethod = PaymentMethod.CASH,
        monthlyEmi: Money? = null,
        signals: PurchaseSignals = household,
    ) = PurchaseInput(
        request = PurchaseRequest(item, price, method, Urgency.ROUTINE, monthlyEmi = monthlyEmi),
        signals = signals,
        today = TODAY,
        nowUtcMillis = NOW_MILLIS,
    )

    private fun advise(
        price: Long,
        signals: PurchaseSignals = household,
        method: PaymentMethod = PaymentMethod.CASH,
        monthlyEmi: Money? = null,
        urgency: Urgency = Urgency.ROUTINE,
    ): PurchaseVerdictCard =
        engine.advise(
            input(price = Money(price), method = method, monthlyEmi = monthlyEmi, signals = signals)
                .let { it.copy(request = it.request.copy(urgency = urgency)) },
        ).expectOk()

    /** The rules seam: the same question under a rulebook a reviewer has narrowed. */
    private fun adviseWith(
        price: Long,
        rules: PurchaseRules,
        urgency: Urgency = Urgency.ROUTINE,
    ): PurchaseVerdictCard =
        engine.advise(
            input(price = Money(price))
                .copy(rules = rules, request = input(price = Money(price)).request.copy(urgency = urgency)),
        ).expectOk()

    private fun gate(
        card: PurchaseVerdictCard,
        id: GateId,
    ): GateResult = card.gates.first { it.gate == id }

    private fun List<GateFigure>.amount(key: String): Money? = firstOrNull { it.key == key }?.amount

    private fun List<GateFigure>.count(key: String): Int? = firstOrNull { it.key == key }?.count

    private fun List<GateFigure>.bps(key: String): Int? = firstOrNull { it.key == key }?.bps

    private fun List<GateFigure>.text(key: String): String? = firstOrNull { it.key == key }?.text

    private fun error(input: PurchaseInput): AppError =
        when (val result = engine.advise(input)) {
            is Ok -> throw AssertionError("expected Err, got ${result.value.verdict}")
            is Err -> result.error
        }

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        val TODAY: LocalDate = LocalDate.parse("2026-09-25")
        const val NOW_MILLIS = 1_790_000_000_000L
    }
}
