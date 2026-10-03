package com.aicfo.domain.engines.insurance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Holds [ProtectionRules] to `ai/rules/rules-kb.json` (§6, CLAUDE.md).
 *
 * Why:  the engine is pure Kotlin and cannot read the rulebook, so it reads a mirror — and a mirror
 *       is only safe while something proves it is still a copy. The failure this prevents is the
 *       quiet one: a multiple raised in the JSON because the guidance moved, the mirror left alone,
 *       and an app that goes on telling people they are adequately covered against last year's rule
 *       while the file that documents it says otherwise.
 * What: the rulebook revision, every parameter of all three rows, and the **versions** the engine
 *       cites — because an insight stored today has to stay reproducible against the thresholds
 *       that produced it (AI-ARC-006).
 * Result: the two cannot silently disagree.
 * Changelog: 2026-10-03 — Created for issue 13.3.
 *
 * Read with small regular expressions rather than a parser: `:domain:*` has no serialisation
 * dependency by design (ARC-002), and this is the same choice every other rulebook drift test makes.
 */
class ProtectionRulebookDriftTest {
    private val rulebook: String by lazy { rulebookFile().readText() }
    private val rules = ProtectionRules.BUNDLED

    @Test
    fun `the rulebook is where this test thinks it is`() {
        assertTrue("the rulebook looks empty or truncated", rulebook.length > 10_000)
        assertTrue("no RULE-TERM-10X in the rulebook", "\"RULE-TERM-10X\"" in rulebook)
    }

    @Test
    fun `the mirror names the rulebook revision it copied from`() {
        assertEquals(metaVersion(), ProtectionRules.RULEBOOK_VERSION)
    }

    @Test
    fun `the three rows this engine reads are all enabled`() {
        listOf(ProtectionRules.TERM_RULE, ProtectionRules.HEALTH_RULE, ProtectionRules.ENDOWMENT_RULE)
            .forEach { ruleId ->
                assertTrue("$ruleId is disabled in the rulebook but the engine still reads it", enabledOf(ruleId))
            }
    }

    @Test
    fun `the cited rule versions match the rulebook`() {
        // Cited beside the id in every piece of evidence, so a stored insight stays reproducible.
        assertEquals(versionOf(ProtectionRules.TERM_RULE), ProtectionRules.TERM_RULE_VERSION)
        assertEquals(versionOf(ProtectionRules.HEALTH_RULE), ProtectionRules.HEALTH_RULE_VERSION)
        assertEquals(versionOf(ProtectionRules.ENDOWMENT_RULE), ProtectionRules.ENDOWMENT_RULE_VERSION)
    }

    @Test
    fun `RULE-TERM-10X's multiples and flags match the rulebook`() {
        val block = ruleBlock(ProtectionRules.TERM_RULE)

        assertEquals(numberIn(block, "income_multiple_min"), rules.incomeMultipleMin.toLong())
        assertEquals(numberIn(block, "income_multiple_max"), rules.incomeMultipleMax.toLong())
        assertEquals(booleanIn(block, "requires_dependents"), rules.requiresDependents)
        assertEquals(booleanIn(block, "include_outstanding_liabilities"), rules.includeOutstandingLiabilities)
        assertEquals(booleanIn(block, "single_income_uses_max_multiple"), rules.singleIncomeUsesMaxMultiple)
    }

