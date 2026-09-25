package com.aicfo.domain.engines.purchase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps [InterviewRules] honest against `RULE-PAI-LADDER` and `RULE-PAI-SCORE` (issue 10.2;
 * CLAUDE.md §6).
 *
 * Why:  these two rows decide how often the app is allowed to ask a question and what each answer
 *       is worth — the difference between a list that helps and one that nags. A mirror that
 *       drifted would keep scoring on last month's policy while every test about the ladder still
 *       passed. The deltas especially: they are minted numbers, so the rulebook is the only place
 *       they can be argued with.
 * What: the bands, the question counts, the cooling-off, the score's start, lines and re-interview
 *       window, and **every delta**, compared name by name.
 * Result: an edit to either side without the other fails the build.
 * Changelog: 2026-09-26 — Created for issue 10.2.
 */
class InterviewRulebookDriftTest {
    private val rulebook: String by lazy { rulebookFile().readText() }
    private val rules = InterviewRules()

    @Test
    fun `the mirror names the rulebook revision it copied from`() {
        assertEquals(InterviewRules.RULEBOOK_VERSION, rulebook.substringBefore("\"rules\"").version())
    }

    @Test
    fun `the ladder's bands match RULE-PAI-LADDER`() {
        val row = ruleBlock("RULE-PAI-LADDER")

        assertEquals(rules.bandCeilingsBps[PurchaseWeight.CASUAL], row.intParam("casual_max_bps"))
        assertEquals(rules.bandCeilingsBps[PurchaseWeight.SMALL], row.intParam("small_max_bps"))
        assertEquals(rules.bandCeilingsBps[PurchaseWeight.SIGNIFICANT], row.intParam("significant_max_bps"))
        assertEquals(rules.bandCeilingsBps[PurchaseWeight.MAJOR], row.intParam("major_max_bps"))
        assertEquals(rules.heavyCoolOffHours, row.intParam("heavy_cooloff_hours"))
        assertTrue("an instalment is no longer always heavy", "\"emi_is_always_heavy\": true" in row)
        assertEquals(InterviewRules.LADDER.ruleVersion, row.version())
    }

    @Test
    fun `every band asks the number of questions the rulebook says`() {
        val row = ruleBlock("RULE-PAI-LADDER")

        mapOf(
            PurchaseWeight.CASUAL to "casual",
            PurchaseWeight.SMALL to "small",
            PurchaseWeight.SIGNIFICANT to "significant",
            PurchaseWeight.MAJOR to "major",
            PurchaseWeight.HEAVY to "heavy",
        ).forEach { (band, key) ->
            assertEquals("$band", rules.questionsFor(band), row.intParam(key))
        }
    }

    @Test
    fun `the score's start, lines and window match RULE-PAI-SCORE`() {
        val row = ruleBlock("RULE-PAI-SCORE")

        assertEquals(rules.startScore, row.intParam("start"))
        assertEquals(rules.keepMin, row.intParam("keep_min"))
        assertEquals(rules.parkMin, row.intParam("park_min"))
        assertEquals(rules.reinterviewDays, row.intParam("reinterview_days"))
        assertEquals(InterviewRules.SCORE.ruleVersion, row.version())
    }

    @Test
    fun `every delta the engine can award is the rulebook's own number`() {
        val row = ruleBlock("RULE-PAI-SCORE")

        rules.knownDeltas().forEach { (reason, points) ->
            assertEquals("delta '$reason'", points, row.intParam(reason))
        }
    }

    @Test
    fun `no single answer can carry a wish out of the middle`() {
        // The claim RULE-PAI-SCORE's source note makes, checked against the rulebook's own numbers
        // rather than the engine's behaviour — so weakening the rule fails here too.
        val row = ruleBlock("RULE-PAI-SCORE")
        val start = row.intParam("start")

        rules.knownDeltas().forEach { (reason, _) ->
            val moved = start + row.intParam(reason)
            assertTrue("'$reason' alone reaches KEEP", moved < row.intParam("keep_min"))
            assertTrue("'$reason' alone reaches SUGGEST_REMOVE", moved >= row.intParam("park_min"))
        }
    }

    @Test
    fun `both rows are enabled and still name AI-PA-INT as their consumer`() {
        listOf("RULE-PAI-LADDER", "RULE-PAI-SCORE").forEach { ruleId ->
            val row = ruleBlock(ruleId)
            assertTrue("$ruleId is disabled", "\"enabled\": true" in row)
            assertTrue("$ruleId no longer names AI-PA-INT", "AI-PA-INT" in row)
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
    }
}
