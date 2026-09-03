package com.aicfo.domain.engines.goals

import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

/**
 * The identities that must hold for **every** waterfall, not just the fifteen in the golden file
 * (issue 7.3; §21.5 "property tests for math", P-08).
 *
 * Why:  a golden file proves the cases somebody thought of. These are the statements that would
 *       still have to be true for a case nobody thought of — and the ones whose violation would be
 *       a **money** bug rather than a wrong label. An allocation that lost a rupee somewhere in the
 *       middle of a nine-goal list would pass every example test that looked at one goal at a time.
 *       The generator is **seeded**, so a failure is reproducible from the seed rather than being a
 *       flake somebody reruns until it passes.
 * What: [CASES] pseudo-random scenarios per property, drawn from one seed.
 * Result: an arithmetic change that breaks an invariant fails naming the scenario that broke it.
 * Changelog: 2026-09-03 — Created for issue 7.3.
 */
class GoalWaterfallPropertyTest {
    private val engine = GoalWaterfallEngineFactory.create()

    @Test
    fun `the waterfall places exactly what it was given, to the paise`() {
        // The invariant the whole engine rests on. `GoalWaterfall.init` asserts it too, so this
        // property is really asking whether the engine can ever *reach* that constructor with a
        // legal input and be refused — which would be an Err on a well-formed scenario.
        forEachScenario { scenario, result ->
            val distributable = maxOf(0L, scenario.surplus ?: 0L)
            val placed =
                result.emergencyAllocated.minor + result.totalAllocated.minor + result.unallocated.minor
            assertEquals("$scenario", distributable, placed)
        }
    }

    @Test
    fun `no goal is ever given more than it asked for`() {
        // A waterfall fills a claim; it does not overfill one. Over-allocating would take money from
        // a goal further down the list and give it to one that did not need it.
        forEachScenario { scenario, result ->
            result.lines.forEach { line ->
                assertTrue(
                    "$scenario: ${line.name} got ${line.allocatedMonthly} against ${line.requiredMonthly}",
                    line.allocatedMonthly <= line.requiredMonthly,
                )
            }
        }
    }

    @Test
    fun `no figure the waterfall reports is ever negative`() {
        // Every one of these renders on a card. A negative allocation or a negative gap would be a
        // minus sign in front of a rupee amount, which is never the truth here.
        forEachScenario { scenario, result ->
            assertTrue("$scenario: gap ${result.gapMonthly}", result.gapMonthly >= Money.ZERO)
            assertTrue("$scenario: leftover ${result.unallocated}", result.unallocated >= Money.ZERO)
            assertTrue("$scenario: ef ${result.emergencyAllocated}", result.emergencyAllocated >= Money.ZERO)
            result.lines.forEach { line ->
                assertTrue("$scenario: ${line.name}", line.allocatedMonthly >= Money.ZERO)
                assertTrue("$scenario: ${line.name}", line.shortfallMonthly >= Money.ZERO)
            }
        }
    }

    @Test
    fun `a bigger surplus never leaves any goal worse off`() {
        // Monotonicity. The property that catches a fold whose remainder is threaded wrongly: the
        // arithmetic can be right for every individual goal while more money somehow reaches one
        // goal by taking it from another.
        forEachScenario { scenario, result ->
            val richer = allocate(scenario.copy(surplus = (scenario.surplus ?: 0L) + RAISE))
            result.lines.forEachIndexed { index, line ->
                assertTrue(
                    "$scenario: ${line.name} got ${line.allocatedMonthly} with more surplus than " +
                        "${richer.lines[index].allocatedMonthly} with less",
                    richer.lines[index].allocatedMonthly >= line.allocatedMonthly,
                )
            }
        }
    }

    @Test
    fun `reordering the goals moves money between them but never invents or loses any`() {
        // FR-GOAL-005's whole promise: the user drags, the plan changes. What must *not* change is
        // how much there is to go round — a reorder that altered the total would mean the order was
        // secretly an input to the arithmetic rather than to the queue.
        forEachScenario { scenario, result ->
            val reversed = allocate(scenario.copy(goals = scenario.goals.reversed()))
            assertEquals("$scenario", result.totalRequiredMonthly, reversed.totalRequiredMonthly)
            assertEquals(
                "$scenario: reordering changed how much reached the goals in total",
                result.totalAllocated,
                reversed.totalAllocated,
            )
            assertEquals("$scenario", result.emergencyAllocated, reversed.emergencyAllocated)
            assertEquals("$scenario", result.unallocated, reversed.unallocated)
        }
    }

    @Test
    fun `the gap is the sum of the shortfalls, and the shortfalls are the contribution levers`() {
        // Three fields that a card shows side by side. Two of them disagreeing would be a bug the
        // user sees before anybody else does.
        forEachScenario { scenario, result ->
            val shortfalls = result.lines.fold(Money.ZERO) { sum, line -> sum + line.shortfallMonthly }
            assertEquals("$scenario", shortfalls, result.gapMonthly)
            result.lines.forEach { line ->
                assertEquals(
                    "$scenario: ${line.name}'s contribution lever must be its shortfall",
                    line.shortfallMonthly,
                    line.levers?.increaseContributionBy ?: Money.ZERO,
                )
            }
        }
    }

