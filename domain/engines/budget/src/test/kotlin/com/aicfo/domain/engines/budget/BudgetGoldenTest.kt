package com.aicfo.domain.engines.budget

import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The golden gate for FR-BUD-002's suggestion math and FR-BUD-003's status math (issue 4.4).
 *
 * Why:  4.4's acceptance criteria name a golden-file test on the suggestion math specifically. Unit
 *       tests prove one number at a time; a golden file proves the *set* of them stays put, which is
 *       what catches the class of bug this engine is exposed to — a seasonal window shifted by one
 *       month, or a shrinkage denominator changed, produces a wholly plausible number in every case
 *       except the ones nobody wrote a unit test for.
 * What: parses `golden/budget.txt` and asserts, per record, the amount, the median, the cited rows
 *       and the derived flags.
 * Result: the arithmetic is frozen; changing it requires changing the fixture, in a diff a reviewer
 *       reads.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 *
 * **The citations are asserted as an ordered list, not a set.** A suggestion that lands on the right
 * rupee by citing the wrong festival is a broken engine that happens to agree today.
 */
class BudgetGoldenTest {
    private val engine = BudgetEngineFactory.create()
    private val records: List<GoldenRecord> by lazy { loadRecords() }

    /**
     * The meta-test, first on purpose.
     *
     * Why:    every assertion below iterates the fixture, so all of them pass vacuously against an
     *         empty list — the exact way this project has already shipped one gate that verified
     *         nothing (governance audit G-01). This test fails when the fixture stops exercising the
     *         behaviour it claims to.
     * Result: asserts the file was found, is non-trivial, and covers both rules, both the seasonal
     *         and unadjusted paths, the no-suggestion path and the withheld-projection path.
     * Input:  the fixture. Output: assertions.
     */
    @Test
    fun `the golden file is loaded and exercises every path`() {
        assertTrue("golden file looks empty or truncated", records.size >= MIN_RECORDS)

        val cited = records.flatMap { it.cite }.toSet()
        assertEquals(
            "the fixture must exercise both rules this engine cites",
            setOf("RULE-BUD-SUGGEST", "RULE-BUD-PACE"),
            cited.filter { it.startsWith("RULE-") }.toSet(),
        )

        val suggestions = records.filter { it.kind == "suggest" }
        assertTrue("no seasonally adjusted record", suggestions.any { it.expectEvent != null })
        assertTrue("no unadjusted record", suggestions.any { it.expectEvent == null && it.expectAmount != null })
        assertTrue("no below-the-floor record", suggestions.any { it.expectAmount == null })
        assertTrue(
            "no record covers a window that wraps the year end — the case four KB events depend on",
            suggestions.any { it.expectEvent == "wedding_season" || it.expectEvent == "onam_pongal" },
        )

        val statuses = records.filter { it.kind == "status" }
        assertTrue("no withheld-projection record", statuses.any { it.expectProjected == null })
        assertTrue("no overspent record", statuses.any { it.expectOverspent })
    }

    /** Input: every `kind=suggest` record. Output: asserts the suggested amount and the median. */
    @Test
    fun `every suggestion lands on the expected rupee`() {
        val wrong =
            records.filter { it.kind == "suggest" }.mapNotNull { record ->
                val actual = (engine.suggest(record.toSuggestionInput()) as Ok).value
                val amount = actual?.amount?.minor
                val median = actual?.medianAmount?.minor
                when {
                    amount != record.expectAmount ->
                        "${record.case}: amount expected ${record.expectAmount}, was $amount"
                    median != record.expectMedian ->
                        "${record.case}: median expected ${record.expectMedian}, was $median"
                    else -> null
                }
            }
        assertEquals("$wrong", 0, wrong.size)
    }

    /** Input: every `kind=suggest` record. Output: asserts the seasonal event and confidence. */
    @Test
    fun `every suggestion cites the expected seasonal event and confidence`() {
        val wrong =
            records.filter { it.kind == "suggest" }.mapNotNull { record ->
                val actual = (engine.suggest(record.toSuggestionInput()) as Ok).value
                val event = actual?.seasonalEventId
                val confidence = actual?.provenance?.confidenceBps
                when {
                    event != record.expectEvent -> "${record.case}: event expected ${record.expectEvent}, was $event"
                    confidence != record.expectConfidence ->
                        "${record.case}: confidence expected ${record.expectConfidence}, was $confidence"
                    else -> null
                }
            }
        assertEquals("$wrong", 0, wrong.size)
    }

    /** Input: every `kind=status` record. Output: asserts all four FR-BUD-003 figures. */
    @Test
    fun `every status reports the expected figures`() {
        val wrong =
            records.filter { it.kind == "status" }.mapNotNull { record ->
                val actual = (engine.status(record.toStatusInput()) as Ok).value
                when {
                    actual.budgeted.minor != record.expectBudgeted ->
                        "${record.case}: budgeted expected ${record.expectBudgeted}, was ${actual.budgeted.minor}"
                    actual.remaining.minor != record.expectRemaining ->
                        "${record.case}: remaining expected ${record.expectRemaining}, was ${actual.remaining.minor}"
                    actual.safePaceToDate.minor != record.expectSafePace ->
                        "${record.case}: safe pace expected ${record.expectSafePace}, " +
                            "was ${actual.safePaceToDate.minor}"
                    actual.projectedEndOfMonth?.minor != record.expectProjected ->
                        "${record.case}: projection expected ${record.expectProjected}, " +
                            "was ${actual.projectedEndOfMonth?.minor}"
                    else -> null
                }
            }
        assertEquals("$wrong", 0, wrong.size)
    }