    @Test
    fun `every IRDAI age band matches the rulebook, in order`() {
        // Order matters: the engine takes the FIRST band containing the age, so two overlapping
        // bands in the wrong order would silently pick the wrong multiple.
        val bands =
            Regex("\\[\\s*(\\d+),\\s*(\\d+),\\s*(\\d+)\\s*]")
                .findAll(arrayIn(ruleBlock(ProtectionRules.TERM_RULE), "hlv_age_multipliers"))
                .map { Triple(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
                .toList()

        assertEquals("the number of HLV bands changed", bands.size, rules.hlvAgeMultipliers.size)
        bands.forEachIndexed { index, (minAge, maxAge, multiple) ->
            val mirrored = rules.hlvAgeMultipliers[index]
            assertEquals("band $index min age", minAge, mirrored.minAge)
            assertEquals("band $index max age", maxAge, mirrored.maxAge)
            assertEquals("band $index multiple", multiple, mirrored.multiple)
        }
    }

    @Test
    fun `RULE-HEALTH-COVER's floors and inflation match the rulebook`() {
        val block = ruleBlock(ProtectionRules.HEALTH_RULE)

        assertEquals(numberIn(block, "base_floor_inr_lakh"), rules.baseFloorInrLakh.toLong())
        assertEquals(numberIn(block, "metro_floor_inr_lakh"), rules.metroFloorInrLakh.toLong())
        assertEquals(numberIn(block, "healthcare_inflation_bps"), rules.healthcareInflationBps.toLong())
    }

    @Test
    fun `RULE-TERM-VS-ENDOW's threshold and comparison match the rulebook`() {
        val block = ruleBlock(ProtectionRules.ENDOWMENT_RULE)

        assertEquals(numberIn(block, "term_premium_per_lakh_paise_max"), rules.termPremiumPerLakhPaiseMax)
        assertEquals(numberIn(block, "flag_premium_per_lakh_paise_min"), rules.flagPremiumPerLakhPaiseMin)
        assertEquals(numberIn(block, "equity_sip_return_bps"), rules.equitySipReturnBps.toLong())
        assertEquals(numberIn(block, "endowment_return_bps_low"), rules.endowmentReturnBpsLow.toLong())
        assertEquals(numberIn(block, "endowment_return_bps_high"), rules.endowmentReturnBpsHigh.toLong())
        assertEquals(numberIn(block, "comparison_horizon_years"), rules.comparisonHorizonYears.toLong())
    }

    @Test
    fun `the flag threshold still sits above the dearest real term plan`() {
        // The invariant the whole detector rests on. A threshold that slipped below real term
        // pricing would flag an older person's genuine cover as an investment — the one false
        // positive here that would cost trust in everything else the app says.
        assertTrue(
            "the flag threshold must stay above term_premium_per_lakh_paise_max",
            rules.flagPremiumPerLakhPaiseMin > rules.termPremiumPerLakhPaiseMax,
        )
    }

    @Test
    fun `the human rulebook names the new rule too`() {
        // `rulebook.md` is the table a person reads; the skill requires the two to agree.
        val doc = File(rulebookFile().parentFile, "rulebook.md").readText()
        assertTrue("rulebook.md does not mention RULE-TERM-VS-ENDOW", ProtectionRules.ENDOWMENT_RULE in doc)
        assertTrue("rulebook.md does not mention AI-INS", "AI-INS" in doc)
    }

    // --- parsing ----------------------------------------------------------------------------------

    private fun metaVersion(): String =
        Regex("\"_meta\"\\s*:\\s*\\{.*?\"version\"\\s*:\\s*\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL)
            .find(rulebook)?.groupValues?.get(1)
            ?: throw AssertionError("no _meta.version in the rulebook")

    /** Result: one rule's JSON object, bounded by the next `rule_id` so keys cannot bleed across. */
    private fun ruleBlock(ruleId: String): String {
        val start = rulebook.indexOf("\"rule_id\": \"$ruleId\"")
        if (start < 0) throw AssertionError("no rule \"$ruleId\" in the rulebook")
        val next = rulebook.indexOf("\"rule_id\"", start + 1)
        return if (next < 0) rulebook.substring(start) else rulebook.substring(start, next)
    }

    private fun numberIn(
        block: String,
        key: String,
    ): Long =
        Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(block)?.groupValues?.get(1)?.toLong()
            ?: throw AssertionError("no \"$key\" in the rule block")

    private fun booleanIn(
        block: String,
        key: String,
    ): Boolean =
        Regex("\"$key\"\\s*:\\s*(true|false)").find(block)?.groupValues?.get(1)?.toBooleanStrict()
            ?: throw AssertionError("no \"$key\" in the rule block")

    /**
     * Result: the text of an array value, however the file happens to be wrapped.
     *
     * Bracket-counted rather than regex-terminated: `rules-kb.json` writes short values inline
     * (`[[18, 35, 25], ...]`) and long ones across lines, and a pattern that assumed either one
     * would pass or fail on formatting rather than on content. This test is about the numbers.
     * Input:  [block]; [key]. Output: the characters between the outer brackets.
     */
    private fun arrayIn(
        block: String,
        key: String,
    ): String {
        val keyAt = block.indexOf("\"$key\"")
        val open = if (keyAt < 0) -1 else block.indexOf('[', keyAt)
        var depth = 0
        var close = -1
        if (open >= 0) {
            for (index in open until block.length) {
                when (block[index]) {
                    '[' -> depth++
                    ']' -> depth--
                    else -> Unit
                }
                if (depth == 0) {
                    close = index
                    break
                }
            }
        }
        if (close < 0) throw AssertionError("no complete \"$key\" array in the rule block")
        return block.substring(open + 1, close)
    }

    private fun versionOf(ruleId: String): String =
        Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(ruleBlock(ruleId))?.groupValues?.get(1)
            ?: throw AssertionError("no version on $ruleId")

    private fun enabledOf(ruleId: String): Boolean = booleanIn(ruleBlock(ruleId), "enabled")

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
