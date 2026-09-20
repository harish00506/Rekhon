package com.aicfo.domain.engines.healthscore

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI-FHS's behaviour, one decision per test (issue 9.4; §14, P-02, P-03).
 *
 * Why:  every curve in §14 is a straight line between two rulebook anchors, and every one of them
 *       has an edge where an off-by-one or a wrong inequality changes the user's band. Each is pinned
 *       with values worked by hand, at the anchors and between them.
 * What: no data; each signal's curve, its absence rule and its refusals; the pillar mean; the
 *       re-weighting; the band edges; the lever; provenance; injected rules.
 * Result: a regression in any of them names itself.
 * Changelog: 2026-09-19 — Created for issue 9.4.
 */
class HealthScoreEngineTest {
    private val engine = HealthScoreEngineFactory.create()

    @Test
    fun `with no data there is no score, only five pillars shown as dashes`() {
        val result = score(HealthInput(nowUtcMillis = NOW))

        assertNull(result.score)
        assertNull(result.band)
        assertNull(result.lever)
        assertEquals(Pillar.entries, result.pillars.map { it.pillar })
        assertTrue(result.pillars.all { it.points == null && it.effectiveWeightBps == 0 && it.contribution == 0 })
        assertEquals(0, result.provenance.confidenceBps)
    }

    @Test
    fun `runway is linear to the target and capped there`() {
        assertEquals(5_000, signal(runway = RunwayInput(30_000, 6), which = Signal.RUNWAY))
        assertEquals(10_000, signal(runway = RunwayInput(90_000, 6), which = Signal.RUNWAY))
    }

    @Test
    fun `a month of runway lifts the runway to the rulebook's floor, and less than a month does not`() {
        // One month of twelve is 833 on the line; the floor is 25 points. Half a month stays on it.
        assertEquals(2_500, signal(runway = RunwayInput(10_000, 12), which = Signal.RUNWAY))
        assertEquals(417, signal(runway = RunwayInput(5_000, 12), which = Signal.RUNWAY))
    }

    @Test
    fun `obligations score full to 30 percent of income and nothing from 55 percent`() {
        assertEquals(10_000, signal(obligations = obligations(30_00_000L, 1_00_00_000L), which = Signal.OBLIGATIONS))
        assertEquals(9_600, signal(obligations = obligations(31_00_000L, 1_00_00_000L), which = Signal.OBLIGATIONS))
        assertEquals(5_000, signal(obligations = obligations(42_50_000L, 1_00_00_000L), which = Signal.OBLIGATIONS))
        assertEquals(0, signal(obligations = obligations(55_00_000L, 1_00_00_000L), which = Signal.OBLIGATIONS))
        assertEquals(0, signal(obligations = obligations(90_00_000L, 1_00_00_000L), which = Signal.OBLIGATIONS))
    }

    @Test
    fun `obligations are not scored without income or without a month of it`() {
        assertEquals(null, signalOrNull(obligations = obligations(1L, 0L), which = Signal.OBLIGATIONS))
        assertEquals(
            null,
            signalOrNull(
                obligations = ObligationInput(Money(1L), Money(100L), monthsOfIncome = 0),
                which = Signal.OBLIGATIONS,
            ),
        )
    }

    @Test
    fun `card utilisation scores full to 30 percent, nothing at 100, and a credit balance as zero used`() {
        assertEquals(
            10_000,
            signal(cards = UtilisationInput(Money(30_000_00L), Money(1_00_000_00L)), which = Signal.CARD_UTILISATION),
        )
        assertEquals(
            5_000,
            signal(cards = UtilisationInput(Money(65_000_00L), Money(1_00_000_00L)), which = Signal.CARD_UTILISATION),
        )
        assertEquals(
            0,
            signal(cards = UtilisationInput(Money(1_00_000_00L), Money(1_00_000_00L)), which = Signal.CARD_UTILISATION),
        )
        assertEquals(
            10_000,
            signal(cards = UtilisationInput(Money(-500_00L), Money(1_00_000_00L)), which = Signal.CARD_UTILISATION),
        )
        assertEquals(
            null,
            signalOrNull(cards = UtilisationInput(Money(500_00L), Money.ZERO), which = Signal.CARD_UTILISATION),
        )
    }

    @Test
    fun `the savings rate is kept over income across the lookback, from 0 at none to full at 30 percent`() {
        assertEquals(5_000, signal(savings = savings(10_000_00L to 1_500_00L), which = Signal.SAVINGS_RATE))
        assertEquals(10_000, signal(savings = savings(10_000_00L to 4_000_00L), which = Signal.SAVINGS_RATE))
        assertEquals(0, signal(savings = savings(10_000_00L to -2_000_00L), which = Signal.SAVINGS_RATE))
        // Two months: ₹3,000 kept of ₹20,000 = 15%, however it split between them.
        assertEquals(
            5_000,
            signal(savings = savings(10_000_00L to 3_500_00L, 10_000_00L to -500_00L), which = Signal.SAVINGS_RATE),
        )
    }

