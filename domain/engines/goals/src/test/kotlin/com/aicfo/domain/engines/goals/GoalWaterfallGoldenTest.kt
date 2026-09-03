package com.aicfo.domain.engines.goals

import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The golden-file gate for the waterfall (issue 7.3; §21.5, and the acceptance criterion
 * "golden-file test on competing goals").
 *
 * Why:  §21.5 asks engines for "golden-file tests (fixed input snapshot → expected result)", and
 *       this issue's criterion names competing goals in particular. What makes the file worth having
 *       beside [GoalWaterfallEngineTest] is that every record fixes **all seven outputs at once** —
 *       the verdict, both totals, the gap, the leftover, the per-goal split and the three levers. An
 *       allocation that came out right for the wrong reason still fails.
 *
 *       That is not hypothetical here. `₹0.00 allocated` is the correct answer for a goal the money
 *       ran out above, for a goal `RULE-EMERG-FIRST` held at zero, for a month with no surplus and
 *       for a month whose surplus is unknown. Four different situations, one identical figure, and
 *       the user needs a different thing said about each. Only the fields beside the number tell
 *       them apart.
 * What: runs every record in `golden/goal-waterfall.txt` through the real pipeline — [GoalEngine]
 *       then [GoalWaterfallEngine], exactly as `GoalWaterfallRepository` composes them.
 * Result: a change to the allocation fails the build naming the record that caught it.
 * Changelog: 2026-09-03 — Created for issue 7.3.
 *
 * **A gate nobody has watched go red is not a gate.** Records were deliberately mis-labelled and
 * this test confirmed to fail before it was trusted — the same drill the other golden files got, and
 * the lesson `docs/report/2026-07-25-governance-standards-audit.md` recorded.
 */
class GoalWaterfallGoldenTest {
    private val goalEngine = GoalEngineFactory.create()
    private val engine = GoalWaterfallEngineFactory.create()
    private val records: List<GoldenCase> by lazy { loadRecords() }

    /**
     * Input:  the golden file.
     * Output: asserts it was found and still exercises every verdict, every basis and both sides of
     *         the gate. Without this the assertions below would score nothing perfectly if the
     *         resource ever went missing — which is how a coverage gate in this repo once passed at
     *         0%.
     */
    @Test
    fun `the golden file is loaded and exercises every verdict, basis and gate outcome`() {
        assertTrue("fewer records than the file is documented to hold", records.size >= MIN_RECORDS)
        assertEquals(
            "the golden file no longer exercises every feasibility verdict",
            Feasibility.entries.map { it.name }.toSet(),
            records.map { it.feasibility }.toSet(),
        )
        assertEquals(
            "the golden file no longer exercises every surplus basis",
            SurplusBasis.entries.map { it.name }.toSet(),
            records.map { it.basis }.toSet(),
        )
        assertEquals(
            "RULE-EMERG-FIRST must be exercised both firing and letting the goals through",
            setOf(true, false),
            records.map { it.emergencyApplied }.toSet(),
        )
        assertTrue(
            "no record has a null surplus — the UNKNOWN branch is untested",
            records.any { it.surplus == null },
        )
        assertTrue(
            "no record has a negative surplus — a profile spending more than it earns is untested",
            records.any { (it.surplus ?: 0L) < 0L },
        )
        assertTrue("no record competes more than two goals", records.any { it.goals.size >= COMPETING })
    }

    /** Input: every record. Output: fails naming each scenario whose headline figures came out wrong. */
    @Test
    fun `every scenario computes to its expected totals and verdict`() {
        val wrong =
            records.mapNotNull { record ->
                val actual = allocate(record)
                val got =
                    "${actual.feasibility}/${actual.totalRequiredMonthly.minor}/" +
                        "${actual.totalAllocated.minor}/${actual.gapMonthly.minor}/" +
                        "${actual.unallocated.minor}"
                val want =
                    "${record.feasibility}/${record.required}/${record.allocated}/" +
                        "${record.gap}/${record.unallocated}"
                if (got == want) null else "${record.label}: expected $want, got $got"
            }

        assertEquals("$wrong", 0, wrong.size)
    }

    /**
     * Input:  every record.
     * Output: fails naming each scenario whose emergency-fund handling came out wrong.
     *
     * Separated from the totals because it is the one term of the waterfall that comes from another
     * engine, and `RULE-EMERG-FIRST` is `severity: fail` — the rule most expensive to get wrong.
     */
    @Test
    fun `every scenario applies RULE-EMERG-FIRST exactly when it should`() {
        val wrong =
            records.mapNotNull { record ->
                val actual = allocate(record)
                val got = "${actual.emergencyFirstApplied}/${actual.emergencyAllocated.minor}"
                val want = "${record.emergencyApplied}/${record.emergencyAllocated}"
                if (got == want) null else "${record.label}: expected $want, got $got"
            }

        assertEquals("$wrong", 0, wrong.size)
    }

