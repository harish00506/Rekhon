package com.aicfo.domain.engines.purchase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps [PurchaseRules] honest against every row the advisor reads (issue 10.1; CLAUDE.md §6).
 *
 * Why:  four of these rows are not the advisor's own. RULE-EMI-40 is a lender's heuristic,
 *       RULE-COOL-OFF is a habit rule, RULE-FCT-CRUNCH belongs to the forecast and RULE-STS to
 *       Safe-to-Spend — and a mirror that drifted from any of them would keep giving verdicts on a
 *       threshold the rulebook had already moved, while every test about the gate itself still
 *       passed. The percentages are stored as whole percents and read here as basis points
 *       (MNY-002), which is exactly the kind of conversion that rots silently.
 * What: both AI-PA rows' params and versions, the reused thresholds, the file version, and that
 *       every row is enabled.
 * Result: an edit to either side without the other fails the build.
 * Changelog: 2026-09-25 — Created for issue 10.1.
 *
 * **Regex, not a JSON library**, as in every sibling drift test: pure Kotlin, no serialisation
 * dependency (ARC-002).
 */
class PurchaseRulebookDriftTest {
    private val rulebook: String by lazy { rulebookFile().readText() }
    private val rules = PurchaseRules()

    @Test
    fun `the rulebook is where this test thinks it is`() {
        assertTrue("rulebook looks empty", rulebook.length > MIN_RULEBOOK_LENGTH)
    }

    @Test
    fun `the mirror names the rulebook revision it copied from`() {
        assertEquals(PurchaseRules.RULEBOOK_VERSION, rulebook.substringBefore("\"rules\"").version())
    }

    @Test
    fun `the gate policy matches RULE-PA-GATES`() {
        val row = ruleBlock("RULE-PA-GATES")

        assertEquals(rules.daysPerMonth, row.intParam("days_per_month"))
        assertEquals(rules.urgencySoftensOneStep, row.flag("urgency_softens_one_step"))
        assertEquals(rules.softenBlockedOnHardFail, row.flag("soften_blocked_on_hard_fail"))
        assertEquals(PurchaseRules.GATES.ruleVersion, row.version())
    }

    @Test
    fun `the opportunity cost matches RULE-PA-OPPCOST`() {
        val row = ruleBlock("RULE-PA-OPPCOST")

        assertEquals(rules.expectedReturnBps, row.intParam("expected_return_bps"))
        assertEquals("the horizons §13 names", listOf(5, 10), rules.opportunityHorizonYears)
        assertTrue("the rulebook no longer shows both horizons", "[5, 10]" in row)
        assertEquals(PurchaseRules.OPPORTUNITY_COST.ruleVersion, row.version())
    }

    @Test
    fun `the obligation lines are the lender's, from RULE-EMI-40`() {
        // Not this engine's numbers to choose: they are the Indian lending heuristics, held as
        // percentages in the rulebook and as basis points here (MNY-002).
        val row = ruleBlock("RULE-EMI-40")

        assertEquals(rules.obligationWarnBps, row.intParam("warn_pct") * BPS_PER_PERCENT)
        assertEquals(rules.obligationFailBps, row.intParam("fail_pct") * BPS_PER_PERCENT)
        assertEquals(PurchaseRules.OBLIGATIONS.ruleVersion, row.version())
    }

    @Test
    fun `the cooling-off trigger matches RULE-COOL-OFF`() {
        val row = ruleBlock("RULE-COOL-OFF")

        assertEquals(
            rules.coolOffTriggerBpsOfAnnualIncome,
            row.intParam("trigger_pct_of_annual_income") * BPS_PER_PERCENT,
        )
        assertEquals(PurchaseRules.COOL_OFF.ruleVersion, row.version())
    }

    @Test
    fun `every row the advisor cites is enabled and names AI-PA where it is the consumer`() {
        listOf("RULE-PA-GATES", "RULE-PA-OPPCOST").forEach { ruleId ->
            val row = ruleBlock(ruleId)
            assertTrue("$ruleId is disabled", "\"enabled\": true" in row)
            assertTrue("$ruleId no longer names AI-PA in consumed_by", "AI-PA" in row)
        }
        listOf("RULE-EMI-40", "RULE-COOL-OFF", "RULE-FCT-CRUNCH", "RULE-STS").forEach { ruleId ->
            assertTrue("$ruleId is disabled", "\"enabled\": true" in ruleBlock(ruleId))
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

    private fun repoFile(path: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, path) }
            .firstOrNull { it.isFile }
            ?: error("Could not find $path walking up from ${File("").absolutePath}")

    private companion object {
        const val BPS_PER_PERCENT = 100
        const val RULEBOOK_PATH = "ai/rules/rules-kb.json"
        const val MIN_RULEBOOK_LENGTH = 5_000
    }
}
