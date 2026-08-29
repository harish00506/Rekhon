package com.aicfo.domain.engines.safetospend

import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The behavioural suite for [SafeToSpendEngine] (issue 5.2; §5.2, §14, AI-STS).
 *
 * Why:  §21.5 wants every engine covered normal-case, edge, boundary, empty and error. What this
 *       file asserts that the golden file cannot is the *reasons* — that each term is subtracted
 *       rather than added, that a term the rules switch off actually disappears from the breakdown,
 *       and that the input's guards fire before a nonsense figure can be built.
 * What: one test per branch of `RULE-STS`, plus the invariants the result type promises.
 * Result: a change to the formula fails here with the term that moved.
 * Changelog: 2026-08-16 — Created for issue 5.2.
 */
class SafeToSpendEngineTest {
    private val engine = SafeToSpendEngineFactory.create()

    /**
     * The worked example from the issue's own plan.
     * Input:  ₹80,000 income, ₹12,400 spent, ₹18,000 scheduled, ₹15,000 savings still owed, 5% buffer.
     * Output: asserts the headline is income − buffer − every deduction, to the paise.
     */
    @Test
    fun `the figure is income less the buffer and every commitment`() {
        val result =
            compute(
                income = rupees(80_000),
                spentToDate = rupees(12_400),
                scheduled = rupees(18_000),
                goalContributionsRemaining = rupees(15_000),
            )

        // 80 000 − 4 000 (5%) − 12 400 − 18 000 − 15 000 = 30 600
        assertEquals(rupees(30_600), result.amount)
    }

    /**
     * Input:  a month with nothing spent, nothing due and nothing saved.
     * Output: asserts the figure is income less the buffer alone — the cold-start case a profile
     *         sees on the 1st, and the one that would hide a term wired with the wrong sign.
     */
    @Test
    fun `a month with no commitments leaves income less the buffer`() {
        val result = compute(income = rupees(50_000))

        assertEquals(rupees(47_500), result.amount)
    }

    /**
     * Input:  commitments larger than income.
     * Output: asserts the figure goes negative rather than clamping.
     *
     * Why:    `RULE-STS.floor_at_zero` is false, and this is the assertion that keeps it false. A
     *         user ₹8,000 past the plan needs the number; a clamped ₹0 reads identically to a month
     *         with nothing left and nothing wrong.
     */
    @Test
    fun `an overcommitted month reports its shortfall, not a clamped zero`() {
        val result =
            compute(
                income = rupees(30_000),
                spentToDate = rupees(28_000),
                recurringDue = rupees(8_500),
            )

        // 30 000 − 1 500 − 28 000 − 8 500 = −8 000
        assertEquals(rupees(-8_000), result.amount)
        assertTrue("the shortfall must be signed, not a magnitude", result.amount < Money.ZERO)
    }

    /**
     * Input:  the same month with `floor_at_zero` switched on in the rules.
     * Output: asserts the engine clamps **and names what the clamp is hiding**.
     *
     * Why:    the threshold has to be honoured or it is decoration — this repo has already shipped
     *         one gate that nothing ever ran. And the clamp cannot simply drop the shortfall: the
     *         breakdown would then sum to −₹8,000 beside a headline of ₹0, which is a fiction on a
     *         card whose entire job is to be checkable (P-02).
     */
    @Test
    fun `the floor applies when the rulebook asks for it, and says what it hid`() {
        val result =
            compute(
                income = rupees(30_000),
                spentToDate = rupees(28_000),
                recurringDue = rupees(8_500),
                rules = SafeToSpendRules(floorAtZero = true),
            )

        assertEquals(Money.ZERO, result.amount)
        assertEquals(
            rupees(8_000),
            result.lines.first { it.component == SafeToSpendComponent.SHORTFALL }.amount,
        )
    }

    /**
     * Input:  a positive month with the floor switched on.
     * Output: asserts no shortfall line appears — the clamp engages below zero, not at it.
     */
    @Test
    fun `the floor leaves a solvent month alone`() {
        val result =
            compute(
                income = rupees(50_000),
                spentToDate = rupees(50_000),
                rules = SafeToSpendRules(bufferPct = 0, floorAtZero = true),
            )

        assertEquals(Money.ZERO, result.amount)
        assertNull(
            "a month that lands exactly on zero has hidden nothing",
            result.lines.firstOrNull { it.component == SafeToSpendComponent.SHORTFALL },
        )
    }

