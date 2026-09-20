package com.aicfo.domain.engines.insight

import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * The golden-file gate for AI-ORCH, on a fixed engine set (issue 9.5; §21.5, the acceptance
 * criterion "Golden-file test on a fixed engine set").
 *
 * Why:  the feed's value is that it is the **same** feed for the same facts — the order, the
 *       fingerprints and the attribution all have to be stable, and all three are easy to change by
 *       accident. The fixed set below is compared line for line with an **independent** oracle
 *       (`golden/insight_oracle.py`), which reads the rulebook itself and re-implements the order
 *       from the rule's words.
 * What: every insight in feed order — fingerprint, severity, figures, label, source engine, rules
 *       and confidence — and then the dashboard's three fingerprints.
 * Result: a change to the ranking, the fingerprints or the attribution fails naming the line.
 * Changelog: 2026-09-20 — Created for issue 9.5.
 */
class InsightGoldenTest {
    private val golden: List<String> by lazy {
        (
            javaClass.classLoader.getResource("golden/insight.txt")?.readText()
                ?: throw AssertionError("golden/insight.txt is missing")
        ).lines().filter { it.isNotBlank() && !it.startsWith("#") }
    }

    @Test
    fun `the fixed engine set produces the oracle's feed, in its order`() {
        val feed = feed()

        assertEquals(golden.filter { it.startsWith("insight ") }, feed.insights.map(::line))
        assertEquals(
            golden.single {
                it.startsWith("dashboard ")
            },
            "dashboard " + feed.dashboard.joinToString(",") { it.fingerprint },
        )
    }

    private fun line(insight: Insight): String =
        "insight ${insight.fingerprint} severity=${insight.severity} " +
            "amount=${insight.amount?.minor ?: "-"} secondary=${insight.secondary?.minor ?: "-"} " +
            "date=${insight.date ?: "-"} quantity=${insight.quantity ?: "-"} label=${insight.subjectLabel ?: "-"} " +
            "engine=${insight.sourceEngineId}/${insight.sourceEngineVersion} " +
            "rules=${insight.citations.joinToString(",") { "${it.ruleId} v${it.ruleVersion}" }} " +
            "confidence=${insight.confidenceBps ?: "-"}"

    /** The oracle's fixed engine set, line for line. */
    private fun feed(): InsightFeed {
        val input =
            InsightInput(
                today = LocalDate.parse("2026-09-20"),
                forecast = forecastSignal(),
                emergency =
                    EmergencyFundSignal(
                        shortfall = Money(1_20_000_00L),
                        topUpMonthly = Money(6_500_00L),
                        runwayMonthsBps = 36_000,
                        provenance = provenance("AI-EMF", "1.0", "RULE-EMF-COACH", 10_000),
                    ),
                health =
                    HealthSignal(
                        score = 742,
                        leverLabel = "SAVINGS_RATE",
                        leverGain = 42,
                        provenance = provenance("AI-FHS", "1.0", "RULE-FHS-PILLARS", 8_500),
                    ),
                budgets = budgetSignals(),
                goals = goalSignals(),
                nowUtcMillis = 1_789_800_000_000L,
            )
        return when (val result = InsightEngineFactory.create().insights(input)) {
            is Ok -> result.value
            is Err -> throw AssertionError("${result.error}")
        }
    }

    /** The forecast the set fixes: two crunch days and three seasonal months, one of them cheaper. */
    private fun forecastSignal() =
        ForecastSignal(
            crunchDays = 2,
            firstCrunchDate = LocalDate.parse("2026-10-05"),
            lowest = Money(3_200_00L),
            buffer = Money(5_000_00L),
            seasonalMonths =
                listOf(
                    SeasonalMonthSignal("2026-10", Money(1_240_00L)),
                    SeasonalMonthSignal("2026-11", Money(-600_00L)),
                    SeasonalMonthSignal("2026-12", Money(450_00L)),
                ),
            provenance = provenance("AI-FCT", "1.1", "RULE-FCT-CRUNCH", 7_000),
        )

    /** Two overspent budgets and one within its plan. */
    private fun budgetSignals() =
        listOf(
            BudgetSignal("dining", "Dining", Money(1_250_00L), "2026-09", budgetProvenance()),
            BudgetSignal("shopping", "Shopping", Money(12_500_00L), "2026-09", budgetProvenance()),
            BudgetSignal("fuel", "Fuel", Money.ZERO, "2026-09", budgetProvenance()),
        )

    /** One goal short of its plan and one that clears it. */
    private fun goalSignals() =
        listOf(
            GoalSignal("g-laptop", "Laptop", Money(2_500_00L), "2027-03-01", goalProvenance()),
            GoalSignal("g-trip", "Trip", Money.ZERO, "2028-01-01", goalProvenance()),
        )

    private fun budgetProvenance() = provenance("budget-planner", "1.0", "RULE-BUD-ALERT", 10_000)

    private fun goalProvenance() = provenance("AI-GOAL", "1.0", "RULE-HORIZON", 9_000)

    private fun provenance(
        engineId: String,
        version: String,
        rule: String,
        confidence: Int,
    ) = EngineProvenance(engineId, version, 0L, listOf(RuleCitation(rule, "1.0")), confidenceBps = confidence)
}
