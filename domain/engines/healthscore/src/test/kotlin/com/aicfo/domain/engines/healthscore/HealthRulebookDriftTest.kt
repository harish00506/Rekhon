package com.aicfo.domain.engines.healthscore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps [HealthRules] honest against `RULE-FHS-*`, `RULE-CC-UTIL` and `RULE-SAVE-RATE` (issue 9.4;
 * CLAUDE.md §6).
 *
 * Why:  §14's weights, bands and anchors are rulebook rows and [HealthRules] is their typed copy until a
 *       loader exists (ADR-0017). A copy that drifted would score the user against numbers the
 *       rulebook no longer says — and move their band without anyone changing a row.
 * What: every parameter of the three FHS rows, the two borrowed anchors, every row version, the file
 *       version, and that the rows are enabled and still name AI-FHS.
 * Result: an edit to either side without the other fails the build.
 * Changelog: 2026-09-19 — Created for issue 9.4.
 *
 * **Regex, not a JSON library**, as in every sibling drift test: pure Kotlin, no serialisation
 * dependency (ARC-002). A lookup that fails to match fails loudly.
 */
class HealthRulebookDriftTest {
    private val rulebook: String by lazy { rulebookFile().readText() }
    private val rules = HealthRules()

    @Test
    fun `the rulebook is where this test thinks it is`() {
        assertTrue("rulebook looks empty", rulebook.length > MIN_RULEBOOK_LENGTH)
    }

    @Test
    fun `the mirror names the rulebook revision it copied from`() {
        assertEquals(HealthRules.RULEBOOK_VERSION, rulebook.substringBefore("\"rules\"").version())
    }

    @Test
    fun `the weights, scale and windows match RULE-FHS-PILLARS`() {
        val row = ruleBlock("RULE-FHS-PILLARS")

        assertEquals(rules.liquidityWeightBps, row.intParam("liquidity_weight_bps"))
        assertEquals(rules.debtWeightBps, row.intParam("debt_weight_bps"))
        assertEquals(rules.disciplineWeightBps, row.intParam("discipline_weight_bps"))
        assertEquals(rules.goalsWeightBps, row.intParam("goals_weight_bps"))
        assertEquals(rules.protectionWeightBps, row.intParam("protection_weight_bps"))
        assertEquals(rules.scoreMax, row.intParam("score_max"))
        assertEquals(rules.lookbackMonths, row.intParam("lookback_months"))
        assertEquals(rules.minMonthsOfSignal, row.intParam("min_months_of_signal"))
        assertEquals(HealthRules.PILLARS.ruleVersion, row.version())
    }

    @Test
    fun `the band edges match RULE-FHS-BANDS`() {
        val row = ruleBlock("RULE-FHS-BANDS")

        assertEquals(rules.excellentMin, row.intParam("excellent_min"))
        assertEquals(rules.goodMin, row.intParam("good_min"))
        assertEquals(rules.fairMin, row.intParam("fair_min"))
        assertEquals(rules.attentionMin, row.intParam("attention_min"))
        assertEquals(HealthRules.BANDS.ruleVersion, row.version())
    }

    @Test
    fun `the curve anchors match RULE-FHS-SIGNALS`() {
        val row = ruleBlock("RULE-FHS-SIGNALS")

        assertEquals(rules.runwayFloorPoints, row.intParam("runway_floor_points"))
        assertEquals(rules.obligationFullPct, row.intParam("obligation_full_pct"))
        assertEquals(rules.obligationZeroPct, row.intParam("obligation_zero_pct"))
        assertEquals(rules.utilisationZeroPct, row.intParam("utilisation_zero_pct"))
        assertEquals(HealthRules.SIGNALS.ruleVersion, row.version())
    }

    @Test
    fun `the borrowed anchors are read from the rows that own them, not restated`() {
        val card = ruleBlock("RULE-CC-UTIL")
        val save = ruleBlock("RULE-SAVE-RATE")

        assertEquals(rules.utilisationFullPct, card.intParam("max_utilisation_pct"))
        assertEquals(HealthRules.CARD_UTILISATION.ruleVersion, card.version())
        assertEquals(rules.savingsFullPct, save.intParam("excellent_pct"))
        assertEquals(HealthRules.SAVINGS_RATE.ruleVersion, save.version())
        val signals = ruleBlock("RULE-FHS-SIGNALS")
        assertTrue("RULE-FHS-SIGNALS restates a utilisation top", "utilisation_full_pct" !in signals)
        assertTrue("RULE-FHS-SIGNALS restates a savings top", "savings_full_pct" !in signals)
    }

    @Test
    fun `every row the score reads is enabled and names AI-FHS as a consumer`() {
        listOf("RULE-FHS-PILLARS", "RULE-FHS-BANDS", "RULE-FHS-SIGNALS", "RULE-CC-UTIL", "RULE-SAVE-RATE").forEach {
                ruleId ->
            val row = ruleBlock(ruleId)
            assertTrue("$ruleId is disabled", "\"enabled\": true" in row)
            assertTrue("$ruleId no longer names AI-FHS in consumed_by", "AI-FHS" in row)
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
