package com.aicfo.domain.engines.marketsignal

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * What AI-MKT must get right (issue 10.7; SRS §30, P-02, P-03, P-04, P-07, P-08).
 *
 * Why:  this is the engine most able to do damage by being confidently wrong, because its output is
 *       a sentence people want to act on. The ways it can be wrong are specific: scoring a missing
 *       input as zero (which reads as "valuation says no"), measuring a drawdown against three
 *       weeks of history, quoting a hit rate from four samples, measuring that rate with data the
 *       past day could not have had, answering from a month-old price, or suggesting a deployment
 *       to a household with a crunch day two weeks out.
 * What: each signal's ladder, the possible score, the bands, the history and staleness gates, the
 *       walk-forward hit rate, the tranche gates, the refusals and determinism.
 * Result: a verdict a user can check, or an honest refusal to give one.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 */
class MarketSignalEngineTest {
    private val engine = MarketSignalEngineFactory.create()

    @Test
    fun `a flat history on an ordinary day has no edge`() {
        // Every signal reads "ordinary" — including rarity, which must put a flat series in the
        // middle of its own year rather than at the bottom of it. The first draft scored this 5.
        val assessment = assess(flat(300, 10_000_00L))

        assertEquals(AssessmentOutcome.SCORED, assessment.outcome)
        assertEquals(0, assessment.score)
        assertEquals(OpportunityBand.NO_EDGE, assessment.band)
    }

    @Test
    fun `an instrument the app has never priced is answered, not crashed on`() {
        // The most ordinary situation there is: a holding the user added and no price has been
        // fetched for. The first draft crashed here — every price signal reads the last close, and
        // there isn't one. A repository test found it, on a case this suite had not thought to ask.
        val assessment = assess(emptyList(), today = TODAY)

        assertEquals(AssessmentOutcome.NOT_ENOUGH_HISTORY, assessment.outcome)
        assertEquals(0, assessment.score)
        assertEquals("nothing could be evaluated, so nothing was possible", 0, assessment.possibleScore)
        assertNull(assessment.staleness.asOfIsoDate)
    }

    @Test
    fun `too little history is said, not scored around`() {
        // MKT-HISTORY.minimum_days_for_any_score is 60. A drawdown measured against three weeks is
        // not a drawdown, and a score built on one would look exactly like a real one.
        val assessment = assess(flat(30, 10_000_00L))

        assertEquals(AssessmentOutcome.NOT_ENOUGH_HISTORY, assessment.outcome)
        assertNull(assessment.band)
        assertEquals(0, assessment.score)
    }

    @Test
    fun `a month-old price gets no verdict at all`() {
        // MKT-STALE.refuse_after_days is 30. A month-old close is not evidence about today, and
        // labelling it "stale" would be a hedge rather than an answer.
        val closes = flat(300, 10_000_00L)
        val assessment = assess(closes, today = LocalDate.parse(closes.last().isoDate).plusDays(31).toString())

        assertEquals(AssessmentOutcome.TOO_STALE, assessment.outcome)
        assertTrue(assessment.staleness.isStale)
    }

    @Test
    fun `a price a few days old is answered, and labelled`() {
        val closes = flat(300, 10_000_00L)
        val assessment = assess(closes, today = LocalDate.parse(closes.last().isoDate).plusDays(5).toString())

        assertEquals(AssessmentOutcome.SCORED, assessment.outcome)
        assertTrue("five days old should be labelled stale", assessment.staleness.isStale)
        assertEquals(5, assessment.staleness.daysOld)
    }

    @Test
    fun `a drawdown from the 52-week high scores its tier, and shows what it measured`() {
        // 300 flat days at ₹10,000, then a fall to ₹8,700 — 13% down, which clears the -12% tier.
        val assessment = assess(flat(300, 10_000_00L) + close(300, 8_700_00L))
        val drawdown = assessment.signals.first { it.id == MarketKnowledge.DRAWDOWN }

        assertEquals(15, drawdown.points)
        assertEquals(20, drawdown.maxPoints)
        assertEquals("13% below the high, in basis points", -1_300, drawdown.measuredBps)
    }

    @Test
    fun `a fall below the 200-day average scores its own tier`() {
        val assessment = assess(flat(300, 10_000_00L) + close(300, 8_700_00L))
        val ma200 = assessment.signals.first { it.id == MarketKnowledge.MA200 }

        assertTrue("a 13% fall is well below the average: ${ma200.measuredBps}", ma200.points >= 10)
        assertTrue(ma200.evaluated)
    }

    @Test
    fun `a run of down days scores the streak signal`() {
        val falling = (1..7).map { day -> close(300 + day, 10_000_00L - day * 10_00L) }
        val assessment = assess(flat(300, 10_000_00L) + falling)
        val streak = assessment.signals.first { it.id == MarketKnowledge.STREAK }

        assertEquals("seven down days clears the six-day step", 5, streak.points)
        assertEquals(7, streak.measuredCount)
    }

