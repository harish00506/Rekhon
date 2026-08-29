package com.aicfo.domain.engines.investment

import com.aicfo.core.model.AssetClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps [InvestmentRules] honest against `ai/rules/rules-kb.json` (CLAUDE.md §6, ADR-0005,
 * ADR-0017).
 *
 * Why:  §6 says a financial threshold is a **data row in `ai/`, never a hardcoded number**, and
 *       this engine holds hardcoded numbers — the deliberate deferral ADR-0017 records. A deferral
 *       is only acceptable while the duplicate cannot drift, and "someone will remember to update
 *       both" is not a mechanism. This is the mechanism.
 * What: parses all three cited rows out of the rulebook and asserts every parameter this engine
 *       applies, plus the revision it was copied from.
 * Result: the §6 deferral costs correctness nothing until the runtime loader lands.
 * Changelog: 2026-08-28 — Created for issue 6.4.
 *
 * **Parsed with regex, not a JSON library, on purpose.** `:domain:*` is pure Kotlin with no
 * serialisation dependency (ARC-002), and adding one to a *test* to check four values would be the
 * tail wagging the dog. The parse is strict — a rule that cannot be found fails loudly rather than
 * passing vacuously. This is `:domain:engines:card`'s test, one rule over.
 *
 * **`cap_pct` appears on two different rows**, which is the one thing this file has to be careful
 * about that its ancestors did not. `RULE-GOLD-CAP` and `RULE-CRYPTO-CAP` share `formula_id`
 * `asset_class_cap` and both expose a bare `cap_pct`, and [intParam] takes the *first* match in
 * whatever it is given. Every read below therefore goes through [ruleBlock], which slices one row
 * out by id — reading `cap_pct` off the whole file would silently assert gold's ceiling twice and
 * never check crypto's at all.
 *
 * **None of it runs unless `build.gradle.kts` declares the rulebook a test input.** Without that
 * line Gradle keeps the task UP-TO-DATE when only the JSON changes, and the gate reports green
 * against a file it never read — the failure found in `:domain:engines:sms` on 2026-08-07.
 */
class RulebookDriftTest {
    private val rulebook: String by lazy { rulebookFile().readText() }

    /**
     * Input:  the repo's rulebook file.
     * Output: asserts it was found and is non-trivial. Without this, every assertion below would
     *         pass vacuously against an empty string if the file ever moved.
     */
    @Test
    fun `the rulebook is where this test thinks it is`() {
        assertTrue("rulebook looks empty or truncated", rulebook.length > 1_000)
        InvestmentRules.CITATIONS.forEach { citation ->
            assertTrue("${citation.ruleId} missing from the rulebook", citation.ruleId in rulebook)
        }
    }

    /** Input: the file's `_meta.version`. Output: asserts the engine copied from this revision. */
    @Test
    fun `the engine names the rulebook revision it copied from`() {
        val meta = rulebook.substringBefore("\"rules\"")
        assertEquals(InvestmentRules.RULEBOOK_VERSION, meta.version())
    }

    /**
     * Input:  RULE-GOLD-CAP's params.
     * Output: asserts gold's ceiling and the class it applies to.
     *
     * The `asset_class` assertion is not decoration: the mirror keys its caps by [AssetClass], and
     * the enum's `storedValue` is what ties the Kotlin constant to the rulebook string. If the row
     * ever named something else, the cap would silently apply to a class the rulebook never
     * capped.
     */
    @Test
    fun `gold's ceiling matches RULE-GOLD-CAP`() {
        val row = ruleBlock("RULE-GOLD-CAP")
        val cap = InvestmentRules().assetClassCaps.getValue(AssetClass.GOLD)

        assertEquals(cap.capPct, row.intParam("cap_pct"))
        assertEquals(AssetClass.GOLD.storedValue, row.stringParam("asset_class"))
        assertEquals(cap.citation.ruleVersion, row.version())
    }

    /** Input: RULE-CRYPTO-CAP's params. Output: asserts crypto's ceiling and the class it applies to. */
    @Test
    fun `crypto's ceiling matches RULE-CRYPTO-CAP`() {
        val row = ruleBlock("RULE-CRYPTO-CAP")
        val cap = InvestmentRules().assetClassCaps.getValue(AssetClass.CRYPTO)

        assertEquals(cap.capPct, row.intParam("cap_pct"))
        assertEquals(AssetClass.CRYPTO.storedValue, row.stringParam("asset_class"))
        assertEquals(cap.citation.ruleVersion, row.version())
    }

    /** Input: RULE-CONC-15-70's params. Output: asserts both concentration ceilings. */
    @Test
    fun `the concentration ceilings match RULE-CONC-15-70`() {
        val rules = InvestmentRules()
        val row = ruleBlock("RULE-CONC-15-70")

        assertEquals(rules.singleHoldingPct, row.intParam("single_holding_pct"))
        assertEquals(rules.singleClassPct, row.intParam("single_class_pct"))
        assertEquals(InvestmentRules.CONCENTRATION.ruleVersion, row.version())
    }

