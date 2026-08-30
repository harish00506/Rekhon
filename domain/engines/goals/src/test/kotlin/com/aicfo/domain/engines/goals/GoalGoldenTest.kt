package com.aicfo.domain.engines.goals

import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The golden-file gate for the goals engine (issue 7.1; §21.5, and the acceptance criteria).
 *
 * Why:  §21.5 asks engines for "golden-file tests (fixed input snapshot → expected result)", and
 *       this issue's acceptance criteria name past-due, zero-target and over-funded explicitly.
 *       What makes it worth having next to [GoalEngineTest] is a **different assertion**: every
 *       record fixes the status, the horizon and the ETA as well as the figure, so a required
 *       monthly that came out right from the wrong branch still fails.
 *
 *       That is not hypothetical. `₹0.00 a month` is the correct answer for an over-funded goal and
 *       for a goal with no target at all, and `the whole remainder` is the correct answer both for a
 *       past-due goal and for one whose date lands inside this month. Only the verdict beside the
 *       figure tells those pairs apart.
 * What: runs every record in `golden/goals.txt` and asserts all five outputs.
 * Result: a change to the formula fails the build naming the record that caught it.
 * Changelog: 2026-08-30 — Created for issue 7.1.
 *
 * **A gate nobody has watched go red is not a gate.** Before this was trusted, records were
 * deliberately mis-labelled and this test was confirmed to fail — the same check the drift tests
 * get, and the lesson `docs/report/2026-07-25-governance-standards-audit.md` recorded.
 */
class GoalGoldenTest {
    private val engine = GoalEngineFactory.create()
    private val records: List<GoldenGoal> by lazy { loadRecords() }

    /**
     * Input:  the golden file.
     * Output: asserts it was found and still exercises every status and every horizon band.
     *         Without this the assertions below would score nothing perfectly if the resource ever
     *         went missing — which is precisely how a coverage gate in this repo once passed at 0%.
     */
    @Test
    fun `the golden file is loaded and exercises every verdict`() {
        assertTrue("fewer records than the file is documented to hold", records.size >= MIN_RECORDS)
        assertEquals(
            "the golden file no longer exercises every status",
            GoalStatus.entries.map { it.name }.toSet(),
            records.map { it.status }.toSet(),
        )
        assertEquals(
            "the golden file no longer exercises every RULE-HORIZON band",
            Horizon.entries.map { it.name }.toSet(),
            records.map { it.horizon }.toSet(),
        )
        assertTrue("no record has an unreachable ETA — the null branch is untested", records.any { it.eta == null })
        assertTrue("no record splits an odd paise", records.any { it.target.minor % ODD_SPLIT_PARTS != 0L })
    }

    /** Input: every record. Output: fails naming each goal whose required monthly came out wrong. */
    @Test
    fun `every goal computes to its expected required monthly`() {
        val wrong =
            records.mapNotNull { record ->
                val actual = project(record).requiredMonthly.minor
                if (actual == record.required) null else "${record.label}: expected ${record.required}, got $actual"
            }

        assertEquals("$wrong", 0, wrong.size)
    }

    /**
     * Input:  every record.
     * Output: fails naming each goal whose verdict came out wrong.
     *
     * The assertion this file exists for: the figure alone cannot distinguish over-funded from
     * no-target, nor past-due from a date inside this month.
     */
    @Test
    fun `every goal computes to its expected months, status, horizon and ETA`() {
        val wrong =
            records.mapNotNull { record ->
                val actual = project(record)
                val got = "${actual.monthsRemaining}/${actual.status}/${actual.horizon}/${actual.etaIsoDate}"
                val want = "${record.months}/${record.status}/${record.horizon}/${record.eta}"
                if (got == want) null else "${record.label}: expected $want, got $got"
            }

        assertEquals("$wrong", 0, wrong.size)
    }

