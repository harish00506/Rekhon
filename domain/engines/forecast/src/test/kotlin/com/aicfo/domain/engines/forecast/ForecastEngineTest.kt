package com.aicfo.domain.engines.forecast

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The forecast engine's contract, on cases small enough to check by hand (issue 9.2; §9.2).
 *
 * Why:  the golden file and the property tests cover the formula's general shape; these pin the
 *       decisions a reader has to be able to predict — how a stale due date rolls forward, what the
 *       31st of a month becomes in February, what "no history" means, where the crunch starts, and
 *       what is refused. Most cases use a history of identical days: the model then predicts it
 *       exactly, the residuals are all zero, and the three bands collapse onto the expected path, so
 *       every figure is plain arithmetic a reader can redo.
 * What: projection of commitments; the everyday-spend model's edges; crunch days and the lowest day;
 *       history and confidence; provenance; refusals.
 * Result: the behaviour the dashboard card depends on, asserted with literal numbers.
 * Changelog: 2026-09-19 — Created for issue 9.2.
 */
class ForecastEngineTest {
    private val engine = ForecastEngineFactory.create()

    // --- the zero-residual path ---------------------------------------------------------------------

    @Test
    fun `a steady ₹100 a day spends the balance down in a straight line, with the bands on it`() {
        val forecast = forecast(opening = 10_000_00L, dailySpend = steady(100_00L))

        assertEquals(90, forecast.days.size)
        assertEquals(TODAY.plusDays(1), forecast.days.first().date)
        assertEquals(Money(100_00L), forecast.dailyBase)
        forecast.days.forEachIndexed { index, day ->
            val expected = Money(10_000_00L - 100_00L * (index + 1))
            assertEquals(expected, day.expected)
            assertEquals("no surprises in the past, no spread in the future", expected, day.p10)
            assertEquals(expected, day.p50)
            assertEquals(expected, day.p90)
        }
    }

    @Test
    fun `crunch days are the P50 days under the ₹5,000 buffer, and the lowest day is the last`() {
        val forecast = forecast(opening = 10_000_00L, dailySpend = steady(100_00L))

        // 10 000 − 100·k < 5 000  ⇔  k > 50: days 51..90.
        assertEquals((51..90).map { TODAY.plusDays(it.toLong()) }, forecast.crunchDays)
        assertEquals(Money(5_000_00L), forecast.buffer)
        assertEquals(TODAY.plusDays(90), forecast.lowest!!.date)
        assertEquals(Money(1_000_00L), forecast.lowest!!.p50)
    }

    @Test
    fun `a balance exactly on the buffer is not a crunch day`() {
        val forecast = forecast(opening = 10_000_00L, dailySpend = steady(100_00L))

        assertTrue(TODAY.plusDays(50) !in forecast.crunchDays)
        assertEquals(Money(5_000_00L), forecast.days[49].p50)
    }

    // --- commitments and one-offs ------------------------------------------------------------------

    @Test
    fun `a monthly salary lands on its day every month in the horizon`() {
        val salary = commitment("Salary", 50_000_00L, Cadence.MONTHLY, LocalDate.parse("2026-10-01"))

        val forecast = forecast(opening = 0L, commitments = listOf(salary))

        assertEquals(
            listOf("2026-10-01", "2026-11-01", "2026-12-01").map(LocalDate::parse),
            forecast.scheduled.map { it.date },
        )
        assertEquals(Money(150_000_00L), forecast.scheduledIncome)
        assertEquals(Money(150_000_00L), forecast.days.last().expected)
    }

    @Test
    fun `a stale due date rolls forward by whole cadences`() {
        val rent = commitment("Rent", -25_000_00L, Cadence.MONTHLY, LocalDate.parse("2026-06-03"))

        val dates = forecast(commitments = listOf(rent)).scheduled.map { it.date }

        assertEquals(listOf("2026-10-03", "2026-11-03", "2026-12-03").map(LocalDate::parse), dates)
    }

