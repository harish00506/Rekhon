package com.aicfo.domain.engines.tax

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Holds [TaxKnowledge] to `ai/knowledge/tax-kb-fy2025-26.json` (§6, §38, TAX-002).
 *
 * Why:  §38.1 is explicit that tax parameters change **every Budget**, which makes this the drift
 *       test with the shortest fuse in the project: the edit is guaranteed to come, and it will come
 *       from somebody updating the JSON. If the mirror is not updated with it, the app goes on
 *       computing last year's tax while the file that documents it says otherwise — and every
 *       figure would still look plausible.
 * What: the FY rules version, every slab of both regimes **in order**, the deductions and caps, the
 *       rebates, the cess, and the capital-gains rates.
 * Result: the two cannot silently disagree.
 * Changelog: 2026-10-09 — Created for issue 13.4.
 *
 * Read with small regular expressions rather than a parser: `:domain:*` has no serialisation
 * dependency by design (ARC-002), as in every other drift test here.
 */
class TaxKbDriftTest {
    private val kb: String by lazy { kbFile().readText() }
    private val knowledge = TaxKnowledge.BUNDLED

    @Test
    fun `the knowledge base is where this test thinks it is`() {
        assertTrue("the tax KB looks empty or truncated", kb.length > 2_000)
        assertTrue("no regimes in the tax KB", "\"regimes\"" in kb)
    }

    @Test
    fun `the mirror names the file revision and the FY rules version it copied from`() {
        assertEquals(stringAt("version"), TaxKnowledge.KB_VERSION)
        assertEquals(stringAt("fy_rules_version"), TaxKnowledge.FY_RULES_VERSION)
        assertEquals(TaxKnowledge.FY_RULES_VERSION, knowledge.fyRulesVersion)
    }

    @Test
    fun `every slab of both regimes matches the file, in order`() {
        // Order and boundaries together: a band walk reads them in sequence, so a reordered or
        // shifted boundary taxes the wrong income at the wrong rate — and only within a few
        // thousand rupees of the edge, where nobody would notice by eye.
        listOf("old_regime" to knowledge.old, "new_regime" to knowledge.new).forEach { (name, rules) ->
            val slabs = slabsOf(name)

            assertEquals("$name slab count", slabs.size, rules.slabs.size)
            slabs.forEachIndexed { index, (upto, rateBps) ->
                val mirrored = rules.slabs[index]
                assertEquals("$name slab $index upper bound", upto, mirrored.uptoExclusive?.minor?.div(PAISE))
                assertEquals("$name slab $index rate", rateBps, mirrored.rateBps.toLong())
            }
        }
    }

    @Test
    fun `the standard deductions and rebates match the file`() {
        val old = blockNamed("old_regime")
        val new = blockNamed("new_regime")

        assertEquals(numberIn(old, "standard_deduction_inr"), rupeesOf(knowledge.old.standardDeduction))
        assertEquals(numberIn(new, "standard_deduction_inr"), rupeesOf(knowledge.new.standardDeduction))
        assertEquals(numberIn(old, "rebate_87A_max_inr"), rupeesOf(knowledge.old.rebateMax))
        assertEquals(numberIn(new, "rebate_87A_max_inr"), rupeesOf(knowledge.new.rebateMax))
        assertEquals(numberIn(old, "rebate_87A_taxable_income_upto_inr"), rupeesOf(knowledge.old.rebateTaxableUpto))
        assertEquals(numberIn(new, "rebate_87A_taxable_income_upto_inr"), rupeesOf(knowledge.new.rebateTaxableUpto))
    }

    @Test
    fun `the old regime's deduction caps match the file`() {
        val old = blockNamed("old_regime")

        assertEquals(numberIn(old, "80C_cap_inr"), rupeesOf(knowledge.old.section80CCap))
        assertEquals(numberIn(old, "80CCD(1B)_nps_additional_inr"), rupeesOf(knowledge.old.section80CcdOneBCap))
        assertEquals(numberIn(old, "80D_cap_inr"), rupeesOf(knowledge.old.section80DCap))
    }

    @Test
    fun `the new regime still allows no itemised deductions`() {
        // The whole point of the comparison. If the file ever lets 80C into the new regime, the
        // mirror's flag has to move with it or the engine would quietly ignore a real deduction.
        val new = blockNamed("new_regime")

        assertTrue("the file no longer lists 80C as unavailable in the new regime", "\"80C\"" in new)
        assertFalse(
            "the mirror must not allow itemised deductions in the new regime",
            knowledge.new.allowsItemisedDeductions,
        )
        assertTrue("the mirror must allow them in the old regime", knowledge.old.allowsItemisedDeductions)
    }

