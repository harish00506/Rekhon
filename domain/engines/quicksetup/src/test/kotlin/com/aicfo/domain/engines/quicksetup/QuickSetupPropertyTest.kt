package com.aicfo.domain.engines.quicksetup

import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Property tests for [QuickSetupEngine] — the identities that must hold for every input (§21.5).
 *
 * Why:  the golden-file cases pin a handful of tidy salaries, and tidy salaries are exactly the
 *       ones that divide evenly. The bugs in a proportional split live at ₹83,333.33 and
 *       ₹1,00,000.01, where the truncated shares leave a stray paise that a naive implementation
 *       either drops or double-counts. These tests hammer awkward amounts and assert the
 *       invariants rather than specific figures, so they keep biting as the formula changes.
 * What: envelopes total exactly, the savings floor is never breached, needs covers the rent while
 *       the cap allows it, and nothing ever goes negative.
 * Result: the split is exact for every income, not just the ones someone thought to write down.
 * Changelog: 2026-07-27 — Created for issue 2.3.
 *
 * **Randomness is seeded (P-08).** A fixed seed means a failure is reproducible from the report
 * alone; an unseeded generator would produce a test that fails once in CI and never again.
 */
class QuickSetupPropertyTest {
    private val engine = QuickSetupEngineFactory.create()

    /**
     * Input:  400 seeded incomes from ₹1 to ~₹20 lakh, each with and without a rent figure.
     * Output: asserts the three envelopes sum to **exactly** the income, every time. This is the
     *         property `Money.allocate` exists for, and the one a hand-rolled `income * 30 / 100`
     *         would break silently — losing a paise per user per month.
     */
    @Test
    fun `envelopes always sum exactly to the income`() {
        forEachSeededCase { income, rent ->
            val plan = engine.plan(inputOf(income, rent)).expectOk()
            assertEquals(
                "envelopes must total the income exactly for income=${income.minor} rent=${rent?.minor}",
                income,
                plan.envelopeTotal(),
            )
        }
    }

    /**
     * Input:  the same seeded cases.
     * Output: asserts the savings envelope is never below the rule's 20% floor (after truncation).
     *         The flex moves money out of wants, never out of savings — a budget that balances by
     *         quietly cancelling the user's saving is worse than one that admits it does not fit.
     */
    @Test
    fun `the savings floor is never breached`() {
        val floorPct = QuickSetupRules().savingsPctMin
        forEachSeededCase { income, rent ->
            val plan = engine.plan(inputOf(income, rent)).expectOk()
            val floor = income.minor * floorPct / 100
            assertTrue(
                "savings ${plan.envelope(BudgetNature.INVEST).minor} fell below the $floorPct% floor $floor " +
                    "for income=${income.minor} rent=${rent?.minor}",
                plan.envelope(BudgetNature.INVEST).minor >= floor,
            )
        }
    }

    /**
     * Input:  seeded cases where the rent fits inside the metro cap.
     * Output: asserts the needs envelope covers the rent. Below the cap the flex has room, so a
     *         needs envelope that still falls short means the flex failed to engage — the bug that
     *         would hand a metro renter a budget short by their largest single outflow.
     */
    @Test
    fun `needs covers the rent whenever the metro cap allows it`() {
        val capPct = QuickSetupRules().metroNeedsPctMax
        forEachSeededCase { income, rent ->
            if (rent == null || rent.minor > income.minor * capPct / 100) return@forEachSeededCase
            val plan = engine.plan(inputOf(income, rent)).expectOk()
            assertTrue(
                "needs ${plan.envelope(BudgetNature.NEED).minor} did not cover rent ${rent.minor} " +
                    "for income=${income.minor}",
                plan.envelope(BudgetNature.NEED) >= rent,
            )
        }
    }

    /**
     * Input:  the same seeded cases.
     * Output: asserts no envelope is negative and the obligation ratio stays a sane basis-point
     *         value. A negative envelope would render as a budget the user owes back.
     */
    @Test
    fun `no envelope is ever negative and the ratio stays in basis points`() {
        forEachSeededCase { income, rent ->
            val plan = engine.plan(inputOf(income, rent)).expectOk()
            plan.envelopes.forEach {
                assertTrue("envelope ${it.nature} was negative: ${it.amount.minor}", it.amount >= Money.ZERO)
            }
            plan.obligationLoadBps?.let {
                assertTrue("obligation ratio $it is not a non-negative bps value", it >= 0)
            }
        }
    }

    /**
     * Input:  the same seeded cases, each computed twice.
     * Output: asserts identical plans. Determinism across the whole input space, rather than for
     *         the one worked example the golden test pins (P-08).
     */
    @Test
    fun `every seeded case is deterministic`() {
        forEachSeededCase { income, rent ->
            val subject = inputOf(income, rent)
            assertEquals(engine.plan(subject).expectOk(), engine.plan(subject).expectOk())
        }
    }

    /**
     * Runs [assertion] over a fixed set of awkward incomes, with and without rent.
     * Why:    every property here needs the same input sweep, and duplicating the generator per
     *         test is how two of them silently end up covering different ranges.
     * Result: invokes [assertion] 800 times with a reproducible sequence.
     * Input:  [assertion] — takes the income and an optional rent. Output: none.
     */
    private fun forEachSeededCase(assertion: (income: Money, rent: Money?) -> Unit) {
        val random = Random(SEED)
        repeat(CASES) {
            // 1 paise .. ~20 lakh, so the sweep includes amounts too small to split evenly.
            val income = Money(random.nextLong(1L, 20_00_000_00L))
            val rent = Money(random.nextLong(0L, income.minor + 1L))
            assertion(income, null)
            assertion(income, rent)
        }
    }

    private fun inputOf(
        income: Money,
        rent: Money?,
    ) = QuickSetupInput(
        monthlyIncome = income,
        rentOrEmi = rent,
        periodStartIsoDate = "2026-07-01",
        nowUtcMillis = 1_785_196_800_000L,
    )

    private companion object {
        /** Fixed so a failure is reproducible from the test report alone (P-08). */
        const val SEED = 20_260_727L
        const val CASES = 400
    }
}
