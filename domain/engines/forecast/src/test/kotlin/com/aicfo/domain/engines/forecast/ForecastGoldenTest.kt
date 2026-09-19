package com.aicfo.domain.engines.forecast

import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The golden-file gate for AI-FCT (issue 9.2; §21.5).
 *
 * Why:  §21.5 asks every engine for a golden file. This one fixes a realistic scenario with every
 *       §9.2 multiplier active — weekend, pay-cycle spike and trough, zero days, a trim that bites,
 *       four commitments — and compares the engine to an **independent** oracle (see the file's
 *       header), so an arithmetic slip is not shared between the code and its expectation.
 * What: the base, the first fortnight of predicted spend day by day, the horizon's total, the
 *       expected path at 30/60/90 days, and the scheduled totals.
 * Result: a change to the model's arithmetic fails naming the figure that moved.
 * Changelog: 2026-09-19 — Created for issue 9.2.
 */
class ForecastGoldenTest {
    private val golden: Map<String, String> by lazy { load() }

    @Test
    fun `the engine reproduces the oracle on every deterministic figure`() {
        val forecast = forecast()

        assertEquals(golden.long("base"), forecast.dailyBase.minor)
        assertEquals(
            golden.getValue("predicted_first_14").split(',').map(String::toLong),
            forecast.days.take(14).map { it.predictedSpend.minor },
        )
        assertEquals(golden.long("predicted_total"), forecast.predictedSpend.minor)
        assertEquals(golden.long("expected_day_30"), forecast.days[29].expected.minor)
        assertEquals(golden.long("expected_day_60"), forecast.days[59].expected.minor)
        assertEquals(golden.long("expected_day_90"), forecast.days[89].expected.minor)
        assertEquals(golden.long("scheduled_count"), forecast.scheduled.size.toLong())
        assertEquals(golden.long("scheduled_income"), forecast.scheduledIncome.minor)
        assertEquals(golden.long("scheduled_outflow"), forecast.scheduledOutflow.minor)
    }

    private fun forecast(): CashFlowForecast {
        val input =
            ForecastInput(
                today = TODAY,
                openingBalance = Money(25_000_00L),
                commitments = commitments(),
                oneOffs =
                    listOf(
                        ScheduledItem(
                            LocalDate.parse("2026-11-10"),
                            Money(-15_000_00L),
                            "Laptop",
                            ItemSource.FUTURE_DATED,
                        ),
                    ),
                dailySpend = (1..90).map { TODAY.minusDays(it.toLong()) }.map { DailySpend(it, Money(spend(it))) },
                historyStart = TODAY.minusDays(90),
                seed = 7L,
                nowUtcMillis = 0L,
            )
        return when (val result = ForecastEngineFactory.create().forecast(input)) {
            is Ok -> result.value
            is Err -> throw AssertionError("${result.error}")
        }
    }

    /** The scenario's salary, rent and maid, exactly as the oracle schedules them. */
    private fun commitments(): List<Commitment> =
        listOf(
            Commitment(
                "Salary",
                Money(60_000_00L),
                Cadence.MONTHLY,
                LocalDate.parse("2026-10-01"),
                ItemSource.RECURRING_RULE,
            ),
            Commitment(
                "Rent",
                Money(-20_000_00L),
                Cadence.MONTHLY,
                LocalDate.parse("2026-10-05"),
                ItemSource.FIXED_STREAM,
            ),
            Commitment(
                "Maid",
                Money(-500_00L),
                Cadence.WEEKLY,
                LocalDate.parse("2026-09-22"),
                ItemSource.RECURRING_RULE,
            ),
        )

    /** The scenario's history, exactly as the oracle writes it (see the golden file's header). */
    private fun spend(day: LocalDate): Long {
        // Python's date.toordinal() counts 1 January of year 1 as day 1.
        val ordinal = ChronoUnit.DAYS.between(LocalDate.of(1, 1, 1), day) + 1
        if (ordinal % 10 == 0L) return 0L
        val weekend = day.dayOfWeek.value >= 6
        val base = if (weekend) 180_000L else 100_000L
        return when {
            day.dayOfMonth <= 5 -> base * 13 / 10
            day.dayOfMonth >= 25 -> base * 8 / 10
            else -> base
        }
    }

    private fun load(): Map<String, String> {
        val text =
            javaClass.classLoader.getResource("golden/forecast.txt")?.readText()
                ?: throw AssertionError("golden/forecast.txt is missing")
        return text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
            line.substringBefore(' ') to line.substringAfter(' ')
        }
    }

    private fun Map<String, String>.long(key: String): Long = getValue(key).toLong()

    private companion object {
        val TODAY: LocalDate = LocalDate.parse("2026-09-19")
    }
}
