package com.aicfo.domain.engines.insight

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * AI-ORCH's behaviour, one decision per test (issue 9.5; §7.2, FR-AI-002, P-03).
 *
 * Why:  the orchestrator decides what the app says unprompted. Each decision — whether a signal is
 *       worth a card at all, which figure the card leads with, whose engine it belongs to, where it
 *       sits in the feed — is a place a wrong answer looks reasonable on screen: a card for a
 *       funded emergency fund, a Diwali note above a crunch day, a card that changes identity
 *       between two recomputations and so can never be dismissed.
 * What: each signal's card and its silence; the ranking and its tie-breaks; the dashboard's three;
 *       the fingerprint; the refusals; provenance; the injected rules.
 * Result: a regression in any of them names itself.
 * Changelog: 2026-09-20 — Created for issue 9.5.
 */
class InsightEngineTest {
    private val engine = InsightEngineFactory.create()

    @Test
    fun `an app with nothing to say says nothing`() {
        val feed = feed(InsightInput(today = TODAY, nowUtcMillis = NOW))

        assertTrue(feed.insights.isEmpty())
        assertTrue(feed.dashboard.isEmpty())
        assertEquals("AI-ORCH", feed.provenance.engineId)
    }

    @Test
    fun `a crunch day is the one critical card, and carries the forecast's own figures and rules`() {
        val feed = feed(InsightInput(today = TODAY, forecast = forecast(crunchDays = 3), nowUtcMillis = NOW))

        val insight = feed.insights.single()
        assertEquals(InsightType.CRUNCH_DAY, insight.type)
        assertEquals(Severity.CRITICAL, insight.severity)
        assertEquals(Money(1_200_00L), insight.amount)
        assertEquals(Money(5_000_00L), insight.secondary)
        assertEquals(LocalDate.parse("2026-10-11"), insight.date)
        assertEquals(3, insight.quantity)
        assertEquals("AI-FCT", insight.sourceEngineId)
        assertEquals("1.1", insight.sourceEngineVersion)
        assertEquals(listOf(RuleCitation("RULE-FCT-CRUNCH", "1.0")), insight.citations)
        assertEquals(7_000, insight.confidenceBps)
    }

    @Test
    fun `a forecast that never dips raises nothing`() {
        assertTrue(
            feed(
                InsightInput(today = TODAY, forecast = forecast(crunchDays = 0), nowUtcMillis = NOW),
            ).insights.isEmpty(),
        )
    }

    @Test
    fun `each overspent budget is its own card, and a budget within its plan is not`() {
        val feed =
            feed(
                InsightInput(
                    today = TODAY,
                    budgets =
                        listOf(
                            budget("dining", "Dining", 1_250_00L),
                            budget("fuel", "Fuel", 0L),
                            budget("shopping", "Shopping", 4_000_00L),
                        ),
                    nowUtcMillis = NOW,
                ),
            )

        assertEquals(listOf("shopping", "dining"), feed.insights.map { it.subject })
        assertEquals(listOf("Shopping", "Dining"), feed.insights.map { it.subjectLabel })
        assertEquals(Money(4_000_00L), feed.insights.first().amount)
        assertEquals("2026-09", feed.insights.first().period)
    }

    @Test
    fun `a short emergency fund is a card with its top-up, and a funded one is not`() {
        val short = feed(InsightInput(today = TODAY, emergency = emergency(shortfall = 80_000_00L), nowUtcMillis = NOW))

        assertEquals(InsightType.EMERGENCY_FUND_SHORT, short.insights.single().type)
        assertEquals(Money(80_000_00L), short.insights.single().amount)
        assertEquals(Money(6_500_00L), short.insights.single().secondary)
        assertTrue(
            feed(
                InsightInput(today = TODAY, emergency = emergency(shortfall = 0L), nowUtcMillis = NOW),
            ).insights.isEmpty(),
        )
    }

    @Test
    fun `a goal that needs more each month is a card, and one that does not is silent`() {
        val feed =
            feed(
                InsightInput(
                    today = TODAY,
                    goals = listOf(goal("g1", "Emergency laptop", 2_500_00L), goal("g2", "Trip", 0L)),
                    nowUtcMillis = NOW,
                ),
            )

        val insight = feed.insights.single()
        assertEquals(InsightType.GOAL_BEHIND, insight.type)
        assertEquals("g1", insight.subject)
        assertEquals(Money(2_500_00L), insight.amount)
        assertEquals("2027-06-01", insight.period)
    }

