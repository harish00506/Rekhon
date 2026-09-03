package com.aicfo.domain.engines.goals

import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The contract [GoalWaterfallGoldenTest] cannot express (issue 7.3; §15.1, FR-GOAL-003/005).
 *
 * Why:  the golden file gates *arithmetic over well-formed input*. This gates the parts that are not
 *       arithmetic: what the type refuses to be constructed as, what the provenance carries, that
 *       order is honoured rather than re-derived, and that the engine reads no clock.
 * What: one test per promise the KDoc makes.
 * Result: a change that keeps every figure right while breaking a promise still fails.
 * Changelog: 2026-09-03 — Created for issue 7.3.
 */
class GoalWaterfallEngineTest {
    private val engine = GoalWaterfallEngineFactory.create()

    /**
     * Input:  three goals, a surplus that runs out at the second.
     * Output: asserts the lines come back in input order, matched by id.
     *
     * The single most load-bearing behaviour here. `goal.sort_order` is the one part of this
     * calculation the user controls (FR-GOAL-005), and an engine that re-sorted its own input — by
     * urgency, by size, by anything — would silently overrule the drag they just performed.
     */
    @Test
    fun `lines come back in the order the caller gave, not an order the engine prefers`() {
        val goals =
            listOf(
                projection("far", required = 100_000, remaining = 1_200_000, months = 12),
                projection("near", required = 900_000, remaining = 900_000, months = 1),
                projection("huge", required = 5_000_000, remaining = 60_000_000, months = 12),
            )

        val result = allocate(goals, surplus = 150_000)

        assertEquals(listOf("far", "near", "huge"), result.lines.map { it.goalId })
        assertEquals("the first in the list is filled first", 100_000L, result.lines[0].allocatedMonthly.minor)
        assertEquals("the second takes what is left", 50_000L, result.lines[1].allocatedMonthly.minor)
        assertEquals("the third gets nothing", 0L, result.lines[2].allocatedMonthly.minor)
    }

    /**
     * Input:  a goal the money ran out above, and a goal the gate held.
     * Output: asserts the two zero allocations are distinguishable.
     *
     * Both read ₹0.00 on a card and they call for opposite advice — "fund your buffer first" versus
     * "this one is below the line, reorder or find more". A screen that could not tell them apart
     * would give one of the two pieces of advice at random.
     */
    @Test
    fun `a goal starved by the gate is distinguishable from one starved by the queue`() {
        val goals = listOf(projection("a", required = 900_000), projection("b", required = 900_000))

        val queued = allocate(goals, surplus = 900_000)
        assertFalse("the queue emptied, the gate did not fire", queued.lines[1].blockedByEmergencyFund)
        assertEquals(0L, queued.lines[1].allocatedMonthly.minor)

        val gated = allocate(goals, surplus = 900_000, runwayBps = 10_000, topUp = 100_000)
        assertTrue("RULE-EMERG-FIRST fired", gated.emergencyFirstApplied)
        assertTrue("and it is said on the line", gated.lines[0].blockedByEmergencyFund)
        assertEquals(0L, gated.lines[0].allocatedMonthly.minor)
    }

    /**
     * Input:  a plan where the gate fires, and one where it does not.
     * Output: asserts `RULE-EMERG-FIRST` is cited on **both**.
     *
     * A deliberate departure from issue 7.2's "cite only the rule that fired". A clamp that does not
     * bind changed nothing, so citing it would mislead — but a *gate* is evaluated every time and
     * both of its outcomes decide whether goals are funded at all. `emergencyFirstApplied` is what
     * says which way it went; the citation says the check happened.
     */
    @Test
    fun `RULE-EMERG-FIRST is cited whichever way the gate goes`() {
        val goals = listOf(projection("a", required = 100_000))

        listOf(10_000, 90_000).forEach { runway ->
            val result = allocate(goals, surplus = 500_000, runwayBps = runway, topUp = 50_000)
            assertEquals(
                "the waterfall must name the gate it checked (P-02, AI-ARC-006)",
                listOf(GoalRules.EMERGENCY_FIRST),
                result.provenance.evidence,
            )
        }
    }

    /**
     * Input:  any plan.
     * Output: asserts the engine identity, and that no confidence is claimed.
     *
     * The id is a sub-id of `AI-GOAL` rather than `AI-GOAL` itself, so a stored waterfall and a
     * stored projection can be told apart when their versions diverge (AI-ARC-006). No
     * `confidenceBps`, for `SafeToSpendEngine`'s reason: this is arithmetic over amounts the caller
     * resolved, not an inference.
     */
    @Test
    fun `the result names this engine and claims no confidence`() {
        val result = allocate(listOf(projection("a", required = 100_000)), surplus = 500_000)

        assertEquals("AI-GOAL.waterfall", result.provenance.engineId)
        assertEquals("1.0", result.provenance.engineVersion)
        assertEquals(TODAY.toString(), result.provenance.inputWindow)
        assertNull("arithmetic does not have a confidence", result.provenance.confidenceBps)
    }

    /**
     * Input:  a surplus of zero, and a surplus of null.
     * Output: asserts the two produce different verdicts.
     *
     * Issue 7.2's lesson, restated where it bites next: a zero target congratulated somebody with
     * nothing saved. Here, treating "we have no data" as "you have no money" would tell a user one
     * month into the app that every goal they own is impossible.
     */
    @Test
    fun `an unknown surplus is not a zero surplus`() {
        val goals = listOf(projection("a", required = 100_000))

        assertEquals(Feasibility.INFEASIBLE, allocate(goals, surplus = 0).feasibility)
        assertEquals(Feasibility.UNKNOWN, allocate(goals, surplus = null).feasibility)
    }

