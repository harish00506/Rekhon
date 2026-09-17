package com.aicfo.domain.engines.orderofoperations

import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.goals.SurplusBasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

/**
 * The identities that must hold for **every** ranking, not just the twelve households in the golden
 * file (issue 7.5; §21.5 "property tests for math", P-08).
 *
 * Why:  a golden file proves the cases somebody thought of. These are the statements that still have
 *       to be true for a case nobody did — and each one's violation would be a money bug or a
 *       ranking bug rather than a wrong label. The generator is **seeded**, so a failure reproduces
 *       from the seed instead of being a flake.
 * What: [CASES] pseudo-random households per property, drawn from one seed.
 * Result: a change that breaks an invariant fails naming the household that broke it.
 * Changelog: 2026-09-17 — Created for issue 7.5.
 */
class OrderOfOperationsPropertyTest {
    private val engine = OrderOfOperationsEngineFactory.create()

    /** Output: the stages plus the leftover place exactly the distributable surplus, to the paise. */
    @Test
    fun `the stages place exactly what was poured, to the paise`() {
        forEachHousehold { input, result ->
            val distributable = maxOf(Money.ZERO, input.monthlySurplus ?: Money.ZERO)
            val placed = result.stages.fold(result.unallocated) { sum, stage -> sum + stage.amountMonthly }
            assertEquals("$input", distributable, placed)
        }
    }

    /** Output: no stage is ever given more than it needs, and nothing is ever negative. */
    @Test
    fun `no stage is overfilled and no figure is negative`() {
        forEachHousehold { input, result ->
            assertTrue("$input: leftover ${result.unallocated}", result.unallocated >= Money.ZERO)
            result.stages.forEach { stage ->
                assertTrue("$input: ${stage.stage} took ${stage.amountMonthly}", stage.amountMonthly >= Money.ZERO)
                stage.need?.let { need ->
                    assertTrue(
                        "$input: ${stage.stage} took ${stage.amountMonthly} of $need",
                        stage.amountMonthly <= need,
                    )
                }
            }
        }
    }

    /**
     * Output: a larger surplus never gives any stage less.
     *
     * Monotonicity catches a waterfall whose remainder is threaded wrongly — each stage's arithmetic
     * right on its own while more money reaches one stage by taking it from another.
     */
    @Test
    fun `a bigger surplus never leaves any stage worse off`() {
        forEachHousehold { input, result ->
            val raised = (input.monthlySurplus ?: Money.ZERO) + RAISE
            val richer = rank(input.copy(monthlySurplus = raised, surplusBasis = SurplusBasis.OBSERVED_MEDIAN))
            result.stages.zip(richer.stages).forEach { (poorer, wealthier) ->
                assertTrue(
                    "$input: ${poorer.stage} got ${poorer.amountMonthly}, but ${wealthier.amountMonthly} with more",
                    wealthier.amountMonthly >= poorer.amountMonthly,
                )
            }
        }
    }

    /**
     * Output: the ranking — every stage's status, reason, need and debts, and the top action — does
     *         not depend on how much money there is.
     *
     * §36's order answers "where should the next rupee go?", which has an answer before anyone knows
     * how many rupees there are. Only the amounts may move with the surplus.
     */
    @Test
    fun `the ranking does not depend on the size of the surplus`() {
        forEachHousehold { input, result ->
            val unknown = rank(input.copy(monthlySurplus = null, surplusBasis = SurplusBasis.NONE))
            assertEquals("$input", result.stages.map { it.withoutAmount() }, unknown.stages.map { it.withoutAmount() })
            assertEquals("$input", result.topAction?.stage, unknown.topAction?.stage)
        }
    }

    /** Output: every stage below the fund is empty-handed whenever the gate holds. */
    @Test
    fun `nothing below the emergency fund is funded while the gate holds`() {
        forEachHousehold { input, result ->
            val runway = input.emergencyRunwayMonthsBps
            if (runway == null || runway < input.emergencyGateMonths * BPS_PER_MONTH) {
                result.stages.drop(FooStage.FULL_EMERGENCY.ordinal + 1).forEach { stage ->
                    assertEquals("$input: ${stage.stage}", Money.ZERO, stage.amountMonthly)
                }
            }
        }
    }

