package com.aicfo.domain.engines.safetospend

import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The math identities [SafeToSpendEngine] must hold for **every** month, not just the fixtured ones
 * (issue 5.2; §21.5).
 *
 * Why:  §21.5 asks for "property tests for math (splits always sum, forecast monotonic identities)".
 *       The example-based suite next door proves the engine is right about the twelve months someone
 *       thought to write down; these prove it about a thousand it generated. The two identities that
 *       carry the weight are **the breakdown always adds up** — a card whose lines disagreed with
 *       its headline would be a plausible fiction beside a correct number — and **monotonicity**: a
 *       commitment can only ever lower what is safe to spend, and a formula that ever got that
 *       backwards would tell someone a scheduled bill had made them richer.
 * What: a seeded generator over realistic-to-extreme months, and four identities over each.
 * Result: an arithmetic sign flipped anywhere in the formula fails here with the month that caught it.
 * Changelog: 2026-08-16 — Created for issue 5.2.
 *
 * **Seeded, never `Random()`** (P-08): a failure here is reproducible by re-running the same seed,
 * and the suite scores the same in CI as on a laptop.
 */
class SafeToSpendPropertyTest {
    private val engine = SafeToSpendEngineFactory.create()

    /**
     * The identity [SafeToSpend] exists to guarantee.
     * Input:  [CASES] generated months.
     * Output: asserts the signed lines sum to the headline in every one.
     */
    @Test
    fun `the breakdown always adds up to the figure it explains`() {
        forEachMonth { _, result ->
            val summed = result.lines.fold(Money.ZERO) { running, line -> running + line.signedAmount }
            assertEquals("the lines and the headline disagree", result.amount, summed)
        }
    }

    /**
     * Input:  [CASES] generated months, each recomputed with one deduction raised by ₹1,000.
     * Output: asserts the figure never rises.
     *
     * Why:    the direction of the whole formula. Every term but income is something the month has
     *         already claimed, so more of any of them can only leave less.
     */
    @Test
    fun `more commitment never means more to spend`() {
        forEachMonth { month, result ->
            val bump = Money(100_000L)
            listOf(
                month.copy(spentToDate = month.spentToDate + bump),
                month.copy(scheduled = month.scheduled + bump),
                month.copy(recurringDue = month.recurringDue + bump),
                month.copy(goalContributionsRemaining = month.goalContributionsRemaining + bump),
            ).forEach { raised ->
                assertTrue(
                    "raising a commitment raised Safe-to-Spend",
                    run(raised).amount <= result.amount,
                )
            }
        }
    }

    /**
     * Input:  [CASES] generated months, each recomputed with income raised by ₹1,000.
     * Output: asserts the figure never falls. The buffer is a *share* of income, so a naive
     *         implementation that withheld a fixed amount instead would still pass — but one that
     *         withheld more than it gained (a buffer over 100%) would not, which is the boundary
     *         `SafeToSpendRules` refuses and this proves it never has to.
     */
    @Test
    fun `more income never means less to spend`() {
        forEachMonth { month, result ->
            val raised = run(month.copy(income = month.income + Money(100_000L)))
            assertTrue("raising income lowered Safe-to-Spend", raised.amount >= result.amount)
        }
    }

    /**
     * Input:  [CASES] generated months, each computed twice.
     * Output: asserts the two results are identical (P-08). The engine reads no clock and no
     *         randomness of its own, and this is what proves it.
     */
    @Test
    fun `the same month always computes to the same answer`() {
        forEachMonth { month, result -> assertEquals(result, run(month)) }
    }

    /**
     * Input:  the same seed, twice.
     * Output: asserts the generator itself is reproducible — without this the three properties above
     *         would be measuring a different thousand months on every run, and a failure nobody
     *         could re-create is a failure nobody can fix.
     */
    @Test
    fun `the generator is reproducible from its seed`() {
        assertEquals(months(), months())
    }

    // --- generation ------------------------------------------------------------------------------

    /**
     * Runs [assertion] over every generated month.
     * Result: none. Input: [assertion] — the month and its computed figure. Output: none.
     */
    private fun forEachMonth(assertion: (SafeToSpendInput, SafeToSpend) -> Unit) {
        months().forEach { month -> assertion(month, run(month)) }
    }

    /**
     * Computes one month, failing the test on an `Err`.
     * Result: the figure. Input: [month]. Output: [SafeToSpend].
     */
    private fun run(month: SafeToSpendInput): SafeToSpend {
        val outcome = engine.compute(month)
        assertTrue("the engine errored on a generated month: $month", outcome is Ok)
        return (outcome as Ok).value
    }

    /**
     * Generates the months every property is checked over.
     * Why:    amounts span a ₹0 term to ₹50 lakh so the set covers the empty month, the ordinary one
     *         and the one whose commitments dwarf its income — the three shapes the formula behaves
     *         differently in. The buffer varies across its whole legal range so no property is
     *         accidentally proved only at 5%.
     * Result: [CASES] inputs, identical for a given seed.
     * Input:  none. Output: `List<SafeToSpendInput>`.
     */
    private fun months(): List<SafeToSpendInput> {
        val random = Random(SEED)
        return List(CASES) {
            SafeToSpendInput(
                income = Money(random.nextLong(0L, MAX_MINOR)),
                spentToDate = Money(random.nextLong(0L, MAX_MINOR)),
                scheduled = Money(random.nextLong(0L, MAX_MINOR)),
                recurringDue = Money(random.nextLong(0L, MAX_MINOR)),
                goalContributionsRemaining = Money(random.nextLong(0L, MAX_MINOR)),
                inputWindow = WINDOW,
                nowUtcMillis = NOW,
                rules =
                    SafeToSpendRules(
                        bufferPct = random.nextInt(0, MAX_BUFFER_PCT),
                        includeGoalContributions = random.nextBoolean(),
                        floorAtZero = random.nextBoolean(),
                    ),
            )
        }
    }

    private companion object {
        /** Fixed so a failure is re-creatable by re-running the suite (P-08). */
        const val SEED = 5_2026L
        const val CASES = 500
        const val NOW = 1_786_082_400_000L
        const val WINDOW = "2026-08-01..2026-08-31"

        /** ₹50 lakh in paise — comfortably past any real month, and far from `Long` overflow. */
        const val MAX_MINOR = 500_000_000L

        /** Exclusive: `SafeToSpendRules` refuses 100 and above. */
        const val MAX_BUFFER_PCT = 100
    }
}
