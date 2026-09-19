package com.aicfo.domain.engines.forecast

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import com.aicfo.domain.engines.seasonality.SeasonalityResult
import com.aicfo.domain.engines.seasonality.SeasonalityRules
import com.aicfo.domain.engines.seasonality.SpendFactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * §9.2's `seasonalAdjustment(d)` term, fed by AI-SEAS (issue 9.3; §9.3, P-02, AI-ARC-003).
 *
 * Why:  the adjustment is a separate term in §9.2's formula and must stay separate on screen
 *       ("the adjustment is shown separately with the rule that fired"), so each property of it is
 *       pinned apart from the predicted spend it scales: its size per day, its month totals and
 *       named events, its sign, and the evidence it adds.
 * What: a factor above and below ×1; months without a factor; the path; the evidence; the version.
 * Result: the forecast's seasonal line is checked on every `unitTests` run.
 * Changelog: 2026-09-19 — Created for issue 9.3.
 */
class ForecastSeasonalityTest {
    private val engine = ForecastEngineFactory.create()

    @Test
    fun `a factor adds its share of each day's predicted spend as a separate seasonal amount`() {
        // Steady ₹100 a day, so every day predicts ₹100.00; October at ×1.2 adds ₹20.00 a day.
        val forecast = forecast(factors = listOf(factor(OCT, 12_000, rising = listOf("diwali"))))

        forecast.days.forEach { day ->
            val expected = if (YearMonth.from(day.date) == OCT) Money(20_00L) else Money.ZERO
            assertEquals("${day.date}", expected, day.seasonal)
            assertEquals(Money(100_00L), day.predictedSpend)
        }
        assertEquals(Money(31 * 20_00L), forecast.seasonalAdjustment)
    }

    @Test
    fun `the expected path pays the seasonal amount too`() {
        val forecast = forecast(factors = listOf(factor(OCT, 12_000)))

        var running = Money.ZERO
        forecast.days.forEach { day ->
            running = running + day.scheduledNet - day.predictedSpend - day.seasonal
            assertEquals(running, day.expected)
        }
    }

    @Test
    fun `a factor below one is a saving, never more than the day's predicted spend`() {
        val forecast = forecast(factors = listOf(factor(NOV, 9_000, easing = listOf("monsoon")), factor(DEC, 0)))

        assertEquals(Money(-10_00L), forecast.days.first { YearMonth.from(it.date) == NOV }.seasonal)
        assertEquals(Money(-100_00L), forecast.days.first { YearMonth.from(it.date) == DEC }.seasonal)
        assertTrue(forecast.days.all { (it.predictedSpend + it.seasonal) >= Money.ZERO })
    }

    @Test
    fun `each month that moved is listed with its total and the events behind it`() {
        val forecast =
            forecast(
                factors =
                    listOf(
                        factor(SEP, 10_000),
                        factor(OCT, 12_000, rising = listOf("diwali")),
                        factor(NOV, 9_000, easing = listOf("monsoon")),
                    ),
            )

        assertEquals(listOf(OCT, NOV), forecast.seasonalMonths.map { it.factor.month })
        assertEquals(listOf(Money(31 * 20_00L), Money(30 * -10_00L)), forecast.seasonalMonths.map { it.adjustment })
        assertEquals(listOf("diwali"), forecast.seasonalMonths.first().factor.rising)
        assertEquals(listOf("monsoon"), forecast.seasonalMonths.last().factor.easing)
    }

    @Test
    fun `only the horizon's days are counted in a month's total`() {
        // The horizon starts on 20 September and ends on 18 December: December has 18 days in it.
        val forecast = forecast(factors = listOf(factor(DEC, 11_000)))

        assertEquals(Money(18 * 10_00L), forecast.seasonalMonths.single().adjustment)
    }

