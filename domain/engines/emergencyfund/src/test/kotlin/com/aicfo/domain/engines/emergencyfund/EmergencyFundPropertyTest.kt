package com.aicfo.domain.engines.emergencyfund

import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

/**
 * The identities that must hold for **every** fund, not just the sixteen in the golden file
 * (issue 7.2; §21.5 "property tests for math", P-08).
 *
 * Why:  a golden file proves the cases somebody thought of. These are the statements that would
 *       still have to be true for a case nobody thought of — and the ones whose violation would be
 *       a money bug rather than a wrong label. The generator is **seeded**, so a failure is
 *       reproducible from the case printed in the message rather than being a flake somebody reruns
 *       until it passes.
 * What: [CASES] pseudo-random funds per property, drawn from one seed.
 * Result: an arithmetic change that breaks an invariant fails naming the fund that broke it.
 * Changelog: 2026-09-02 — Created for issue 7.2.
 */
class EmergencyFundPropertyTest {
    private val engine = EmergencyFundEngineFactory.create()

    @Test
    fun `the top-up always closes the shortfall, so the fund is actually reached`() {
        // The property that matters most: paying `topUpMonthly` every month for M months must get
        // there. A rounding change that truncated instead of distributing the remainder would leave
        // every fund a few paise short, which no single example would obviously catch.
        forEachFund { case, plan ->
            val paid = Math.multiplyExact(plan.topUpMonthly.minor, plan.multiplierMonths.toLong())
            assertTrue(
                "$case: ${plan.topUpMonthly} x ${plan.multiplierMonths} = $paid falls short of ${plan.shortfall}",
                paid >= plan.shortfall.minor,
            )
        }
    }

    @Test
    fun `the top-up is the smallest that closes it, never padded`() {
        // The other half: over-quoting would be "safe" and would also be wrong, telling the user to
        // find money the arithmetic does not need.
        forEachFund { case, plan ->
            val oneLess = Math.multiplyExact(plan.topUpMonthly.minor - 1, plan.multiplierMonths.toLong())
            assertTrue(
                "$case: ${plan.topUpMonthly} is more than needed for ${plan.shortfall}",
                plan.topUpMonthly.minor == 0L || oneLess < plan.shortfall.minor,
            )
        }
    }

    @Test
    fun `no figure the engine reports is ever negative`() {
        // A surplus is a state, not a debt the fund owes back. Every one of these would render with
        // a minus sign on the card.
        forEachFund { case, plan ->
            assertTrue("$case: negative target ${plan.target}", plan.target >= Money.ZERO)
            assertTrue("$case: negative shortfall ${plan.shortfall}", plan.shortfall >= Money.ZERO)
            assertTrue("$case: negative top-up ${plan.topUpMonthly}", plan.topUpMonthly >= Money.ZERO)
            assertTrue("$case: negative funded ratio ${plan.fundedRatioBps}", plan.fundedRatioBps >= 0)
            assertTrue("$case: negative cv ${plan.incomeCvBps}", (plan.incomeCvBps ?: 0) >= 0)
            assertTrue("$case: negative runway ${plan.runwayMonthsBps}", (plan.runwayMonthsBps ?: 0) >= 0)
        }
    }

    @Test
    fun `the multiplier always lands inside the clamp`() {
        // RULE-RUNWAY-M's whole job. The shipped params never reach either edge, so nothing else in
        // the suite would notice if `coerceIn` were dropped.
        val rules = EmergencyFundRules()
        forEachFund { case, plan ->
            assertTrue(
                "$case: M=${plan.multiplierMonths} outside [${rules.runwayMinMonths}, ${rules.runwayMaxMonths}]",
                plan.multiplierMonths in rules.runwayMinMonths..rules.runwayMaxMonths,
            )
        }
    }

    @Test
    fun `the same input answers the same twice`() {
        // P-08. The engine reads no clock and holds no state, so this can only break by someone
        // reaching for a hash iteration order, a `Double`, or a source of randomness.
        forEachFund { case, first ->
            val second = assess(case)
            assertEquals("$case is not reproducible", first, second)
        }
    }

    @Test
    fun `a wider spread of income never lowers the multiplier`() {
        // The volatility term's direction. Doubling every deviation from the mean leaves the mean
        // untouched and can only make the income lumpier — so M must not go down. An inverted
        // comparison in `volatilityBumpFor` would still produce plausible numbers and would advise
        // a steady earner to hold more than a freelancer.
        val random = Random(SEED)
        repeat(CASES) { index ->
            val steady = incomeSeries(random)
            val widened = widen(steady)
            val calm = assess(case(index, incomes = steady))
            val lumpy = assess(case(index, incomes = widened))

            assertTrue(
                "widening $steady to $widened lowered the cv from ${calm.incomeCvBps} to ${lumpy.incomeCvBps}",
                (lumpy.incomeCvBps ?: 0) >= (calm.incomeCvBps ?: 0),
            )
            assertTrue(
                "widening $steady to $widened lowered M from ${calm.multiplierMonths} to ${lumpy.multiplierMonths}",
                lumpy.multiplierMonths >= calm.multiplierMonths,
            )
        }
    }