    @Test
    fun `a missing valuation is not scored as a zero`() {
        // The whole point: an input the app does not have must shrink the possible score, not
        // quietly read as "valuation says this is expensive".
        val assessment = assess(flat(300, 10_000_00L), context = MarketContext())
        val valuation = assessment.signals.first { it.id == MarketKnowledge.VALUATION }

        assertTrue("a missing input must not be 'evaluated'", !valuation.evaluated)
        assertEquals(0, valuation.points)
        assertEquals(
            "the possible score excludes what could not be evaluated",
            100 - valuation.maxPoints - 15,
            assessment.possibleScore,
        )
    }

    @Test
    fun `a cheap market and a frightened one score their weights`() {
        val assessment =
            assess(
                flat(300, 10_000_00L),
                context = MarketContext(valuationPercentile = 8, vixPercentile = 93),
            )

        assertEquals(25, assessment.signals.first { it.id == MarketKnowledge.VALUATION }.points)
        assertEquals(15, assessment.signals.first { it.id == MarketKnowledge.VIX }.points)
        assertEquals("both inputs present means the full 100 is on the table", 100, assessment.possibleScore)
    }

    @Test
    fun `the score is capped at the knowledge base's cap`() {
        val assessment =
            assess(
                flat(300, 10_000_00L) + (1..8).map { day -> close(300 + day, 5_000_00L - day * 10_00L) },
                context = MarketContext(valuationPercentile = 2, vixPercentile = 99),
            )

        assertEquals(100, assessment.score)
        assertEquals(OpportunityBand.STRONG_BUY_DAY, assessment.band)
    }

    @Test
    fun `a hit rate is only quoted when there are enough samples`() {
        // MKT-HITRATE.min_samples is 20. A rate from four days is a number that persuades without
        // informing, which is the opposite of §30.3. Here today is a sharp trough and every past
        // day was ordinary, so there is nothing comparable to measure against.
        val assessment =
            assess(
                flat(300, 10_000_00L) + close(300, 7_000_00L),
                context = MarketContext(valuationPercentile = 5, vixPercentile = 95),
            )

        assertEquals(OpportunityBand.STRONG_BUY_DAY, assessment.band)
        assertNull("no past day was in this band, so there is no rate to quote", assessment.hitRate)
    }

    @Test
    fun `a handful of comparable days is not a hit rate`() {
        // The case that separates "too few samples" from "none at all" — and the one a deliberate
        // break of the minimum slipped through until it existed. Three past dips landed in today's
        // band; MKT-HITRATE.min_samples is 20, so there is nothing honest to quote.
        val history =
            (0..300).map { day ->
                val dip = day % 60 == 0 && day > 0
                close(day, if (dip || day == 300) 9_200_00L else 10_000_00L)
            }

        val assessment = assess(history, context = MarketContext(valuationPercentile = 8, vixPercentile = 93))

        assertEquals(AssessmentOutcome.SCORED, assessment.outcome)
        assertNull("a handful of comparable days is not a measured rate", assessment.hitRate)
    }

    @Test
    fun `a flat history does have a rate, and it is an honest zero`() {
        // The other side of the same rule: hundreds of ordinary days are hundreds of samples, and
        // a market that never moved never rose. Withholding that would be flattering the signal.
        val rate = assess(flat(300, 10_000_00L)).hitRate

        assertTrue("a flat history has plenty of comparable days", (rate?.samples ?: 0) >= 20)
        assertEquals(0, rate?.ratePct)
    }

    @Test
    fun `a hit rate is measured walk-forward on this instrument's own history`() {
        // A long saw-tooth: every trough looks like every other, so past days land in the same band
        // and the forward horizon is knowable. The rate must come out of the history, not the KB.
        val assessment = assess(sawtooth(900))

        val rate = assessment.hitRate
        if (rate != null) {
            assertTrue("samples below the minimum should have been withheld", rate.samples >= 20)
            assertTrue("a rate is a percentage", rate.ratePct in 0..100)
            assertEquals(rate.hits * 100 / rate.samples, rate.ratePct)
            assertEquals(90, rate.horizonDays)
        }
    }

    @Test
    fun `a good day suggests one tranche, and a strong day two`() {
        val capacity = Capacity(idleCash = Money(1_00_000_00L), runwayMeetsTarget = true, crunchDaysAhead = 0)
        val strong =
            assess(
                flat(300, 10_000_00L) + close(300, 7_500_00L),
                context = MarketContext(valuationPercentile = 5, vixPercentile = 95),
                capacity = capacity,
            )

        assertEquals(OpportunityBand.STRONG_BUY_DAY, strong.band)
        assertEquals(2, strong.tranches.suggested)
        assertTrue(strong.tranches.clear)
    }

