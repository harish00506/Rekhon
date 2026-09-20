package com.aicfo.domain.engines.insight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps [InsightRules] honest against `RULE-INS-RANK` and `RULE-INS-DEDUP` (issue 9.5; CLAUDE.md §6).
 *
 * Why:  the feed's order, its size and its snooze window are rulebook rows, and [InsightRules] is
 *       their typed copy until a loader exists (ADR-0017). Two of them are not numbers but **words**
 *       — the severity order and the tie-break — and those are expressed in Kotlin as the order of
 *       [Severity]'s constants and the engine's comparator. This test pins the words to the code, so
 *       an editor who reorders the rule's `severity_order` finds out that the enum has to move too.
 * What: both rows' params and versions, the file version, the enum order against the row's strings,
 *       and that both rows are enabled and still name AI-ORCH.
 * Result: an edit to either side without the other fails the build.
 * Changelog: 2026-09-20 — Created for issue 9.5.
 *
 * **Regex, not a JSON library**, as in every sibling drift test: pure Kotlin, no serialisation
 * dependency (ARC-002). A lookup that fails to match fails loudly.
 */
class InsightRulebookDriftTest {
    private val rulebook: String by lazy { rulebookFile().readText() }
    private val rules = InsightRules()

    @Test
    fun `the rulebook is where this test thinks it is`() {
        assertTrue("rulebook looks empty", rulebook.length > MIN_RULEBOOK_LENGTH)
    }

    @Test
    fun `the mirror names the rulebook revision it copied from`() {
        assertEquals(InsightRules.RULEBOOK_VERSION, rulebook.substringBefore("\"rules\"").version())
    }

    @Test
    fun `the dashboard size matches RULE-INS-RANK`() {
        val row = ruleBlock("RULE-INS-RANK")

        assertEquals(rules.dashboardMax, row.intParam("dashboard_max"))
        assertEquals(InsightRules.RANK.ruleVersion, row.version())
    }

    @Test
    fun `the severity order in the rule is the order of the enum's constants`() {
        val row = ruleBlock("RULE-INS-RANK")
        val order = Regex("\"severity_order\"\\s*:\\s*\\[([^\\]]+)]").find(row)

        assertNotNull("RULE-INS-RANK no longer states a severity order", order)
        val stated =
            Regex(
                "\"([a-z_]+)\"",
            ).findAll(order!!.groupValues[1]).map { it.groupValues[1].uppercase() }.toList()
        assertEquals(stated, Severity.entries.map { it.name })
    }

    @Test
    fun `the tie-break the rule names is the one the engine applies`() {
        assertTrue(
            "RULE-INS-RANK's tie_break no longer reads amount-then-fingerprint",
            "\"tie_break\": \"amount_desc_then_fingerprint\"" in ruleBlock("RULE-INS-RANK"),
        )
    }

    @Test
    fun `the fingerprint and the snooze window match RULE-INS-DEDUP`() {
        val row = ruleBlock("RULE-INS-DEDUP")

        assertEquals(rules.snoozeDays, row.intParam("snooze_days"))
        assertTrue(
            "the fingerprint is no longer type+subject+period",
            "\"fingerprint\": \"type+subject+period\"" in row,
        )
        assertTrue("a match no longer updates the existing row", "\"on_match\": \"update_existing\"" in row)
        assertEquals(InsightRules.DEDUP.ruleVersion, row.version())
    }

    @Test
    fun `both rows are enabled and still name AI-ORCH as their consumer`() {
        listOf("RULE-INS-RANK", "RULE-INS-DEDUP").forEach { ruleId ->
            val row = ruleBlock(ruleId)
            assertTrue("$ruleId is disabled", "\"enabled\": true" in row)
            assertTrue("$ruleId no longer names AI-ORCH in consumed_by", "AI-ORCH" in row)
        }
    }

    // --- parsing ------------------------------------------------------------------------------------

    private fun ruleBlock(ruleId: String): String {
        val start = rulebook.indexOf("\"rule_id\": \"$ruleId\"")
        assertTrue("$ruleId is not in the rulebook", start >= 0)
        val next = rulebook.indexOf("\"rule_id\":", start + 1)
        return if (next < 0) rulebook.substring(start) else rulebook.substring(start, next)
    }

    private fun String.intParam(name: String): Int {
        val match = Regex("\"$name\"\\s*:\\s*(-?\\d+)").find(this)
        assertNotNull("parameter '$name' not found", match)
        return match!!.groupValues[1].toInt()
    }

    private fun String.version(): String {
        val match = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(this)
        assertNotNull("no version here — AI-ARC-006 requires one", match)
        return match!!.groupValues[1]
    }

    private fun rulebookFile(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, RULEBOOK_PATH) }
            .firstOrNull { it.isFile }
            ?: error("Could not find $RULEBOOK_PATH walking up from ${File("").absolutePath}")

    private companion object {
        const val RULEBOOK_PATH = "ai/rules/rules-kb.json"
        const val MIN_RULEBOOK_LENGTH = 5_000
    }
}