    /**
     * Input:  every rule id the engine cites.
     * Output: asserts each exists and is enabled. A citation pointing at a disabled rule would show
     *         the user a reason that is no longer the app's.
     */
    @Test
    fun `every rule the engine cites exists and is enabled`() {
        InvestmentRules.CITATIONS.forEach { citation ->
            val row = ruleBlock(citation.ruleId)
            assertTrue(
                "${citation.ruleId} is disabled in the rulebook but still cited",
                "\"enabled\": true" in row,
            )
        }
    }

    /**
     * Input:  the two rows the two ceilings live on.
     * Output: asserts neither grew the other's parameters.
     *
     * Why:    the tripwire the precedent demands. All three rows are shipped at version 1.0 and
     *         cited in provenance stored on users' devices; adding a param to any of them bumps
     *         that version, which is ADR-0017's trigger 3 and forces the runtime rules loader
     *         before this module can compile again. The concentration row is the tempting home for
     *         a per-class cap, because it is already the concentration rule — but it answers "is
     *         any one thing too much of the whole", not "is *this class* past the line the rulebook
     *         drew for it specifically".
     */
    @Test
    fun `the class caps and the concentration ceilings stay on their own rows`() {
        listOf("single_holding_pct", "single_class_pct").forEach { key ->
            listOf("RULE-GOLD-CAP", "RULE-CRYPTO-CAP").forEach { ruleId ->
                assertTrue(
                    "'$key' appeared on $ruleId. The concentration ceilings belong to " +
                        "RULE-CONC-15-70: adding one here bumps a shipped row's version, which is " +
                        "ADR-0017 trigger 3 and forces the runtime rules loader before this module " +
                        "can compile again",
                    "\"$key\"" !in ruleBlock(ruleId),
                )
            }
        }
        assertTrue(
            "'cap_pct' appeared on RULE-CONC-15-70 as well as on the class-cap rows. One threshold, " +
                "one row — two would let the chart and the flag disagree with both tests green",
            "\"cap_pct\"" !in ruleBlock("RULE-CONC-15-70"),
        )
    }

    /**
     * Input:  the two class-cap rows.
     * Output: asserts they remain two rows about two different classes.
     *
     * The failure this guards is subtle and total: if a copy-paste ever made both rows name
     * `"gold"`, [ruleBlock] would still find two blocks, both `cap_pct` assertions would still
     * pass against a mirror that had been edited to match, and crypto would quietly stop being
     * capped while every test in this file stayed green.
     */
    @Test
    fun `the two class caps name two different classes`() {
        val classes = listOf("RULE-GOLD-CAP", "RULE-CRYPTO-CAP").map { ruleBlock(it).stringParam("asset_class") }

        assertEquals("the two class-cap rows must cap two different classes", classes.toSet().size, classes.size)
    }

    // --- parsing ------------------------------------------------------------------------------

    /**
     * Extracts one rule's JSON object as raw text.
     * Why:    the params are only unambiguous within their own row — `version` appears on every one,
     *         and `cap_pct` on two.
     * Result: the text from this rule's id to the start of the next rule.
     * Input:  [ruleId]. Output: the row's text; fails the test if the id is absent.
     */
    private fun ruleBlock(ruleId: String): String {
        val start = rulebook.indexOf("\"rule_id\": \"$ruleId\"")
        assertTrue("$ruleId is not in the rulebook — the engine cites a rule that no longer exists", start >= 0)
        val next = rulebook.indexOf("\"rule_id\":", start + 1)
        return if (next < 0) rulebook.substring(start) else rulebook.substring(start, next)
    }

    /**
     * Reads one integer parameter out of a rule's text.
     * Result: the value. Input: the receiver — a rule block; [name] — the JSON key.
     * Output: [Int]; fails the test when the key is missing rather than defaulting.
     */
    private fun String.intParam(name: String): Int {
        val match = Regex("\"$name\"\\s*:\\s*(-?\\d+)").find(this)
        assertNotNull("parameter '$name' not found — the rulebook's shape changed", match)
        return match!!.groupValues[1].toInt()
    }

    /**
     * Reads one string parameter out of a rule's text.
     * Result: the value. Input: the receiver; [name]. Output: [String]; fails when the key is
     *         missing, so a renamed key cannot read as absent and quietly agree with the engine.
     */
    private fun String.stringParam(name: String): String {
        val match = Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"").find(this)
        assertNotNull("parameter '$name' not found — the rulebook's shape changed", match)
        return match!!.groupValues[1]
    }

    /** Result: the block's `version`. Input: the receiver — a rule block or the meta block. */
    private fun String.version(): String {
        val match = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(this)
        assertNotNull("no version here — AI-ARC-006 requires one", match)
        return match!!.groupValues[1]
    }

    /**
     * Finds `ai/rules/rules-kb.json` by walking up from the test's working directory.
     * Why:    Gradle runs JVM tests with the module directory as the working directory, but that is
     *         a default an IDE or a future convention-plugin change can move. Walking up finds the
     *         file from anywhere inside the repo, and fails with a readable message rather than a
     *         `FileNotFoundException` naming a path nobody expected.
     * Result: the rulebook file. Input: none. Output: [File].
     */
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