    @Test
    fun `the 31st stays the 31st where the month has one`() {
        // Anchored on 31 Aug (stale): 30 Sep, 31 Oct, 30 Nov — each counted from the anchor, never
        // from the clamped previous date, or 30 Sep would drag every later month to the 30th.
        val bill = commitment("Card", -1_000_00L, Cadence.MONTHLY, LocalDate.parse("2026-08-31"))

        val dates = forecast(commitments = listOf(bill)).scheduled.map { it.date }

        assertEquals(listOf("2026-09-30", "2026-10-31", "2026-11-30").map(LocalDate::parse), dates)
    }

    @Test
    fun `weekly and yearly commitments repeat at their own cadence`() {
        val weekly = commitment("Maid", -500_00L, Cadence.WEEKLY, LocalDate.parse("2026-09-21"))
        val yearly = commitment("Insurance", -12_000_00L, Cadence.YEARLY, LocalDate.parse("2026-11-15"))

        val scheduled = forecast(commitments = listOf(weekly, yearly)).scheduled

        assertEquals(13, scheduled.count { it.label == "Maid" })
        assertEquals(
            listOf(LocalDate.parse("2026-11-15")),
            scheduled.filter { it.label == "Insurance" }.map { it.date },
        )
    }

    @Test
    fun `nothing past the horizon is scheduled`() {
        val far = ScheduledItem(TODAY.plusDays(91), Money(-1_00L), "Later", ItemSource.FUTURE_DATED)
        val last = ScheduledItem(TODAY.plusDays(90), Money(-1_00L), "Last day", ItemSource.FUTURE_DATED)

        val forecast = forecast(oneOffs = listOf(far, last))

        assertEquals(listOf("Last day"), forecast.scheduled.map { it.label })
        assertEquals(Money(1_00L), forecast.scheduledOutflow)
    }

    @Test
    fun `scheduled items are listed in date order and each shows on its own day`() {
        val a = ScheduledItem(TODAY.plusDays(5), Money(-3_00L), "b", ItemSource.FUTURE_DATED)
        val b = ScheduledItem(TODAY.plusDays(2), Money(7_00L), "a", ItemSource.FUTURE_DATED)

        val forecast = forecast(oneOffs = listOf(a, b))

        assertEquals(listOf(TODAY.plusDays(2), TODAY.plusDays(5)), forecast.scheduled.map { it.date })
        assertEquals(Money(7_00L), forecast.days[1].scheduledNet)
        assertEquals(Money(-3_00L), forecast.days[4].scheduledNet)
        assertEquals(Money(4_00L), forecast.days.last().expected)
    }

    // --- history ------------------------------------------------------------------------------------

    @Test
    fun `no history predicts no everyday spend and says so with zero confidence`() {
        val forecast = forecast(opening = 1_000_00L, dailySpend = emptyList(), historyStart = null)

        assertEquals(Money(0L), forecast.predictedSpend)
        assertEquals(0, forecast.historyDays)
        assertEquals(0, forecast.provenance.confidenceBps)
        assertTrue(forecast.days.all { it.p50 == Money(1_000_00L) })
    }

    @Test
    fun `days before the ledger starts are unknown, not zero`() {
        // Ten days of ₹100 and nothing before: the base is ₹100, not ₹100 × 10 / 90.
        val start = TODAY.minusDays(10)
        val tenDays = (1..10).map { DailySpend(TODAY.minusDays(it.toLong()), Money(100_00L)) }

        val forecast = forecast(dailySpend = tenDays, historyStart = start)

        assertEquals(Money(100_00L), forecast.dailyBase)
        assertEquals(10, forecast.historyDays)
        assertEquals(1_111, forecast.provenance.confidenceBps)
    }

    @Test
    fun `a day inside the history with no row is a day of no spend`() {
        // Ninety days, spend only on even days: the trimmed mean sees the zeros.
        val alternate = (1..90).filter { it % 2 == 0 }.map { DailySpend(TODAY.minusDays(it.toLong()), Money(200_00L)) }

        val forecast = forecast(dailySpend = alternate, historyStart = TODAY.minusDays(200))

        assertEquals(Money(100_00L), forecast.dailyBase)
    }