    /** Output: the same household ranked twice gives equal results (P-08). */
    @Test
    fun `ranking is deterministic`() {
        forEachHousehold { input, result -> assertEquals("$input", result, rank(input)) }
    }

    // --- generator ----------------------------------------------------------------------------

    /**
     * Runs [check] on [CASES] households drawn from [SEED].
     * Input: [check] — given each household and its ranking. Output: none; fails inside [check].
     */
    private fun forEachHousehold(check: (OrderOfOperationsInput, OrderOfOperations) -> Unit) {
        val random = Random(SEED)
        repeat(CASES) {
            val input = household(random)
            check(input, rank(input))
        }
    }

    /**
     * Result: one plausible household — every field independently absent, zero or populated, so the
     * edges (unknown runway, unsized fund, no goals, negative surplus) are all drawn regularly.
     */
    private fun household(random: Random): OrderOfOperationsInput {
        val surplus = random.maybe { Money(random.nextLong(-2_000_000, 20_000_000)) }
        val goalCount = random.nextInt(0, 4)
        return OrderOfOperationsInput(
            monthlySurplus = surplus,
            surplusBasis = if (surplus == null) SurplusBasis.NONE else SurplusBasis.OBSERVED_MEDIAN,
            monthlyEssentials = random.maybe { Money(random.nextLong(0, 10_000_000)) },
            liquidFunds = Money(random.nextLong(0, 40_000_000)),
            emergencyShortfall = random.maybe { Money(random.nextLong(0, 30_000_000)) },
            emergencyTopUpMonthly = Money(random.nextLong(0, 5_000_000)),
            emergencyRunwayMonthsBps = random.maybe { random.nextInt(0, 150_000) },
            debts = List(random.nextInt(0, 5)) { index -> debt(random, index) },
            goalsRequiredMonthly = if (goalCount == 0) Money.ZERO else Money(random.nextLong(0, 5_000_000)),
            goalCount = goalCount,
            today = TODAY,
        )
    }

    /** Result: one debt — a card may have no rate; rates span every band, edges included. */
    private fun debt(
        random: Random,
        index: Int,
    ): DebtPosition {
        val kind = if (random.nextBoolean()) DebtKind.CARD else DebtKind.LOAN
        val rate = RATES[random.nextInt(RATES.size)]
        return DebtPosition(
            accountId = "debt-$index",
            name = "Debt $index",
            kind = kind,
            outstanding = Money(random.nextLong(0, 50_000_000)),
            aprBps = if (kind == DebtKind.CARD && random.nextInt(4) == 0) null else rate,
        )
    }

    /** Result: [value] two times in three, null otherwise. */
    private fun <T> Random.maybe(value: () -> T): T? = if (nextInt(3) == 0) null else value()

    /** Result: the ranking, unwrapped; fails on `Err`. */
    private fun rank(input: OrderOfOperationsInput): OrderOfOperations {
        val result = engine.rank(input)
        assertTrue("$input: expected Ok, was $result", result is Ok)
        return (result as Ok).value
    }

    /** Result: the stage with its amount zeroed — everything the ranking decides, nothing the pour does. */
    private fun StageOutcome.withoutAmount(): StageOutcome = copy(amountMonthly = Money.ZERO)

    private companion object {
        const val SEED = 20_260_917
        const val CASES = 500
        const val BPS_PER_MONTH = 10_000
        val RAISE = Money(750_000)
        val TODAY: LocalDate = LocalDate.of(2026, 9, 17)

        /** Every band, and one basis point either side of both boundaries. */
        val RATES = listOf(0, 850, 999, 1_000, 1_150, 1_349, 1_350, 1_400, 3_600, 4_200)
    }
}