    /**
     * Input:  a month with every term non-zero.
     * Output: asserts the breakdown holds one line per term, in `SafeToSpendComponent` order.
     *
     * Why:    §5.2's acceptance criterion is that the card "shows the breakdown/rule that produced
     *         it". A result whose figure was right but whose lines were missing or reordered would
     *         satisfy every amount assertion above and fail the requirement.
     */
    @Test
    fun `every non-zero term appears as its own line, in order`() {
        val result =
            compute(
                income = rupees(80_000),
                spentToDate = rupees(12_400),
                scheduled = rupees(18_000),
                recurringDue = rupees(2_000),
                goalContributionsRemaining = rupees(15_000),
            )

        assertEquals(
            SafeToSpendComponent.entries - SafeToSpendComponent.SHORTFALL,
            result.lines.map { it.component },
        )
        assertEquals(rupees(80_000), result.lines.first { it.component == SafeToSpendComponent.INCOME }.amount)
        assertEquals(rupees(4_000), result.lines.first { it.component == SafeToSpendComponent.BUFFER }.amount)
    }

    /**
     * Input:  a month with nothing scheduled and nothing recurring.
     * Output: asserts the zero terms are absent from the breakdown entirely.
     *
     * Why:    a card listing "Bills due ₹0" and "Scheduled ₹0" is four lines of noise around the one
     *         line that matters, and it invites the user to stop reading the breakdown — which is
     *         the breakdown's whole purpose (P-02).
     */
    @Test
    fun `a term that is zero is left out of the breakdown`() {
        val result = compute(income = rupees(50_000), spentToDate = rupees(9_000))

        assertEquals(
            listOf(SafeToSpendComponent.INCOME, SafeToSpendComponent.BUFFER, SafeToSpendComponent.SPENT),
            result.lines.map { it.component },
        )
    }

    /**
     * Input:  a savings envelope still owed, with `include_goal_contributions` switched off.
     * Output: asserts the term is neither subtracted nor shown.
     */
    @Test
    fun `goal contributions vanish when the rulebook switches them off`() {
        val result =
            compute(
                income = rupees(50_000),
                goalContributionsRemaining = rupees(12_000),
                rules = SafeToSpendRules(includeGoalContributions = false),
            )

        assertEquals(rupees(47_500), result.amount)
        assertNull(result.lines.firstOrNull { it.component == SafeToSpendComponent.GOALS })
    }

    /**
     * Input:  a zero buffer.
     * Output: asserts no buffer line is drawn and nothing is withheld — the boundary of a threshold
     *         whose valid range starts at zero.
     */
    @Test
    fun `a zero buffer withholds nothing and shows nothing`() {
        val result = compute(income = rupees(50_000), rules = SafeToSpendRules(bufferPct = 0))

        assertEquals(rupees(50_000), result.amount)
        assertNull(result.lines.firstOrNull { it.component == SafeToSpendComponent.BUFFER })
    }

    /**
     * Input:  an income whose 5% does not divide into whole paise (₹1,234.57 → 6172.85 paise).
     * Output: asserts the buffer rounds HALF_EVEN to a whole paise, as `Money.percentOf` promises —
     *         the money-math case MNY-001 requires 100% coverage of.
     */
    @Test
    fun `the buffer rounds to whole paise`() {
        val result = compute(income = Money(123_457L))

        // 123 457 x 5% = 6172.85 -> 6173 paise (HALF_EVEN on .85 rounds up to the even-ward whole).
        assertEquals(Money(6_173L), result.lines.first { it.component == SafeToSpendComponent.BUFFER }.amount)
        assertEquals(Money(123_457L - 6_173L), result.amount)
    }

    /**
     * Input:  a well-formed month.
     * Output: asserts AI-ARC-003's provenance is complete and cites `RULE-STS` (P-02).
     */
    @Test
    fun `the result names the engine, the version, the window and the rule`() {
        val result = compute(income = rupees(50_000))

        assertEquals("safe-to-spend", result.provenance.engineId)
        assertEquals("1.0", result.provenance.engineVersion)
        assertEquals(WINDOW, result.provenance.inputWindow)
        assertEquals(NOW, result.provenance.computedAtUtcMillis)
        assertEquals(listOf(SafeToSpendRules.SAFE_TO_SPEND), result.provenance.evidence)
    }

    /**
     * Input:  the same month twice.
     * Output: asserts byte-identical results (P-08). The engine reads no clock and no randomness, so
     *         this is what proves the figure is reproducible rather than merely correct today.
     */
    @Test
    fun `the same month computes to the same answer every time`() {
        val first = compute(income = rupees(80_000), spentToDate = rupees(12_400))
        val second = compute(income = rupees(80_000), spentToDate = rupees(12_400))

        assertEquals(first, second)
    }