    @Test
    fun `a month the season makes dearer is a card, and a cheaper one is not`() {
        val feed =
            feed(
                InsightInput(
                    today = TODAY,
                    forecast =
                        forecast(
                            crunchDays = 0,
                            seasonal = listOf("2026-10" to 1_240_00L, "2026-11" to -600_00L),
                        ),
                    nowUtcMillis = NOW,
                ),
            )

        val insight = feed.insights.single()
        assertEquals(InsightType.SEASONAL_MONTH, insight.type)
        assertEquals(Severity.INFO, insight.severity)
        assertEquals("2026-10", insight.period)
        assertEquals("2026-10", insight.subject)
        assertEquals(Money(1_240_00L), insight.amount)
    }

    @Test
    fun `the health score's biggest lever is a card, and a score with nothing to gain is not`() {
        val feed =
            feed(InsightInput(today = TODAY, health = health(lever = "SAVINGS_RATE", gain = 42), nowUtcMillis = NOW))

        val insight = feed.insights.single()
        assertEquals(InsightType.HEALTH_LEVER, insight.type)
        assertEquals("SAVINGS_RATE", insight.subject)
        assertEquals(42, insight.quantity)
        assertNull(insight.amount)
        assertTrue(
            feed(
                InsightInput(today = TODAY, health = health(lever = null, gain = null), nowUtcMillis = NOW),
            ).insights.isEmpty(),
        )
    }

    @Test
    fun `the feed is ordered by severity, then by the amount at stake, then by fingerprint`() {
        // Warnings by size: the fund's ₹80,000 shortfall, the goal's ₹2,500 a month, the budget's
        // ₹1,250 over. Then the info pair, the seasonal month first because the lever has no amount.
        val feed = feed(everything())

        assertEquals(
            listOf(
                InsightType.CRUNCH_DAY,
                InsightType.EMERGENCY_FUND_SHORT,
                InsightType.GOAL_BEHIND,
                InsightType.BUDGET_OVERSPENT,
                InsightType.SEASONAL_MONTH,
                InsightType.HEALTH_LEVER,
            ),
            feed.insights.map { it.type },
        )
    }

    @Test
    fun `two insights of the same severity and amount are ordered by fingerprint`() {
        val feed =
            feed(
                InsightInput(
                    today = TODAY,
                    budgets = listOf(budget("zzz", "Zzz", 1_000_00L), budget("aaa", "Aaa", 1_000_00L)),
                    nowUtcMillis = NOW,
                ),
            )

        assertEquals(listOf("aaa", "zzz"), feed.insights.map { it.subject })
    }

    @Test
    fun `the dashboard takes the rulebook's first three`() {
        val feed = feed(everything())

        assertEquals(feed.insights.take(3), feed.dashboard)
        assertEquals(1, feed(everything().copy(rules = InsightRules(dashboardMax = 1))).dashboard.size)
    }

    @Test
    fun `a fingerprint is its type, its subject and its period, and is stable across recomputation`() {
        val first = feed(everything()).insights
        val again = feed(everything().copy(nowUtcMillis = NOW + 86_400_000L)).insights

        assertEquals(first.map { it.fingerprint }, again.map { it.fingerprint })
        assertEquals(
            "BUDGET_OVERSPENT|dining|2026-09",
            first.single { it.type == InsightType.BUDGET_OVERSPENT }.fingerprint,
        )
        assertEquals("CRUNCH_DAY|-|2026-09-20", first.single { it.type == InsightType.CRUNCH_DAY }.fingerprint)
    }

    @Test
    fun `the order the signals arrived in does not matter`() {
        val input = everything()
        val shuffled = input.copy(budgets = input.budgets.reversed(), goals = input.goals.reversed())

        assertEquals(feed(input), feed(shuffled))
    }

