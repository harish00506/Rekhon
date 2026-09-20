package com.aicfo.domain.engines.insight

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

/**
 * AI-ORCH's identities, over many generated engine sets (issue 9.5; §21.5, P-08).
 *
 * Why:  "feed ranking is deterministic" is the acceptance criterion, and a feed is easy to make
 *       almost-deterministic: stable until two amounts tie, or until the signals arrive in another
 *       order. These say it holds everywhere, along with the promises the dedup depends on — one
 *       insight per fingerprint, and a fingerprint that does not move when the clock does.
 * What: 300 seeded engine sets per property.
 * Result: a broken identity names its case.
 * Changelog: 2026-09-20 — Created for issue 9.5.
 */
class InsightPropertyTest {
    private val engine = InsightEngineFactory.create()

    @Test
    fun `the feed is sorted by severity, then amount descending, then fingerprint`() {
        cases { input ->
            val insights = engine.insights(input).expectOk().insights
            insights.zipWithNext().forEach { (first, second) ->
                assertTrue("$first before $second", COMPARATOR.compare(key(first), key(second)) <= 0)
            }
        }
    }

    @Test
    fun `the same input gives the same feed, and the order it arrived in does not matter`() {
        cases { input ->
            val shuffled = input.copy(budgets = input.budgets.reversed(), goals = input.goals.reversed())
            assertEquals(engine.insights(input).expectOk(), engine.insights(input).expectOk())
            assertEquals(engine.insights(input).expectOk(), engine.insights(shuffled).expectOk())
        }
    }

    @Test
    fun `every fingerprint in a feed is unique`() {
        cases { input ->
            val fingerprints = engine.insights(input).expectOk().insights.map { it.fingerprint }
            assertEquals(fingerprints.size, fingerprints.distinct().size)
        }
    }

    @Test
    fun `a later clock changes nothing but the stamp`() {
        cases { input ->
            val later = engine.insights(input.copy(nowUtcMillis = input.nowUtcMillis + 3_600_000L)).expectOk()
            assertEquals(engine.insights(input).expectOk().insights, later.insights)
        }
    }

    @Test
    fun `the dashboard is the feed's own first few, never more`() {
        cases { input ->
            val feed = engine.insights(input).expectOk()
            assertEquals(feed.insights.take(input.rules.dashboardMax), feed.dashboard)
        }
    }

    @Test
    fun `every insight repeats an engine that published it, with that engine's rules`() {
        cases { input ->
            engine.insights(input).expectOk().insights.forEach {
                assertTrue(it.sourceEngineId.isNotBlank() && it.sourceEngineVersion.isNotBlank())
                assertTrue(it.citations.isNotEmpty())
            }
        }
    }

    /** The rule's own order, rebuilt here: severity, then "has an amount", then amount, then fingerprint. */
    private fun key(insight: Insight): List<Comparable<*>> =
        listOf(
            insight.severity.ordinal,
            if (insight.amount == null) 1 else 0,
            -(insight.amount?.minor ?: 0L),
            insight.fingerprint,
        )

    private fun cases(check: (InsightInput) -> Unit) {
        repeat(CASES) { case ->
            val input = generate(Random(case.toLong()))
            try {
                check(input)
            } catch (failure: AssertionError) {
                throw AssertionError("case $case: ${failure.message}", failure)
            }
        }
    }

    private fun generate(random: Random): InsightInput {
        val today = LocalDate.parse("2026-09-20").plusDays(random.nextLong(0, 400))

        fun <T> maybe(value: () -> T): T? = if (random.nextInt(4) == 0) null else value()
        return InsightInput(
            today = today,
            forecast = maybe { forecastSignal(random, today) },
            emergency =
                maybe {
                    EmergencyFundSignal(
                        shortfall = Money(random.nextLong(0, 5_00_000_00L)),
                        topUpMonthly = Money(random.nextLong(0, 50_000_00L)),
                        runwayMonthsBps = random.nextInt(0, 200_000),
                        provenance = provenance("AI-EMF", random),
                    )
                },
            health =
                maybe {
                    HealthSignal(
                        score = random.nextInt(0, 1_001),
                        leverLabel = "SIGNAL_${random.nextInt(0, 6)}",
                        leverGain = random.nextInt(0, 200),
                        provenance = provenance("AI-FHS", random),
                    )
                },
            budgets = budgetSignals(random),
            goals = goalSignals(random),
            nowUtcMillis = random.nextLong(0, 2_000_000_000_000L),
            rules = InsightRules(dashboardMax = random.nextInt(1, 6)),
        )
    }

    /** Up to five budgets, each over by anything from nothing to ₹20,000. */
    private fun budgetSignals(random: Random) =
        (0 until random.nextInt(0, 6)).map {
            BudgetSignal(
                "cat-$it",
                "Category $it",
                Money(random.nextLong(0, 20_000_00L)),
                "2026-09",
                provenance("budget-planner", random),
            )
        }

    /** Up to four goals, each short by anything from nothing to ₹30,000 a month. */
    private fun goalSignals(random: Random) =
        (0 until random.nextInt(0, 5)).map {
            GoalSignal(
                "goal-$it",
                "Goal $it",
                Money(random.nextLong(0, 30_000_00L)),
                "2027-0${it + 1}-01",
                provenance("AI-GOAL", random),
            )
        }

    /** A forecast with between none and four crunch days, and up to three seasonal months. */
    private fun forecastSignal(
        random: Random,
        today: LocalDate,
    ): ForecastSignal {
        val crunch = random.nextInt(0, 5)
        return ForecastSignal(
            crunchDays = crunch,
            firstCrunchDate = if (crunch > 0) today.plusDays(random.nextLong(1, 90)) else null,
            lowest = if (crunch > 0) Money(random.nextLong(-50_000_00L, 50_000_00L)) else null,
            buffer = Money(5_000_00L),
            seasonalMonths =
                (0 until random.nextInt(0, 4)).map {
                    SeasonalMonthSignal(
                        "${today.year}-${(it + 1).toString().padStart(2, '0')}",
                        Money(random.nextLong(-5_000_00L, 5_000_00L)),
                    )
                },
            provenance = provenance("AI-FCT", random),
        )
    }

    private fun provenance(
        engineId: String,
        random: Random,
    ) = EngineProvenance(
        engineId,
        "1.0",
        0L,
        listOf(RuleCitation("RULE-X", "1.0")),
        confidenceBps = random.nextInt(0, 10_001),
    )

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val CASES = 300

        @Suppress("UNCHECKED_CAST")
        val COMPARATOR: Comparator<List<Comparable<*>>> =
            Comparator { left, right ->
                left.zip(right)
                    .map { (a, b) -> (a as Comparable<Any>).compareTo(b as Any) }
                    .firstOrNull { it != 0 } ?: 0
            }
    }
}
