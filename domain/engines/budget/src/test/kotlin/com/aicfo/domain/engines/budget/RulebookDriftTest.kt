package com.aicfo.domain.engines.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps [BudgetRules] honest against `ai/rules/rules-kb.json` (CLAUDE.md §6, ADR-0005, ADR-0017).
 *
 * Why:  §6 says a financial threshold is a **data row in `ai/`, never a hardcoded number**, and this
 *       engine holds hardcoded numbers — a deliberate deferral recorded in ADR-0017, because nothing
 *       in the app loads `ai/` yet and 4.4 would otherwise have to build that loader first. A
 *       deferral is only acceptable while the duplicate cannot drift, and "someone will remember to
 *       update both" is not a mechanism. This test is the mechanism: edit a threshold in the
 *       rulebook and the build goes red until the engine agrees.
 * What: parses the four `RULE-BUD-*` rows out of the rulebook JSON and asserts every threshold and
 *       version the engine copied still matches.
 * Result: the §6 deferral costs correctness nothing until the loader lands.
 * Changelog:
 *   2026-08-11 — Created for issue 4.4.
 *   2026-08-13 — Added RULE-BUD-ALERT for issue 4.5 (FR-BUD-004).
 *   2026-08-14 — Added RULE-BUD-REVIEW for issue 4.6 (§5.5), and a second separation tripwire.
 *
 * **Parsed with regex, not a JSON library, on purpose.** `:domain:*` is pure Kotlin with no
 * serialisation dependency (ARC-002), and adding one to a *test* to check five integers would be
 * the tail wagging the dog. The parse is deliberately strict — a rule that cannot be found fails
 * loudly rather than passing vacuously.
 */
class RulebookDriftTest {
    private val rulebook: String by lazy { rulebookFile().readText() }

    /**
     * Input:  the repo's rulebook file.
     * Output: asserts it was found and is non-trivial. Without this, every assertion below would
     *         pass vacuously against an empty string if the file ever moved.
     */
    @Test
    fun `the rulebook is where this test thinks it is`() {
        assertTrue("rulebook looks empty or truncated", rulebook.length > 1_000)
        assertTrue("RULE-BUD-SUGGEST missing from the rulebook", "RULE-BUD-SUGGEST" in rulebook)
        assertTrue("RULE-BUD-PACE missing from the rulebook", "RULE-BUD-PACE" in rulebook)
        assertTrue("RULE-BUD-ALERT missing from the rulebook", "RULE-BUD-ALERT" in rulebook)
        assertTrue("RULE-BUD-REVIEW missing from the rulebook", "RULE-BUD-REVIEW" in rulebook)
    }

    /** Input: the file's `_meta.version`. Output: asserts the engine copied from this revision. */
    @Test
    fun `the engine names the rulebook revision it copied from`() {
        val meta = rulebook.substringBefore("\"rules\"")
        assertEquals(BudgetRules.RULEBOOK_VERSION, meta.version())
    }

    /** Input: RULE-BUD-SUGGEST's params. Output: asserts every suggestion threshold matches. */
    @Test
    fun `the suggestion thresholds match RULE-BUD-SUGGEST`() {
        val rules = BudgetRules()
        val row = ruleBlock("RULE-BUD-SUGGEST")

        assertEquals(rules.lookbackMonths, row.intParam("lookback_months"))
        assertEquals(rules.minMonthsRequired, row.intParam("min_months_required"))
        assertEquals(rules.shrinkageDenominatorMonths, row.intParam("shrinkage_denominator_months"))
        assertEquals(rules.roundToMinor, row.intParam("round_to_minor").toLong())
        assertTrue(
            "the engine only applies seasonality when the rule says it may",
            "\"seasonality_enabled\": true" in row,
        )
        assertEquals(BudgetRules.SUGGESTION.ruleVersion, row.version())
    }