    @Test
    fun `a lookback with no income has no savings rate`() {
        assertEquals(null, signalOrNull(savings = savings(0L to -1_000_00L), which = Signal.SAVINGS_RATE))
        assertEquals(null, signalOrNull(savings = SavingsInput(emptyList()), which = Signal.SAVINGS_RATE))
    }

    @Test
    fun `budgets and goals score their share in good standing, and an empty set is not scored`() {
        assertEquals(7_500, signal(budgets = ShareInput(3, 4), which = Signal.BUDGET_ADHERENCE))
        assertEquals(6_667, signal(goals = ShareInput(2, 3), which = Signal.GOALS_ON_TRACK))
        assertEquals(null, signalOrNull(budgets = ShareInput(0, 0), which = Signal.BUDGET_ADHERENCE))
    }

    @Test
    fun `a pillar is the mean of the signals it has`() {
        val debt =
            score(
                HealthInput(
                    obligations = obligations(30_00_000L, 1_00_00_000L),
                    cards = UtilisationInput(Money(65_000_00L), Money(1_00_000_00L)),
                    nowUtcMillis = NOW,
                ),
            ).pillars.single { it.pillar == Pillar.DEBT }

        assertEquals(7_500, debt.points)
    }

    @Test
    fun `pillars without data give their weight to those with it, so one pillar alone is the whole score`() {
        val result = score(HealthInput(runway = RunwayInput(48_000, 6), nowUtcMillis = NOW))

        assertEquals(800, result.score)
        assertEquals(10_000, result.pillars.single { it.pillar == Pillar.LIQUIDITY }.effectiveWeightBps)
        assertEquals(800, result.pillars.single { it.pillar == Pillar.LIQUIDITY }.contribution)
        assertEquals(2_500, result.provenance.confidenceBps)
    }

    @Test
    fun `the protection pillar has no signal yet and is always shown as a dash`() {
        val protection = score(FULL).pillars.single { it.pillar == Pillar.PROTECTION }

        assertNull(protection.points)
        assertEquals(0, protection.effectiveWeightBps)
    }

    @Test
    fun `contributions and effective weights add up exactly`() {
        val result = score(FULL)

        assertEquals(result.score, result.pillars.sumOf { it.contribution })
        assertEquals(10_000, result.pillars.sumOf { it.effectiveWeightBps })
    }

    @Test
    fun `the bands change exactly at the rulebook's edges`() {
        // Liquidity alone: the score is the runway's points / 10.
        mapOf(
            48_000 to HealthBand.EXCELLENT,
            47_940 to HealthBand.GOOD,
            39_000 to HealthBand.GOOD,
            38_940 to HealthBand.FAIR,
            30_000 to HealthBand.FAIR,
            29_940 to HealthBand.NEEDS_ATTENTION,
            21_000 to HealthBand.NEEDS_ATTENTION,
            20_940 to HealthBand.AT_RISK,
        ).forEach { (runwayBps, band) ->
            val result = score(HealthInput(runway = RunwayInput(runwayBps, 6), nowUtcMillis = NOW))
            assertEquals("runway $runwayBps → ${result.score}", band, result.band)
        }
    }

    @Test
    fun `the lever is the signal with the most points still to gain, and there is none at full marks`() {
        val result =
            score(
                HealthInput(
                    runway = RunwayInput(60_000, 6),
                    goals = ShareInput(1, 2),
                    nowUtcMillis = NOW,
                ),
            )

        // Liquidity 25 and goals 20 share the whole weight: goals at 50/100 leaves 20/45 × 500 = 222.
        assertEquals(Lever(Signal.GOALS_ON_TRACK, 222), result.lever)
        assertNull(score(HealthInput(runway = RunwayInput(60_000, 6), nowUtcMillis = NOW)).lever)
    }

    @Test
    fun `impossible inputs are refused by field`() {
        assertEquals(
            AppError.Validation("health.runway"),
            error(HealthInput(runway = RunwayInput(-1, 6), nowUtcMillis = NOW)),
        )
        assertEquals(
            AppError.Validation("health.runway"),
            error(HealthInput(runway = RunwayInput(1, 0), nowUtcMillis = NOW)),
        )
        assertEquals(
            AppError.Validation("health.obligations"),
            error(HealthInput(obligations = obligations(-1L, 100L), nowUtcMillis = NOW)),
        )
        assertEquals(
            AppError.Validation("health.cards"),
            error(HealthInput(cards = UtilisationInput(Money(1L), Money(-1L)), nowUtcMillis = NOW)),
        )
        assertEquals(
            AppError.Validation("health.savings"),
            error(HealthInput(savings = savings(-1L to 0L), nowUtcMillis = NOW)),
        )
        assertEquals(
            AppError.Validation("health.budgets"),
            error(HealthInput(budgets = ShareInput(3, 2), nowUtcMillis = NOW)),
        )
        assertEquals(
            AppError.Validation("health.goals"),
            error(HealthInput(goals = ShareInput(-1, 2), nowUtcMillis = NOW)),
        )
    }

