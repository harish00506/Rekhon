package com.aicfo.domain.engines.marketsignal

import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * The golden-file gate for AI-MKT (issue 10.7; §21.5, and the acceptance criterion "golden-file
 * test").
 *
 * Why:  a score is a sum of seven ladders over five derived quantities, and an error in any one of
 *       them still produces a plausible number. Eight fixed histories are compared line for line
 *       with an **independent** oracle (`golden/market_oracle.py`), which re-implements §30's
 *       library from the knowledge base in Python — including the two conventions most easily got
 *       wrong: the mid-rank percentile and Wilder's RSI.
 * What: an ordinary market, one with no context inputs at all, a 13% dip, a cheap and frightened
 *       one with no price signal, a crash that caps the score, a saw-tooth, a young history and a
 *       month-old price.
 * Result: a change to a ladder, a minimum or a derived quantity fails here, naming the scenario.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 *
 * Regenerate with `python3 market_oracle.py > market.txt` from the golden directory — but only
 * after deciding that the *oracle* is right, never to make this test pass.
 */
class MarketGoldenTest {
    private val engine = MarketSignalEngineFactory.create()

    private val golden: List<String> by lazy {
        (
            javaClass.classLoader.getResource("golden/market.txt")?.readText()
                ?: throw AssertionError("golden/market.txt is missing")
        ).lines().filter { it.isNotBlank() && !it.startsWith("#") }
    }

    @Test
    fun `every fixed history scores exactly as the oracle says`() {
        assertEquals(golden, SCENARIOS.map(::line))
    }

    /** Result: one golden line for a scenario. Input: [scenario]. Output: [String]. */
    private fun line(scenario: Scenario): String {
        val closes = scenario.closes.mapIndexed { index, paise -> DailyClose(day(index), Money(paise)) }
        val today = LocalDate.parse(day(scenario.closes.size - 1)).plusDays(scenario.daysOld.toLong())
        val assessment =
            when (
                val result =
                    engine.assess(
                        MarketSignalInput(
                            instrument = Instrument("NIFTY50", "Nifty 50"),
                            closes = closes,
                            context = MarketContext(scenario.valuation, scenario.vix),
                            todayIsoDate = today.toString(),
                            nowUtcMillis = NOW,
                        ),
                    )
            ) {
                is Ok -> result.value
                is Err -> throw AssertionError("${scenario.name}: ${result.error}")
            }
        val detail =
            assessment.signals
                .sortedBy { it.id }
                .joinToString("|") { "${it.id.removePrefix("SIG-")}:${if (it.evaluated) "${it.points}" else "-"}" }
        return "${scenario.name} ${assessment.outcome} score=${assessment.score}/${assessment.possibleScore} " +
            "band=${assessment.band?.name ?: "-"} $detail"
    }

    /** Result: the ISO date [index] days into the series. Input: [index]. Output: [String]. */
    private fun day(index: Int): String = START.plusDays(index.toLong()).toString()

    /** One fixed history, described the way the oracle describes it. */
    private data class Scenario(
        val name: String,
        val closes: List<Long>,
        val valuation: Int?,
        val vix: Int?,
        val daysOld: Int = 0,
    )

    private companion object {
        val START: LocalDate = LocalDate.parse("2023-01-02")
        const val NOW = 1_790_000_000_000L

        val FLAT = List(300) { 10_000_00L }
        val DIP = FLAT + 8_700_00L
        val CRASH = FLAT + (1..8).map { 7_000_00L - it * 10_00L }
        val SAWTOOTH =
            (0 until 400).map { day ->
                val phase = day % 60
                10_000_00L + (if (phase < 30) phase else 60 - phase) * 20_00L
            }

        val SCENARIOS =
            listOf(
                Scenario("flat_ordinary_day", FLAT, 50, 50),
                Scenario("flat_no_context", FLAT, null, null),
                Scenario("thirteen_pct_dip", DIP, 50, 50),
                Scenario("cheap_and_frightened", FLAT, 8, 93),
                Scenario("crash_with_context", CRASH, 2, 99),
                Scenario("sawtooth_today", SAWTOOTH, 50, 50),
                Scenario("young_history", FLAT.take(30), 50, 50),
                Scenario("month_old_price", FLAT, 50, 50, daysOld = 31),
            )
    }
}
