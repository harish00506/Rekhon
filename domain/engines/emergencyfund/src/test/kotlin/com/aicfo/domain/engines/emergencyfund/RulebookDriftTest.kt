package com.aicfo.domain.engines.emergencyfund

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps [EmergencyFundRules] honest against `ai/rules/rules-kb.json` (CLAUDE.md §6, ADR-0005,
 * ADR-0017, ADR-0034).
 *
 * Why:  §6 says a financial threshold is a **data row in `ai/`, never a hardcoded number**, and this
 *       engine holds hardcoded numbers — the deliberate deferral ADR-0017 records, because nothing
 *       in the app loads `ai/` yet. A deferral is only acceptable while the duplicate cannot drift,
 *       and "someone will remember to update both" is not a mechanism. This test is the mechanism:
 *       edit either row in the rulebook and the build goes red until this module agrees.
 *
 *       **Issue 7.2 found that this mechanism could be skipped.** The rulebook is read at runtime
 *       from the repository root, so it was not a declared input to any Gradle task — a threshold
 *       edit left every drift test in the repo UP-TO-DATE and the build green. The convention plugin
 *       now declares it (`configureRulebookAsTestInput`), which is what makes this file a gate
 *       rather than a test that happens to run sometimes.
 * What: parses both rows out of the rulebook JSON, asserts every parameter, both versions, and that
 *       each row still claims this engine — and asserts the **absence** of the three §10.1 terms
 *       this issue deferred.
 * Result: the §6 deferral costs correctness nothing until the loader lands.
 * Changelog: 2026-09-02 — Created for issue 7.2.
 *
 * **Parsed with regex, not a JSON library, on purpose.** `:domain:*` is pure Kotlin with no
 * serialisation dependency (ARC-002), and adding one to a *test* to check a dozen values would be
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
        assertTrue("rulebook looks empty or truncated", rulebook.length > MIN_RULEBOOK_LENGTH)
        assertTrue("RULE-EMF-MULT missing from the rulebook", "RULE-EMF-MULT" in rulebook)
        assertTrue("RULE-EMF-COACH missing from the rulebook", "RULE-EMF-COACH" in rulebook)
    }

    /** Input: the file's `_meta.version`. Output: asserts the engine copied from this revision. */
    @Test
    fun `the engine names the rulebook revision it copied from`() {
        val meta = rulebook.substringBefore("\"rules\"")
        assertEquals(EmergencyFundRules.RULEBOOK_VERSION, meta.version())
    }

    /** Input: RULE-EMF-MULT's params. Output: asserts every one of them and the cited version. */
    @Test
    fun `the multiplier parameters match RULE-EMF-MULT`() {
        val rules = EmergencyFundRules()
        val row = ruleBlock("RULE-EMF-MULT")

        assertEquals(rules.baseMonths, row.intParam("base_months"))
        assertEquals(rules.cvLowBps, row.intParam("cv_low_bps"))
        assertEquals(rules.cvHighBps, row.intParam("cv_high_bps"))
        assertEquals(rules.cvMidBump, row.intParam("cv_mid_bump"))
        assertEquals(rules.cvHighBump, row.intParam("cv_high_bump"))
        assertEquals(rules.essentialsLookbackMonths, row.intParam("essentials_lookback_months"))
        assertEquals(rules.minMonthsObserved, row.intParam("min_months_observed"))
        assertEquals(
            "the citation stamped into every assessment must name the row's own version",
            EmergencyFundRules.MULTIPLIER.ruleVersion,
            row.version(),
        )
    }

    /** Input: RULE-EMF-COACH's params. Output: asserts both bands and the cited version. */
    @Test
    fun `the coach bands match RULE-EMF-COACH`() {
        val rules = EmergencyFundRules()
        val row = ruleBlock("RULE-EMF-COACH")

        assertEquals(rules.urgentBelowMonths, row.intParam("urgent_below_months"))
        assertEquals(rules.surplusAboveTargetMonths, row.intParam("surplus_above_target_months"))
        assertEquals(
            "the citation stamped into every assessment must name the row's own version",
            EmergencyFundRules.COACH.ruleVersion,
            row.version(),
        )
    }

    /**
     * Input:  RULE-RUNWAY-M's params.
     * Output: asserts the clamp this engine applies is the clamp the row states.
     *
     * The row is **not** this issue's: it shipped with the file, and its `multiplier_source` has
     * named `AI-EMF` since before this engine existed. Issue 7.2 changed no number in it, which is
     * why its version is still 1.0 — it simply became true.
     */
    @Test
    fun `the clamp matches RULE-RUNWAY-M, whose multiplier_source is this engine`() {
        val rules = EmergencyFundRules()
        val row = ruleBlock("RULE-RUNWAY-M")
        val clamp = Regex("\"clamp_months\"\\s*:\\s*\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*]").find(row)
        assertNotNull("clamp_months not found — RULE-RUNWAY-M's shape changed", clamp)

        assertEquals(rules.runwayMinMonths, clamp!!.groupValues[1].toInt())
        assertEquals(rules.runwayMaxMonths, clamp.groupValues[2].toInt())
        assertTrue(
            "RULE-RUNWAY-M no longer names AI-EMF as its multiplier_source, so this engine is " +
                "applying a clamp that has stopped pointing at it",
            "AI-EMF" in row,
        )
        assertEquals(EmergencyFundRules.RUNWAY_CLAMP.ruleVersion, row.version())
    }

    /** Input: both rows. Output: asserts each is enabled and still claims this engine. */
    @Test
    fun `both rows are enabled and still name this engine as their consumer`() {
        listOf("RULE-EMF-MULT", "RULE-EMF-COACH").forEach { ruleId ->
            val row = ruleBlock(ruleId)
            assertTrue("$ruleId is disabled — the engine cites a rule that is switched off", "\"enabled\": true" in row)
            assertTrue("$ruleId no longer names AI-EMF in consumed_by, so the citation is stale", "AI-EMF" in row)
        }
    }

    /**
     * Input:  every rule in the file.
     * Output: asserts §10.1's three deferred multiplier terms are still absent.
     *
     * **The point of this test, and the reason it asserts an absence.** §10.1's M also adds +1 for a
     * single-earner household with dependents, +1 for no health cover, and ±1 for a job-stability
     * self-assessment. No field in this app holds any of the three, so issue 7.2 minted no param for
     * them: a threshold nothing reads looks identical to a threshold that works.
     *
     * If one of these keys appears, someone has added the parameter without the field, or the field
     * without telling [EmergencyFundRules]. Either way the multiplier the user sees would no longer
     * be the multiplier the rulebook describes. Adding them is a deliberate act with its own ADR —
     * the same standard issue 7.1 held itself to when it minted nothing at all.
     */
    @Test
    fun `issue 7-2 deferred three of section 10-1's multiplier terms, and they are still absent`() {
        listOf(
            "dependents_bump",
            "single_earner_bump",
            "no_health_cover_bump",
            "job_stability_bump",
        ).forEach { key ->
            assertTrue(
                "'$key' appeared in the rulebook. §10.1 wants it, but it needs a field to read " +
                    "first — see EmergencyFundRules and ADR-0034 on why 7.2 shipped without it",
                "\"$key\"" !in rulebook,
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
        val match = Regex("\"$name\"\\s*:\\s*(-?\\d+)").find(this)
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

        /** Shorter than this and the file is a stub, not the rulebook — every `in` would pass. */
        const val MIN_RULEBOOK_LENGTH = 5_000
    }
}
