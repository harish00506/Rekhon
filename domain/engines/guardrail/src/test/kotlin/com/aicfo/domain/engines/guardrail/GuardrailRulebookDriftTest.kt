package com.aicfo.domain.engines.guardrail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps [GuardrailRules] honest against `RULE-GRD-LADDER` and `RULE-GRD-TRANSFORMS` (issue 9.7;
 * CLAUDE.md §6).
 *
 * Why:  these two rows are the gate's own settings, and drift here is the quietest possible
 *       failure: a mirror that still allowed a transform the rulebook had withdrawn would go on
 *       passing text the reviewer believed was blocked, and nothing would look wrong. The rows are
 *       marked `critical` in the rulebook for that reason, and this test is what makes the marking
 *       mean something.
 * What: both rows' params and versions, the file version, and that the rows are enabled and still
 *       name AI-GRD.
 * Result: an edit to either side without the other fails the build.
 * Changelog: 2026-09-23 — Created for issue 9.7.
 *
 * **Regex, not a JSON library**, as in every sibling drift test: pure Kotlin, no serialisation
 * dependency (ARC-002).
 */
class GuardrailRulebookDriftTest {
    private val rulebook: String by lazy { rulebookFile().readText() }
    private val rules = GuardrailRules()

    @Test
    fun `the rulebook is where this test thinks it is`() {
        assertTrue("rulebook looks empty", rulebook.length > MIN_RULEBOOK_LENGTH)
    }

    @Test
    fun `the mirror names the rulebook revision it copied from`() {
        assertEquals(GuardrailRules.RULEBOOK_VERSION, rulebook.substringBefore("\"rules\"").version())
    }

    @Test
    fun `the ladder matches RULE-GRD-LADDER`() {
        val row = ruleBlock("RULE-GRD-LADDER")

        assertEquals(rules.maxAttempts, row.intParam("max_attempts"))
        assertTrue("the rulebook no longer refuses when the attempts run out", "\"refuse_on_exhaustion\": true" in row)
        assertEquals(GuardrailRules.LADDER.ruleVersion, row.version())
    }

    @Test
    fun `the allowlist matches RULE-GRD-TRANSFORMS`() {
        val row = ruleBlock("RULE-GRD-TRANSFORMS")

        assertEquals(rules.maxDisplayDecimals, row.intParam("max_display_decimals"))
        assertEquals(rules.allowRoundedDisplay, row.flag("allow_rounded_display"))
        assertEquals(rules.allowLakhCroreWords, row.flag("allow_lakh_crore_words"))
        assertEquals(rules.allowMinorUnits, row.flag("allow_minor_units"))
        assertEquals(rules.allowBpsAsPercent, row.flag("allow_bps_as_percent"))
        assertEquals(rules.allowYearAlone, row.flag("allow_year_alone"))
        assertEquals(GuardrailRules.TRANSFORMS.ruleVersion, row.version())
    }

    @Test
    fun `both rows are enabled, critical, and still name AI-GRD as their consumer`() {
        listOf("RULE-GRD-LADDER", "RULE-GRD-TRANSFORMS").forEach { ruleId ->
            val row = ruleBlock(ruleId)
            assertTrue("$ruleId is disabled", "\"enabled\": true" in row)
            assertTrue("$ruleId no longer names AI-GRD in consumed_by", "AI-GRD" in row)
            assertTrue(
                "$ruleId is no longer critical — it gates every figure the app states",
                "\"severity\": \"critical\"" in row,
            )
        }
    }

    @Test
    fun `the guardrail contract file still states the ladder this engine implements`() {
        // `ai/chat/guardrail.md` is the contract; the rulebook holds its numbers. If the prose were
        // rewritten to a different ladder, the rows would be the last thing to notice.
        val contract = contractFile().readText()

        assertTrue("PASS is no longer in the contract", "PASS" in contract)
        assertTrue("REGENERATE is no longer in the contract", "REGENERATE" in contract)
        assertTrue("REFUSE is no longer in the contract", "REFUSE" in contract)
        assertTrue("GRD-001 (never let the model certify itself) is gone", "GRD-001" in contract)
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

    private fun String.flag(name: String): Boolean {
        val match = Regex("\"$name\"\\s*:\\s*(true|false)").find(this)
        assertNotNull("flag '$name' not found", match)
        return match!!.groupValues[1].toBoolean()
    }

    private fun String.version(): String {
        val match = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(this)
        assertNotNull("no version here — AI-ARC-006 requires one", match)
        return match!!.groupValues[1]
    }

    private fun rulebookFile(): File = repoFile(RULEBOOK_PATH)

    private fun contractFile(): File = repoFile(CONTRACT_PATH)

    private fun repoFile(path: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, path) }
            .firstOrNull { it.isFile }
            ?: error("Could not find $path walking up from ${File("").absolutePath}")

    private companion object {
        const val RULEBOOK_PATH = "ai/rules/rules-kb.json"
        const val CONTRACT_PATH = "ai/chat/guardrail.md"
        const val MIN_RULEBOOK_LENGTH = 5_000
    }
}
