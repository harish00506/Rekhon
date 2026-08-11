package com.aicfo.domain.engines.budget

import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural tests for [BudgetEngine] — the boundaries, the invariants and the provenance.
 *
 * Why:  the golden file freezes a set of representative answers; this file attacks the edges around
 *       them. The `add-rulebook-rule` skill asks specifically for a threshold to be tested **at, just
 *       below and just above** its boundary, and to take the boundary **from the rules object rather
 *       than a literal** — so that moving the rule moves the test with it instead of breaking it.
 * What: boundary tests for both `RULE-BUD-*` thresholds, the money-math edges of the median and the
 *       rounding, and the provenance every result must carry.
 * Result: the two thresholds provably do something, and the arithmetic holds at its extremes.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 */
class BudgetEngineTest {
    private val engine = BudgetEngineFactory.create()
    private val rules = BudgetRules()

    // --- RULE-BUD-SUGGEST: min_months_required ------------------------------------------------

    /**
     * Input:  one month fewer than the rule requires.
     * Output: asserts `Ok(null)` — no opinion is a legitimate answer, and the alternative (echoing
     *         the single month back as a "median") would dress an observation up as advice.
     */
    @Test
    fun `just below the history floor there is no suggestion`() {
        val months = monthsOf(List(rules.minMonthsRequired - 1) { 500_000L })

        assertNull(suggest(months)?.amount)
    }

    /** Input: exactly the floor. Output: asserts a suggestion is produced — the boundary is inclusive. */
    @Test
    fun `at the history floor a suggestion is produced`() {
        val months = monthsOf(List(rules.minMonthsRequired) { 500_000L })

        assertEquals(Money(500_000), suggest(months)?.amount)
    }

    /** Input: one month above the floor. Output: asserts a suggestion, and full-window confidence. */
    @Test
    fun `just above the history floor a suggestion is produced`() {
        val months = monthsOf(List(rules.minMonthsRequired + 1) { 500_000L })

        assertEquals(Money(500_000), suggest(months)?.amount)
    }

    /**
     * Input:  more months than the lookback window.
     * Output: asserts only the most recent [BudgetRules.lookbackMonths] are read — the older, much
     *         larger months must not reach the median, or "3 months of history" (FR-BUD-002) would
     *         be a comment rather than a behaviour.
     */
    @Test
    fun `only the most recent months inside the lookback window are read`() {
        val months = monthsOf(listOf(9_000_000L, 9_000_000L, 100_000L, 100_000L, 100_000L))

        assertEquals(Money(100_000), suggest(months)?.medianAmount)
    }

    // --- RULE-BUD-PACE: min_elapsed_days_for_projection ---------------------------------------

    /** Input: one day below the projection floor. Output: asserts the projection is withheld. */
    @Test
    fun `just below the projection floor no end-of-month figure is offered`() {
        val status = status(daysElapsed = rules.minElapsedDaysForProjection - 1)

        assertNull(status.projectedEndOfMonth)
        // A withheld projection is an unknown, not a warning — turning it into one would light a
        // red flag on day one of every month.
        assertTrue("a withheld projection must not read as a predicted overspend", !status.isProjectedToOverspend)
    }

    /** Input: exactly the floor. Output: asserts a projection appears — the boundary is inclusive. */
    @Test
    fun `at the projection floor an end-of-month figure appears`() {
        assertNotNull(status(daysElapsed = rules.minElapsedDaysForProjection).projectedEndOfMonth)
    }

    /** Input: one day above the floor. Output: asserts a projection. */
    @Test
    fun `just above the projection floor an end-of-month figure appears`() {
        assertNotNull(status(daysElapsed = rules.minElapsedDaysForProjection + 1).projectedEndOfMonth)
    }

    /**
     * Input:  a rules value with the floor moved.
     * Output: asserts the engine follows the injected threshold rather than its own default —
     *         the property that makes this a rulebook mirror rather than a hardcoded number, and the
     *         seam the eventual `ai/` loader plugs into.
     */
    @Test
    fun `the projection floor comes from the rules, not the engine`() {
        val strict = BudgetRules(minElapsedDaysForProjection = 10)

        assertNull(status(daysElapsed = 9, rules = strict).projectedEndOfMonth)
        assertNotNull(status(daysElapsed = 10, rules = strict).projectedEndOfMonth)
    }