    /** Input: every record. Output: fails naming each scenario whose per-goal split came out wrong. */
    @Test
    fun `every goal gets exactly what the waterfall says it gets`() {
        val wrong =
            records.mapNotNull { record ->
                val got = allocate(record).lines.joinToString(",") { it.allocatedMonthly.minor.toString() }
                val want = record.allocations.joinToString(",")
                if (got == want) null else "${record.label}: expected $want, got $got"
            }

        assertEquals("$wrong", 0, wrong.size)
    }

    /**
     * Input:  every record.
     * Output: fails naming each goal whose three levers came out wrong.
     *
     * FR-GOAL-003 names the levers as part of the requirement, not as decoration: "infeasible plans
     * show the gap **and three levers**". A gap with no way out is a complaint.
     */
    @Test
    fun `every under-funded goal offers the three levers FR-GOAL-003 requires`() {
        val wrong =
            records.mapNotNull { record ->
                val got = allocate(record).lines.joinToString(",") { it.levers.render() }
                val want = record.levers.joinToString(",").ifEmpty { NO_GOALS }
                if (got.ifEmpty { NO_GOALS } == want) null else "${record.label}: expected $want, got $got"
            }

        assertEquals("$wrong", 0, wrong.size)
    }

    /**
     * Input:  every record.
     * Output: asserts the shortfall on each line never disagrees with the levers beside it.
     *
     * The card shows both, so they have to mean the same thing: a goal with a shortfall must offer
     * a way out, and a fully funded one must not offer to fix what is not broken.
     */
    @Test
    fun `a line's shortfall and its levers never disagree`() {
        records.forEach { record ->
            allocate(record).lines.forEach { line ->
                assertEquals(
                    "${record.label}/${line.name}: levers=${line.levers} beside a shortfall of " +
                        "${line.shortfallMonthly}",
                    line.shortfallMonthly == Money.ZERO,
                    line.levers == null,
                )
                assertEquals(
                    "${record.label}/${line.name}: the contribution lever must be the shortfall",
                    line.shortfallMonthly,
                    line.levers?.increaseContributionBy ?: line.shortfallMonthly,
                )
            }
        }
    }

    /** Result: the waterfall for one record, through the real pipeline. Input: [record]. */
    private fun allocate(record: GoldenCase): GoalWaterfall {
        val plan =
            goalEngine.plan(
                GoalPlanInput(
                    goals =
                        record.goals.map { goal ->
                            GoalSpec(
                                id = goal.name,
                                name = goal.name,
                                target = Money(goal.target),
                                targetDate = goal.targetDate,
                                saved = Money(goal.saved),
                            )
                        },
                    today = TODAY,
                ),
            )
        val waterfall =
            engine.allocate(
                GoalWaterfallInput(
                    goals = (plan as Ok).value.goals,
                    monthlySurplus = record.surplus?.let(::Money),
                    surplusBasis = SurplusBasis.valueOf(record.basis),
                    emergencyTopUpMonthly = Money(record.emergencyTopUp),
                    emergencyRunwayMonthsBps = record.runwayBps,
                    emergencyGateMonths = record.gateMonths,
                    today = TODAY,
                ),
            )
        return (waterfall as Ok).value
    }

    /** Result: the fixture's spelling of a line's levers. Input: the receiver, which may be null. */
    private fun GoalLevers?.render(): String =
        if (this == null) {
            FULLY_FUNDED
        } else {
            "${extendByMonths ?: NULL_LEVER}|${reduceTargetTo?.minor ?: NULL_LEVER}|" +
                "${increaseContributionBy.minor}"
        }

    /**
     * Reads `golden/goal-waterfall.txt`.
     * Why:    a resource rather than a Kotlin list, so a reviewer reads the cases without reading
     *         the test, and so adding one costs no code.
     * Result: the records. Input: none. Output: the parsed list; fails if the resource is missing.
     *
     * Parsed to a list of pairs rather than a map, because `goal` repeats within a record and a map
     * would silently keep only the last one — which would turn every competing-goals case into a
     * single-goal case while every assertion still passed.
     */
    private fun loadRecords(): List<GoldenCase> {
        val text =
            requireNotNull(javaClass.getResourceAsStream(GOLDEN_PATH)) {
                "missing golden resource $GOLDEN_PATH"
            }.bufferedReader().readText()
        return text.split("===")
            .map { block -> block.lines().filter { it.trimStart().startsWith("#") }.map(::keyValue) }
            .filter { it.isNotEmpty() }
            .map(GoldenCase::from)
    }