    @Test
    fun `more money in the bank never makes the verdict worse`() {
        // The coach bands' direction, and the one a user would notice immediately. `EmergencyStatus`
        // is declared worst-last, so a larger ordinal is a worse verdict — saving more must never
        // increase it. This is the emergency-fund twin of Safe-to-Spend's "saving does not increase
        // what is safe to spend", the invariant that caught ADR-0021's straight replacement.
        val random = Random(SEED)
        repeat(CASES) { index ->
            val poorer = case(index, random = random)
            val richer = poorer.copy(liquid = Math.addExact(poorer.liquid, EXTRA_SAVED))
            val before = assess(poorer)
            val after = assess(richer)

            assertTrue(
                "adding $EXTRA_SAVED paise moved the verdict from ${before.status} to ${after.status}",
                after.status.ordinal <= before.status.ordinal,
            )
            assertTrue(
                "adding $EXTRA_SAVED paise shortened the runway from ${before.runwayMonthsBps} to " +
                    "${after.runwayMonthsBps}",
                (after.runwayMonthsBps ?: 0) >= (before.runwayMonthsBps ?: 0),
            )
        }
    }

    // --- generation ---------------------------------------------------------------------------

    /** Result: runs [assertion] over [CASES] seeded funds. Input: [assertion]. Output: none. */
    private fun forEachFund(assertion: (Case, EmergencyFundPlan) -> Unit) {
        val random = Random(SEED)
        repeat(CASES) { index ->
            val generated = case(index, random = random)
            assertion(generated, assess(generated))
        }
    }

    /**
     * One generated fund.
     *
     * Essentials are never null and never zero here: the unknown branch has no arithmetic to hold an
     * invariant about, and [EmergencyFundEngineTest] covers it directly.
     */
    private data class Case(
        val label: String,
        val essentials: Long,
        val liquid: Long,
        val incomes: List<Long>,
    )

    /** Result: a seeded case. Input: [index]; optionally fixed [incomes]; [random]. Output: [Case]. */
    private fun case(
        index: Int,
        incomes: List<Long>? = null,
        random: Random = Random(SEED + index),
    ): Case {
        val essentials = random.nextLong(MIN_ESSENTIALS, MAX_ESSENTIALS)
        val liquid = random.nextLong(0L, MAX_LIQUID)
        return Case(
            label = "case $index",
            essentials = essentials,
            liquid = liquid,
            incomes = incomes ?: incomeSeries(random),
        )
    }

    /** Result: three to eight whole months of income. Input: [random]. Output: paise per month. */
    private fun incomeSeries(random: Random): List<Long> {
        val months = random.nextInt(MIN_MONTHS, MAX_MONTHS)
        return List(months) { random.nextLong(MIN_INCOME, MAX_INCOME) }
    }

    /**
     * Doubles every deviation from the mean, leaving the mean where it was.
     * Why:    the only honest way to say "lumpier" without also saying "richer" — a spread test that
     *         moved the mean would prove nothing about a *coefficient* of variation.
     * Result: the widened series. Input: [incomes]. Output: paise per month, never negative.
     */
    private fun widen(incomes: List<Long>): List<Long> {
        val mean = incomes.sum() / incomes.size
        return incomes.map { month -> maxOf(0L, mean + (month - mean) * WIDEN_FACTOR) }
    }

    /** Result: the assessment for one case. Input: [case]. Output: [EmergencyFundPlan]. */
    private fun assess(case: Case): EmergencyFundPlan {
        val result =
            engine.assess(
                EmergencyFundInput(
                    monthlyEssentials = Money(case.essentials),
                    essentialsBasis = EssentialsBasis.OBSERVED_MEDIAN,
                    monthlyIncomes = case.incomes.map { Money(it) },
                    liquidFunds = Money(case.liquid),
                    today = TODAY,
                ),
            )
        return (result as Ok).value
    }

    private companion object {
        /** One seed, printed nowhere because it never changes — a failure is reproducible by rerun. */
        const val SEED = 7_2026L

        /** Enough to walk every band and both sides of every boundary many times over. */
        const val CASES = 500

        val TODAY: LocalDate = LocalDate.parse("2026-09-02")

        /** ₹1,000 to ₹2,00,000 a month of essentials, in paise. */
        const val MIN_ESSENTIALS = 1_000_00L
        const val MAX_ESSENTIALS = 2_00_000_00L

        /** Nothing saved, up to ₹50,00,000 — wide enough to reach SURPLUS and URGENT both. */
        const val MAX_LIQUID = 50_00_000_00L

        /** ₹0 to ₹5,00,000 a month of income, in paise. Zero is legitimate: a month with none. */
        const val MIN_INCOME = 0L
        const val MAX_INCOME = 5_00_000_00L

        /** Three is `min_months_observed`; eight is wide enough for the reading to settle. */
        const val MIN_MONTHS = 3
        const val MAX_MONTHS = 9

        /** ₹1,00,000 more in the bank — enough to cross a band for a typical generated fund. */
        const val EXTRA_SAVED = 1_00_000_00L

        /** Doubling the deviations makes the series strictly lumpier without moving its mean. */
        const val WIDEN_FACTOR = 2L
    }
}