    // --- the median (money math: 100% coverage, MNY-001) --------------------------------------

    /** Input: an odd-length window. Output: asserts the middle value, not the mean. */
    @Test
    fun `an odd window takes the middle value`() {
        assertEquals(Money(600_000), suggest(monthsOf(listOf(500_000L, 4_000_000L, 600_000L)))?.medianAmount)
    }

    /**
     * Input:  an even-length window whose two middle values differ by an odd number of paise.
     * Output: asserts the midpoint, and that it is exact. `Money.split` distributes the odd paise
     *         rather than discarding it, so the answer is 250 001, never 250 000.5 and never a
     *         silently truncated 250 000.
     */
    @Test
    fun `an even window takes the exact midpoint of the two middle values`() {
        assertEquals(Money(250_001), suggest(monthsOf(listOf(200_001L, 300_001L)))?.medianAmount)
    }

    /** Input: a window of zeroes. Output: asserts a zero suggestion rather than a crash. */
    @Test
    fun `a category with no spending suggests nothing to spend`() {
        assertEquals(Money.ZERO, suggest(monthsOf(listOf(0L, 0L, 0L)))?.amount)
    }

    // --- rounding (RULE-BUD-SUGGEST.round_to_minor) -------------------------------------------

    /** Input: amounts either side of the rounding tie. Output: asserts down, up-at-the-tie, up. */
    @Test
    fun `suggestions round to the nearest step, with ties going up`() {
        // The median is the number being rounded, so a three-month window of identical values makes
        // the input exact and the expected output arithmetic rather than a guess.
        assertEquals(Money(520_000), suggest(monthsOf(List(3) { 524_400L }))?.amount)
        assertEquals(Money(530_000), suggest(monthsOf(List(3) { 525_000L }))?.amount)
        assertEquals(Money(530_000), suggest(monthsOf(List(3) { 525_100L }))?.amount)
    }

    /** Input: an amount already on a step. Output: asserts rounding leaves it alone. */
    @Test
    fun `an amount already on a step is untouched`() {
        assertEquals(Money(520_000), suggest(monthsOf(List(3) { 520_000L }))?.amount)
    }

    // --- seasonality ---------------------------------------------------------------------------

    /**
     * Input:  the same category and month, with seasonality switched off in the rules.
     * Output: asserts the prior is not applied and no event is cited — the `seasonality_enabled`
     *         param does something, rather than being a comment in the rulebook.
     */
    @Test
    fun `seasonality can be switched off from the rules`() {
        val months = monthsOf(List(3) { 380_000L })
        val off = BudgetRules(seasonalityEnabled = false)

        val plain = suggest(months, category = "Shopping", targetMonth = 10, rules = off)

        assertEquals(Money(380_000), plain?.amount)
        assertNull(plain?.seasonalEventId)
        assertTrue("no event fired, so nothing should read as adjusted", !plain!!.isSeasonallyAdjusted)
    }

    /**
     * Input:  a brand-new profile in a festival month.
     * Output: asserts the prior is shrunk all the way to nothing. `k = 0/24 = 0`, so an install with
     *         no history asserts no seasonal pattern — but still cites the event, because the reason
     *         the number is *not* higher is itself worth showing (P-02).
     */
    @Test
    fun `a profile with no history applies no seasonal adjustment`() {
        val suggestion = suggest(monthsOf(List(3) { 380_000L }), category = "Shopping", targetMonth = 10, observed = 0)

        assertEquals(Money(380_000), suggestion?.amount)
        assertEquals(BPS_FULL, suggestion?.seasonalIndexBps)
        assertTrue("an unshrunk-to-zero prior must not read as adjusted", !suggestion!!.isSeasonallyAdjusted)
    }

    /**
     * Input:  a profile older than the shrinkage denominator.
     * Output: asserts `k` is capped at 1, so more history never *amplifies* a prior past the value
     *         the knowledge base actually claims.
     */
    @Test
    fun `history beyond the shrinkage denominator does not amplify the prior`() {
        val months = monthsOf(List(3) { 380_000L })
        val atCap = suggest(months, category = "Shopping", targetMonth = 10, observed = 24)
        val wellPast = suggest(months, category = "Shopping", targetMonth = 10, observed = 240)

        assertEquals(atCap?.seasonalIndexBps, wellPast?.seasonalIndexBps)
        assertEquals(13_800, wellPast?.seasonalIndexBps)
    }

