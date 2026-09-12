package com.aicfo.domain.engines.goals

import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * [GoalEngine] — the cases the golden file cannot express (issue 7.1; §15, AI-GOAL).
 *
 * Why:  `golden/goals.txt` fixes one goal per record, so it says nothing about a batch, about what a
 *       malformed goal does, about the provenance every result must carry, or about whether moving a
 *       rulebook threshold actually moves the answer. Those live here.
 * What: input validation, the batch contract, provenance, the injected thresholds, and the
 *       no-clock guarantee.
 * Result: the parts of the contract a reviewer would otherwise have to take on trust.
 * Changelog: 2026-08-30 — Created for issue 7.1.
 *            2026-09-06 — Issue 7.4: the evidenced/declared split and RULE-PAY-FIRST's anchor.
 */
class GoalEngineTest {
    private val engine = GoalEngineFactory.create()

    // --- the batch contract ---------------------------------------------------------------------

    @Test
    fun `no goals is an empty plan, not an error`() {
        // A user who has set no goals has nothing wrong with them; an Err here would put an error
        // banner on a screen whose correct state is "add your first goal".
        val plan = plan(goals = emptyList())

        assertTrue(plan.goals.isEmpty())
        assertEquals(Money.ZERO, plan.totalRequiredMonthly)
    }

    @Test
    fun `projections come back in the order the goals went in`() {
        // The caller matches them up by id, but order is what a list screen renders, and silently
        // reordering somebody's goals is the kind of thing nobody writes a bug report about.
        val plan =
            plan(
                goals =
                    listOf(
                        goal(id = "g1", target = Money(100_000)),
                        goal(id = "g2", target = Money(200_000)),
                        goal(id = "g3", target = Money(300_000)),
                    ),
            )

        assertEquals(listOf("g1", "g2", "g3"), plan.goals.map { it.goalId })
    }

    @Test
    fun `the total is what Safe-to-Spend subtracts`() {
        // ADR-0021's stand-in replaced: this sum is the real "goal contributions not yet made" term.
        val plan =
            plan(
                goals =
                    listOf(
                        goal(id = "g1", target = Money(120_000), targetDate = MONTHS_12),
                        goal(id = "g2", target = Money(240_000), targetDate = MONTHS_12),
                    ),
            )

        assertEquals(Money(10_000 + 20_000), plan.totalRequiredMonthly)
    }

    // --- what a goal may not be -----------------------------------------------------------------

    @Test
    fun `a negative target is refused rather than turned into a refund`() {
        // Without this, `remaining` would be negative and the "required monthly" a payment *out* of
        // the goal — an amount the UI would render with a minus sign and no explanation.
        val thrown =
            assertThrows(IllegalArgumentException::class.java) {
                goal(target = Money(-1))
            }

        assertTrue("$thrown", "must not be negative" in thrown.message.orEmpty())
    }

    @Test
    fun `a negative saved amount or a negative plan is refused`() {
        assertThrows(IllegalArgumentException::class.java) { goal(saved = Money(-1)) }
        assertThrows(IllegalArgumentException::class.java) { goal(planned = Money(-1)) }
    }

    // --- provenance (AI-ARC-003, AI-ARC-006, P-02) ----------------------------------------------

    @Test
    fun `the plan names the engine, its version and the rule that shaped it`() {
        val plan = plan(goals = listOf(goal()), nowUtcMillis = 1_756_512_000_000L)

        assertEquals("AI-GOAL", plan.provenance.engineId)
        assertEquals(
            "issue 7.4 split `saved` and added an anchor, so the formula's version moved",
            "1.1",
            plan.provenance.engineVersion,
        )
        assertEquals(1_756_512_000_000L, plan.provenance.computedAtUtcMillis)
        assertEquals(listOf(GoalRules.HORIZON), plan.provenance.evidence)
        assertEquals(TODAY.toString(), plan.provenance.inputWindow)
    }

    @Test
    fun `a plan that cannot cite a rule cannot be constructed`() {
        // P-02 as a precondition on the type rather than a convention someone can forget — the same
        // guard SafeToSpend puts on its own result.
        val thrown =
            assertThrows(IllegalArgumentException::class.java) {
                GoalPlan(goals = emptyList(), provenance = provenanceWithNoEvidence())
            }

        assertTrue("$thrown", "names the rule" in thrown.message.orEmpty())
    }

    @Test
    fun `no confidence is claimed, because this is arithmetic and not an inference`() {
        assertNull(plan(goals = listOf(goal())).provenance.confidenceBps)
    }

