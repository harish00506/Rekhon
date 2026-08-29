package com.aicfo.domain.engines.safetospend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps [SafeToSpendRules] honest against `ai/rules/rules-kb.json` (CLAUDE.md §6, ADR-0005, ADR-0017).
 *
 * Why:  §6 says a financial threshold is a **data row in `ai/`, never a hardcoded number**, and this
 *       engine holds hardcoded numbers — the deliberate deferral ADR-0017 records, because nothing
 *       in the app loads `ai/` yet and issue 5.2 would otherwise have to build that loader first. A
 *       deferral is only acceptable while the duplicate cannot drift, and "someone will remember to
 *       update both" is not a mechanism. This test is the mechanism: edit `RULE-STS` in the rulebook
 *       and the build goes red until this module agrees.
 * What: parses `RULE-STS` out of the rulebook JSON and asserts every parameter, including the two
 *       the engine does not mirror as fields.
 * Result: the §6 deferral costs correctness nothing until the loader lands.
 * Changelog: 2026-08-16 — Created for issue 5.2.
 *
 * **Parsed with regex, not a JSON library, on purpose.** `:domain:*` is pure Kotlin with no
 * serialisation dependency (ARC-002), and adding one to a *test* to check three values would be the
 * tail wagging the dog. The parse is deliberately strict — a rule that cannot be found fails loudly
 * rather than passing vacuously. This is `:domain:engines:budget`'s test, one rule over.
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
        assertTrue("RULE-STS missing from the rulebook", "RULE-STS" in rulebook)
    }

    /** Input: the file's `_meta.version`. Output: asserts the engine copied from this revision. */
    @Test
    fun `the engine names the rulebook revision it copied from`() {
        val meta = rulebook.substringBefore("\"rules\"")
        assertEquals(SafeToSpendRules.RULEBOOK_VERSION, meta.version())
    }

    /** Input: RULE-STS's params. Output: asserts every threshold the engine applies matches. */
    @Test
    fun `the thresholds match RULE-STS`() {
        val rules = SafeToSpendRules()
        val row = ruleBlock("RULE-STS")

        assertEquals(rules.bufferPct, row.intParam("buffer_pct"))
        assertEquals(
            "the engine subtracts goal contributions only while the rule asks for it",
            rules.includeGoalContributions,
            row.boolParam("include_goal_contributions"),
        )
        assertEquals(
            "the engine clamps an overcommitted month only while the rule asks for it",
            rules.floorAtZero,
            row.boolParam("floor_at_zero"),
        )
        assertEquals(SafeToSpendRules.SAFE_TO_SPEND.ruleVersion, row.version())
    }

    /**
     * Input:  RULE-STS's two non-numeric params.
     * Output: asserts the basis and the horizon the *repository* implements are still what the rule
     *         asks for.
     *
     * Why:    neither is a number, so neither is a field on [SafeToSpendRules] — and that is exactly
     *         why they need a test. `income_basis` decides whether `SafeToSpendRepository` reads the
     *         quick-setup envelopes or the ledger, and `horizon` decides where its window ends;
     *         changing either in the rulebook without changing the repository would leave every
     *         threshold assertion above green while the figure came from the wrong data entirely.
     */
    @Test
    fun `the income basis and horizon the repository implements are still the rule's`() {
        val row = ruleBlock("RULE-STS")

        assertEquals(SafeToSpendRules.INCOME_BASIS, row.stringParam("income_basis"))
        assertEquals(SafeToSpendRules.HORIZON, row.stringParam("horizon"))
    }

    /**
     * Input:  the rule id the engine cites.
     * Output: asserts it exists and is enabled. A citation pointing at a rule that has been
     *         deprecated or disabled would show the user a reason that is no longer the app's.
     */
    @Test
    fun `the rule the engine cites exists and is enabled`() {
        val row = ruleBlock(SafeToSpendRules.SAFE_TO_SPEND.ruleId)
        assertTrue(
            "${SafeToSpendRules.SAFE_TO_SPEND.ruleId} is disabled in the rulebook but still cited",
            "\"enabled\": true" in row,
        )
    }

    /**
     * Input:  RULE-BUD-PACE's and RULE-50-30-20's rows.
     * Output: asserts the Safe-to-Spend buffer is **not** on either.
     *
     * Why:    the same boundary `:domain:engines:budget`'s drift test guards, one rule over. Both are
     *         shipped rows at version 1.0 cited in provenance already stored on the user's device,
     *         and adding a param to one bumps its version — ADR-0017's trigger 3, which forces the
     *         runtime rules loader before this module can compile again. `RULE-50-30-20` is the
     *         tempting home, because it already talks about how income divides; it answers "how
     *         should this month be shaped?", where RULE-STS answers "what is left of it today".
     */
    @Test
    fun `the buffer lives on RULE-STS, not on a shipped budget rule`() {
        listOf("RULE-BUD-PACE", "RULE-50-30-20").forEach { ruleId ->
            val row = ruleBlock(ruleId)
            listOf("buffer_pct", "income_basis", "floor_at_zero").forEach { key ->
                assertTrue(
                    "'$key' appeared on $ruleId. §5.2's parameters belong to RULE-STS: adding one " +
                        "here bumps a shipped row's version, which is ADR-0017 trigger 3 and forces " +
                        "the runtime rules loader before this module can compile again",
                    "\"$key\"" !in row,
                )
            }
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

    /**
     * Reads one boolean parameter out of a rule's text.
     * Result: the value. Input: the receiver; [name]. Output: [Boolean]; fails when the key is
     *         missing, so a deleted flag cannot read as `false` and quietly agree with the engine.
     */
    private fun String.boolParam(name: String): Boolean {
        val match = Regex("\"$name\"\\s*:\\s*(true|false)").find(this)
        assertNotNull("parameter '$name' not found — the rulebook's shape changed", match)
        return match!!.groupValues[1].toBooleanStrict()
    }

    /**
     * Reads one string parameter out of a rule's text.
     * Result: the value. Input: the receiver; [name]. Output: [String]; fails when the key is missing.
     */
    private fun String.stringParam(name: String): String {
        val match = Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"").find(this)
        assertNotNull("parameter '$name' not found — the rulebook's shape changed", match)
        return match!!.groupValues[1]
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