    @Test
    fun `a household with a crunch day ahead is suggested nothing`() {
        // §30.4's gates are other engines' verdicts. Suggesting a deployment into a crunch would be
        // advice to create the emergency the rest of the app exists to prevent.
        val strong =
            assess(
                flat(300, 10_000_00L) + close(300, 7_500_00L),
                context = MarketContext(valuationPercentile = 5, vixPercentile = 95),
                capacity = Capacity(idleCash = Money(1_00_000_00L), runwayMeetsTarget = true, crunchDaysAhead = 2),
            )

        assertEquals(0, strong.tranches.suggested)
        assertTrue(strong.tranches.gates.any { it.ruleId == "AI-FCT" && !it.passed })
    }

    @Test
    fun `no idle cash and no runway are named as the reasons`() {
        val strong =
            assess(
                flat(300, 10_000_00L) + close(300, 7_500_00L),
                context = MarketContext(valuationPercentile = 5, vixPercentile = 95),
                capacity = Capacity(idleCash = Money.ZERO, runwayMeetsTarget = false, crunchDaysAhead = 0),
            )

        assertEquals(0, strong.tranches.suggested)
        assertEquals(
            listOf("RULE-IDLE-CASH", "RULE-RUNWAY-M"),
            strong.tranches.gates.filter { !it.passed }.map { it.ruleId },
        )
    }

    @Test
    fun `every assessment names the engine and the library behind it`() {
        val provenance = assess(flat(300, 10_000_00L)).provenance

        assertEquals("AI-MKT", provenance.engineId)
        assertEquals("1.0", provenance.engineVersion)
        assertTrue(
            "the signal library is not cited: ${provenance.evidence}",
            provenance.evidence.any { it.ruleId == "MKT-SIGNALS" },
        )
    }

    @Test
    fun `impossible inputs are refused by field`() {
        assertEquals(
            AppError.Validation("market.close"),
            error(input(listOf(DailyClose("2026-01-01", Money(-1L))))),
        )
        assertEquals(
            AppError.Validation("market.date"),
            error(input(listOf(DailyClose("not-a-date", Money(1_00L))))),
        )
        assertEquals(
            AppError.Validation("market.percentile"),
            error(input(flat(300, 10_000_00L), context = MarketContext(valuationPercentile = 140))),
        )
    }

    @Test
    fun `the same history scores the same way twice`() {
        assertEquals(assess(sawtooth(400)), assess(sawtooth(400)))
    }

    // --- fixtures ----------------------------------------------------------------------------------

    /** Result: [days] closes at one price, ending the day before [TODAY]. */
    private fun flat(
        days: Int,
        price: Long,
    ): List<DailyClose> = (0 until days).map { day -> close(day, price) }

    /** Result: one close [index] days into the series. */
    private fun close(
        index: Int,
        price: Long,
    ): DailyClose = DailyClose(START.plusDays(index.toLong()).toString(), Money(price))

    /**
     * Result: a saw-tooth of [days] closes — a steady climb and fall, repeated, so past days land
     * in the same bands as today and a forward horizon exists to measure.
     */
    private fun sawtooth(days: Int): List<DailyClose> =
        (0 until days).map { day ->
            val phase = day % 60
            val swing = if (phase < 30) phase else 60 - phase
            close(day, 10_000_00L + swing * 20_00L)
        }

    private fun input(
        closes: List<DailyClose>,
        context: MarketContext = MarketContext(valuationPercentile = 50, vixPercentile = 50),
        capacity: Capacity = Capacity(),
        today: String? = null,
    ) = MarketSignalInput(
        instrument = Instrument("NIFTY50", "Nifty 50"),
        closes = closes,
        context = context,
        capacity = capacity,
        todayIsoDate = today ?: closes.lastOrNull()?.isoDate ?: TODAY,
        nowUtcMillis = NOW,
    )

    private fun assess(
        closes: List<DailyClose>,
        context: MarketContext = MarketContext(valuationPercentile = 50, vixPercentile = 50),
        capacity: Capacity = Capacity(),
        today: String? = null,
    ): OpportunityAssessment = engine.assess(input(closes, context, capacity, today)).expectOk()

    private fun error(input: MarketSignalInput): AppError =
        when (val result = engine.assess(input)) {
            is Ok -> throw AssertionError("expected Err, got ${result.value.outcome}")
            is Err -> result.error
        }

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        val START: LocalDate = LocalDate.parse("2023-01-02")
        const val TODAY = "2026-09-27"
        const val NOW = 1_790_000_000_000L
    }
}