    @Test
    fun `provenance names the engine, its rules, the borrowed rows it used and the window`() {
        val provenance = score(FULL.copy(window = "2026-06..2026-08")).provenance

        assertEquals("AI-FHS", provenance.engineId)
        assertEquals("1.0", provenance.engineVersion)
        assertEquals(NOW, provenance.computedAtUtcMillis)
        assertEquals("2026-06..2026-08", provenance.inputWindow)
        assertEquals(
            listOf(
                HealthRules.PILLARS,
                HealthRules.BANDS,
                HealthRules.SIGNALS,
                HealthRules.CARD_UTILISATION,
                HealthRules.SAVINGS_RATE,
            ),
            provenance.evidence,
        )
        assertEquals(8_500, provenance.confidenceBps)
        assertEquals(
            listOf(HealthRules.PILLARS, HealthRules.BANDS, HealthRules.SIGNALS),
            score(HealthInput(goals = ShareInput(1, 1), nowUtcMillis = NOW)).provenance.evidence,
        )
    }

    @Test
    fun `the weights are the rulebook's, not the engine's`() {
        val rules =
            HealthRules(
                liquidityWeightBps = 5_000,
                debtWeightBps = 1_000,
                disciplineWeightBps = 1_000,
                goalsWeightBps = 1_500,
                protectionWeightBps = 1_500,
            )
        val input = HealthInput(runway = RunwayInput(60_000, 6), goals = ShareInput(0, 1), nowUtcMillis = NOW)

        // 5 000 / 6 500 of the scale at full marks: 769.23 → 769.
        assertEquals(769, score(input.copy(rules = rules)).score)
        assertEquals(556, score(input).score)
    }

    // --- fixtures -----------------------------------------------------------------------------------

    private fun obligations(
        obligations: Long,
        income: Long,
    ) = ObligationInput(Money(obligations), Money(income), monthsOfIncome = 3)

    private fun savings(vararg months: Pair<Long, Long>) =
        SavingsInput(
            months.mapIndexed {
                    k,
                    (income, saved),
                ->
                MonthFlow("2026-0${k + 1}", Money(income), Money(saved))
            },
        )

    @Suppress("LongParameterList") // one argument per signal a test varies
    private fun signalOrNull(
        runway: RunwayInput? = null,
        obligations: ObligationInput? = null,
        cards: UtilisationInput? = null,
        savings: SavingsInput? = null,
        budgets: ShareInput? = null,
        goals: ShareInput? = null,
        which: Signal,
    ): Int? =
        score(HealthInput(runway, obligations, cards, savings, budgets, goals, nowUtcMillis = NOW))
            .pillars.flatMap { it.signals }.singleOrNull { it.signal == which }?.points

    @Suppress("LongParameterList") // one argument per signal a test varies
    private fun signal(
        runway: RunwayInput? = null,
        obligations: ObligationInput? = null,
        cards: UtilisationInput? = null,
        savings: SavingsInput? = null,
        budgets: ShareInput? = null,
        goals: ShareInput? = null,
        which: Signal,
    ): Int =
        signalOrNull(runway, obligations, cards, savings, budgets, goals, which)
            ?: throw AssertionError("$which was not scored")

    private fun score(input: HealthInput): HealthScore = engine.score(input).expectOk()

    private fun error(input: HealthInput): AppError =
        when (val result = engine.score(input)) {
            is Ok -> throw AssertionError("expected Err, got ${result.value}")
            is Err -> result.error
        }

    private fun <T> Result<T, AppError>.expectOk(): T =
        when (this) {
            is Ok -> value
            is Err -> throw AssertionError("expected Ok, got $error")
        }

    private companion object {
        const val NOW = 1_789_800_000_000L

        /** Every signal the engine scores, each somewhere in the middle of its curve. */
        val FULL =
            HealthInput(
                runway = RunwayInput(48_000, 6),
                obligations = ObligationInput(Money(31_50_000L), Money(95_00_000L), 3),
                cards = UtilisationInput(Money(13_80_000L), Money(40_00_000L)),
                savings = SavingsInput(listOf(MonthFlow("2026-08", Money(95_00_000L), Money(19_00_000L)))),
                budgets = ShareInput(5, 7),
                goals = ShareInput(2, 3),
                nowUtcMillis = NOW,
            )
    }
}