    /**
     * Input:  every record.
     * Output: asserts `onTrack` never disagrees with the shortfall beside it.
     *
     * Two fields that can contradict each other are a bug waiting to be shipped; the card shows
     * both, so they have to mean the same thing.
     */
    @Test
    fun `on-track and the shortfall never disagree`() {
        records.forEach { record ->
            val actual = project(record)
            assertEquals(
                "${record.label}: onTrack=${actual.onTrack} beside a shortfall of ${actual.shortfallMonthly}",
                actual.shortfallMonthly == Money.ZERO,
                actual.onTrack,
            )
        }
    }

    /** Result: the projection for one record. Input: [record]. Output: [GoalProjection]. */
    private fun project(record: GoldenGoal): GoalProjection {
        val plan =
            engine.plan(
                GoalPlanInput(
                    goals =
                        listOf(
                            GoalSpec(
                                id = record.label,
                                name = record.label,
                                target = Money(record.target.minor),
                                targetDate = record.targetDate,
                                saved = Money(record.saved),
                                plannedMonthly = Money(record.planned),
                            ),
                        ),
                    today = TODAY,
                ),
            )
        return (plan as Ok).value.goals.single()
    }

    /**
     * Reads `golden/goals.txt`.
     * Why:    a resource rather than a Kotlin list, so a reviewer reads the cases without reading
     *         the test, and so adding one costs no code.
     * Result: the records. Input: none. Output: the parsed list; fails if the resource is missing.
     */
    private fun loadRecords(): List<GoldenGoal> {
        val text =
            requireNotNull(javaClass.getResourceAsStream(GOLDEN_PATH)) {
                "missing golden resource $GOLDEN_PATH"
            }.bufferedReader().readText()
        return text.split("===")
            .map { block -> block.lines().filter { it.trimStart().startsWith("#") } }
            .filter { it.isNotEmpty() }
            .map { lines -> GoldenGoal.from(lines.associate(::keyValue)) }
    }

    /** Result: one `# key=value` line split. Input: [line]. Output: the pair. */
    private fun keyValue(line: String): Pair<String, String> {
        val body = line.trimStart().removePrefix("#").trim()
        val key = body.substringBefore('=').trim()
        return key to body.substringAfter('=').trim()
    }

    /** One record from the golden file. */
    private data class GoldenGoal(
        val label: String,
        val target: Money,
        val saved: Long,
        val planned: Long,
        val targetDate: LocalDate,
        val required: Long,
        val months: Int,
        val status: String,
        val horizon: String,
        val eta: String?,
    ) {
        companion object {
            /**
             * Result: a record. Input: [fields] — the record's key/value pairs.
             * Output: [GoldenGoal]; throws naming the missing key rather than defaulting an
             *   expectation, so a typo cannot turn an assertion into a tautology.
             */
            fun from(fields: Map<String, String>): GoldenGoal =
                GoldenGoal(
                    label = fields.getValue("label"),
                    target = Money(fields.getValue("target").toLong()),
                    saved = fields["saved"]?.toLong() ?: 0L,
                    planned = fields["planned"]?.toLong() ?: 0L,
                    targetDate = LocalDate.parse(fields.getValue("target_date")),
                    required = fields.getValue("expect_required").toLong(),
                    months = fields.getValue("expect_months").toInt(),
                    status = fields.getValue("expect_status"),
                    horizon = fields.getValue("expect_horizon"),
                    eta = fields.getValue("expect_eta").takeIf { it != "none" },
                )
        }
    }

    private companion object {
        const val GOLDEN_PATH = "/golden/goals.txt"

        /** The day every record is reckoned from. One instant, so the file is byte-reproducible. */
        val TODAY: LocalDate = LocalDate.parse("2026-08-30")

        /** The file is documented to cover every status, every band, and the boundaries between. */
        const val MIN_RECORDS = 12

        /** Three parts is the smallest split that leaves a remainder for a round-rupee target. */
        const val ODD_SPLIT_PARTS = 3L
    }
}