    @Test
    fun `with no seasonality, or only factors of one, nothing is adjusted and nothing extra is cited`() {
        listOf(forecast(factors = null), forecast(factors = listOf(factor(OCT, 10_000)))).forEach { forecast ->
            assertEquals(Money.ZERO, forecast.seasonalAdjustment)
            assertTrue(forecast.seasonalMonths.isEmpty())
            assertTrue(forecast.days.all { it.seasonal == Money.ZERO })
            assertFalse(SeasonalityRules.INDEX in forecast.provenance.evidence)
        }
    }

    @Test
    fun `an adjustment that applies cites AI-SEAS's evidence after the forecast's own rules`() {
        val forecast = forecast(factors = listOf(factor(OCT, 12_000, rising = listOf("diwali"))))

        assertEquals(
            listOf(ForecastRules.METHOD, ForecastRules.CRUNCH, SeasonalityRules.INDEX, RuleCitation("diwali", "1.1")),
            forecast.provenance.evidence,
        )
    }

    @Test
    fun `the forecast engine is 1_1 now that it carries the seasonal term`() {
        assertEquals("1.1", forecast(factors = null).provenance.engineVersion)
    }

    @Test
    fun `the bands move with the seasonal amount, exactly`() {
        val plain = forecast(factors = null, noisy = true)
        val seasonal = forecast(factors = listOf(factor(OCT, 12_000)), noisy = true)

        plain.days.zip(seasonal.days).forEach { (a, b) ->
            val paid = seasonal.days.filter { it.date <= b.date }.sumOf { it.seasonal.minor }
            assertEquals(a.p10.minor - paid, b.p10.minor)
            assertEquals(a.p50.minor - paid, b.p50.minor)
            assertEquals(a.p90.minor - paid, b.p90.minor)
        }
    }

    // --- fixtures -----------------------------------------------------------------------------------

    private fun forecast(
        factors: List<SpendFactor>?,
        noisy: Boolean = false,
    ): CashFlowForecast {
        // Steady spend has no weekend or pay-cycle shape, so every day predicts exactly ₹100.00.
        val spend =
            (1..90).map {
                val paise =
                    if (noisy && it % 3 == 0) {
                        160_00L
                    } else if (noisy) {
                        70_00L
                    } else {
                        100_00L
                    }
                DailySpend(TODAY.minusDays(it.toLong()), Money(paise))
            }
        val input =
            ForecastInput(
                today = TODAY,
                openingBalance = Money.ZERO,
                commitments = emptyList(),
                oneOffs = emptyList(),
                dailySpend = spend,
                historyStart = TODAY.minusDays(365),
                seed = 1L,
                nowUtcMillis = 0L,
                seasonality = factors?.let(::seasonality),
            )
        return when (val result: Result<CashFlowForecast, AppError> = engine.forecast(input)) {
            is Ok -> result.value
            is Err -> throw AssertionError("${result.error}")
        }
    }

    private fun seasonality(factors: List<SpendFactor>) =
        SeasonalityResult(
            indices = emptyList(),
            factors = factors,
            monthsObserved = 24,
            provenance =
                EngineProvenance(
                    engineId = "AI-SEAS",
                    engineVersion = "1.0",
                    computedAtUtcMillis = 0L,
                    evidence = listOf(SeasonalityRules.INDEX, RuleCitation("diwali", "1.1")),
                ),
        )

    private fun factor(
        month: YearMonth,
        bps: Int,
        rising: List<String> = emptyList(),
        easing: List<String> = emptyList(),
    ) = SpendFactor(month, bps, rising, easing, fromOwnHistory = false)

    private companion object {
        val TODAY: LocalDate = LocalDate.parse("2026-09-19")
        val SEP: YearMonth = YearMonth.of(2026, 9)
        val OCT: YearMonth = YearMonth.of(2026, 10)
        val NOV: YearMonth = YearMonth.of(2026, 11)
        val DEC: YearMonth = YearMonth.of(2026, 12)
    }
}
