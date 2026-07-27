package com.aicfo.domain.engines.quicksetup

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import com.aicfo.core.model.sum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden-file, boundary and validation cases for [QuickSetupEngine] (issue 2.3; FR-ONB-002).
 *
 * Why:  this engine turns three numbers a user typed in thirty seconds into the budget they will
 *       be measured against for months, so the failure that matters is not a crash — it is a
 *       plausible-looking wrong figure. Two shapes of wrong are guarded here specifically. First,
 *       **fabrication**: a field the user left blank must produce nothing at all, never ₹0, because
 *       "I did not say" and "I earn nothing" seed completely different budgets (P-03). Second,
 *       **a budget that quietly does not fit**: when rent exceeds what the 50/30/20 frame allows,
 *       the engine caps at the metro preset and leaves the needs envelope visibly short rather than
 *       inventing room by raiding the savings floor.
 * What: the worked cases from the rulebook, the threshold boundaries, and every rejected input.
 * Result: each figure the onboarding summary shows is pinned to an expected paise value.
 * Changelog: 2026-07-27 — Created for issue 2.3, red before the engine existed.
 *
 * Amounts are written as `<rupees>_00` paise so a reader can check them against the rupee figures
 * in the doc comments without doing arithmetic (MNY-001).
 */
class QuickSetupEngineTest {
    private val engine = QuickSetupEngineFactory.create()

    // --- the ordinary case ------------------------------------------------------------------

    /**
     * Input:  ₹85,000 income, ₹24,000 rent, ₹15,000 savings — rent sits inside the 50% needs band.
     * Output: asserts the unflexed 50/30/20 split, the 3-month emergency target off the needs
     *         envelope, and the obligation ratio in basis points (MNY-002).
     */
    @Test
    fun `an ordinary salary splits 50-30-20 and needs no flex`() {
        val plan = engine.plan(input(income = 85_000_00L, rent = 24_000_00L, savings = 15_000_00L)).expectOk()

        assertEquals(Money(42_500_00L), plan.envelope(BudgetNature.NEED))
        assertEquals(Money(25_500_00L), plan.envelope(BudgetNature.WANT))
        assertEquals(Money(17_000_00L), plan.envelope(BudgetNature.INVEST))
        assertEquals(Money(1_27_500_00L), plan.emergencyFundTarget)
        // 24,000 / 85,000 = 28.235% -> 2823 bps, truncated (never rounded up into a worse verdict).
        assertEquals(2_823, plan.obligationLoadBps)
        assertEquals(ObligationVerdict.WITHIN_LIMIT, plan.obligationVerdict)
    }

    /**
     * Input:  the same ordinary case.
     * Output: asserts all three recurring seeds are produced with the sign convention the
     *         `transactions` table already uses — income positive, obligations negative — so the
     *         repository can write `amount_minor` straight through without re-deciding the sign.
     */
    @Test
    fun `recurring seeds carry the users own figures, signed by direction`() {
        val plan = engine.plan(input(income = 85_000_00L, rent = 24_000_00L, savings = 15_000_00L)).expectOk()

        assertEquals(
            listOf(
                RecurringSeed(RecurringKind.INCOME, Money(85_000_00L), nextDueIsoDate = "2026-08-01"),
                RecurringSeed(RecurringKind.RENT_EMI, Money(-24_000_00L), nextDueIsoDate = "2026-08-01"),
                RecurringSeed(RecurringKind.SAVINGS, Money(-15_000_00L), nextDueIsoDate = "2026-08-01"),
            ),
            plan.recurring,
        )
    }

    // --- the flex (RULE-50-30-20's auto_flex_to_fixed_load) ---------------------------------

