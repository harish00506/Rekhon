package com.aicfo.domain.engines.healthscore

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * AI-FHS's identities, over many generated profiles (issue 9.4; §21.5, P-08).
 *
 * Why:  a score a user watches has promises a reader assumes without checking: it stays on its
 *       scale, its parts add up to it, getting better at something never lowers it, and the order
 *       the data arrived in changes nothing.
 * What: 300 seeded profiles per property, every signal present or absent at random.
 * Result: a broken identity names its case.
 * Changelog: 2026-09-19 — Created for issue 9.4.
 */
class HealthPropertyTest {
    private val engine = HealthScoreEngineFactory.create()

    @Test
    fun `the score stays on its scale and the band agrees with it`() {
        cases { input ->
            val result = engine.score(input).expectOk()
            result.score?.let { score ->
                assertTrue("$score", score in 0..input.rules.scoreMax)
                assertEquals(bandOf(score, input.rules), result.band)
            }
            result.pillars.forEach { pillar -> pillar.points?.let { assertTrue("$it", it in 0..FULL) } }
        }
    }

    @Test
    fun `contributions sum to the score and effective weights to the whole`() {
        cases { input ->
            val result = engine.score(input).expectOk()
            assertEquals(result.score ?: 0, result.pillars.sumOf { it.contribution })
            val weights = result.pillars.sumOf { it.effectiveWeightBps }
            assertEquals(if (result.score == null) 0 else FULL, weights)
        }
    }

    @Test
    fun `more runway never lowers the score`() {
        cases { input ->
            val runway = input.runway ?: return@cases
            val better = input.copy(runway = runway.copy(runwayMonthsBps = runway.runwayMonthsBps + 5_000))
            assertTrue(engine.score(better).expectOk().score!! >= engine.score(input).expectOk().score!!)
        }
    }

    @Test
    fun `less card debt never lowers the score`() {
        cases { input ->
            val cards = input.cards ?: return@cases
            if (cards.limit.minor <= 0L) return@cases
            val better = input.copy(cards = cards.copy(used = Money(maxOf(0L, cards.used.minor) / 2)))
            assertTrue(engine.score(better).expectOk().score!! >= engine.score(input).expectOk().score!!)
        }
    }

    @Test
    fun `month order does not matter`() {
        cases { input ->
            val savings = input.savings ?: return@cases
            val shuffled = input.copy(savings = SavingsInput(savings.months.reversed()))
            assertEquals(engine.score(input).expectOk(), engine.score(shuffled).expectOk())
        }
    }

    @Test
    fun `a pillar with data gets weight and one without gets none`() {
        cases { input ->
            engine.score(input).expectOk().pillars.forEach { pillar ->
                assertEquals(pillar.points == null, pillar.effectiveWeightBps == 0 && pillar.signals.isEmpty())
            }
        }
    }

    private fun bandOf(
        score: Int,
        rules: HealthRules,
    ): HealthBand =
        when {
            score >= rules.excellentMin -> HealthBand.EXCELLENT
            score >= rules.goodMin -> HealthBand.GOOD
            score >= rules.fairMin -> HealthBand.FAIR
            score >= rules.attentionMin -> HealthBand.NEEDS_ATTENTION
            else -> HealthBand.AT_RISK
        }

    private fun cases(check: (HealthInput) -> Unit) {
        repeat(CASES) { case ->
            val input = generate(Random(case.toLong()))
            try {
                check(input)
            } catch (failure: AssertionError) {
                throw AssertionError("case $case: ${failure.message}", failure)
            }
        }
    }

    private fun generate(random: Random): HealthInput {
        fun <T> maybe(value: () -> T): T? = if (random.nextInt(4) == 0) null else value()
        return HealthInput(
            runway = maybe { RunwayInput(random.nextInt(0, 200_000), random.nextInt(1, 13)) },
            obligations =
                maybe {
                    ObligationInput(
                        Money(random.nextLong(0, 2_00_000_00L)),
                        Money(random.nextLong(0, 3_00_000_00L)),
                        random.nextInt(0, 4),
                    )
                },
            cards =
                maybe {
                    UtilisationInput(
                        Money(random.nextLong(-50_000_00L, 3_00_000_00L)),
                        Money(random.nextLong(0, 2_00_000_00L)),
                    )
                },
            savings =
                maybe {
                    SavingsInput(
                        (1..random.nextInt(0, 4)).map {
                            MonthFlow(
                                "2026-0$it",
                                Money(random.nextLong(0, 2_00_000_00L)),
                                Money(random.nextLong(-1_00_000_00L, 1_00_000_00L)),
                            )
                        },
                    )
                },
            budgets = maybe { random.nextInt(0, 9).let { total -> ShareInput(random.nextInt(0, total + 1), total) } },
            goals = maybe { random.nextInt(0, 6).let { total -> ShareInput(random.nextInt(0, total + 1), total) } },
            nowUtcMillis = 0L,
        )
    }

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val CASES = 300
        const val FULL = 10_000
    }
}