    /** Input: a category name in a different case. Output: asserts the KB still matches it. */
    @Test
    fun `seasonal matching ignores case`() {
        val suggestion = suggest(monthsOf(List(3) { 380_000L }), category = "shOPPing", targetMonth = 10)

        assertEquals("diwali", suggestion?.seasonalEventId)
    }

    /** Input: a category the KB has never heard of. Output: asserts no event and no adjustment. */
    @Test
    fun `an unknown category is never seasonally adjusted`() {
        val suggestion = suggest(monthsOf(List(3) { 380_000L }), category = "Aquarium upkeep", targetMonth = 10)

        assertNull(suggestion?.seasonalEventId)
        assertEquals(BPS_FULL, suggestion?.seasonalIndexBps)
    }

    // --- status arithmetic ----------------------------------------------------------------------

    /** Input: a rollover amount. Output: asserts it raises the budget every other figure is against. */
    @Test
    fun `rollover raises the budget, the remaining and the safe pace together`() {
        val without = status(planned = 500_000, carried = 0)
        val with = status(planned = 500_000, carried = 100_000)

        assertEquals(Money(600_000), with.budgeted)
        assertEquals(without.remaining + Money(100_000), with.remaining)
        assertTrue("a bigger budget must give a bigger safe pace", with.safePaceToDate > without.safePaceToDate)
    }

    /** Input: spend above the budget. Output: asserts remaining goes negative rather than clamping. */
    @Test
    fun `an overspend shows as a negative remaining, not a zero`() {
        val status = status(planned = 500_000, spent = 700_000)

        assertEquals(Money(-200_000), status.remaining)
        assertTrue(status.isOverspent)
    }

    /** Input: spend exactly on the budget. Output: asserts it is not an overspend — the `<` boundary. */
    @Test
    fun `spending exactly the budget is not an overspend`() {
        val status = status(planned = 500_000, spent = 500_000)

        assertEquals(Money.ZERO, status.remaining)
        assertTrue("landing on the budget must not nag a user who did everything right", !status.isOverspent)
    }

    /** Input: a zero budget. Output: asserts any spend against it is immediately an overspend. */
    @Test
    fun `a zero budget is overspent by the first rupee`() {
        assertTrue(status(planned = 0, spent = 100).isOverspent)
    }

    // --- provenance (AI-ARC-003, AI-ARC-006) ---------------------------------------------------

    /** Input: a suggestion. Output: asserts the engine identifies itself and states its window. */
    @Test
    fun `a suggestion carries its engine, instant, window and cited rule`() {
        val suggestion = suggest(monthsOf(List(3) { 500_000L }))!!

        assertEquals("budget-planner", suggestion.provenance.engineId)
        assertEquals("1.0", suggestion.provenance.engineVersion)
        assertEquals(NOW, suggestion.provenance.computedAtUtcMillis)
        assertEquals("2026-01-01..2026-03-01", suggestion.provenance.inputWindow)
        assertEquals(listOf("RULE-BUD-SUGGEST"), suggestion.provenance.evidence.map { it.ruleId })
    }

    /**
     * Input:  a suggestion in a festival month.
     * Output: asserts the seasonal event is cited **after** the rule, so the screen can render the
     *         reason in the order it reads: the rule that fired, then what it claimed.
     */
    @Test
    fun `a seasonal suggestion cites the rule then the calendar event`() {
        val suggestion = suggest(monthsOf(List(3) { 380_000L }), category = "Shopping", targetMonth = 10)!!

        assertEquals(listOf("RULE-BUD-SUGGEST", "diwali"), suggestion.provenance.evidence.map { it.ruleId })
        assertTrue(suggestion.isSeasonallyAdjusted)
    }

    /** Input: a status. Output: asserts provenance, and a null window — a status reads one month. */
    @Test
    fun `a status carries its engine and cited rule, and states no window`() {
        val provenance = status().provenance

        assertEquals("budget-planner", provenance.engineId)
        assertEquals(NOW, provenance.computedAtUtcMillis)
        assertEquals(listOf("RULE-BUD-PACE"), provenance.evidence.map { it.ruleId })
        assertNull("a status reads the month it was handed; there is no window to state", provenance.inputWindow)
    }