    @Test
    fun `the cess and the surcharge stance match the file`() {
        val block = blockNamed("surcharge_and_cess")

        assertEquals(numberIn(block, "health_and_education_cess_bps"), knowledge.cessBps.toLong())
        assertEquals(booleanIn(block, "surcharge_modelled"), knowledge.surchargeModelled)
        assertEquals(
            numberIn(block, "surcharge_applies_above_taxable_inr"),
            rupeesOf(knowledge.surchargeAppliesAboveTaxable),
        )
    }

    @Test
    fun `the equity capital-gains rates match the file`() {
        val equity = assetBlock("equity_or_equity_mf")

        assertEquals(numberIn(equity, "long_term_after_months"), knowledge.equityLongTermAfterMonths.toLong())
        assertEquals(numberIn(equity, "stcg_rate_bps"), knowledge.equityShortTermRateBps.toLong())
        assertEquals(numberIn(equity, "ltcg_rate_bps"), knowledge.equityLongTermRateBps.toLong())
        assertEquals(numberIn(equity, "ltcg_annual_exemption_inr"), rupeesOf(knowledge.equityLtcgAnnualExemption))
    }

    @Test
    fun `the harvesting thresholds match the file`() {
        val block = blockNamed("harvesting_alerts")

        assertEquals(
            numberIn(block, "exemption_alert_days_before_fy_end"),
            knowledge.exemptionAlertDaysBeforeFyEnd.toLong(),
        )
        assertEquals(numberIn(block, "stcg_to_ltcg_countdown_days"), knowledge.stcgToLtcgCountdownDays.toLong())
    }

    @Test
    fun `property is still marked unmodelled in the file, and absent from the engine`() {
        // §38.2 describes property's dual computation; TAX-002 sends complex cases to a
        // professional. The engine has no AssetClass for it on purpose, and this is the reminder
        // if the file ever starts modelling one.
        assertTrue("the file no longer marks property unmodelled", "\"modelled\": false" in assetBlock("property"))
        assertTrue(
            "an AssetClass for property now exists — §38.2's dual computation needs an indexation table",
            AssetClass.entries.none { it.name.contains("PROPERTY") },
        )
    }

    // --- parsing ----------------------------------------------------------------------------------

    private fun stringAt(key: String): String =
        Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(kb)?.groupValues?.get(1)
            ?: throw AssertionError("no \"$key\" in the tax KB")

    private fun blockNamed(name: String): String {
        val start = kb.indexOf("\"$name\"")
        if (start < 0) throw AssertionError("no \"$name\" block in the tax KB")
        val open = kb.indexOf('{', start)
        var depth = 0
        var close = -1
        for (index in open until kb.length) {
            if (kb[index] == '{') depth++
            if (kb[index] == '}') depth--
            if (depth == 0) {
                close = index
                break
            }
        }
        if (close < 0) throw AssertionError("\"$name\" block is unterminated")
        return kb.substring(open, close + 1)
    }

    private fun assetBlock(asset: String): String {
        val start = kb.indexOf("\"asset\": \"$asset\"")
        if (start < 0) throw AssertionError("no asset \"$asset\" in the tax KB")
        val open = kb.lastIndexOf('{', start)
        val close = kb.indexOf('}', start)
        return kb.substring(open, close + 1)
    }

    /** Result: a regime's slabs from the file, as (upper bound in rupees or null, rate in bps). */
    private fun slabsOf(regime: String): List<Pair<Long?, Long>> =
        Regex("\\{\\s*\"upto_inr\"\\s*:\\s*(null|\\d+),\\s*\"rate_bps\"\\s*:\\s*(\\d+)\\s*}")
            .findAll(blockNamed(regime))
            .map { it.groupValues[1].takeIf { value -> value != "null" }?.toLong() to it.groupValues[2].toLong() }
            .toList()

    private fun numberIn(
        block: String,
        key: String,
    ): Long =
        Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+)").find(block)?.groupValues?.get(1)?.toLong()
            ?: throw AssertionError("no \"$key\" in the block")

    private fun booleanIn(
        block: String,
        key: String,
    ): Boolean =
        Regex("\"$key\"\\s*:\\s*(true|false)").find(block)?.groupValues?.get(1)?.toBooleanStrict()
            ?: throw AssertionError("no \"$key\" in the block")

    /** Result: a Money as whole rupees, for comparing against the file's rupee figures. */
    private fun rupeesOf(amount: com.aicfo.core.model.Money): Long = amount.minor / PAISE

    private fun kbFile(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = File(directory, KB_PATH)
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("Could not find $KB_PATH walking up from ${File("").absolutePath}")
    }

    private companion object {
        const val KB_PATH = "ai/knowledge/tax-kb-fy2025-26.json"
        const val PAISE = 100L
    }
}
