package com.aicfo.domain.engines.quicksetup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps [QuickSetupRules] honest against `ai/rules/rules-kb.json` (CLAUDE.md §6, ADR-0005).
 *
 * Why:  §6 says a financial threshold is a **data row in `ai/`, never a hardcoded number**, and
 *       this engine holds hardcoded numbers — a deliberate deferral recorded in ADR-0005, because
 *       nothing in the app loads `ai/` yet and 2.3 would otherwise have to build that loader
 *       first. A deferral is only acceptable while the duplicate cannot drift, and "someone will
 *       remember to update both" is not a mechanism. This test is the mechanism: edit a threshold
 *       in the rulebook and the build goes red until the engine agrees.
 * What: parses the four cited rules out of the rulebook JSON and asserts every threshold and
 *       version the engine copied still matches.
 * Result: the §6 deferral costs correctness nothing until the loader lands.
 * Changelog: 2026-07-27 — Created for issue 2.3.
 *
 * **Parsed with regex, not a JSON library, on purpose.** `:domain:*` is pure Kotlin with no
 * serialisation dependency (ARC-002), and adding one to a *test* to check five integers would be
 * the tail wagging the dog. The parse is deliberately strict — a rule that cannot be found fails
 * loudly rather than passing vacuously, which is the failure mode this project has already been
 * bitten by once (the governance audit's no-op coverage gate).
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
        assertTrue("RULE-50-30-20 missing from the rulebook", "RULE-50-30-20" in rulebook)
    }

    /** Input: RULE-50-30-20's params. Output: asserts the engine's 50/30/20 defaults match. */
    @Test
    fun `the budget split matches RULE-50-30-20`() {
        val rules = QuickSetupRules()
        val row = ruleBlock("RULE-50-30-20")

        assertEquals(rules.needsPctMax, row.intParam("needs_pct_max"))
        assertEquals(rules.wantsPctMax, row.intParam("wants_pct_max"))
        assertEquals(rules.savingsPctMin, row.intParam("savings_pct_min"))
        assertEquals("1.0", row.version())
    }

    /**
     * Input:  RULE-50-30-20's `metro_preset` block.
     * Output: asserts the flex cap matches. The preset is nested, so the parser has to take the
     *         *second* `needs_pct_max` in the row — a subtlety worth pinning, since silently
     *         reading the first would make this test agree with the wrong number.
     */
    @Test
    fun `the metro flex cap matches RULE-50-30-20's preset`() {
        val rules = QuickSetupRules()
        val preset = ruleBlock("RULE-50-30-20").substringAfter("\"metro_preset\"")

        assertEquals(rules.metroNeedsPctMax, preset.intParam("needs_pct_max"))
        assertTrue(
            "the engine only flexes when the rule says it may",
            "\"auto_flex_to_fixed_load\": true" in ruleBlock("RULE-50-30-20"),
        )
    }

    /** Input: RULE-EMERG-FIRST's params. Output: asserts the 3-month runway matches. */
    @Test
    fun `the emergency runway matches RULE-EMERG-FIRST`() {
        val row = ruleBlock("RULE-EMERG-FIRST")

        assertEquals(QuickSetupRules().emergencyRunwayMonths, row.intParam("min_runway_months"))
        assertEquals("1.0", row.version())
    }

    /** Input: RULE-RUNWAY-M's `clamp_months`. Output: asserts the 3..12 clamp matches. */
    @Test
    fun `the runway clamp matches RULE-RUNWAY-M`() {
        val rules = QuickSetupRules()
        val row = ruleBlock("RULE-RUNWAY-M")
        val clamp = CLAMP.find(row)?.destructured ?: error("clamp_months not found in RULE-RUNWAY-M")

        assertEquals(rules.runwayMinMonths, clamp.component1().toInt())
        assertEquals(rules.runwayMaxMonths, clamp.component2().toInt())
        assertEquals("1.0", row.version())
    }

    /** Input: RULE-EMI-40's params. Output: asserts the 40%/50% obligation bands match. */
    @Test
    fun `the obligation bands match RULE-EMI-40`() {
        val rules = QuickSetupRules()
        val row = ruleBlock("RULE-EMI-40")

        assertEquals(rules.obligationWarnPct, row.intParam("warn_pct"))
        assertEquals(rules.obligationFailPct, row.intParam("fail_pct"))
        assertEquals("1.0", row.version())
    }

    /**
     * Input:  the rule ids the engine cites.
     * Output: asserts each exists and is enabled. A citation pointing at a rule that has been
     *         deprecated or disabled would show the user a reason that is no longer the app's.
     */
    @Test
    fun `every rule the engine cites exists and is enabled`() {
        listOf("RULE-50-30-20", "RULE-EMERG-FIRST", "RULE-RUNWAY-M", "RULE-EMI-40").forEach { id ->
            val row = ruleBlock(id)
            assertTrue("$id is disabled in the rulebook but still cited by the engine", "\"enabled\": true" in row)
        }
    }

    // --- parsing ------------------------------------------------------------------------------

    /**
     * Extracts one rule's JSON object as raw text.
     * Why:    the params are only unambiguous within their own row — `version` and `needs_pct_max`
     *         both appear many times in the file.
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
        val CLAMP = Regex("\"clamp_months\"\\s*:\\s*\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*]")
    }
}
