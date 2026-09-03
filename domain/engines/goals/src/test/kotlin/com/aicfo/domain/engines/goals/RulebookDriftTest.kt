package com.aicfo.domain.engines.goals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps [GoalRules] honest against `ai/rules/rules-kb.json` (CLAUDE.md §6, ADR-0005, ADR-0017).
 *
 * Why:  §6 says a financial threshold is a **data row in `ai/`, never a hardcoded number**, and this
 *       engine holds hardcoded numbers — the deliberate deferral ADR-0017 records, because nothing
 *       in the app loads `ai/` yet. A deferral is only acceptable while the duplicate cannot drift,
 *       and "someone will remember to update both" is not a mechanism. This test is the mechanism:
 *       edit `RULE-HORIZON` in the rulebook and the build goes red until this module agrees.
 * What: parses `RULE-HORIZON` out of the rulebook JSON, asserts both bands and its version, and
 *       asserts the row still claims this engine as a consumer.
 * Result: the §6 deferral costs correctness nothing until the loader lands.
 * Changelog: 2026-08-30 — Created for issue 7.1.
 *
 * **Parsed with regex, not a JSON library, on purpose.** `:domain:*` is pure Kotlin with no
 * serialisation dependency (ARC-002), and adding one to a *test* to check two values would be the
 * tail wagging the dog. The parse is deliberately strict — a rule that cannot be found fails loudly
 * rather than passing vacuously.
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
        assertTrue("RULE-HORIZON missing from the rulebook", "RULE-HORIZON" in rulebook)
    }

    /** Input: the file's `_meta.version`. Output: asserts the engine copied from this revision. */
    @Test
    fun `the engine names the rulebook revision it copied from`() {
        val meta = rulebook.substringBefore("\"rules\"")
        assertEquals(GoalRules.RULEBOOK_VERSION, meta.version())
    }

    /** Input: RULE-HORIZON's params. Output: asserts both bands and the cited version match. */
    @Test
    fun `the horizon bands match RULE-HORIZON`() {
        val rules = GoalRules()
        val row = ruleBlock("RULE-HORIZON")

        assertEquals(rules.shortYearsMax, row.intParam("short_years_max"))
        assertEquals(rules.hybridYearsMax, row.intParam("hybrid_years_max"))
        assertEquals(
            "the citation stamped into every projection must name the row's own version",
            GoalRules.HORIZON.ruleVersion,
            row.version(),
        )
    }

    /**
     * Input:  RULE-HORIZON's row.
     * Output: asserts it is enabled and still claims this engine.
     *
     * The row named `AI-GOAL.funding_buckets` in `consumed_by` **before this engine existed**. That
     * is why issue 7.1 minted no rulebook row of its own, and if the claim were ever removed the
     * citation on every projection would be pointing at a rule that no longer says it applies here.
     */
    @Test
    fun `RULE-HORIZON is enabled and still names this engine as its consumer`() {
        val row = ruleBlock("RULE-HORIZON")

        assertTrue(
            "RULE-HORIZON is disabled — the engine cites a rule that is switched off",
            "\"enabled\": true" in row,
        )
        assertTrue(
            "RULE-HORIZON no longer names AI-GOAL in consumed_by, so this engine's citation is stale",
            "AI-GOAL" in row,
        )
    }

    /**
     * Input:  every rule in the file.
     * Output: asserts issue 7.1 invented no threshold anywhere.
     *
     * The inverse of the boundary guard the other engines' drift tests carry, and it is the point:
     * a required monthly is arithmetic and "on track" is an exact comparison, so **no parameter was
     * needed**. If one ever appears — a slack band, an assumed rate of return — it must arrive as a
     * reviewed rulebook row with its own ADR, not as a constant somebody added to the engine and
     * mirrored here afterwards. Minting one also bumps `_meta.version`, which forces every other
     * engine's mirror to restate it, so this is a decision worth making on purpose.
     */
    @Test
    fun `issue 7-1 minted no threshold of its own`() {
        listOf("on_track_tolerance_pct", "assumed_return_pct", "goal_required_monthly").forEach { key ->
            assertTrue(
                "'$key' appeared in the rulebook. If the goals engine now needs a threshold, it needs " +
                    "an ADR and a GoalRules field to go with it — see GoalRules on why 7.1 had none",
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

        /** Short enough that a truncated or emptied file cannot pass the sanity check above. */
        const val MIN_RULEBOOK_LENGTH = 1_000
    }
}
