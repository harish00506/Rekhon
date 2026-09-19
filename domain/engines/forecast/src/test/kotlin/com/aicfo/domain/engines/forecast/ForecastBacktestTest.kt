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
 * The forecast backtest gate (issue 9.2; §21.5 "forecast backtests … regression thresholds block
 * merges", AI-FCT-004).
 *
 * Why:  a forecast is only worth showing if it is right often enough, and "right" has to be a number
 *       the build enforces, or every later tweak to the model can quietly make it worse. Two
 *       questions, each with its own threshold, **both fixed before the engine was run against the
 *       data** and not to be relaxed to make a change pass:
 *       1. *Is the everyday-spend estimate close?* — the error of the predicted 90-day total against
 *          what was actually spent, as a share of the actual: **median across ledgers ≤ 15%**.
 *       2. *Are the bands honest?* — P10–P90 is an 80% interval by construction, so the real balance
 *          should sit inside it on most days: **mean coverage across ledgers ≥ 70%** (ten points of
 *          slack for the effects the model does not capture: drift and regime changes).
 * What: runs the shipped engine on the 20 frozen ledgers in `backtest/ledgers.txt`, with the real
 *       rules and seed, and scores both.
 * Result: a model change that makes the forecast worse on this set fails the build.
 * Changelog: 2026-09-19 — Created for issue 9.2.
 *
 * The ledgers are **synthetic** (see the file's header): no real user data exists off-device (P-01).
 * On-device accuracy tracking against the user's own actuals — AI-FCT-004's stored snapshots and
 * MAPE — is recorded as deferred in ADR-0043.
 */
class ForecastBacktestTest {
    private val engine = ForecastEngineFactory.create()
    private val ledgers: List<Ledger> by lazy { load() }

    @Test
    fun `the frozen set is loaded whole`() {
        assertEquals(LEDGERS, ledgers.size)
        ledgers.forEach { ledger ->
            assertEquals("${ledger.id}: history", HISTORY_DAYS, ledger.history.size)
            assertEquals("${ledger.id}: actuals", HORIZON_DAYS, ledger.actual.size)
        }
    }

    @Test
    fun `the predicted 90-day everyday spend is within 15 percent of actual, at the median`() {
        val errors = ledgers.map { ledger -> spendErrorBps(ledger) }.sorted()
        val median = (errors[errors.size / 2 - 1] + errors[errors.size / 2]) / 2

        assertTrue(
            "median 90-day spend error $median bps exceeds $MAX_MEDIAN_SPEND_ERROR_BPS; per ledger: $errors",
            median <= MAX_MEDIAN_SPEND_ERROR_BPS,
        )
    }

    @Test
    fun `the real balance sits inside P10-P90 on at least 70 percent of days, on average`() {
        val coverage = ledgers.map { ledger -> coverageBps(ledger) }
        val mean = coverage.sum() / coverage.size

        assertTrue(
            "mean band coverage $mean bps is below $MIN_MEAN_COVERAGE_BPS; per ledger: $coverage",
            mean >= MIN_MEAN_COVERAGE_BPS,
        )
    }

    // --- scoring ------------------------------------------------------------------------------------

    /** Result: |predicted − actual| ÷ actual for the whole horizon, in bps. */
    private fun spendErrorBps(ledger: Ledger): Long {
        val forecast = forecast(ledger)
        val actual = ledger.actual.sumOf { it.amount.minor }
        return kotlin.math.abs(forecast.predictedSpend.minor - actual) * FULL_BPS / actual
    }

    /** Result: the share of horizon days whose real balance lies in [P10, P90], in bps. */
    private fun coverageBps(ledger: Ledger): Long {
        val forecast = forecast(ledger)
        val spentByDay = ledger.actual.associate { it.date to it.amount }
        var real = ledger.opening
        val inside =
            forecast.days.count { day ->
                real = real + day.scheduledNet - (spentByDay[day.date] ?: Money.ZERO)
                real >= day.p10 && real <= day.p90
            }
        return inside.toLong() * FULL_BPS / forecast.days.size
    }

    private fun forecast(ledger: Ledger): CashFlowForecast =
        engine.forecast(
            ForecastInput(
                today = ledger.today,
                openingBalance = ledger.opening,
                commitments = ledger.commitments,
                oneOffs = emptyList(),
                dailySpend = ledger.history,
                historyStart = ledger.today.minusDays(HISTORY_DAYS.toLong()),
                seed = SEED,
                nowUtcMillis = 0L,
            ),
        ).expectOk()

    // --- parsing ------------------------------------------------------------------------------------

    private fun load(): List<Ledger> {
        val text =
            javaClass.classLoader.getResource("backtest/ledgers.txt")?.readText()
                ?: throw AssertionError("backtest/ledgers.txt is missing")
        return text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
            .fold(mutableListOf<MutableList<String>>()) { blocks, line ->
                if (line.startsWith("ledger ")) blocks.add(mutableListOf(line)) else blocks.last().add(line)
                blocks
            }
            .map(::parse)
    }

    private fun parse(block: List<String>): Ledger {
        fun field(name: String) = block.first { it.startsWith("$name ") }.removePrefix("$name ")

        fun days(name: String) =
            field(name).split(',').map { cell ->
                val (date, paise) = cell.split(':')
                DailySpend(LocalDate.parse(date), Money(paise.toLong()))
            }
        return Ledger(
            id = block.first().removePrefix("ledger "),
            today = LocalDate.parse(field("today")),
            opening = Money(field("opening").toLong()),
            commitments =
                block.filter { it.startsWith("commitment ") }.map { line ->
                    val cells = line.removePrefix("commitment ").split('|')
                    Commitment(
                        cells[0],
                        Money(cells[1].toLong()),
                        Cadence.valueOf(cells[2]),
                        LocalDate.parse(cells[3]),
                        ItemSource.RECURRING_RULE,
                    )
                },
            history = days("history"),
            actual = days("actual"),
        )
    }

    private data class Ledger(
        val id: String,
        val today: LocalDate,
        val opening: Money,
        val commitments: List<Commitment>,
        val history: List<DailySpend>,
        val actual: List<DailySpend>,
    )

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val LEDGERS = 20
        const val HISTORY_DAYS = 90
        const val HORIZON_DAYS = 90
        const val SEED = 20_260_919L
        const val FULL_BPS = 10_000L

        /** Frozen before the first run (§21.5). Not to be relaxed to make a change pass. */
        const val MAX_MEDIAN_SPEND_ERROR_BPS = 1_500L

        /** Frozen before the first run (§21.5). Not to be relaxed to make a change pass. */
        const val MIN_MEAN_COVERAGE_BPS = 7_000L
    }
}