    // --- the thresholds are injected, not baked in ----------------------------------------------

    @Test
    fun `moving RULE-HORIZON's band moves the answer`() {
        // The seam that proves the mirror is load-bearing: if the engine ignored `rules` and used
        // its own constants, this test would still see SHORT.
        val twoYearsOut = goal(targetDate = TODAY.plusMonths(24))

        val default = plan(goals = listOf(twoYearsOut)).goals.single()
        val widened = plan(goals = listOf(twoYearsOut), rules = GoalRules(shortYearsMax = 1)).goals.single()

        assertEquals(Horizon.SHORT, default.horizon)
        assertEquals(Horizon.HYBRID, widened.horizon)
    }

    @Test
    fun `an inverted band is refused, so the middle bucket can never become unreachable`() {
        val thrown =
            assertThrows(IllegalArgumentException::class.java) {
                GoalRules(shortYearsMax = 5, hybridYearsMax = 3)
            }

        assertTrue("$thrown", "must not be below" in thrown.message.orEmpty())
    }

    @Test
    fun `a non-positive short band is refused`() {
        assertThrows(IllegalArgumentException::class.java) { GoalRules(shortYearsMax = 0) }
    }

    // --- TIM-001: the engine reads no clock ------------------------------------------------------

    @Test
    fun `the same input twice is the same answer, because nothing here reads a clock`() {
        // If any date came from the system rather than from `today`, a test run at a month boundary
        // would produce two different answers — and would do it once a month, in CI, unrepeatably.
        val goals = listOf(goal(), goal(id = "g2", targetDate = MONTHS_12))

        assertEquals(plan(goals = goals), plan(goals = goals))
    }

    // --- issue 7.4: progress is evidenced, declared, or both -------------------------------------

    /**
     * Input:  a goal whose whole progress came from linked movements.
     * Output: asserts the split reports all of it as evidenced and none as declared.
     * Result: the ordinary case once 7.4 has been used — nothing typed, everything pointed at.
     */
    @Test
    fun `a goal funded entirely by linked movements declares nothing`() {
        val projection =
            plan(goals = listOf(goal(saved = Money(50_000), evidenced = Money(50_000)))).goals.single()

        assertEquals(Money(50_000), projection.saved)
        assertEquals(Money(50_000), projection.savedEvidenced)
        assertEquals(Money.ZERO, projection.savedDeclared)
    }

    /**
     * Input:  a goal with nothing linked — every pre-7.4 profile on the day it upgrades.
     * Output: asserts the whole figure reports as declared, which is §15's "ghost progress".
     * Result: an upgrade changes no number and adds one distinction.
     */
    @Test
    fun `an untouched goal is entirely ghost progress`() {
        val projection = plan(goals = listOf(goal(saved = Money(50_000)))).goals.single()

        assertEquals(Money.ZERO, projection.savedEvidenced)
        assertEquals(Money(50_000), projection.savedDeclared)
    }

    /**
     * Input:  a goal with some of each.
     * Output: asserts both halves are reported and that they add to the total.
     * Result: the mixed case §15 asks to be shown as visually distinct — the card needs both figures
     *         to do that, and the engine is what has them.
     */
    @Test
    fun `a part-evidenced goal reports both halves and they reconcile`() {
        val projection =
            plan(goals = listOf(goal(saved = Money(80_000), evidenced = Money(30_000)))).goals.single()

        assertEquals(Money(30_000), projection.savedEvidenced)
        assertEquals(Money(50_000), projection.savedDeclared)
        assertEquals(projection.saved, projection.savedEvidenced + projection.savedDeclared)
    }

    /**
     * Input:  evidenced progress larger than the total.
     * Output: asserts `GoalSpec` refuses it.
     * Result: the guard that keeps the split honest. The caller floors the pair together, so a
     *         declared half can never come out negative — which would be the app inventing a claim.
     */
    @Test
    fun `evidenced progress cannot exceed the total`() {
        val thrown =
            assertThrows(IllegalArgumentException::class.java) {
                goal(saved = Money(10_000), evidenced = Money(10_001))
            }

        assertTrue("$thrown", "cannot exceed total progress" in thrown.message.orEmpty())
    }

    /**
     * Input:  a projection whose two halves do not add up.
     * Output: asserts it cannot be constructed.
     * Result: P-02 as a precondition on the type, the way [GoalPlan] guards its citation. A card
     *         showing a split that did not reconcile would be worse than one showing no split.
     */
    @Test
    fun `a projection whose halves do not reconcile cannot be constructed`() {
        val thrown =
            assertThrows(IllegalArgumentException::class.java) {
                plan(goals = listOf(goal(saved = Money(80_000), evidenced = Money(30_000))))
                    .goals
                    .single()
                    .copy(savedDeclared = Money(1))
            }

        assertTrue("$thrown", "must add up" in thrown.message.orEmpty())
    }

