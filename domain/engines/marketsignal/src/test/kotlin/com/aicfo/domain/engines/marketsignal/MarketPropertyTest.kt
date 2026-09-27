package com.aicfo.domain.engines.marketsignal

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

/**
 * AI-MKT's promises, over many generated histories (issue 10.7; §30, §21.5, P-02, P-07).
 *
 * Why:  the example tests pin particular markets. These pin what must hold for **every** one —
 *       including the two claims a user would be right to check: that a signal the app could not
 *       evaluate contributes nothing *and* is left out of the total it is measured against, and
 *       that a cheaper day never scores worse than a dearer one. A failure of either would be
 *       invisible on screen and wrong in exactly the direction that costs money.
 * What: six properties, 300 seeded histories each.
 * Result: a broken promise names the case that broke it.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 */
class MarketPropertyTest {
    private val engine = MarketSignalEngineFactory.create()

    @Test
    fun `a score never exceeds the cap, or what was actually measurable`() {
        repeat(CASES) { case ->
            val assessment = engine.assess(market(Random(case))).expectOk()

            assertTrue("case $case: ${assessment.score}", assessment.score <= MarketKnowledge.BUNDLED.bands.cap)
            assertTrue(
                "case $case: scored ${assessment.score} out of a possible ${assessment.possibleScore}",
                assessment.score <= assessment.possibleScore,
            )
        }
    }

    @Test
    fun `a signal the app could not evaluate contributes nothing and counts for nothing`() {
        // The property the honesty of the whole screen rests on: a missing input must not read as
        // a signal that looked and said no.
        repeat(CASES) { case ->
            val assessment = engine.assess(market(Random(case))).expectOk()

            assessment.signals.filter { !it.evaluated }.forEach { signal ->
                assertEquals("case $case: ${signal.id}", 0, signal.points)
            }
            assertEquals(
                "case $case",
                assessment.signals.filter { it.evaluated }.sumOf { it.maxPoints },
                assessment.possibleScore,
            )
        }
    }

    @Test
    fun `the band is always the band the knowledge base says that score is`() {
        repeat(CASES) { case ->
            val assessment = engine.assess(market(Random(case))).expectOk()

            if (assessment.outcome == AssessmentOutcome.SCORED) {
                assertEquals(
                    "case $case: ${assessment.score}",
                    MarketKnowledge.BUNDLED.bands.bandFor(assessment.score),
                    assessment.band,
                )
            } else {
                assertEquals("case $case: an unscored assessment has no band", null, assessment.band)
            }
        }
    }

    @Test
    fun `a cheaper day never scores worse than a dearer one`() {
        // Every signal in the library points the same way: cheaper is better, or at worst neutral.
        // If a lower close could score fewer points, the screen would tell someone to buy the dearer
        // of two days.
        repeat(CASES) { case ->
            val random = Random(case)
            val dearer = market(random)
            val cheaper =
                dearer.copy(
                    closes =
                        dearer.closes.dropLast(1) +
                            dearer.closes.last().let {
                                it.copy(close = Money(it.close.minor * 8 / 10))
                            },
                )

            assertTrue(
                "case $case",
                engine.assess(cheaper).expectOk().score >= engine.assess(dearer).expectOk().score,
            )
        }
    }

    @Test
    fun `nothing is suggested unless every capacity gate passes`() {
        repeat(CASES) { case ->
            val random = Random(case)
            val input = market(random)
            val plan = engine.assess(input).expectOk().tranches

            if (plan.gates.any { !it.passed }) {
                assertTrue("case $case: suggested ${plan.suggested} with a gate closed", plan.suggested == 0)
            }
            assertTrue(
                "case $case: ${plan.suggested} tranches",
                plan.suggested <= MarketKnowledge.BUNDLED.tranches.strongBuyDay,
            )
        }
    }

    @Test
    fun `the same history scores the same way every time`() {
        repeat(CASES) { case ->
            val input = market(Random(case))

            assertEquals("case $case", engine.assess(input).expectOk(), engine.assess(input).expectOk())
        }
    }

    // --- generator ---------------------------------------------------------------------------------

    /**
     * One plausible market: a random walk of up to 420 closes, sometimes with the context series,
     * sometimes with capacity, sometimes barely any history at all.
     * Result: an input the engine must cope with. Input: [random] — seeded. Output: the input.
     */
    private fun market(random: Random): MarketSignalInput {
        // From none at all: an instrument the app has never priced is a real case, and the one the
        // first draft crashed on.
        val days = random.nextInt(0, 420)
        var price = random.nextLong(1_000_00L, 50_000_00L)
        val closes =
            (0 until days).map { day ->
                price = maxOf(1_00L, price + random.nextLong(-price / 50, price / 50))
                DailyClose(START.plusDays(day.toLong()).toString(), Money(price))
            }
        return MarketSignalInput(
            instrument = Instrument("GEN", "Generated"),
            closes = closes,
            context =
                MarketContext(
                    valuationPercentile = if (random.nextBoolean()) random.nextInt(0, 101) else null,
                    vixPercentile = if (random.nextBoolean()) random.nextInt(0, 101) else null,
                ),
            capacity =
                Capacity(
                    idleCash = if (random.nextBoolean()) Money(random.nextLong(1, 5_00_000_00L)) else Money.ZERO,
                    runwayMeetsTarget = random.nextBoolean(),
                    crunchDaysAhead = if (random.nextBoolean()) 0 else random.nextInt(1, 5),
                ),
            todayIsoDate = START.plusDays(days.toLong() - 1 + random.nextInt(0, 4)).toString(),
            nowUtcMillis = NOW,
        )
    }

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val CASES = 300
        const val NOW = 1_790_000_000_000L
        val START: LocalDate = LocalDate.parse("2023-01-02")
    }
}
