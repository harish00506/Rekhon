package com.aicfo.domain.engines.simulator

import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The golden-file gate for AI-SIM (issue 10.3; §21.5, and the acceptance criterion "golden-file
 * tests vs known scenarios").
 *
 * Why:  every figure here is the result of a loop over hundreds of months, so an error of one
 *       rounding step compounds into a number nobody would notice by eye. Six fixed scenarios are
 *       compared line for line with an **independent** oracle (`golden/simulator_oracle.py`), which
 *       re-implements §36 and §40.2 in Python's own decimal arithmetic.
 * What: two prepayment questions where the answer goes opposite ways, and two debt piles — one
 *       where the strategies disagree and one where the dearest debt is also the smallest, so they
 *       cannot.
 * Result: a change to the rounding, the rollover or the ordering fails naming the scenario.
 * Changelog: 2026-09-26 — Created for issue 10.3.
 */
class SimulatorGoldenTest {
    private val golden: List<String> by lazy {
        (
            javaClass.classLoader.getResource("golden/simulator.txt")?.readText()
                ?: throw AssertionError("golden/simulator.txt is missing")
        ).lines().filter { it.isNotBlank() && !it.startsWith("#") }
    }

    @Test
    fun `every fixed scenario comes out exactly as the oracle says`() {
        val actual = PREPAY.map(::prepayLine) + PAYOFF.flatMap { payoffLines(it) }

        assertEquals(golden, actual)
    }

    private fun prepayLine(scenario: PrepayScenario): String {
        val comparison =
            when (val result = SimulatorFactory.prepayVsInvest().simulate(scenario.input)) {
                is Ok -> result.value
                is Err -> throw AssertionError("${result.error}")
            }
        return "prepay ${scenario.name} " +
            "baseline=${comparison.baseline.months}m/${comparison.baseline.totalInterest.minor} " +
            "after=${comparison.prepay.months}m/${comparison.prepay.totalInterest.minor} " +
            "saved=${comparison.prepay.interestSaved.minor} " +
            "invest=${comparison.invest.afterTaxGain.minor} ${comparison.verdict}"
    }

    private fun payoffLines(scenario: PayoffScenario): List<String> {
        val comparison =
            when (val result = SimulatorFactory.debtPayoff().simulate(scenario.input)) {
                is Ok -> result.value
                is Err -> throw AssertionError("${result.error}")
            }
        return listOf("avalanche" to comparison.avalanche, "snowball" to comparison.snowball).map { (name, plan) ->
            "payoff ${scenario.name} $name ${plan.months}m interest=${plan.totalInterest.minor} " +
                "order=${plan.order.joinToString("|")}"
        }
    }

    private data class PrepayScenario(
        val name: String,
        val input: PrepayInput,
    )

    private data class PayoffScenario(
        val name: String,
        val input: PayoffInput,
    )

    private companion object {
        val PREPAY =
            listOf(
                PrepayScenario(
                    "home_loan_9pc",
                    PrepayInput(
                        outstanding = Money(20_00_000_00L),
                        annualRateBps = 900,
                        remainingMonths = 180,
                        emi = Money(20_285_00L),
                        lumpSum = Money(2_00_000_00L),
                        expectedReturnBps = 1_200,
                        taxOnReturnsBps = 3_000,
                    ),
                ),
                PrepayScenario(
                    "car_loan_14pc",
                    PrepayInput(
                        outstanding = Money(4_00_000_00L),
                        annualRateBps = 1_400,
                        remainingMonths = 36,
                        emi = Money(13_000_00L),
                        lumpSum = Money(1_00_000_00L),
                        expectedReturnBps = 1_000,
                        taxOnReturnsBps = 2_000,
                    ),
                ),
            )

        val PAYOFF =
            listOf(
                PayoffScenario(
                    "three_debts",
                    PayoffInput(
                        debts =
                            listOf(
                                Debt("Card", Money(80_000_00L), 4_200, Money(4_000_00L)),
                                Debt("Personal loan", Money(2_00_000_00L), 1_600, Money(7_000_00L)),
                                Debt("Phone EMI", Money(18_000_00L), 1_400, Money(1_600_00L)),
                            ),
                        extraMonthly = Money(5_000_00L),
                    ),
                ),
                // The dearest card is also the smallest, so both strategies target the same debt
                // first and must agree to the paise — the degenerate case worth pinning.
                PayoffScenario(
                    "two_cards",
                    PayoffInput(
                        debts =
                            listOf(
                                Debt("Card A", Money(60_000_00L), 3_600, Money(3_000_00L)),
                                Debt("Card B", Money(25_000_00L), 4_200, Money(1_500_00L)),
                            ),
                        extraMonthly = Money(2_000_00L),
                    ),
                ),
            )
    }
}
