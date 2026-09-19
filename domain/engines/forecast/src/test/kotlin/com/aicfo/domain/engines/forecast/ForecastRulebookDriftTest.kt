package com.aicfo.domain.engines.forecast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps [ForecastRules] honest against `RULE-FCT-METHOD` and `RULE-FCT-CRUNCH` (issue 9.2; CLAUDE.md §6).
 *
 * Why:  §9.2's numbers are rulebook rows and [ForecastRules] is their typed copy until a loader
 *       exists (ADR-0017). A copy is acceptable only while it cannot drift silently: a forecast run on
 *       last quarter's buffer would call days safe the rulebook says are not.
 * What: every parameter of both rows, both row versions, the file version, and that both rows are
 *       enabled and still name AI-FCT. The rulebook is a declared test input for every Kotlin library
 *       (`configureRulebookAsTestInput`), so an edit to the file alone re-runs this.
 * Result: an edit to either side without the other fails the build.
 * Changelog: 2026-09-19 — Created for issue 9.2.
 *
 * **Regex, not a JSON library**, as in every sibling drift test: pure Kotlin, no serialisation
 * dependency (ARC-002). A lookup that fails to match fails loudly.
 */
class ForecastRulebookDriftTest {
    private val rulebook: String by lazy { rulebookFile().readText() }
    private val rules = ForecastRules()

    @Test
    fun `the rulebook is where this test thinks it is`() {
        assertTrue("rulebook looks empty", rulebook.length > MIN_RULEBOOK_LENGTH)
    }

    @Test
    fun `the mirror names the rulebook revision it copied from`() {
        assertEquals(ForecastRules.RULEBOOK_VERSION, rulebook.substringBefore("\"rules\"").version())
    }

    @Test
    fun `the method parameters match RULE-FCT-METHOD`() {
        val row = ruleBlock("RULE-FCT-METHOD")

        assertEquals(rules.horizonDays, row.intParam("horizon_days"))
        assertEquals(rules.lookbackDays, row.intParam("lookback_days"))
        assertEquals(rules.trimBps, row.intParam("trim_bps"))
        assertEquals(rules.simulations, row.intParam("simulations"))
        assertEquals(rules.bandLowPct, row.intParam("band_low_pct"))
        assertEquals(rules.bandMidPct, row.intParam("band_mid_pct"))
        assertEquals(rules.bandHighPct, row.intParam("band_high_pct"))
        assertEquals(rules.monthStartSpikeLastDay, row.intParam("month_start_spike_last_day"))
        assertEquals(rules.monthEndTroughFirstDay, row.intParam("month_end_trough_first_day"))
        assertEquals(ForecastRules.METHOD.ruleVersion, row.version())
    }

    @Test
    fun `the buffer matches RULE-FCT-CRUNCH, in paise`() {
        val row = ruleBlock("RULE-FCT-CRUNCH")

        assertEquals(rules.buffer.minor, row.intParam("buffer_minor").toLong())
        assertTrue("RULE-FCT-CRUNCH no longer measures the P50 band", "\"band\": \"P50\"" in row)
        assertEquals(ForecastRules.CRUNCH.ruleVersion, row.version())
    }

    @Test
    fun `both rows are enabled and still name AI-FCT as their consumer`() {
        listOf("RULE-FCT-METHOD", "RULE-FCT-CRUNCH").forEach { ruleId ->
            val row = ruleBlock(ruleId)
            assertTrue("$ruleId is disabled", "\"enabled\": true" in row)
            assertTrue("$ruleId no longer names AI-FCT in consumed_by", "AI-FCT" in row)
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
