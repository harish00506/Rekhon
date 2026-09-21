package com.aicfo.domain.engines.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps [NotificationRules] honest against `RULE-NTF-BUDGET` and `RULE-NTF-QUIET` (issue 9.6;
 * CLAUDE.md §6).
 *
 * Why:  these two rows decide how often the app may interrupt someone and when it may not. A mirror
 *       that drifted would keep interrupting on last month's policy — and the symptom would be a
 *       user turning the channel off, not a failing test. The taxonomy's channel ids are pinned here
 *       too: they are stable strings in the OS, and renaming one silently orphans the user's setting.
 * What: both rows' params and versions, the file version, the channel ids, and that the rows are
 *       enabled and still name AI-NTF.
 * Result: an edit to either side without the other fails the build.
 * Changelog: 2026-09-20 — Created for issue 9.6.
 *
 * **Regex, not a JSON library**, as in every sibling drift test: pure Kotlin, no serialisation
 * dependency (ARC-002). A lookup that fails to match fails loudly.
 */
class NotificationRulebookDriftTest {
    private val rulebook: String by lazy { rulebookFile().readText() }
    private val rules = NotificationRules()

    @Test
    fun `the rulebook is where this test thinks it is`() {
        assertTrue("rulebook looks empty", rulebook.length > MIN_RULEBOOK_LENGTH)
    }

    @Test
    fun `the mirror names the rulebook revision it copied from`() {
        assertEquals(NotificationRules.RULEBOOK_VERSION, rulebook.substringBefore("\"rules\"").version())
    }

    @Test
    fun `the caps match RULE-NTF-BUDGET`() {
        val row = ruleBlock("RULE-NTF-BUDGET")

        assertEquals(rules.dailyMax, row.intParam("daily_max"))
        assertEquals(rules.weeklyMax, row.intParam("weekly_max"))
        assertEquals(rules.windowDays, row.intParam("window_days"))
        assertTrue("critical events are no longer exempt from the caps", "\"critical_exempt\": true" in row)
        assertEquals(rules.criticalExempt, "\"critical_exempt\": true" in row)
        assertEquals(NotificationRules.BUDGET.ruleVersion, row.version())
    }

    @Test
    fun `the quiet window matches RULE-NTF-QUIET`() {
        val row = ruleBlock("RULE-NTF-QUIET")

        assertEquals(rules.quietStartHour, row.intParam("start_hour"))
        assertEquals(rules.quietEndHour, row.intParam("end_hour"))
        assertEquals(rules.criticalMayBypassQuietHours, "\"critical_may_bypass\": true" in row)
        assertEquals(NotificationRules.QUIET.ruleVersion, row.version())
    }

    @Test
    fun `every taxonomy row has its own channel, and the ids are stable`() {
        val channels = NotificationKind.entries.map { it.channelId }

        assertEquals("NTF-006: one channel per taxonomy row, never shared", channels.size, channels.distinct().size)
        assertEquals(
            listOf(
                "critical-money-events",
                "budget-alerts",
                "debt-discipline",
                "ai-insights",
                "maintenance-renewals",
                "goal-events",
                "habit-capture",
            ),
            channels,
        )
    }

    @Test
    fun `both rows are enabled and still name AI-NTF as their consumer`() {
        listOf("RULE-NTF-BUDGET", "RULE-NTF-QUIET").forEach { ruleId ->
            val row = ruleBlock(ruleId)
            assertTrue("$ruleId is disabled", "\"enabled\": true" in row)
            assertTrue("$ruleId no longer names AI-NTF in consumed_by", "AI-NTF" in row)
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