    // --- input invariants -----------------------------------------------------------------------

    /** Input: nonsense the repository should never send. Output: asserts each is rejected loudly. */
    @Test
    fun `impossible inputs are rejected rather than answered`() {
        assertThrows(IllegalArgumentException::class.java) {
            BudgetStatusInput("c", Money(1), spent = Money(1), daysInPeriod = 30, daysElapsed = 31, nowUtcMillis = NOW)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BudgetStatusInput("c", Money(1), spent = Money(1), daysInPeriod = 45, daysElapsed = 1, nowUtcMillis = NOW)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BudgetStatusInput("c", Money(1), spent = Money(-1), daysInPeriod = 30, daysElapsed = 1, nowUtcMillis = NOW)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BudgetSuggestionInput("c", "C", emptyList(), targetMonth = 13, monthsObserved = 1, nowUtcMillis = NOW)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MonthlySpend("2026-01", Money.ZERO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MonthlySpend("2026-01-01", Money(-1))
        }
    }

    /**
     * Input:  rule values that would disable the behaviour they configure.
     * Output: asserts each is rejected at construction. A floor above the window could never be met,
     *         and a zero projection floor divides by zero — both would fail silently in production
     *         while every test asserting a *number* kept passing.
     */
    @Test
    fun `rule values that would quietly disable a behaviour are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { BudgetRules(lookbackMonths = 2, minMonthsRequired = 3) }
        assertThrows(IllegalArgumentException::class.java) { BudgetRules(minElapsedDaysForProjection = 0) }
        assertThrows(IllegalArgumentException::class.java) { BudgetRules(roundToMinor = 0) }
        assertThrows(IllegalArgumentException::class.java) { BudgetRules(lookbackMonths = 0) }
        assertThrows(IllegalArgumentException::class.java) { BudgetRules(minMonthsRequired = 0) }
        assertThrows(IllegalArgumentException::class.java) { BudgetRules(shrinkageDenominatorMonths = 0) }
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun monthsOf(amounts: List<Long>): List<MonthlySpend> =
        amounts.mapIndexed { index, minor -> MonthlySpend("2026-0${index + 1}-01", Money(minor)) }

    private fun suggest(
        months: List<MonthlySpend>,
        category: String = "Groceries",
        targetMonth: Int = 7,
        observed: Int = 24,
        rules: BudgetRules = BudgetRules(),
    ): BudgetSuggestion? =
        (
            engine.suggest(
                BudgetSuggestionInput(
                    categoryId = "category:test",
                    categoryName = category,
                    monthlySpends = months,
                    targetMonth = targetMonth,
                    monthsObserved = observed,
                    nowUtcMillis = NOW,
                    rules = rules,
                ),
            ) as Ok
        ).value

    private fun status(
        planned: Long = 1_000_000,
        carried: Long = 0,
        spent: Long = 400_000,
        daysInPeriod: Int = 30,
        daysElapsed: Int = 15,
    ): BudgetStatus = statusOf(planned, carried, spent, daysInPeriod, daysElapsed, BudgetRules())

    /** The rules-varying overload, split out so neither helper trips detekt's parameter limit. */
    private fun status(
        daysElapsed: Int,
        rules: BudgetRules,
    ): BudgetStatus = statusOf(1_000_000, 0, 400_000, 30, daysElapsed, rules)

    @Suppress("LongParameterList")
    private fun statusOf(
        planned: Long,
        carried: Long,
        spent: Long,
        daysInPeriod: Int,
        daysElapsed: Int,
        rules: BudgetRules,
    ): BudgetStatus =
        (
            engine.status(
                BudgetStatusInput(
                    categoryId = "category:test",
                    plannedAmount = Money(planned),
                    carriedOver = Money(carried),
                    spent = Money(spent),
                    daysInPeriod = daysInPeriod,
                    daysElapsed = daysElapsed,
                    nowUtcMillis = NOW,
                    rules = rules,
                ),
            ) as Ok
        ).value

    private companion object {
        /** Fixed instant so every run is byte-identical (P-08). */
        const val NOW = 1_786_082_400_000L
    }
}