    /**
     * Input:  ₹60,000 income against ₹36,000 rent — exactly the metro preset's 60%.
     * Output: asserts needs flexes up to cover the rent, wants absorbs the difference, and the
     *         savings floor is untouched. A high metro rent must not read as a permanent failure,
     *         which is the entire reason the rule carries `auto_flex_to_fixed_load`.
     */
    @Test
    fun `rent above the needs band flexes needs up and wants down`() {
        val plan = engine.plan(input(income = 60_000_00L, rent = 36_000_00L)).expectOk()

        assertEquals(Money(36_000_00L), plan.envelope(BudgetNature.NEED))
        assertEquals(Money(12_000_00L), plan.envelope(BudgetNature.WANT))
        assertEquals(Money(12_000_00L), plan.envelope(BudgetNature.INVEST))
        assertEquals(ObligationVerdict.HARD_FAIL, plan.obligationVerdict)
    }

    /**
     * Input:  ₹50,000 income against ₹40,000 rent — 80%, far past the metro preset's cap.
     * Output: asserts the flex stops at 60% and the needs envelope stays **visibly short of the
     *         rent**. This is the most important case in the file: the tempting behaviour is to
     *         keep flexing until the budget balances, which would silently zero the savings floor
     *         and hand the user a plan that says their situation is fine. It is not fine, and the
     *         honest output is a short envelope plus a hard-fail verdict.
     */
    @Test
    fun `the flex stops at the metro cap rather than raiding the savings floor`() {
        val plan = engine.plan(input(income = 50_000_00L, rent = 40_000_00L)).expectOk()

        assertEquals(Money(30_000_00L), plan.envelope(BudgetNature.NEED))
        assertEquals(Money(10_000_00L), plan.envelope(BudgetNature.WANT))
        assertEquals(Money(10_000_00L), plan.envelope(BudgetNature.INVEST))
        assertTrue(
            "the needs envelope must not pretend to cover the rent",
            plan.envelope(BudgetNature.NEED) < Money(40_000_00L),
        )
        assertEquals(8_000, plan.obligationLoadBps)
        assertEquals(ObligationVerdict.HARD_FAIL, plan.obligationVerdict)
    }

    // --- absent answers stay absent (P-03) ---------------------------------------------------

    /**
     * Input:  income alone.
     * Output: asserts envelopes and the emergency target are still produced, but the obligation
     *         ratio is `null` rather than 0 bps — with no rent figure the question was not answered,
     *         and 0% would read as "no obligations at all", which is a claim the user never made.
     */
    @Test
    fun `income alone still budgets, but claims nothing about obligations`() {
        val plan = engine.plan(input(income = 85_000_00L)).expectOk()

        assertEquals(Money(42_500_00L), plan.envelope(BudgetNature.NEED))
        assertEquals(Money(1_27_500_00L), plan.emergencyFundTarget)
        assertNull(plan.obligationLoadBps)
        assertEquals(ObligationVerdict.UNKNOWN, plan.obligationVerdict)
        assertEquals(listOf(RecurringKind.INCOME), plan.recurring.map { it.kind })
    }

    /**
     * Input:  rent alone, no income.
     * Output: asserts the rent is still captured as a recurring seed, but no envelope and no
     *         emergency target are invented — every one of those needs an income to be a share of.
     */
    @Test
    fun `rent without an income produces a recurring seed and nothing else`() {
        val plan = engine.plan(input(rent = 24_000_00L)).expectOk()

        assertTrue(plan.envelopes.isEmpty())
        assertNull(plan.emergencyFundTarget)
        assertNull(plan.obligationLoadBps)
        assertEquals(listOf(RecurringKind.RENT_EMI), plan.recurring.map { it.kind })
    }

    /**
     * Input:  nothing answered — the skipped step, and also a step the user tabbed through.
     * Output: asserts a wholly empty plan. This is what makes "nothing is fabricated if skipped"
     *         true at the engine rather than relying on a caller remembering not to persist.
     */
    @Test
    fun `an unanswered step produces an empty plan`() {
        val plan = engine.plan(input()).expectOk()

        assertTrue(plan.isEmpty)
        assertTrue(plan.envelopes.isEmpty())
        assertTrue(plan.recurring.isEmpty())
        assertNull(plan.emergencyFundTarget)
        assertEquals(ObligationVerdict.UNKNOWN, plan.obligationVerdict)
        assertTrue("an empty plan cites no rule", plan.provenance.evidence.isEmpty())
    }