    /** Input: RULE-BUD-PACE's params. Output: asserts the projection floor matches. */
    @Test
    fun `the pace thresholds match RULE-BUD-PACE`() {
        val rules = BudgetRules()
        val row = ruleBlock("RULE-BUD-PACE")

        assertEquals(rules.minElapsedDaysForProjection, row.intParam("min_elapsed_days_for_projection"))
        assertTrue(
            "the engine extrapolates a run rate; a different basis would need a different formula",
            "\"projection_basis\": \"run_rate\"" in row,
        )
        assertEquals(BudgetRules.PACE.ruleVersion, row.version())
    }

    /** Input: RULE-BUD-ALERT's params. Output: asserts both FR-BUD-004 bands match. */
    @Test
    fun `the alert thresholds match RULE-BUD-ALERT`() {
        val rules = BudgetRules()
        val row = ruleBlock("RULE-BUD-ALERT")

        assertEquals(rules.warnPct, row.intParam("warn_pct"))
        assertEquals(rules.exceededPct, row.intParam("exceeded_pct"))
        assertTrue(
            "the engine promises one alert per band per month; the rule must still ask for that",
            "\"notify_once_per_band_per_month\": true" in row,
        )
        assertEquals(BudgetRules.ALERT.ruleVersion, row.version())
    }

    /** Input: RULE-BUD-REVIEW's params. Output: asserts the materiality threshold matches. */
    @Test
    fun `the review threshold matches RULE-BUD-REVIEW`() {
        val rules = BudgetRules()
        val row = ruleBlock("RULE-BUD-REVIEW")

        assertEquals(rules.minVariancePct, row.intParam("min_variance_pct"))
        assertTrue(
            "the review prices its proposals with RULE-BUD-SUGGEST's median; another basis would " +
                "need a different formula and a different citation on every proposal",
            "\"proposal_basis\": \"median\"" in row,
        )
        assertTrue(
            "the review promises one notification per month; the rule must still ask for that",
            "\"review_once_per_month\": true" in row,
        )
        assertEquals(BudgetRules.REVIEW.ruleVersion, row.version())
    }

    /**
     * Input:  the rule ids the engine cites.
     * Output: asserts each exists and is enabled. A citation pointing at a rule that has been
     *         deprecated or disabled would show the user a reason that is no longer the app's.
     */
    @Test
    fun `every rule the engine cites exists and is enabled`() {
        listOf(BudgetRules.SUGGESTION, BudgetRules.PACE, BudgetRules.ALERT, BudgetRules.REVIEW).forEach { citation ->
            val row = ruleBlock(citation.ruleId)
            assertTrue(
                "${citation.ruleId} is disabled in the rulebook but still cited by the engine",
                "\"enabled\": true" in row,
            )
        }
    }

    /**
     * Input:  RULE-BUD-PACE's row.
     * Output: asserts the alert bands are **not** on it.
     *
     * Why:    this test was written for 4.4 to hold FR-BUD-004's bands back until the screen that
     *         explains them existed. 4.5 built that screen — and kept the separation, permanently,
     *         for a different and larger reason (ADR-0019). `RULE-BUD-PACE` is a **shipped row at
     *         version 1.0**. Adding a parameter to it would bump that version, and a version bump is
     *         ADR-0017's trigger 3: the moment any row can exist at two versions, a typed mirror
     *         cannot represent it and the runtime rules loader has to be built first. So the bands
     *         live on their own row, and this test now guards that boundary rather than a schedule.
     *
     *         It is also the right seam on the merits: pace answers "am I on track?", alerting
     *         answers "should this person be interrupted?" — different questions, edited by
     *         different people for different reasons.
     */
    @Test
    fun `the alert bands live on RULE-BUD-ALERT, not RULE-BUD-PACE`() {
        val row = ruleBlock("RULE-BUD-PACE")
        listOf("warn_pct", "alert_pct", "exceeded_pct").forEach { key ->
            assertTrue(
                "'$key' appeared on RULE-BUD-PACE. FR-BUD-004's bands belong to RULE-BUD-ALERT: " +
                    "adding a param here bumps a shipped row's version, which is ADR-0017 trigger 3 " +
                    "and forces the runtime rules loader before this module can compile again",
                "\"$key\"" !in row,
            )
        }
    }