    @Test
    fun `spend outside the lookback and today's own spend are ignored`() {
        val rows =
            steady(100_00L) + DailySpend(TODAY, Money(99_999_00L)) +
                DailySpend(TODAY.minusDays(91), Money(99_999_00L))

        assertEquals(Money(100_00L), forecast(dailySpend = rows).dailyBase)
    }

    @Test
    fun `the trim drops the extremes before averaging`() {
        // 90 days of ₹100 with nine ₹10,000 spikes: a 10% trim removes exactly the nine spikes.
        val rows = steady(100_00L).mapIndexed { i, d -> if (i < 9) d.copy(amount = Money(10_000_00L)) else d }

        assertEquals(Money(100_00L), forecast(dailySpend = rows).dailyBase)
    }

    // --- provenance and refusals --------------------------------------------------------------------

    @Test
    fun `the forecast names its engine, window, confidence and rules`() {
        val forecast = forecast(dailySpend = steady(100_00L))

        assertEquals("AI-FCT", forecast.provenance.engineId)
        assertEquals("1.0", forecast.provenance.engineVersion)
        assertEquals(NOW, forecast.provenance.computedAtUtcMillis)
        assertEquals(10_000, forecast.provenance.confidenceBps)
        assertEquals(listOf(ForecastRules.METHOD, ForecastRules.CRUNCH), forecast.provenance.evidence)
        assertEquals("2026-06-21..2026-09-18 → 2026-09-20..2026-12-18", forecast.provenance.inputWindow)
    }

    @Test
    fun `a negative daily spend is refused`() {
        val negative = listOf(DailySpend(TODAY.minusDays(1), Money(-1L)))
        assertEquals(Err(AppError.Validation("forecast.spend")), engine.forecast(input(dailySpend = negative)))
    }

    @Test
    fun `a one-off dated today or earlier is refused`() {
        val past = listOf(ScheduledItem(TODAY, Money(-1L), "x", ItemSource.FUTURE_DATED))
        assertEquals(Err(AppError.Validation("forecast.item")), engine.forecast(input(oneOffs = past)))
    }

    @Test
    fun `the shortest horizon the rules allow is one day, and it is the lowest`() {
        val forecast = forecast(rules = ForecastRules(horizonDays = 1))

        assertEquals(1, forecast.days.size)
        assertEquals(forecast.days.single(), forecast.lowest)
    }

    @Test
    fun `a horizon of zero days is refused by the rules themselves`() {
        assertTrue(runCatching { ForecastRules(horizonDays = 0) }.isFailure)
    }

    // --- fixtures -----------------------------------------------------------------------------------

    @Suppress("LongParameterList") // one argument per input field a test varies
    private fun forecast(
        opening: Long = 0L,
        commitments: List<Commitment> = emptyList(),
        oneOffs: List<ScheduledItem> = emptyList(),
        dailySpend: List<DailySpend> = emptyList(),
        historyStart: LocalDate? = TODAY.minusDays(365),
        rules: ForecastRules = ForecastRules(),
    ): CashFlowForecast =
        engine.forecast(
            input(opening, commitments, oneOffs, dailySpend, historyStart, rules),
        ).expectOk()

    @Suppress("LongParameterList") // one argument per input field a test varies
    private fun input(
        opening: Long = 0L,
        commitments: List<Commitment> = emptyList(),
        oneOffs: List<ScheduledItem> = emptyList(),
        dailySpend: List<DailySpend> = emptyList(),
        historyStart: LocalDate? = TODAY.minusDays(365),
        rules: ForecastRules = ForecastRules(),
    ) = ForecastInput(TODAY, Money(opening), commitments, oneOffs, dailySpend, historyStart, SEED, NOW, rules)

    /** Result: ninety days of the same spend, ending yesterday. */
    private fun steady(paise: Long) = (1..90).map { DailySpend(TODAY.minusDays(it.toLong()), Money(paise)) }

    private fun commitment(
        label: String,
        paise: Long,
        cadence: Cadence,
        nextDue: LocalDate,
    ) = Commitment(label, Money(paise), cadence, nextDue, ItemSource.RECURRING_RULE)

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        val TODAY: LocalDate = LocalDate.parse("2026-09-19")
        const val NOW = 1_789_800_000_000L
        const val SEED = 42L
    }
}