    /** Result: one `# key=value` line split. Input: [line]. Output: the pair. */
    private fun keyValue(line: String): Pair<String, String> {
        val body = line.trimStart().removePrefix("#").trim()
        return body.substringBefore('=').trim() to body.substringAfter('=').trim()
    }

    /** One goal within a record. */
    private data class GoldenGoal(
        val name: String,
        val target: Long,
        val saved: Long,
        val targetDate: LocalDate,
    )

    /** One scenario from the golden file. */
    private data class GoldenCase(
        val label: String,
        val surplus: Long?,
        val basis: String,
        val emergencyTopUp: Long,
        val runwayBps: Int?,
        val gateMonths: Int,
        val goals: List<GoldenGoal>,
        val feasibility: String,
        val emergencyApplied: Boolean,
        val emergencyAllocated: Long,
        val required: Long,
        val allocated: Long,
        val gap: Long,
        val unallocated: Long,
        val allocations: List<Long>,
        val levers: List<String>,
    ) {
        companion object {
            /**
             * Result: a record. Input: [pairs] — the record's key/value lines, in order.
             * Output: [GoldenCase]; throws naming the missing key rather than defaulting an
             *   expectation, so a typo cannot turn an assertion into a tautology.
             */
            fun from(pairs: List<Pair<String, String>>): GoldenCase {
                val fields = pairs.filterNot { it.first == "goal" }.toMap()
                return GoldenCase(
                    label = fields.getValue("label"),
                    surplus = fields.getValue("surplus").takeIf { it != UNKNOWN }?.toLong(),
                    basis = fields.getValue("basis"),
                    emergencyTopUp = fields["ef_topup"]?.toLong() ?: 0L,
                    runwayBps = (fields["ef_runway_bps"] ?: UNKNOWN).takeIf { it != UNKNOWN }?.toInt(),
                    gateMonths = fields["ef_gate_months"]?.toInt() ?: DEFAULT_GATE_MONTHS,
                    goals = pairs.filter { it.first == "goal" }.map { goalFrom(it.second) },
                    feasibility = fields.getValue("expect_feasibility"),
                    emergencyApplied = fields.getValue("expect_ef_applied").toBooleanStrict(),
                    emergencyAllocated = fields.getValue("expect_ef_allocated").toLong(),
                    required = fields.getValue("expect_required").toLong(),
                    allocated = fields.getValue("expect_allocated").toLong(),
                    gap = fields.getValue("expect_gap").toLong(),
                    unallocated = fields.getValue("expect_unallocated").toLong(),
                    allocations = fields.getValue("expect_alloc").splitList().map(String::toLong),
                    levers = fields.getValue("expect_levers").splitList(),
                )
            }

            /** Result: one `name|target|saved|date` field parsed. Input: [spec]. */
            private fun goalFrom(spec: String): GoldenGoal {
                val parts = spec.split('|')
                return GoldenGoal(
                    name = parts[0],
                    target = parts[1].toLong(),
                    saved = parts[2].toLong(),
                    targetDate = LocalDate.parse(parts[3]),
                )
            }

            /** Result: a comma list, or empty for the `none` a record with no goals writes. */
            private fun String.splitList(): List<String> = if (this == NO_GOALS) emptyList() else split(',')
        }
    }

    private companion object {
        const val GOLDEN_PATH = "/golden/goal-waterfall.txt"

        /** The day every record is reckoned from. One instant, so the file is byte-reproducible. */
        val TODAY: LocalDate = LocalDate.parse("2026-09-03")

        /** The file is documented to cover every verdict, every basis and both sides of the gate. */
        const val MIN_RECORDS = 14

        /** More than two goals sharing one surplus — the acceptance criterion's own case. */
        const val COMPETING = 3

        /** RULE-EMERG-FIRST.min_runway_months, as the fixture's default. */
        const val DEFAULT_GATE_MONTHS = 3

        /** The fixture's spelling of a null: an unknown surplus, an unknown runway, a null lever. */
        const val UNKNOWN = "unknown"

        /** The fixture's spelling of an absent list — a record with no goals. */
        const val NO_GOALS = "none"

        /** The fixture's spelling of a lever that does not exist. */
        const val NULL_LEVER = "none"

        /** The fixture's spelling of a line that needs no levers. */
        const val FULLY_FUNDED = "-"
    }
}