    @Test
    fun `impossible signals are refused by field`() {
        assertEquals(
            AppError.Validation("insight.forecast"),
            error(InsightInput(today = TODAY, forecast = forecast(crunchDays = -1), nowUtcMillis = NOW)),
        )
        assertEquals(
            AppError.Validation("insight.budget"),
            error(InsightInput(today = TODAY, budgets = listOf(budget("d", "D", -1L)), nowUtcMillis = NOW)),
        )
        assertEquals(
            AppError.Validation("insight.emergency"),
            error(InsightInput(today = TODAY, emergency = emergency(shortfall = -1L), nowUtcMillis = NOW)),
        )
        assertEquals(
            AppError.Validation("insight.goal"),
            error(InsightInput(today = TODAY, goals = listOf(goal("g", "G", -1L)), nowUtcMillis = NOW)),
        )
        assertEquals(
            AppError.Validation("insight.health"),
            error(InsightInput(today = TODAY, health = health(lever = "X", gain = -1), nowUtcMillis = NOW)),
        )
    }

    @Test
    fun `provenance names the orchestrator, its two rules and the day it ran`() {
        val provenance = feed(everything()).provenance

        assertEquals("AI-ORCH", provenance.engineId)
        assertEquals("1.0", provenance.engineVersion)
        assertEquals(NOW, provenance.computedAtUtcMillis)
        assertEquals(listOf(InsightRules.RANK, InsightRules.DEDUP), provenance.evidence)
        assertEquals("2026-09-20", provenance.inputWindow)
        // The orchestrator computes no figure of its own, so it claims no confidence in one; each
        // insight carries the confidence of the engine whose figure it repeats.
        assertNull(provenance.confidenceBps)
    }

    // --- fixtures -----------------------------------------------------------------------------------

    private fun everything() =
        InsightInput(
            today = TODAY,
            forecast = forecast(crunchDays = 3, seasonal = listOf("2026-10" to 1_240_00L)),
            emergency = emergency(shortfall = 80_000_00L),
            health = health(lever = "SAVINGS_RATE", gain = 42),
            budgets = listOf(budget("dining", "Dining", 1_250_00L)),
            goals = listOf(goal("g1", "Trip", 2_500_00L)),
            nowUtcMillis = NOW,
        )

    private fun forecast(
        crunchDays: Int,
        seasonal: List<Pair<String, Long>> = emptyList(),
    ) = ForecastSignal(
        crunchDays = crunchDays,
        firstCrunchDate = if (crunchDays > 0) LocalDate.parse("2026-10-11") else null,
        lowest = if (crunchDays > 0) Money(1_200_00L) else null,
        buffer = Money(5_000_00L),
        seasonalMonths = seasonal.map { (month, amount) -> SeasonalMonthSignal(month, Money(amount)) },
        provenance =
            EngineProvenance(
                engineId = "AI-FCT",
                engineVersion = "1.1",
                computedAtUtcMillis = NOW,
                evidence = listOf(RuleCitation("RULE-FCT-CRUNCH", "1.0")),
                confidenceBps = 7_000,
            ),
    )

    private fun emergency(shortfall: Long) =
        EmergencyFundSignal(
            shortfall = Money(shortfall),
            topUpMonthly = Money(6_500_00L),
            runwayMonthsBps = 36_000,
            provenance = provenanceOf("AI-EMF", "1.0", "RULE-EMF-COACH"),
        )

    private fun health(
        lever: String?,
        gain: Int?,
    ) = HealthSignal(
        score = 742,
        leverLabel = lever,
        leverGain = gain,
        provenance = provenanceOf("AI-FHS", "1.0", "RULE-FHS-PILLARS"),
    )

    private fun budget(
        id: String,
        name: String,
        overspent: Long,
    ) = BudgetSignal(id, name, Money(overspent), "2026-09", provenanceOf("budget-planner", "1.0", "RULE-BUD-ALERT"))

    private fun goal(
        id: String,
        name: String,
        shortfall: Long,
    ) = GoalSignal(id, name, Money(shortfall), "2027-06-01", provenanceOf("AI-GOAL", "1.0", "RULE-HORIZON"))

    private fun provenanceOf(
        engineId: String,
        version: String,
        rule: String,
    ) = EngineProvenance(engineId, version, NOW, listOf(RuleCitation(rule, "1.0")), confidenceBps = 9_000)

    private fun feed(input: InsightInput): InsightFeed = engine.insights(input).expectOk()

    private fun error(input: InsightInput): AppError =
        when (val result = engine.insights(input)) {
            is Ok -> throw AssertionError("expected Err, got ${result.value}")
            is Err -> result.error
        }

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        val TODAY: LocalDate = LocalDate.parse("2026-09-20")
        const val NOW = 1_789_800_000_000L
    }
}
