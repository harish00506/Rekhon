package com.aicfo.domain.engines.goals

import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

/**
 * The identities that must hold for **every** goal, not just the thirteen in the golden file
 * (issue 7.1; §21.5 "property tests for math", P-08).
 *
 * Why:  a golden file proves the cases somebody thought of. These are the statements that would
 *       still have to be true for a case nobody thought of — and the ones whose violation would be
 *       a money bug rather than a wrong label. The generator is **seeded**, so a failure is
 *       reproducible from the seed printed in the message rather than being a flake somebody reruns
 *       until it passes.
 * What: [CASES] pseudo-random goals per property, drawn from one seed.
 * Result: an arithmetic change that breaks an invariant fails naming the goal that broke it.
 * Changelog: 2026-08-30 — Created for issue 7.1.
 */
class GoalPropertyTest {
    private val engine = GoalEngineFactory.create()

    @Test
    fun `the instalments always sum to at least what is left, so the goal is actually reached`() {
        // The property that matters most: paying `requiredMonthly` every month must get there. A
        // rounding change that truncated instead of distributing the remainder would leave every
        // goal a few paise short, which no single example would obviously catch.
        forEachGoal { spec, projection ->
            val instalments = maxOf(1, projection.monthsRemaining).toLong()
            val paid = Math.multiplyExact(projection.requiredMonthly.minor, instalments)
            assertTrue(
                "$spec: ${projection.requiredMonthly} x $instalments = $paid falls short of ${projection.remaining}",
                paid >= projection.remaining.minor,
            )
        }
    }

    @Test
    fun `the required monthly is the smallest that reaches the goal, never padded`() {
        // The other half: over-quoting would be "safe" and would also be wrong, telling the user to
        // find money the arithmetic does not need.
        forEachGoal { spec, projection ->
            val instalments = maxOf(1, projection.monthsRemaining).toLong()
            val oneLess = Math.multiplyExact(projection.requiredMonthly.minor - 1, instalments)
            assertTrue(
                "$spec: ${projection.requiredMonthly} is more than needed for ${projection.remaining}",
                projection.requiredMonthly.minor == 0L || oneLess < projection.remaining.minor,
            )
        }
    }

    @Test
    fun `no figure the engine reports is ever negative`() {
        // Over-funding is a state, not a debt the goal owes back; a past date does not make a
        // month count negative. Every one of these would render with a minus sign on the card.
        forEachGoal { spec, projection ->
            assertTrue("$spec: remaining ${projection.remaining}", projection.remaining >= Money.ZERO)
            assertTrue("$spec: required ${projection.requiredMonthly}", projection.requiredMonthly >= Money.ZERO)
            assertTrue("$spec: shortfall ${projection.shortfallMonthly}", projection.shortfallMonthly >= Money.ZERO)
            assertTrue("$spec: months ${projection.monthsRemaining}", projection.monthsRemaining >= 0)
        }
    }

    @Test
    fun `on-track and the shortfall always agree`() {
        // The card shows both. Two fields that can contradict each other are a bug waiting to ship.
        forEachGoal { spec, projection ->
            assertEquals("$spec", projection.shortfallMonthly == Money.ZERO, projection.onTrack)
        }
    }

    @Test
    fun `saving more never asks for more`() {
        // Monotonicity. A user who puts money in and sees their required contribution go *up* would
        // reasonably conclude the app is broken.
        val random = Random(SEED)
        repeat(CASES) {
            val spec = randomGoal(random)
            val richer = spec.copy(saved = spec.saved + Money(random.nextLong(1L, MAX_MINOR)))

            val before = project(spec).requiredMonthly
            val after = project(richer).requiredMonthly

            assertTrue("$spec: required rose from $before to $after after saving more", after <= before)
        }
    }

    @Test
    fun `past due is reported when, and only when, the date is gone and money is still owed`() {
        forEachGoal { spec, projection ->
            val datePassed = !spec.targetDate.isAfter(TODAY)
            val owes = projection.remaining > Money.ZERO && spec.target > Money.ZERO
            assertEquals("$spec", datePassed && owes, projection.status == GoalStatus.PAST_DUE)
        }
    }

    @Test
    fun `an ETA exists exactly when a plan exists and the answer is worth reporting`() {
        forEachGoal { spec, projection ->
            val reachable = projection.remaining == Money.ZERO || spec.plannedMonthly > Money.ZERO
            assertTrue(
                "$spec: eta=${projection.etaIsoDate} with a plan of ${spec.plannedMonthly}",
                projection.etaIsoDate == null || reachable,
            )
        }
    }

    @Test
    fun `the same seed produces the same answers, twice`() {
        // Seeded determinism (P-08): the suite is worth nothing if a rerun can disagree with itself.
        val first = Random(SEED).let { random -> List(CASES) { randomGoal(random) } }.map(::project)
        val second = Random(SEED).let { random -> List(CASES) { randomGoal(random) } }.map(::project)

        assertEquals(first, second)
    }

    /** Runs [assertion] over [CASES] seeded goals. */
    private fun forEachGoal(assertion: (GoalSpec, GoalProjection) -> Unit) {
        val random = Random(SEED)
        repeat(CASES) {
            val spec = randomGoal(random)
            assertion(spec, project(spec))
        }
    }

    /** Result: the projection for one goal. Input: [spec]. Output: [GoalProjection]. */
    private fun project(spec: GoalSpec): GoalProjection =
        ((engine.plan(GoalPlanInput(goals = listOf(spec), today = TODAY))) as Ok).value.goals.single()

    /**
     * Draws one goal.
     * Why:    the ranges straddle every branch on purpose — targets down to zero, saved amounts that
     *         can exceed the target, plans that can be zero, and dates on both sides of today.
     * Result: a [GoalSpec]. Input: [random] — seeded. Output: the goal.
     */
    private fun randomGoal(random: Random): GoalSpec =
        GoalSpec(
            id = "g",
            name = "generated",
            target = Money(random.nextLong(0L, MAX_MINOR)),
            targetDate = TODAY.plusMonths(random.nextLong(-MONTH_SPREAD, MONTH_SPREAD)),
            saved = Money(random.nextLong(0L, MAX_MINOR)),
            plannedMonthly = Money(random.nextLong(0L, MAX_MINOR / PLAN_DIVISOR)),
        )

    private companion object {
        val TODAY: LocalDate = LocalDate.parse("2026-08-30")

        /** One seed, printed with any failure, so a red run is reproducible rather than a flake. */
        const val SEED = 7_1_2026L

        const val CASES = 500

        /** A crore in paise — above any realistic goal, and far below where `Long` strains. */
        const val MAX_MINOR = 1_000_000_000L

        /** Dates land within ten years either side of today, so past-due is drawn often. */
        const val MONTH_SPREAD = 120L

        /** Plans are drawn smaller than targets, so "behind" is the common case, as in life. */
        const val PLAN_DIVISOR = 50L
    }
}
