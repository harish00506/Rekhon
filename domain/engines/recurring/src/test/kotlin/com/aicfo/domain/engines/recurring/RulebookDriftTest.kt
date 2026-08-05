package com.aicfo.domain.engines.recurring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps [RecurringRules] honest against `ai/rules/rules-kb.json` (CLAUDE.md §6, ADR-0005).
 *
 * Why:  §6 says a financial threshold is a **data row in `ai/`, never a hardcoded number**, and
 *       this engine holds hardcoded numbers — the deferral ADR-0005 recorded, because nothing in
 *       the app loads `ai/` yet. A deferral is only acceptable while the duplicate cannot drift,
 *       and "someone will remember to update both" is not a mechanism. This test is the mechanism:
 *       edit `RULE-RECUR-DETECT` in the rulebook and the build goes red until the engine agrees.
 * What: parses the rule out of the rulebook JSON and asserts every threshold and the version the
 *       engine copied still matches.
 * Result: the §6 deferral costs correctness nothing until the loader lands.
 * Changelog: 2026-08-05 — Created for issue 3.7.
 *
 * **Parsed with regex, not a JSON library, and duplicated from `:domain:engines:quicksetup`'s copy
 * on purpose.** `:domain:*` is pure Kotlin with no serialisation dependency (ARC-002), and the
 * alternative to the duplication is a shared test-fixtures module existing solely to hold thirty
 * lines of regex — the tail wagging the dog. The parse is deliberately strict: a rule that cannot
 * be found fails loudly rather than passing vacuously, which is the failure mode this project has
 * already been bitten by once (the governance audit's no-op coverage gate).
 */
class RulebookDriftTest {
    private val rulebook: String by lazy { rulebookFile().readText() }

    /**
     * Input:  the repo's rulebook file.
     * Output: asserts it was found and holds the rule. Without this, every assertion below would
     *         pass vacuously against an empty string if the file ever moved.
     */
    @Test
    fun `the rulebook is where this test thinks it is`() {
        assertTrue("rulebook looks empty or truncated", rulebook.length > 1_000)
        assertTrue("RULE-RECUR-DETECT missing from the rulebook", RULE_ID in rulebook)
    }

    /** Input: the rule's params. Output: asserts the engine's occurrence and amount bands match. */
    @Test
    fun `the match thresholds match RULE-RECUR-DETECT`() {
        val rules = RecurringRules()
        val row = ruleBlock(RULE_ID)

        assertEquals(rules.minOccurrences, row.intParam("min_occurrences"))
        assertEquals(rules.amountTolerancePct, row.intParam("amount_tolerance_pct"))
        assertEquals(RecurringRules.SERIES_MATCH.ruleVersion, row.version())
    }

    /**
     * Input:  the rule's nested `cadence_tolerance_days` block.
     * Output: asserts all three tolerances match. Read from the nested block rather than the whole
     *         row, so a top-level key that happened to share a name could not satisfy it.
     */
    @Test
    fun `the cadence tolerances match RULE-RECUR-DETECT`() {
        val rules = RecurringRules()
        val tolerances = ruleBlock(RULE_ID).substringAfter("\"cadence_tolerance_days\"")

        assertEquals(rules.weeklyToleranceDays, tolerances.intParam("weekly"))
        assertEquals(rules.monthlyToleranceDays, tolerances.intParam("monthly"))
        assertEquals(rules.yearlyToleranceDays, tolerances.intParam("yearly"))
    }

    /**
     * Input:  the rule the engine cites.
     * Output: asserts it exists and is enabled. A citation pointing at a rule that has been
     *         deprecated or disabled would show the user a reason that is no longer the app's.
     */
    @Test
    fun `the cited rule exists and is enabled`() {
        assertTrue(
            "$RULE_ID is disabled in the rulebook but still cited by the engine",
            "\"enabled\": true" in ruleBlock(RULE_ID),
        )
    }

    // --- parsing ------------------------------------------------------------------------------

    /**
     * Extracts one rule's JSON object as raw text.
     * Why:    the params are only unambiguous within their own row — `version` appears on every
     *         rule in the file.
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

    /** Result: the rule's `version`. Input: the receiver — a rule block. Output: [String]. */
    private fun String.version(): String {
        val match = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(this)
        assertNotNull("no version on this rule — AI-ARC-006 requires one", match)
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
        const val RULE_ID = "RULE-RECUR-DETECT"
    }
}