    /**
     * Input:  no goals, with a surplus.
     * Output: asserts an empty plan, feasible, with the whole surplus left over.
     *
     * A user who has set no goals has nothing wrong with them; the leftover is the app's cue that
     * the cash is idle, which is `RULE-IDLE-CASH`'s question and not this engine's.
     */
    @Test
    fun `no goals is an empty feasible plan, not an error`() {
        val result = allocate(emptyList(), surplus = 500_000)

        assertEquals(emptyList<GoalAllocation>(), result.lines)
        assertEquals(Feasibility.FEASIBLE, result.feasibility)
        assertEquals(500_000L, result.unallocated.minor)
    }

    /**
     * Input:  the same input twice, and an input whose gate threshold has been moved.
     * Output: asserts identical results, and that the threshold is genuinely applied.
     *
     * P-08: fixed input, fixed output, and no clock read — the engine is handed `today` and
     * `nowUtcMillis` precisely so it cannot reach for one (TIM-001).
     */
    @Test
    fun `the engine is deterministic and moves with the gate it is given`() {
        val goals = listOf(projection("a", required = 100_000))

        val first = allocate(goals, surplus = 500_000, runwayBps = 40_000, topUp = 50_000)
        val second = allocate(goals, surplus = 500_000, runwayBps = 40_000, topUp = 50_000)
        assertEquals(first, second)
        assertFalse("four months clears a three-month gate", first.emergencyFirstApplied)

        val stricter =
            allocate(goals, surplus = 500_000, runwayBps = 40_000, topUp = 50_000, gateMonths = 6)
        assertTrue("four months does not clear a six-month gate", stricter.emergencyFirstApplied)
    }

    /**
     * Input:  inputs that contradict themselves.
     * Output: asserts each is refused at construction.
     *
     * A basis naming a source for an absent figure, or an amount with no source, would put a number
     * on screen that the app could not explain (P-02). Better to be unconstructable than to be
     * unexplainable.
     */
    @Test
    fun `an input whose surplus and basis disagree cannot be constructed`() {
        assertThrows(IllegalArgumentException::class.java) {
            GoalWaterfallInput(
                goals = emptyList(),
                monthlySurplus = null,
                surplusBasis = SurplusBasis.OBSERVED_MEDIAN,
                today = TODAY,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoalWaterfallInput(
                goals = emptyList(),
                monthlySurplus = Money(1),
                surplusBasis = SurplusBasis.NONE,
                today = TODAY,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoalWaterfallInput(
                goals = emptyList(),
                monthlySurplus = Money.ZERO,
                surplusBasis = SurplusBasis.OBSERVED_MEDIAN,
                emergencyTopUpMonthly = Money(-1),
                today = TODAY,
            )
        }
    }

    /**
     * Input:  a hand-built [GoalWaterfall] whose parts do not sum to the surplus.
     * Output: asserts it cannot be constructed.
     *
     * The invariant the whole engine rests on. It lives on the type rather than in a test so that
     * *any* future caller — a migration, a fixture, 7.5's order-of-operations engine — is held to it
     * too, not just this engine's own code path.
     */
    @Test
    fun `a waterfall that does not place exactly what it was given cannot be constructed`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                GoalWaterfall(
                    monthlySurplus = Money(500_000),
                    surplusBasis = SurplusBasis.OBSERVED_MEDIAN,
                    totalRequiredMonthly = Money(100_000),
                    totalAllocated = Money(100_000),
                    gapMonthly = Money.ZERO,
                    feasibility = Feasibility.FEASIBLE,
                    emergencyFirstApplied = false,
                    emergencyAllocated = Money.ZERO,
                    lines = emptyList(),
                    // 500 000 was given; 100 000 placed and 100 000 left over loses 300 000.
                    unallocated = Money(100_000),
                    provenance = ProvenanceFixture.of(GoalRules.EMERGENCY_FIRST),
                )
            }
        assertTrue("$error", "place exactly what it was given" in error.message.orEmpty())
    }

    // --- helpers ------------------------------------------------------------------------------

    /** Result: the waterfall for these goals. Input: the scenario's terms. Output: [GoalWaterfall]. */
    private fun allocate(
        goals: List<GoalProjection>,
        surplus: Long?,
        runwayBps: Int? = CLEAR_RUNWAY_BPS,
        topUp: Long = 0L,
        gateMonths: Int = 3,
    ): GoalWaterfall {
        val result =
            engine.allocate(
                GoalWaterfallInput(
                    goals = goals,
                    monthlySurplus = surplus?.let(::Money),
                    surplusBasis =
                        if (surplus == null) SurplusBasis.NONE else SurplusBasis.OBSERVED_MEDIAN,
                    emergencyTopUpMonthly = Money(topUp),
                    emergencyRunwayMonthsBps = runwayBps,
                    emergencyGateMonths = gateMonths,
                    today = TODAY,
                    nowUtcMillis = NOW,
                ),
            )
        return (result as Ok).value
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.parse("2026-09-03")
        const val NOW = 1_788_000_000_000L

        /** Nine months — comfortably clear of the three-month gate. */
        const val CLEAR_RUNWAY_BPS = 90_000
    }
}
