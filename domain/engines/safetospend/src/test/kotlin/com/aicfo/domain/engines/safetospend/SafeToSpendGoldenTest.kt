package com.aicfo.domain.engines.safetospend

import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The golden-file gate for Safe-to-Spend (issue 5.2; §21.5, and the acceptance criteria).
 *
 * Why:  §21.5 asks engines for "golden-file tests (fixed input snapshot → expected result)", and
 *       this issue's acceptance criteria name one explicitly. What makes it worth having next to
 *       [SafeToSpendEngineTest] is a **different assertion**: every record fixes the *breakdown* as
 *       well as the figure, so a month whose total came out right from the wrong terms — a buffer
 *       folded into "spent", a scheduled bill counted as recurring — still fails.
 *
 *       That is not hypothetical. Four of the six deductions are plain subtractions of an amount the
 *       caller resolved, so any two of them could be transposed and every total in this file would
 *       still be correct. Only the line list tells them apart.
 * What: runs every record in `golden/safe-to-spend.txt` and asserts the amount and the ordered
 *       components.
 * Result: a change to the formula fails the build naming the record that caught it.
 * Changelog: 2026-08-16 — Created for issue 5.2.
 *
 * **A gate nobody has watched go red is not a gate.** Before this was trusted, records were
 * deliberately mis-labelled and this test was confirmed to fail — the same check the drift tests
 * get, and the lesson `docs/report/2026-07-25-governance-standards-audit.md` recorded.
 */
class SafeToSpendGoldenTest {
    private val engine = SafeToSpendEngineFactory.create()
    private val records: List<GoldenMonth> by lazy { loadRecords() }

    /**
     * Input:  the golden file.
     * Output: asserts it was found and still exercises every component and every rule parameter.
     *         Without this the assertions below would score nothing perfectly if the resource ever
     *         went missing — which is precisely how a coverage gate in this repo once passed at 0%.
     */
    @Test
    fun `the golden file is loaded and exercises every component`() {
        assertTrue("fewer records than the file is documented to hold", records.size >= MIN_RECORDS)
        assertEquals(
            "the golden file no longer exercises every breakdown component",
            SafeToSpendComponent.entries.map { it.name }.toSet(),
            records.flatMap { it.lines }.toSet(),
        )
        assertTrue("no record exercises the floor", records.any { it.floor })
        assertTrue("no record switches goal contributions off", records.any { !it.includeGoals })
        assertTrue("every record uses the default buffer", records.any { it.bufferPct != DEFAULT_BUFFER_PCT })
        assertTrue("no record goes negative — the unclamped branch is untested", records.any { it.expect < 0L })
    }

    /**
     * Input:  every record.
     * Output: fails naming each month whose headline figure came out wrong.
     */
    @Test
    fun `every month computes to its expected figure`() {
        val wrong =
            records.mapNotNull { record ->
                val actual = compute(record).amount.minor
                if (actual == record.expect) null else "${record.label}: expected ${record.expect}, got $actual"
            }

        assertEquals("$wrong", 0, wrong.size)
    }

    /**
     * The assertion this file exists for.
     * Input:  every record.
     * Output: fails naming each month whose breakdown is wrong — **including the ones whose figure
     *         is right**, which is the whole point.
     */
    @Test
    fun `every month is explained by the expected breakdown`() {
        val wrong =
            records.mapNotNull { record ->
                val actual = compute(record).lines.map { it.component.name }
                if (actual == record.lines) null else "${record.label}: expected ${record.lines}, got $actual"
            }

        assertEquals("$wrong", 0, wrong.size)
    }

    // --- running and loading ----------------------------------------------------------------------

    /**
     * Runs the engine over one record.
     * Why:    every record shares one instant and one window, so the whole file is reproducible to
     *         the byte (P-08) — the engine reads no clock (TIM-001), and this is what proves the file
     *         would score the same in a year.
     * Result: the figure. Input: [record]. Output: [SafeToSpend]; fails on an `Err`.
     */
    private fun compute(record: GoldenMonth): SafeToSpend {
        val outcome =
            engine.compute(
                SafeToSpendInput(
                    income = Money(record.income),
                    spentToDate = Money(record.spent),
                    scheduled = Money(record.scheduled),
                    recurringDue = Money(record.recurring),
                    goalContributionsRemaining = Money(record.goals),
                    inputWindow = WINDOW,
                    nowUtcMillis = NOW,
                    rules =
                        SafeToSpendRules(
                            bufferPct = record.bufferPct,
                            includeGoalContributions = record.includeGoals,
                            floorAtZero = record.floor,
                        ),
                ),
            )
        assertTrue("the engine errored on a well-formed record: ${record.label}", outcome is Ok)
        return (outcome as Ok).value
    }