    /**
     * Input:  a stored zero, which `SettingsStore` already maps to "unanswered".
     * Output: asserts the engine agrees. A ₹0 income reaching here as a real answer would divide
     *         the obligation ratio by zero; treating it as unanswered is both safe and consistent
     *         with how the value round-trips through the proto.
     */
    @Test
    fun `a zero amount is treated as unanswered, not as a claim of nothing`() {
        val plan = engine.plan(input(income = 0L, rent = 0L, savings = 0L)).expectOk()

        assertTrue(plan.isEmpty)
    }

    // --- boundaries of RULE-EMI-40 -----------------------------------------------------------

    /**
     * Input:  obligation ratios either side of the 40% warn and 50% fail thresholds.
     * Output: asserts each lands in the right band. The rule reads "<= 40%", so 40% itself passes
     *         and 50% itself fails — the two off-by-one errors that would misclassify a user
     *         sitting exactly on a threshold.
     *
     * ₹40,100 rather than ₹40,001 for the first step past the warn band, because the ratio is
     * **truncated** to whole basis points: ₹40,001 of ₹1,00,000 is 4000.1 bps, which truncates back
     * to 4000 and stays within the limit. That is deliberate — the arithmetic never pushes a user
     * into a worse verdict — and pinning it here stops a later "fix" from rounding it up.
     */
    @Test
    fun `the obligation verdict bands are inclusive exactly where the rule says`() {
        val income = 1_00_000_00L
        assertEquals(ObligationVerdict.WITHIN_LIMIT, verdictFor(income, rent = 40_000_00L))
        assertEquals(ObligationVerdict.WITHIN_LIMIT, verdictFor(income, rent = 40_001_00L))
        assertEquals(ObligationVerdict.ABOVE_LIMIT, verdictFor(income, rent = 40_100_00L))
        assertEquals(ObligationVerdict.ABOVE_LIMIT, verdictFor(income, rent = 49_999_00L))
        assertEquals(ObligationVerdict.HARD_FAIL, verdictFor(income, rent = 50_000_00L))
    }

    // --- provenance (AI-ARC-003, P-02) -------------------------------------------------------

    /**
     * Input:  a fully answered step.
     * Output: asserts the plan cites the rules that actually fired, in display order, and stamps
     *         the instant it was handed rather than reading a clock (TIM-001).
     */
    @Test
    fun `the plan cites every rule that fired`() {
        val plan = engine.plan(input(income = 85_000_00L, rent = 24_000_00L)).expectOk()

        assertEquals(
            listOf(
                RuleCitation("RULE-50-30-20", "1.0"),
                RuleCitation("RULE-EMERG-FIRST", "1.0"),
                RuleCitation("RULE-EMI-40", "1.0"),
            ),
            plan.provenance.evidence,
        )
        assertEquals("quick-setup", plan.provenance.engineId)
        assertEquals(NOW_UTC_MILLIS, plan.provenance.computedAtUtcMillis)
    }

    /**
     * Input:  a runway of 24 months, outside RULE-RUNWAY-M's 3..12 clamp.
     * Output: asserts the target is clamped to 12 months **and** that the clamping rule is cited.
     *         A clamp that silently changes the answer without appearing in the evidence would
     *         leave the user's drill-down showing arithmetic that does not reproduce (P-02).
     */
    @Test
    fun `a runway outside the clamp is clamped and the clamping rule is cited`() {
        val rules = QuickSetupRules(emergencyRunwayMonths = 24)
        val plan = engine.plan(input(income = 85_000_00L, rules = rules)).expectOk()

        assertEquals(Money(42_500_00L) * 12, plan.emergencyFundTarget)
        assertTrue(RuleCitation("RULE-RUNWAY-M", "1.0") in plan.provenance.evidence)
    }

    /**
     * Input:  a runway inside the clamp.
     * Output: asserts RULE-RUNWAY-M is **not** cited. Evidence lists the rules that changed the
     *         answer; a clamp that did nothing did not fire, and padding the list with rules that
     *         had no effect is how a reasoning card becomes noise the user learns to ignore.
     */
    @Test
    fun `a runway inside the clamp does not cite the clamping rule`() {
        val plan = engine.plan(input(income = 85_000_00L)).expectOk()

        assertTrue(RuleCitation("RULE-RUNWAY-M", "1.0") !in plan.provenance.evidence)
    }

