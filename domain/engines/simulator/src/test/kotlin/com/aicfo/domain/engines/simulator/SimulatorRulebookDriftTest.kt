package com.aicfo.domain.engines.simulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps [SimulatorRules] honest against the two rows AI-SIM applies (issue 10.3; CLAUDE.md §6).
 *
 * Why:  unusually, this issue minted nothing — `RULE-PREPAY-VS-INVEST` and `RULE-PAYOFF-ORDER`
 *       shipped with the rulebook's first version and already described these simulators exactly.
 *       That makes drift *more* likely to go unnoticed, not less: there is no fresh row anybody is
 *       watching. So the flags that decide what the cards show are pinned here, along with the fact
 *       that both rows still name this engine.
 * What: both rows' switches and versions, the file version, and the consumer names.
 * Result: an edit to either side without the other fails the build.
 * Changelog: 2026-09-26 — Created for issue 10.3.
 */
class SimulatorRulebookDriftTest {
    private val rulebook: String by lazy { rulebookFile().readText() }
    private val rules = SimulatorRules()

    @Test
    fun `the mirror names the rulebook revision it copied from`() {
        assertEquals(SimulatorRules.RULEBOOK_VERSION, rulebook.substringBefore("\"rules\"").version())
    }

    @Test
    fun `the prepay row still asks for a breakeven`() {
        val row = ruleBlock("RULE-PREPAY-VS-INVEST")

        assertEquals(rules.showBreakeven, "\"show_breakeven\": true" in row)
        assertTrue(
            "the row no longer compares the loan rate with an after-tax return",
            "loan_rate_vs_after_tax_expected_return" in row,
        )
        assertEquals(SimulatorRules.PREPAY_VS_INVEST.ruleVersion, row.version())
    }

    @Test
    fun `the payoff row still offers both strategies and the interest delta`() {
        val row = ruleBlock("RULE-PAYOFF-ORDER")

        assertEquals(rules.showInterestDelta, "\"show_interest_delta\": true" in row)
        assertTrue("avalanche is no longer offered", "avalanche" in row)
        assertTrue("snowball is no longer offered", "snowball" in row)
        assertEquals(SimulatorRules.PAYOFF_ORDER.ruleVersion, row.version())
    }

    @Test
    fun `both rows are enabled and name AI-SIM as their consumer`() {
        listOf("RULE-PREPAY-VS-INVEST", "RULE-PAYOFF-ORDER").forEach { ruleId ->
            val row = ruleBlock(ruleId)
            assertTrue("$ruleId is disabled", "\"enabled\": true" in row)
            assertTrue("$ruleId no longer names AI-SIM", "AI-SIM" in row)
        }
    }

    @Test
    fun `the breakeven search is bounded, so an answer always arrives`() {
        // P-08: a fixed number of steps is what makes the breakeven reproducible to the basis point.
        assertTrue(rules.breakevenSearchSteps > 0)
        assertTrue(rules.breakevenSearchSteps <= 200)
    }

    // --- parsing ------------------------------------------------------------------------------------

    private fun ruleBlock(ruleId: String): String {
        val start = rulebook.indexOf("\"rule_id\": \"$ruleId\"")
        assertTrue("$ruleId is not in the rulebook", start >= 0)
        val next = rulebook.indexOf("\"rule_id\":", start + 1)
        return if (next < 0) rulebook.substring(start) else rulebook.substring(start, next)
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
    }
}