    /** Input: every `kind=status` record. Output: asserts the three derived flags the screen reads. */
    @Test
    fun `every status derives the expected flags`() {
        val wrong =
            records.filter { it.kind == "status" }.mapNotNull { record ->
                val actual = (engine.status(record.toStatusInput()) as Ok).value
                when {
                    actual.isOverspent != record.expectOverspent ->
                        "${record.case}: overspent expected ${record.expectOverspent}, was ${actual.isOverspent}"
                    actual.isAheadOfPace != record.expectAhead ->
                        "${record.case}: ahead expected ${record.expectAhead}, was ${actual.isAheadOfPace}"
                    actual.isProjectedToOverspend != record.expectWillOverspend ->
                        "${record.case}: will-overspend expected ${record.expectWillOverspend}, " +
                            "was ${actual.isProjectedToOverspend}"
                    else -> null
                }
            }
        assertEquals("$wrong", 0, wrong.size)
    }

    /** Input: every record. Output: asserts the cited rows, **in order** (P-02, AI-ARC-006). */
    @Test
    fun `every record cites the expected rows in order`() {
        val wrong =
            records.mapNotNull { record ->
                val actual =
                    if (record.kind == "suggest") {
                        (engine.suggest(record.toSuggestionInput()) as Ok).value?.provenance?.evidence.orEmpty()
                    } else {
                        (engine.status(record.toStatusInput()) as Ok).value.provenance.evidence
                    }.map { it.ruleId }
                if (actual != record.cite) "${record.case}: cited $actual, expected ${record.cite}" else null
            }
        assertEquals("$wrong", 0, wrong.size)
    }

    // --- parsing --------------------------------------------------------------------------------

    /**
     * One fixture record.
     * Why:    a named type so a failure message can say which case broke rather than which index.
     * Input:  the fields documented in the fixture header. Output: an immutable value.
     */
    private data class GoldenRecord(
        val kind: String,
        val case: String,
        val fields: Map<String, String>,
    ) {
        val cite: List<String> = fields["cite"].orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val expectAmount: Long? = fields.longOrNull("expect_amount")
        val expectMedian: Long? = fields.longOrNull("expect_median")
        val expectEvent: String? = fields["expect_event"]?.takeUnless { it == NONE }
        val expectConfidence: Int? = fields.longOrNull("expect_conf")?.toInt()
        val expectBudgeted: Long? = fields.longOrNull("expect_budgeted")
        val expectRemaining: Long? = fields.longOrNull("expect_remaining")
        val expectSafePace: Long? = fields.longOrNull("expect_safe_pace")
        val expectProjected: Long? = fields.longOrNull("expect_projected")
        val expectOverspent: Boolean = fields["expect_overspent"].toBoolean()
        val expectAhead: Boolean = fields["expect_ahead"].toBoolean()
        val expectWillOverspend: Boolean = fields["expect_will_overspend"].toBoolean()

        fun toSuggestionInput(): BudgetSuggestionInput =
            BudgetSuggestionInput(
                categoryId = "category:${case.hashCode()}",
                categoryName = required("category"),
                monthlySpends =
                    required("months").split(",").mapIndexed { index, minor ->
                        // The dates only have to be distinct, ordered and real — the engine reads them
                        // for the provenance window, never for arithmetic (TIM-001).
                        MonthlySpend("2026-0${index + 1}-01", Money(minor.trim().toLong()))
                    },
                targetMonth = required("target_month").toInt(),
                monthsObserved = required("observed").toInt(),
                nowUtcMillis = NOW,
            )

        fun toStatusInput(): BudgetStatusInput =
            BudgetStatusInput(
                categoryId = "category:${case.hashCode()}",
                plannedAmount = Money(required("planned").toLong()),
                carriedOver = Money(required("carried").toLong()),
                spent = Money(required("spent").toLong()),
                daysInPeriod = required("days").toInt(),
                daysElapsed = required("elapsed").toInt(),
                nowUtcMillis = NOW,
            )

        private fun required(key: String): String =
            checkNotNull(fields[key]) { "record '$case' is missing required field '$key'" }

        private fun Map<String, String>.longOrNull(key: String): Long? = this[key]?.takeUnless { it == NONE }?.toLong()
    }

    /**
     * Reads the fixture off the classpath and splits it into records.
     * Why:    the parse is deliberately strict — `matchEntire` on a tight regex, so a prose line can
     *         never be read as a field. A loose split once read documentation as fixtures in
     *         `:domain:engines:sms`, and the gate went green on data it had invented.
     * Result: the records. Input: none. Output: a list; fails the test if the file is missing.
     */
    private fun loadRecords(): List<GoldenRecord> {
        val text =
            checkNotNull(javaClass.getResourceAsStream(GOLDEN_PATH)) {
                "$GOLDEN_PATH is not on the test classpath"
            }.bufferedReader().readText()

        return text.split(RECORD_SEPARATOR).mapNotNull { block ->
            val fields =
                block.lineSequence()
                    .mapNotNull { HEADER.matchEntire(it.trim()) }
                    .associate { it.groupValues[1] to it.groupValues[2].trim() }
            val kind = fields["kind"] ?: return@mapNotNull null
            GoldenRecord(kind, checkNotNull(fields["case"]) { "record with kind=$kind has no case label" }, fields)
        }
    }

    private companion object {
        const val GOLDEN_PATH = "/golden/budget.txt"

        /** Fixed instant so every run is byte-identical (P-08). */
        const val NOW = 1_786_082_400_000L

        const val NONE = "none"

        /** Below this the fixture has stopped covering the paths the meta-test names. */
        const val MIN_RECORDS = 12

        val RECORD_SEPARATOR = Regex("^===\\s*$", RegexOption.MULTILINE)
        val HEADER = Regex("""#\s*([a-z_]+)=(.*)""")
    }
}