    /**
     * Input:  evidenced progress that is negative — a dedicated account that paid out more than it
     *   took in since it was linked.
     * Output: asserts it is accepted, and that the declared half absorbs it.
     * Result: a real state, not an error. Refusing it would force the repository to lie about what
     *         the ledger says.
     */
    @Test
    fun `negative evidenced progress is a state, not an error`() {
        val projection =
            plan(goals = listOf(goal(saved = Money(40_000), evidenced = Money(-10_000)))).goals.single()

        assertEquals(Money(-10_000), projection.savedEvidenced)
        assertEquals(Money(50_000), projection.savedDeclared)
        assertEquals(Money(40_000), projection.saved)
    }

    // --- issue 7.4: RULE-PAY-FIRST ---------------------------------------------------------------

    /**
     * Input:  a plan with a known salary-credit day.
     * Output: asserts the day reaches every projection and the rule is cited.
     * Result: `RULE-PAY-FIRST` finally has a reader, after naming `AI-GOAL` in `consumed_by` since
     *         the rulebook was written.
     */
    @Test
    fun `a known salary day is carried onto every goal and cites RULE-PAY-FIRST`() {
        val plan = plan(goals = listOf(goal(id = "g1"), goal(id = "g2")), anchorDay = 7)

        assertEquals(listOf(7, 7), plan.goals.map { it.contributionAnchorDay })
        assertEquals(listOf(GoalRules.HORIZON, GoalRules.PAY_FIRST), plan.provenance.evidence)
    }

    /**
     * Input:  a plan with no salary-credit day.
     * Output: asserts nothing is carried and the rule is **not** cited.
     * Result: silence rather than an invented payday (P-03), and an evidence list that names the
     *         rules that fired rather than the rules that exist.
     */
    @Test
    fun `an unknown salary day says nothing and cites nothing`() {
        val plan = plan(goals = listOf(goal()))

        assertNull(plan.goals.single().contributionAnchorDay)
        assertEquals(listOf(GoalRules.HORIZON), plan.provenance.evidence)
    }

    /**
     * Input:  a salary day outside 1..31.
     * Output: asserts the input refuses it.
     * Result: a calendar bound checked where it enters, rather than a projection carrying a day no
     *         month has.
     */
    @Test
    fun `a salary day outside the month is refused`() {
        for (day in listOf(0, 32, -1)) {
            val thrown =
                assertThrows(IllegalArgumentException::class.java) {
                    GoalPlanInput(goals = emptyList(), today = TODAY, contributionAnchorDay = day)
                }
            assertTrue("$day: $thrown", "day of the month" in thrown.message.orEmpty())
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** Result: a plan, unwrapped. Input: the goals, the instant, the thresholds. */
    private fun plan(
        goals: List<GoalSpec>,
        nowUtcMillis: Long = 0L,
        rules: GoalRules = GoalRules(),
        anchorDay: Int? = null,
    ): GoalPlan =
        (
            engine.plan(
                GoalPlanInput(
                    goals = goals,
                    today = TODAY,
                    nowUtcMillis = nowUtcMillis,
                    rules = rules,
                    contributionAnchorDay = anchorDay,
                ),
            ) as Ok
        ).value

    /** Result: an ordinary goal with one field varied. */
    @Suppress("LongParameterList") // One knob per GoalSpec field; every one is varied by a case above.
    private fun goal(
        id: String = "g1",
        target: Money = Money(120_000),
        targetDate: LocalDate = MONTHS_12,
        saved: Money = Money.ZERO,
        planned: Money = Money.ZERO,
        evidenced: Money = Money.ZERO,
    ) = GoalSpec(
        id = id,
        name = "goal $id",
        target = target,
        targetDate = targetDate,
        saved = saved,
        plannedMonthly = planned,
        savedEvidenced = evidenced,
    )

    /** A provenance with no citation — the thing [GoalPlan] must refuse. */
    private fun provenanceWithNoEvidence() =
        com.aicfo.core.model.EngineProvenance(
            engineId = "AI-GOAL",
            engineVersion = "1.0",
            computedAtUtcMillis = 0L,
        )

    private companion object {
        val TODAY: LocalDate = LocalDate.parse("2026-08-30")
        val MONTHS_12: LocalDate = LocalDate.parse("2027-08-30")
    }
}
