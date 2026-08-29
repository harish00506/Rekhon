package com.aicfo.domain.engines.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps [CardRules] honest against `ai/rules/rules-kb.json` (CLAUDE.md §6, ADR-0005, ADR-0017).
 *
 * Why:  §6 says a financial threshold is a **data row in `ai/`, never a hardcoded number**, and this
 *       engine holds hardcoded numbers — the deliberate deferral ADR-0017 records. A deferral is
 *       only acceptable while the duplicate cannot drift, and "someone will remember to update
 *       both" is not a mechanism. This is the mechanism.
 * What: parses both cited rows out of the rulebook and asserts every parameter this engine applies.
 * Result: the §6 deferral costs correctness nothing until the runtime loader lands.
 * Changelog: 2026-08-17 — Created for issue 6.1.
 *
 * **Parsed with regex, not a JSON library, on purpose.** `:domain:*` is pure Kotlin with no
 * serialisation dependency (ARC-002), and adding one to a *test* to check four values would be the
 * tail wagging the dog. The parse is strict — a rule that cannot be found fails loudly rather than
 * passing vacuously. This is `:domain:engines:safetospend`'s test, two rules over.
 *
 * **None of it runs unless `build.gradle.kts` declares the rulebook a test input.** Without that
 * line Gradle keeps the task UP-TO-DATE when only the JSON changes, and the gate reports green
 * against a file it never read — the failure found in `:domain:engines:sms` on 2026-08-07.
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
        assertTrue("RULE-CC-UTIL missing from the rulebook", "RULE-CC-UTIL" in rulebook)
        assertTrue("RULE-CC-DUE missing from the rulebook", "RULE-CC-DUE" in rulebook)
    }

    /** Input: the file's `_meta.version`. Output: asserts the engine copied from this revision. */
    @Test
    fun `the engine names the rulebook revision it copied from`() {
        val meta = rulebook.substringBefore("\"rules\"")
        assertEquals(CardRules.RULEBOOK_VERSION, meta.version())
    }

    /**
     * Input:  RULE-CC-UTIL's params.
     * Output: asserts the utilisation line matches the row this engine did **not** author.
     *
     * This row shipped before issue 6.1 and is consumed by the health score as well as by these
     * alerts, so drift here would move two features at once, in opposite directions if only one of
     * them was being looked at.
     */
    @Test
    fun `the utilisation threshold matches RULE-CC-UTIL`() {
        val row = ruleBlock("RULE-CC-UTIL")

        assertEquals(CardRules().maxUtilisationPct, row.intParam("max_utilisation_pct"))
        assertEquals(CardRules.UTILISATION.ruleVersion, row.version())
    }

    /** Input: RULE-CC-DUE's params. Output: asserts every value the reminder window is built from. */
    @Test
    fun `the reminder window matches RULE-CC-DUE`() {
        val rules = CardRules()
        val row = ruleBlock("RULE-CC-DUE")

        assertEquals(rules.remindDaysBefore, row.intParam("remind_days_before"))
        assertEquals(
            "the engine fires a second reminder only while the rule asks for it",
            rules.remindOnDueDay,
            row.boolParam("remind_on_due_day"),
        )
        assertEquals(
            "the engine stays quiet about a settled card only while the rule asks for it",
            rules.skipWhenNothingDue,
            row.boolParam("skip_when_nothing_due"),
        )
        assertEquals(CardRules.DUE.ruleVersion, row.version())
    }

    /**
     * Input:  both rule ids the engine cites.
     * Output: asserts each exists and is enabled. A citation pointing at a disabled rule would show
     *         the user a reason that is no longer the app's.
     */
    @Test
    fun `every rule the engine cites exists and is enabled`() {
        listOf(CardRules.UTILISATION, CardRules.DUE).forEach { citation ->
            val row = ruleBlock(citation.ruleId)
            assertTrue(
                "${citation.ruleId} is disabled in the rulebook but still cited",
                "\"enabled\": true" in row,
            )
        }
    }

    /**
     * Input:  RULE-CC-UTIL's row.
     * Output: asserts none of the reminder's params leaked onto it.
     *
     * Why:    the tripwire the precedent demands, and the reason `RULE-CC-DUE` exists at all.
     *         `RULE-CC-UTIL` is a shipped row at version 1.0, cited in provenance already stored on
     *         users' devices; adding a param to it bumps that version, which is ADR-0017's trigger 3
     *         and forces the runtime rules loader before this module can compile again. It is also
     *         the tempting home, because it is already the card rule and already names `card_alerts`
     *         — but it answers "how much of a limit is in use", not "when should someone be woken
     *         up about a date".
     */
    @Test
    fun `the reminder window lives on RULE-CC-DUE, not on the shipped utilisation row`() {
        val row = ruleBlock("RULE-CC-UTIL")
        listOf("remind_days_before", "remind_on_due_day", "skip_when_nothing_due").forEach { key ->
            assertTrue(
                "'$key' appeared on RULE-CC-UTIL. The reminder's parameters belong to RULE-CC-DUE: " +
                    "adding one here bumps a shipped row's version, which is ADR-0017 trigger 3 and " +
                    "forces the runtime rules loader before this module can compile again",
                "\"$key\"" !in row,
            )
        }
    }

    /**
     * Input:  RULE-CC-DUE's row.
     * Output: asserts the utilisation threshold did **not** get duplicated onto it.
     *
     * The mirror image of the test above, and the failure it guards is worse: two rows both naming
     * a `max_utilisation_pct` would let a reviewer change one, see the drift test stay green, and
     * ship a screen and an alert that disagree about where the line is.
     */
    @Test
    fun `the utilisation threshold is not duplicated onto the reminder row`() {
        val row = ruleBlock("RULE-CC-DUE")
        assertTrue(
            "'max_utilisation_pct' appeared on RULE-CC-DUE as well as RULE-CC-UTIL. One threshold, " +
                "one row — two would let the screen and the alert disagree with both tests green",
            "\"max_utilisation_pct\"" !in row,
        )
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
