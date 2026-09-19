package com.aicfo.domain.engines.forecast

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.seasonality.SeasonalityResult
import com.aicfo.domain.engines.seasonality.SeasonalityRules
import com.aicfo.domain.engines.seasonality.SpendFactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import kotlin.random.Random

/**
 * The forecast's identities, over many generated ledgers (issue 9.2; §21.5 "forecast monotonic
 * identities", P-08).
 *
 * Why:  a forecast is a sum of parts plus a spread, and each identity below is something a reader
 *       assumes without checking — that more money now means more money later by exactly that much,
 *       that a bill lowers every future day by exactly the bill, that the bands are ordered, that the
 *       components add up to the path. Each would be a quiet, plausible-looking bug if broken: the
 *       card would still show three numbers.
 * What: 200 generated scenarios per property, each from a fixed seed (the ledger) and the engine's own
 *       fixed seed (the draws), so a failure is reproducible to the case.
 * Result: the acceptance criterion "monotonic identities hold (property tests)".
 * Changelog: 2026-09-19 — Created for issue 9.2.
 *            2026-09-19 — Issue 9.3: half the cases carry random seasonal factors, and the path,
 *            component and non-negativity identities include the seasonal term.
 */
class ForecastPropertyTest {
    private val engine = ForecastEngineFactory.create()

    @Test
    fun `P10 never exceeds P50, and P50 never exceeds P90, on any day`() {
        cases { input ->
            engine.forecast(input).expectOk().days.forEach { day ->
                assertTrue("${day.date}: ${day.p10} > ${day.p50}", day.p10 <= day.p50)
                assertTrue("${day.date}: ${day.p50} > ${day.p90}", day.p50 <= day.p90)
            }
        }
    }

    @Test
    fun `more money today shifts every band on every day by exactly that much`() {
        cases { input ->
            val base = engine.forecast(input).expectOk()
            val richer =
                engine.forecast(
                    input.copy(openingBalance = input.openingBalance + Money(12_345_67L)),
                ).expectOk()

            base.days.zip(richer.days).forEach { (a, b) ->
                assertEquals(a.p10 + Money(12_345_67L), b.p10)
                assertEquals(a.p50 + Money(12_345_67L), b.p50)
                assertEquals(a.p90 + Money(12_345_67L), b.p90)
            }
        }
    }

    @Test
    fun `a scheduled bill lowers every band from its day on by exactly the bill, and nothing before`() {
        cases { input ->
            val day = input.today.plusDays(30)
            val bill = ScheduledItem(day, Money(-4_321_00L), "bill", ItemSource.FUTURE_DATED)
            val base = engine.forecast(input).expectOk()
            val billed = engine.forecast(input.copy(oneOffs = input.oneOffs + bill)).expectOk()

            base.days.zip(billed.days).forEach { (a, b) ->
                val drop = if (a.date >= day) Money(4_321_00L) else Money.ZERO
                assertEquals(a.p10 - drop, b.p10)
                assertEquals(a.p50 - drop, b.p50)
                assertEquals(a.p90 - drop, b.p90)
            }
        }
    }

    @Test
    fun `the components add up to the expected path`() {
        cases { input ->
            val forecast = engine.forecast(input).expectOk()

            assertEquals(
                input.openingBalance + forecast.scheduledIncome - forecast.scheduledOutflow - forecast.predictedSpend -
                    forecast.seasonalAdjustment,
                forecast.days.last().expected,
            )
            assertEquals(forecast.seasonalAdjustment.minor, forecast.days.sumOf { it.seasonal.minor })
            assertEquals(forecast.seasonalAdjustment.minor, forecast.seasonalMonths.sumOf { it.adjustment.minor })
            assertEquals(forecast.scheduled.sumOf { it.amount.minor }, forecast.days.sumOf { it.scheduledNet.minor })
            assertEquals(forecast.predictedSpend.minor, forecast.days.sumOf { it.predictedSpend.minor })
        }
    }

    @Test
    fun `the expected path steps by each day's scheduled net less its predicted spend`() {
        cases { input ->
            var running = input.openingBalance
            engine.forecast(input).expectOk().days.forEach { day ->
                running = running + day.scheduledNet - day.predictedSpend - day.seasonal
                assertEquals(running, day.expected)
            }
        }
    }

    @Test
    fun `predicted spend is never negative, nor is it once the seasonal term is added`() {
        cases { input ->
            val days = engine.forecast(input).expectOk().days
            assertTrue(days.all { it.predictedSpend >= Money.ZERO })
            assertTrue(days.all { it.predictedSpend + it.seasonal >= Money.ZERO })
        }
    }

    @Test
    fun `crunch days are exactly the days whose P50 is below the buffer`() {
        cases { input ->
            val forecast = engine.forecast(input).expectOk()
            assertEquals(forecast.days.filter { it.p50 < forecast.buffer }.map { it.date }, forecast.crunchDays)
        }
    }