    /**
     * Input:  RULE-BUD-SUGGEST's row.
     * Output: asserts the review's materiality threshold is **not** on it.
     *
     * Why:    the same boundary the test above guards, one issue later and one row over — and the
     *         temptation here is stronger, because the review genuinely *uses* `RULE-BUD-SUGGEST`'s
     *         median to price its proposals, so putting the variance threshold beside it looks tidy.
     *         It is the same trap: `RULE-BUD-SUGGEST` is a shipped row at version 1.0, cited in
     *         provenance stored on every budget the app suggested, and a params change bumps that
     *         version (ADR-0017 trigger 3).
     *
     *         On the merits the split is right too. `RULE-BUD-SUGGEST` answers "what should this
     *         cost?" — a question about money. `RULE-BUD-REVIEW` answers "is this gap worth
     *         mentioning?" — a question about attention, whose parameter a user might well want to
     *         raise while leaving the median exactly where it is.
     */
    @Test
    fun `the review threshold lives on RULE-BUD-REVIEW, not RULE-BUD-SUGGEST`() {
        val row = ruleBlock("RULE-BUD-SUGGEST")
        listOf("min_variance_pct", "variance_pct", "review_once_per_month").forEach { key ->
            assertTrue(
                "'$key' appeared on RULE-BUD-SUGGEST. §5.5's materiality threshold belongs to " +
                    "RULE-BUD-REVIEW: adding a param here bumps a shipped row's version, which is " +
                    "ADR-0017 trigger 3 and forces the runtime rules loader before this module can " +
                    "compile again",
                "\"$key\"" !in row,
            )
        }
    }

    // --- parsing ------------------------------------------------------------------------------

    /**
     * Extracts one rule's JSON object as raw text.
     * Why:    the params are only unambiguous within their own row — `version` appears on every one.
     * Result: the text from this rule's id to the start of the next rule.
     * Input:  [ruleId]. Output: the row's text; fails the test if the id is absent.
     */
    private fun ruleBlock(ruleId: String): String {
        val start = rulebook.indexOf("\"rule_id\": \"$ruleId\"")
        assertTrue("$ruleId is not in the rulebook — the engine cites a rule that no longer exists", start >= 0)
        val next = rulebook.indexOf("\"rule_id\":", start + 1)
        return if (next < 0) rulebook.substring(start) else rulebook.substring(start, next)
    }

    /**
     * Reads one integer parameter out of a rule's text.
     * Result: the value. Input: the receiver — a rule block; [name] — the JSON key.
     * Output: [Int]; fails the test when the key is missing rather than defaulting.
     */
    private fun String.intParam(name: String): Int {
        val match = Regex("\"$name\"\\s*:\\s*(\\d+)").find(this)
        assertNotNull("parameter '$name' not found — the rulebook's shape changed", match)
        return match!!.groupValues[1].toInt()
    }

    /** Result: the block's `version`. Input: the receiver — a rule block or the meta block. */
    private fun String.version(): String {
        val match = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(this)
        assertNotNull("no version here — AI-ARC-006 requires one", match)
        return match!!.groupValues[1]
    }

    /**
     * Finds `ai/rules/rules-kb.json` by walking up from the test's working directory.
     * Why:    Gradle runs JVM tests with the module directory as the working directory, but that is
     *         a default an IDE or a future convention-plugin change can move. Walking up finds the
     *         file from anywhere inside the repo, and fails with a readable message rather than a
     *         `FileNotFoundException` naming a path nobody expected.
     * Result: the rulebook file. Input: none. Output: [File].
     */
    private fun rulebookFile(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = File(directory, RULEBOOK_PATH)
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("Could not find $RULEBOOK_PATH walking up from ${File("").absolutePath}")
    }

    private companion object {
        const val RULEBOOK_PATH = "ai/rules/rules-kb.json"
    }
}