    /**
     * Input:  a negative term.
     * Output: asserts the input refuses it.
     *
     * Why:    a negative "spent" would be *added* to Safe-to-Spend, handing the user a refund to
     *         spend twice. The guard is on the input rather than the engine so the failure names the
     *         caller that built the bad term.
     */
    @Test
    fun `a negative term is rejected at the input`() {
        assertThrows(IllegalArgumentException::class.java) {
            input(income = rupees(50_000), spentToDate = rupees(-100))
        }
        assertThrows(IllegalArgumentException::class.java) { input(income = rupees(-1)) }
    }

    /**
     * Input:  a blank window.
     * Output: asserts the input refuses it — AI-ARC-003 requires the window a result was read over,
     *         and a blank one makes the figure unreproducible.
     */
    @Test
    fun `a blank window is rejected at the input`() {
        assertThrows(IllegalArgumentException::class.java) {
            SafeToSpendInput(
                income = rupees(50_000),
                spentToDate = Money.ZERO,
                scheduled = Money.ZERO,
                recurringDue = Money.ZERO,
                goalContributionsRemaining = Money.ZERO,
                inputWindow = "  ",
                nowUtcMillis = NOW,
            )
        }
    }

    /**
     * Input:  a buffer outside 0..99.
     * Output: asserts the rules refuse it. At 100 the whole month's income is withheld, so the card
     *         could never be positive; below zero the "safety margin" adds money.
     */
    @Test
    fun `a buffer that would withhold everything is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { SafeToSpendRules(bufferPct = 100) }
        assertThrows(IllegalArgumentException::class.java) { SafeToSpendRules(bufferPct = -1) }
    }

    /**
     * Input:  an income near `Long.MAX_VALUE` with a deduction that underflows.
     * Output: asserts the engine returns `Err` rather than throwing across the layer boundary
     *         (§21.6) — the one failure path this engine has.
     */
    @Test
    fun `arithmetic that will not fit reports an error instead of throwing`() {
        val outcome =
            engine.compute(
                input(
                    income = Money(Long.MAX_VALUE),
                    // The buffer alone is fine; adding it to the deductions is what overflows.
                    spentToDate = Money(Long.MAX_VALUE),
                    scheduled = Money(Long.MAX_VALUE),
                ),
            )

        assertTrue("an overflow must degrade to an Err, not crash the home screen", outcome.isErr)
    }

    // --- helpers ---------------------------------------------------------------------------------

    /**
     * Runs the engine and unwraps, failing the test on an `Err`.
     * Result: the figure. Input: the terms, all defaulting to nothing. Output: [SafeToSpend].
     */
    @Suppress("LongParameterList") // One parameter per term of the formula; a wrapper would hide them.
    private fun compute(
        income: Money,
        spentToDate: Money = Money.ZERO,
        scheduled: Money = Money.ZERO,
        recurringDue: Money = Money.ZERO,
        goalContributionsRemaining: Money = Money.ZERO,
        rules: SafeToSpendRules = SafeToSpendRules(),
    ): SafeToSpend {
        val outcome: Result<SafeToSpend, *> =
            engine.compute(input(income, spentToDate, scheduled, recurringDue, goalContributionsRemaining, rules))
        assertTrue("the engine errored on a well-formed month", outcome is Ok)
        return (outcome as Ok).value
    }

    /** Result: an input over the fixed window and instant. Input: the terms. Output: [SafeToSpendInput]. */
    @Suppress("LongParameterList")
    private fun input(
        income: Money,
        spentToDate: Money = Money.ZERO,
        scheduled: Money = Money.ZERO,
        recurringDue: Money = Money.ZERO,
        goalContributionsRemaining: Money = Money.ZERO,
        rules: SafeToSpendRules = SafeToSpendRules(),
    ): SafeToSpendInput =
        SafeToSpendInput(
            income = income,
            spentToDate = spentToDate,
            scheduled = scheduled,
            recurringDue = recurringDue,
            goalContributionsRemaining = goalContributionsRemaining,
            inputWindow = WINDOW,
            nowUtcMillis = NOW,
            rules = rules,
        )

    private companion object {
        /** Fixed so every assertion is reproducible (P-08, TIM-001). */
        const val NOW = 1_786_082_400_000L
        const val WINDOW = "2026-08-01..2026-08-31"

        /** Result: the amount in paise. Input: whole rupees. Output: [Money] (MNY-001). */
        fun rupees(whole: Long): Money = Money(whole * 100L)
    }
}