    // --- determinism (P-08) ------------------------------------------------------------------

    /**
     * Input:  the same input computed twice, and a second engine instance.
     * Output: asserts byte-identical plans. P-08 is what makes a golden-file test meaningful at
     *         all: without it, a passing assertion proves only that this run happened to agree.
     */
    @Test
    fun `the same input always produces the same plan`() {
        val subject = input(income = 85_000_00L, rent = 24_000_00L, savings = 15_000_00L)

        assertEquals(engine.plan(subject).expectOk(), engine.plan(subject).expectOk())
        assertEquals(engine.plan(subject).expectOk(), QuickSetupEngineFactory.create().plan(subject).expectOk())
    }

    // --- rejected input ----------------------------------------------------------------------

    /**
     * Input:  each amount in turn as a negative.
     * Output: asserts the offending field is named. A negative income is not a refund, it is a
     *         parse or UI bug, and naming the field is what lets the screen point at it (§21.6
     *         validation errors carry a field name, never a message).
     */
    @Test
    fun `a negative amount is rejected and names its field`() {
        assertEquals("monthlyIncome", engine.plan(input(income = -1L)).expectValidationField())
        assertEquals("rentOrEmi", engine.plan(input(rent = -1L)).expectValidationField())
        assertEquals("typicalSavings", engine.plan(input(savings = -1L)).expectValidationField())
    }

    /**
     * Input:  a period start that is not an ISO date.
     * Output: asserts it is rejected rather than throwing. The value becomes a stored column and
     *         a due-date calculation; a malformed one would corrupt both (TIM-002).
     */
    @Test
    fun `a malformed period start is rejected`() {
        val malformed = input(income = 85_000_00L).copy(periodStartIsoDate = "01-07-2026")

        assertEquals("periodStartIsoDate", engine.plan(malformed).expectValidationField())
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun verdictFor(
        income: Long,
        rent: Long,
    ): ObligationVerdict = engine.plan(input(income = income, rent = rent)).expectOk().obligationVerdict

    private fun input(
        income: Long? = null,
        rent: Long? = null,
        savings: Long? = null,
        rules: QuickSetupRules = QuickSetupRules(),
    ) = QuickSetupInput(
        monthlyIncome = income?.let(::Money),
        rentOrEmi = rent?.let(::Money),
        typicalSavings = savings?.let(::Money),
        periodStartIsoDate = "2026-07-01",
        nowUtcMillis = NOW_UTC_MILLIS,
        rules = rules,
    )

    private companion object {
        /** 2026-07-27T00:00:00Z — fixed, because a plan that depends on when it ran is not testable. */
        const val NOW_UTC_MILLIS = 1_785_196_800_000L
    }
}

/** Result: the envelope for [nature]. Input: the receiver, [nature]. Output: [Money]. */
internal fun QuickSetupPlan.envelope(nature: BudgetNature): Money = envelopes.first { it.nature == nature }.amount

/** Result: the total of every envelope. Input: the receiver. Output: [Money]. */
internal fun QuickSetupPlan.envelopeTotal(): Money = envelopes.map { it.amount }.sum()

/** Result: the plan, failing the test on `Err`. Input: the receiver. Output: [QuickSetupPlan]. */
internal fun EngineResult.expectOk(): QuickSetupPlan =
    when (this) {
        is Ok -> value
        is Err -> throw AssertionError("expected a plan, got ${error.code}")
    }

/** Result: the rejected field name, failing the test on anything else. Output: [String]. */
internal fun EngineResult.expectValidationField(): String =
    when (this) {
        is Ok -> throw AssertionError("expected a validation failure, got a plan")
        is Err ->
            (error as? AppError.Validation)?.field
                ?: throw AssertionError("expected Validation, got ${error.code}")
    }

/** What every engine call returns. Aliased so the two helpers above fit on a line. */
internal typealias EngineResult = Result<QuickSetupPlan, AppError>