    @Test
    fun `the same input forecasts the same way, every time (P-08)`() {
        cases { input -> assertEquals(engine.forecast(input).expectOk(), engine.forecast(input).expectOk()) }
    }

    @Test
    fun `the seed moves only the spread, never the expected path or the scheduled items`() {
        var bandsMoved = false
        cases { input ->
            val a = engine.forecast(input).expectOk()
            val b = engine.forecast(input.copy(seed = input.seed + 1)).expectOk()

            assertEquals(a.days.map { it.expected }, b.days.map { it.expected })
            assertEquals(a.scheduled, b.scheduled)
            if (a.days.map { it.p50 } != b.days.map { it.p50 }) bandsMoved = true
        }
        assertTrue("a different seed never moved a band — the draws are not being used", bandsMoved)
    }

    @Test
    fun `input order does not matter`() {
        cases { input ->
            val shuffled =
                input.copy(
                    commitments = input.commitments.reversed(),
                    oneOffs = input.oneOffs.reversed(),
                    dailySpend = input.dailySpend.reversed(),
                )
            assertEquals(engine.forecast(input).expectOk(), engine.forecast(shuffled).expectOk())
        }
    }

    @Test
    fun `a noisy history gives the bands width`() {
        var widened = false
        cases { input ->
            val forecast = engine.forecast(input).expectOk()
            if (forecast.days.last().p90 > forecast.days.last().p10) widened = true
        }
        assertNotEquals("no generated ledger produced any spread", false, widened)
    }

    // --- generation -----------------------------------------------------------------------------------

    /** Runs [check] on 200 generated inputs; a failure names the case. */
    private fun cases(check: (ForecastInput) -> Unit) {
        repeat(CASES) { case ->
            val input = generate(Random(case.toLong()))
            try {
                check(input)
            } catch (failure: AssertionError) {
                throw AssertionError("case $case: ${failure.message}", failure)
            }
        }
    }

    /** One plausible ledger: some history, a salary, rent, a few one-offs. */
    private fun generate(random: Random): ForecastInput {
        val today = LocalDate.parse("2026-09-19").plusDays(random.nextLong(0, 365))
        val historyDays = random.nextInt(0, 91)
        val spend =
            (1..historyDays)
                .filter { random.nextInt(10) < 8 }
                .map { DailySpend(today.minusDays(it.toLong()), Money(random.nextLong(0, 5_000_00L))) }
        val commitments = commitments(random, today)
        val oneOffs =
            (0 until random.nextInt(0, 4)).map {
                ScheduledItem(
                    today.plusDays(random.nextLong(1, 120)),
                    Money(random.nextLong(-20_000_00L, 20_000_00L)),
                    "one-off $it",
                    ItemSource.FUTURE_DATED,
                )
            }
        return ForecastInput(
            seasonality = if (random.nextBoolean()) seasonality(random, today) else null,
            today = today,
            openingBalance = Money(random.nextLong(-10_000_00L, 200_000_00L)),
            commitments = commitments,
            oneOffs = oneOffs,
            dailySpend = spend,
            historyStart = if (historyDays == 0) null else today.minusDays(historyDays.toLong()),
            seed = random.nextLong(),
            nowUtcMillis = 1_789_800_000_000L,
        )
    }

    /** Up to three of a salary, a rent and a maid, anchored around [today]. */
    private fun commitments(
        random: Random,
        today: LocalDate,
    ): List<Commitment> =
        listOf(
            Commitment(
                "Salary",
                Money(random.nextLong(20_000_00L, 150_000_00L)),
                Cadence.MONTHLY,
                today.plusDays(random.nextLong(1, 31)),
                ItemSource.RECURRING_RULE,
            ),
            Commitment(
                "Rent",
                Money(-random.nextLong(5_000_00L, 40_000_00L)),
                Cadence.MONTHLY,
                today.minusDays(random.nextLong(0, 60)),
                ItemSource.FIXED_STREAM,
            ),
            Commitment(
                "Maid",
                Money(-random.nextLong(100_00L, 1_000_00L)),
                Cadence.WEEKLY,
                today.plusDays(random.nextLong(1, 8)),
                ItemSource.RECURRING_RULE,
            ),
        ).take(random.nextInt(0, 4))

    /** Random factors (0 to ×2) for the horizon's four months, as AI-SEAS would hand them over. */
    private fun seasonality(
        random: Random,
        today: LocalDate,
    ) = SeasonalityResult(
        indices = emptyList(),
        factors =
            (0L..3L).map {
                SpendFactor(
                    YearMonth.from(today).plusMonths(it),
                    random.nextInt(0, 20_001),
                    emptyList(),
                    emptyList(),
                    false,
                )
            },
        monthsObserved = 0,
        provenance = EngineProvenance("AI-SEAS", "1.0", 0L, listOf(SeasonalityRules.INDEX)),
    )

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val CASES = 200
    }
}
