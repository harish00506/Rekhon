package com.aicfo.domain.engines.purchase

import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * The golden-file gate for AI-PA (issue 10.1; §21.5, and the acceptance criterion "golden-file test
 * across affordable/borderline/unaffordable cases").
 *
 * Why:  a verdict is seven gates interacting, and the interaction is where a change does its damage:
 *       a threshold nudged in one gate quietly moves verdicts that gate never fired on. Five fixed
 *       households are compared line for line with an **independent** oracle
 *       (`golden/purchase_oracle.py`), which reads the rulebook and re-applies §13.1 in its own
 *       arithmetic — so a mistake has to be made twice, in two languages, to survive.
 * What: comfortable, borderline, unaffordable, an instalment past the lender's line, and the same
 *       borderline purchase called urgent.
 * Result: a change to a gate, a threshold or the verdict rule fails naming the line.
 * Changelog: 2026-09-25 — Created for issue 10.1.
 */
class PurchaseGoldenTest {
    private val golden: List<String> by lazy {
        (
            javaClass.classLoader.getResource("golden/purchase.txt")?.readText()
                ?: throw AssertionError("golden/purchase.txt is missing")
        ).lines().filter { it.isNotBlank() && !it.startsWith("#") }
    }

    @Test
    fun `every fixed household is judged exactly as the oracle judges it`() {
        val actual = SCENARIOS.flatMap(::lines)

        assertEquals(golden, actual)
    }

    /** Result: the gate, figure, cool-off and verdict lines. Input: [scenario]. Output: the lines. */
    private fun lines(scenario: Scenario): List<String> {
        val card =
            when (val result = PurchaseAdvisorEngineFactory.create().advise(scenario.input)) {
                is Ok -> result.value
                is Err -> throw AssertionError("${result.error}")
            }
        val gates = card.gates.map { "gate ${scenario.name} ${it.gate} ${it.outcome}" }
        val figures =
            listOf("figure ${scenario.name} goalDelayDays ${card.impact.goalDelayDays}") +
                listOf(5, 10).map { years ->
                    val value =
                        card.gates.first { it.gate == GateId.OPPORTUNITY_COST }
                            .figures.first { it.key == "futureValue${years}y" }.amount!!.minor
                    "figure ${scenario.name} futureValue${years}y $value"
                }
        return gates + figures +
            "cooloff ${scenario.name} ${card.alternatives.coolOffSuggested}" +
            "verdict ${scenario.name} ${card.verdict}"
    }

    /** One fixed household and what it is thinking of buying. */
    private data class Scenario(
        val name: String,
        val input: PurchaseInput,
    )

    private companion object {
        val TODAY: LocalDate = LocalDate.parse("2026-09-25")

        /** The same household the oracle builds: ₹1,00,000 liquid over a ₹50,000 floor. */
        val HOUSEHOLD =
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
            )

        fun scenario(
            name: String,
            price: Long,
            method: PaymentMethod = PaymentMethod.CASH,
            emi: Money? = null,
            urgency: Urgency = Urgency.ROUTINE,
        ) = Scenario(
            name,
            PurchaseInput(
                request = PurchaseRequest("Item", Money(price), method, urgency, monthlyEmi = emi),
                signals = HOUSEHOLD,
                today = TODAY,
                nowUtcMillis = 0L,
            ),
        )

        val SCENARIOS =
            listOf(
                scenario("comfortable", 2_000_00L),
                scenario("borderline", 30_000_00L),
                scenario("unaffordable", 2_00_000_00L),
                scenario("emi_over_the_line", 3_00_000_00L, PaymentMethod.EMI, Money(25_000_00L)),
                scenario("urgent_borderline", 30_000_00L, urgency = Urgency.URGENT),
            )
    }
}