    /**
     * Reads the golden file off the classpath.
     * Why:    a resource rather than a path on disk, so the fixture travels with the test jar and
     *         cannot be found only when the working directory happens to be right.
     * Result: every record. Input: none. Output: `List<GoldenMonth>`; throws if the resource is
     *         missing, which is louder than computing nothing perfectly.
     */
    private fun loadRecords(): List<GoldenMonth> {
        val raw =
            checkNotNull(javaClass.getResourceAsStream(FIXTURE_PATH)) {
                "$FIXTURE_PATH is not on the test classpath"
            }.bufferedReader().readText()
        return raw.split(SEPARATOR)
            .map { block -> block.lines().map(String::trim) }
            .mapNotNull { it.toMonthOrNull() }
    }

    private companion object {
        const val FIXTURE_PATH = "/golden/safe-to-spend.txt"
        const val SEPARATOR = "==="

        /** Fixed so the whole file is reproducible (P-08, TIM-001). */
        const val NOW = 1_786_082_400_000L
        const val WINDOW = "2026-08-01..2026-08-31"

        /** The file is documented as covering every branch; fewer means one was lost in an edit. */
        const val MIN_RECORDS = 12

        /** `RULE-STS.buffer_pct` — the value a record inherits when it does not say. */
        const val DEFAULT_BUFFER_PCT = 5
    }
}

/** A record header: `# key=value`, lower-case key, no spaces around the `=`. See [toMonthOrNull]. */
private val HEADER = Regex("""#\s*([a-z_]+)=(.*)""")

/**
 * One labelled month from the golden file (issue 5.2).
 *
 * Why:    a value rather than a map, so a record missing a required field is a parse failure at load
 *         time instead of a silently skipped case that made the score look better.
 * Result: the element type of the golden file.
 * Changelog: 2026-08-16 — Created for issue 5.2.
 *
 * Input:  [income], [spent], [scheduled], [recurring], [goals] — the terms in paise; [bufferPct],
 *         [includeGoals], [floor] — the rule parameters; [expect] — the fixed headline in paise;
 *         [lines] — the fixed breakdown, in order; [label] — the record as written, so a failure
 *         names the case rather than an index.
 * Output: an immutable value.
 */
private data class GoldenMonth(
    val income: Long,
    val spent: Long,
    val scheduled: Long,
    val recurring: Long,
    val goals: Long,
    val bufferPct: Int,
    val includeGoals: Boolean,
    val floor: Boolean,
    val expect: Long,
    val lines: List<String>,
    val label: String,
)

/**
 * Parses one `# key=value` block into a record.
 * Why:    matched with a regex rather than "starts with # and contains =", the mistake
 *         `:domain:engines:sms`'s first draft made: the file's own prose contains `expect ...` and a
 *         loose split read the documentation as fixtures.
 * Result: the record, or `null` for the file's header block and the blank tail after the last
 *         separator. A block carrying an `expect=` but missing a required field is an **error**
 *         rather than a skip — a truncated record must not quietly shrink the set the gate measures.
 * Input:  the receiver — one block's trimmed lines. Output: `GoldenMonth?`.
 * Changelog: 2026-08-16 — Created for issue 5.2.
 */
private fun List<String>.toMonthOrNull(): GoldenMonth? {
    val headers =
        mapNotNull { HEADER.matchEntire(it) }
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
    val expect = headers["expect"] ?: return null
    val label = headers["label"] ?: "(unlabelled record expecting $expect)"
    return GoldenMonth(
        income = checkNotNull(headers["income"]) { "$label has no income" }.toLong(),
        spent = headers.paise("spent"),
        scheduled = headers.paise("scheduled"),
        recurring = headers.paise("recurring"),
        goals = headers.paise("goals"),
        bufferPct = headers["buffer_pct"]?.toInt() ?: 5,
        includeGoals = headers["include_goals"]?.toBooleanStrict() ?: true,
        floor = headers["floor"]?.toBooleanStrict() ?: false,
        expect = expect.toLong(),
        lines = checkNotNull(headers["lines"]) { "$label fixes no breakdown" }.split(',').map { it.trim() },
        label = label,
    )
}

/** Result: an optional amount, zero when absent. Input: the receiver; [key]. Output: paise. */
private fun Map<String, String>.paise(key: String): Long = this[key]?.toLong() ?: 0L