    @Test
    fun `the date lever always reaches the target, and one month less never does`() {
        // FR-GOAL-003's date lever is only useful if it is the *smallest* extension that works.
        // Quoting a month too many is a lie in the safe direction, and still a lie.
        forEachScenario { scenario, result ->
            result.lines.forEachIndexed { index, line ->
                val extension = line.levers?.extendByMonths ?: return@forEachIndexed
                val goal = scenario.goals[index]
                val months = (goal.monthsRemaining + extension).toLong()
                val paid = Math.multiplyExact(line.allocatedMonthly.minor, months)
                assertTrue(
                    "$scenario: ${line.name} still short after $months months",
                    paid >= goal.remaining.minor,
                )
                val oneLess = Math.multiplyExact(line.allocatedMonthly.minor, months - 1)
                assertTrue(
                    "$scenario: ${line.name} would have got there a month sooner",
                    extension == 1 || oneLess < goal.remaining.minor,
                )
            }
        }
    }

    @Test
    fun `RULE-EMERG-FIRST holding means no goal is funded at all`() {
        // `severity: fail` — the rule most expensive to get wrong. A partial application of it would
        // be worse than none, because the card would still say the buffer comes first.
        forEachScenario { scenario, result ->
            if (!result.emergencyFirstApplied) return@forEachScenario
            assertEquals("$scenario", Money.ZERO, result.totalAllocated)
            assertTrue("$scenario", result.lines.all { it.allocatedMonthly == Money.ZERO })
        }
    }

    @Test
    fun `the same seed produces the same answers, twice`() {
        // Seeded determinism (P-08): the suite is worth nothing if a rerun can disagree with itself.
        val first = Random(SEED).let { random -> List(CASES) { randomScenario(random) } }.map(::allocate)
        val second = Random(SEED).let { random -> List(CASES) { randomScenario(random) } }.map(::allocate)

        assertEquals(first, second)
    }

    /** Runs [assertion] over [CASES] seeded scenarios. */
    private fun forEachScenario(assertion: (Scenario, GoalWaterfall) -> Unit) {
        val random = Random(SEED)
        repeat(CASES) {
            val scenario = randomScenario(random)
            assertion(scenario, allocate(scenario))
        }
    }

    /** Result: the waterfall for one scenario. Input: [scenario]. Output: [GoalWaterfall]. */
    private fun allocate(scenario: Scenario): GoalWaterfall {
        val result =
            engine.allocate(
                GoalWaterfallInput(
                    goals = scenario.goals,
                    monthlySurplus = scenario.surplus?.let(::Money),
                    surplusBasis =
                        if (scenario.surplus == null) SurplusBasis.NONE else SurplusBasis.OBSERVED_MEDIAN,
                    emergencyTopUpMonthly = Money(scenario.topUp),
                    emergencyRunwayMonthsBps = scenario.runwayBps,
                    today = TODAY,
                ),
            )
        return (result as Ok).value
    }

    /**
     * Draws one scenario.
     * Why:    the ranges straddle every branch on purpose — zero, negative and unknown surpluses,
     *         runways on both sides of the gate and unknown, zero to eight competing goals, and
     *         required monthlies large enough that the surplus frequently runs out mid-list, which
     *         is where the fold is most likely to be wrong.
     * Result: a [Scenario]. Input: [random] — seeded. Output: the scenario.
     */
    private fun randomScenario(random: Random): Scenario =
        Scenario(
            goals =
                List(random.nextInt(0, MAX_GOALS)) { index ->
                    val required = random.nextLong(0L, MAX_MINOR)
                    val months = random.nextInt(0, MAX_MONTHS)
                    projection(
                        id = "g$index",
                        required = required,
                        remaining = Math.multiplyExact(required, maxOf(1, months).toLong()),
                        months = months,
                        saved = random.nextLong(0L, MAX_MINOR),
                    )
                },
            surplus =
                random.nextLong(-MAX_MINOR, MAX_MINOR * SURPLUS_HEADROOM)
                    .takeIf { random.nextInt(UNKNOWN_ODDS) != 0 },
            topUp = random.nextLong(0L, MAX_MINOR),
            runwayBps =
                random.nextInt(0, MAX_RUNWAY_BPS).takeIf { random.nextInt(UNKNOWN_ODDS) != 0 },
        )

    /** One generated scenario, kept as a value so a failure message can print the whole thing. */
    private data class Scenario(
        val goals: List<GoalProjection>,
        val surplus: Long?,
        val topUp: Long,
        val runwayBps: Int?,
    ) {
        override fun toString(): String =
            "surplus=$surplus topUp=$topUp runway=$runwayBps goals=" +
                goals.joinToString(transform = ::describe)

        /** Result: one goal, short enough to read in a failure message. Input: [goal]. */
        private fun describe(goal: GoalProjection): String =
            "${goal.goalId}(req=${goal.requiredMonthly.minor}," +
                "rem=${goal.remaining.minor},m=${goal.monthsRemaining})"
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.parse("2026-09-03")

        /** One seed, printed with any failure, so a red run is reproducible rather than a flake. */
        const val SEED = 7_3_2026L

        const val CASES = 500

        /** A lakh in paise — small enough that the surplus runs out mid-list often. */
        const val MAX_MINOR = 10_000_000L

        /** Up to eight goals, well past the two or three a real profile holds. */
        const val MAX_GOALS = 9

        /** Zero months (past due) up to five years. */
        const val MAX_MONTHS = 60

        /** Surpluses reach a few times a single goal's claim, so `FEASIBLE` is drawn regularly. */
        const val SURPLUS_HEADROOM = 4L

        /** Runways from nothing to a year, straddling the three-month gate. */
        const val MAX_RUNWAY_BPS = 120_000

        /** One in this many draws is unknown, so the null branches are exercised without dominating. */
        const val UNKNOWN_ODDS = 7

        /** The extra surplus the monotonicity property adds — a whole goal's worth. */
        const val RAISE = MAX_MINOR
    }
}
