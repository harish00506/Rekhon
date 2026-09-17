package com.aicfo.domain.engines.orderofoperations

import com.aicfo.core.model.Money
import com.aicfo.core.model.RuleCitation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * Keeps [OrderOfOperationsRules] and [FooStage] honest against
 * `ai/rules/financial-order-of-operations.json` (issue 7.5; FOO-003, CLAUDE.md §6, ADR-0017).
 *
 * Why:  the engine holds hardcoded copies of the FOO file's thresholds — the deliberate deferral
 *       every engine here makes, because nothing loads `ai/` at runtime. That is only acceptable
 *       while the copy cannot drift, and this test is what stops it: edit a stage in the file and
 *       the build goes red until the engine agrees.
 * What: parses each stage out of the FOO file and asserts its mirrored parameters, the file version,
 *       the stage order, the parameters deliberately *not* mirrored, and the rulebook rows the file
 *       points at.
 * Result: FOO-003's "editable, versioned, cited" holds for the test even before it holds at runtime.
 * Changelog: 2026-09-17 — Created for issue 7.5.
 *
 * **Parsed with regex, not a JSON library, on purpose** — `RulebookDriftTest`'s reason: `:domain:*`
 * has no serialisation dependency (ARC-002), and the parse is strict, so a missing key fails loudly
 * rather than passing vacuously.
 *
 * **`build.gradle.kts` declares the FOO file as an input to this task.** Without that, editing the
 * file alone leaves this test UP-TO-DATE and the gate green against a file it never re-read.
 */
class OrderOfOperationsRulesDriftTest {
    private val foo: String by lazy { repoFile(FOO_PATH).readText() }
    private val rulebook: String by lazy { repoFile(RULEBOOK_PATH).readText() }
    private val rules = OrderOfOperationsRules()

    /** Input: the FOO file. Output: asserts it was found and is not a stub. */
    @Test
    fun `the FOO file is where this test thinks it is`() {
        assertTrue("FOO file looks empty or truncated", foo.length > MIN_FILE_LENGTH)
        assertTrue("the FOO file has no stages array", "\"stages\"" in foo)
    }

    /** Input: `_meta.version`. Output: asserts every stage citation names the revision copied from. */
    @Test
    fun `the engine names the FOO revision it copied from`() {
        val meta = foo.substringBefore("\"stages\"")
        assertEquals(OrderOfOperationsRules.FILE_VERSION, meta.stringParam("version"))
    }

    /**
     * Input:  the file's `stages` array.
     * Output: asserts the stage ids, in the file's order, are exactly [FooStage] — and that each
     *         stage's number is its position.
     *
     * [FooStage]'s declaration order *is* the ranking. If the file reorders two stages, or the enum
     * does, the advice inverts while every amount test stays green.
     */
    @Test
    fun `the stages and their order match the FOO file exactly`() {
        val ids = Regex("\"id\"\\s*:\\s*\"([A-Z_]+)\"").findAll(foo).map { it.groupValues[1] }.toList()
        assertEquals(FooStage.entries.map { it.fileId }, ids)

        FooStage.entries.forEachIndexed { index, stage ->
            assertEquals("${stage.fileId}'s stage number", index, stageBlock(stage).intParam("stage"))
        }
    }

    /** Input: Stage 0's params. Output: asserts the cap (in paise) and the month count. */
    @Test
    fun `the starter buffer matches Stage 0`() {
        val block = stageBlock(FooStage.STARTER_BUFFER)

        assertEquals(rules.starterCap, Money(block.intParam("cap_inr").toLong() * PAISE_PER_RUPEE))
        assertEquals(rules.starterEssentialsMonths, block.intParam("or_months_essentials"))
    }

    /**
     * Input:  Stage 2's params.
     * Output: asserts the fire threshold, converted from the file's percentage to basis points.
     *
     * The conversion is done here by string arithmetic on the decimal, never through a `Double`, so
     * the test cannot pass on a rounding accident — `13.5` must become exactly `1350`.
     */
    @Test
    fun `the fire threshold matches Stage 2 in basis points`() {
        val block = stageBlock(FooStage.KILL_FIRE_DEBT)

        assertEquals(rules.fireAprThresholdBps, block.percentParamAsBps("apr_threshold_pct"))
    }

    /**
     * Input:  Stage 6's params.
     * Output: asserts the grey floor and the equity comparison, and that the band's *upper* figure is
     *         still 12% and still below the fire threshold.
     *
     * The upper figure is **deliberately not mirrored** — the band runs to the fire threshold so no
     * debt falls between Stages 2 and 6 (see [OrderOfOperationsRules] and ADR-0037). What must stay
     * true is that the file still says what that reading assumes: a typical top below the fire line.
     * If someone raised it past 13.5%, the reading would no longer follow from the file.
     */
    @Test
    fun `the grey band and the equity comparison match Stage 6`() {
        val block = stageBlock(FooStage.GREY_ZONE_DEBT)
        val range = Regex("\"apr_range_pct\"\\s*:\\s*\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*]").find(block)
        assertNotNull("Stage 6 has no apr_range_pct pair", range)

        assertEquals(rules.greyAprMinBps, range!!.groupValues[1].toInt() * BPS_PER_PERCENT)
        assertEquals(TYPICAL_GREY_TOP_PCT, range.groupValues[2].toInt())
        assertTrue(
            "Stage 6's typical top (${range.groupValues[2]}%) must sit below the fire threshold",
            range.groupValues[2].toInt() * BPS_PER_PERCENT < rules.fireAprThresholdBps,
        )
        assertEquals(rules.equityNominalBps, block.intParam("equity_nominal_pct") * BPS_PER_PERCENT)
    }

    /**
     * Input:  Stage 4's params.
     * Output: asserts Stage 4 is still conditional on the regime comparator — the reason the engine
     *         always skips it, and the reason `nps_1b_inr` is not mirrored.
     */
    @Test
    fun `Stage 4 still depends on the regime comparator, so skipping it is still right`() {
        val block = stageBlock(FooStage.TAX_ADVANTAGED)

        assertTrue("Stage 4 is no longer gated on §38's comparator", "old_regime_wins" in block)
        assertEquals(NPS_1B_INR, block.intParam("nps_1b_inr"))
    }

    /**
     * Input:  the rulebook rows the FOO file points at.
     * Output: asserts each exists, is enabled, and is at the version the engine cites — and that
     *         Stage 3 and Stage 5 still name them.
     *
     * The engine holds these as **citations, not mirrors** (ADR-0035). Nothing here can drift from
     * their numbers because nothing here holds them; what must stay true is that the rows cited are
     * real, switched on and at the version stamped into every stored ranking (AI-ARC-006).
     */
    @Test
    fun `the rulebook rows the stages cite exist, are enabled and are at the cited version`() {
        assertEquals("RULE-EMERG-FIRST", stageBlock(FooStage.FULL_EMERGENCY).stringParam("gate_rule"))
        assertEquals("RULE-HORIZON", stageBlock(FooStage.GOAL_INVESTING).stringParam("bucket_rule"))

        listOf(
            OrderOfOperationsRules.EMERGENCY_FIRST,
            OrderOfOperationsRules.HORIZON,
            OrderOfOperationsRules.PREPAY_VS_INVEST,
        ).forEach(::assertRuleIsLive)
        assertTrue(
            "RULE-EMERG-FIRST no longer names AI-FOO in consumed_by, so Stage 3's gate citation is stale",
            "AI-FOO" in ruleBlock(OrderOfOperationsRules.EMERGENCY_FIRST.ruleId),
        )
        assertTrue(
            "RULE-PREPAY-VS-INVEST no longer names AI-FOO.stage7, so Stage 7's citation is stale",
            "AI-FOO.stage7" in ruleBlock(OrderOfOperationsRules.PREPAY_VS_INVEST.ruleId),
        )
    }

    /**
     * Input:  [OrderOfOperationsRules]' declared fields.
     * Output: asserts the mirror holds exactly the five parameters the engine applies.
     *
     * The same guard `RulebookDriftTest` keeps on `GoalRules`. Adding `minRunwayMonths` here is the
     * obvious next step once you notice Stage 3's gate number is not asserted above, and doing so
     * would fire ADR-0017's trigger 2 without anyone noticing. A new field is not forbidden — it is
     * **stop and think**, with an ADR.
     */
    @Test
    fun `the mirror holds exactly the parameters the engine applies`() {
        val mirrored =
            OrderOfOperationsRules::class.java.declaredFields
                .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
                .map { it.name }
                .toSet()

        assertEquals(
            "OrderOfOperationsRules' fields changed. RULE-EMERG-FIRST's number reaches the engine as an " +
                "input (ADR-0035); nps_1b_inr and apr_range_pct[1] are deliberately unmirrored (ADR-0037)",
            setOf("starterCap", "starterEssentialsMonths", "fireAprThresholdBps", "greyAprMinBps", "equityNominalBps"),
            mirrored,
        )
    }

    // --- parsing ------------------------------------------------------------------------------

    /**
     * Result: one stage's JSON object as raw text, from its `"stage": n` to the next stage.
     * Input:  [stage]. Output: the block; fails the test if the stage id is absent.
     */
    private fun stageBlock(stage: FooStage): String {
        val stagesStart = foo.indexOf("\"stages\"")
        val idAt = foo.indexOf("\"id\": \"${stage.fileId}\"", stagesStart)
        assertTrue("${stage.fileId} is not in the FOO file", idAt >= 0)
        val start = foo.lastIndexOf("\"stage\":", idAt)
        val next = foo.indexOf("\"stage\":", idAt)
        return if (next < 0) foo.substring(start) else foo.substring(start, next)
    }

    /** Result: one rulebook row's text. Input: [ruleId]. Output: the row; fails if absent. */
    private fun ruleBlock(ruleId: String): String {
        val start = rulebook.indexOf("\"rule_id\": \"$ruleId\"")
        assertTrue("$ruleId is not in the rulebook — a FOO stage cites a rule that no longer exists", start >= 0)
        val next = rulebook.indexOf("\"rule_id\":", start + 1)
        return if (next < 0) rulebook.substring(start) else rulebook.substring(start, next)
    }

    /** Asserts one cited rule is present, enabled and at [citation]'s version. */
    private fun assertRuleIsLive(citation: RuleCitation) {
        val row = ruleBlock(citation.ruleId)
        assertEquals("${citation.ruleId}'s version", citation.ruleVersion, row.stringParam("version"))
        assertTrue("${citation.ruleId} is disabled — a stage cites a switched-off rule", "\"enabled\": true" in row)
    }

    /** Result: an integer param. Input: the receiver; [name]. Output: [Int]; fails when absent. */
    private fun String.intParam(name: String): Int {
        val match = Regex("\"$name\"\\s*:\\s*(-?\\d+)(?![.\\d])").find(this)
        assertNotNull("integer parameter '$name' not found — the FOO file's shape changed", match)
        return match!!.groupValues[1].toInt()
    }

    /** Result: a string param. Input: the receiver; [name]. Output: [String]; fails when absent. */
    private fun String.stringParam(name: String): String {
        val match = Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"").find(this)
        assertNotNull("string parameter '$name' not found — the file's shape changed", match)
        return match!!.groupValues[1]
    }

    /**
     * Result: a percentage param as basis points, by decimal string arithmetic — `13.5` → `1350`.
     * Input:  the receiver; [name]. Output: [Int]; fails when absent or finer than a basis point.
     */
    private fun String.percentParamAsBps(name: String): Int {
        val match = Regex("\"$name\"\\s*:\\s*(\\d+)(?:\\.(\\d{1,2}))?(?!\\d)").find(this)
        assertNotNull("percentage parameter '$name' not found, or finer than a basis point", match)
        val whole = match!!.groupValues[1].toInt()
        val fraction = match.groupValues[2].padEnd(2, '0').toInt()
        return whole * BPS_PER_PERCENT + fraction
    }

    /**
     * Result: a file under the repo root, found by walking up from the working directory — the
     *         approach `RulebookDriftTest` takes, for the same reason.
     * Input:  [path]. Output: [File].
     */
    private fun repoFile(path: String): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = File(directory, path)
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("Could not find $path walking up from ${File("").absolutePath}")
    }

    private companion object {
        const val FOO_PATH = "ai/rules/financial-order-of-operations.json"
        const val RULEBOOK_PATH = "ai/rules/rules-kb.json"

        /** Short enough that a truncated file cannot pass the sanity check. */
        const val MIN_FILE_LENGTH = 1_000

        const val PAISE_PER_RUPEE = 100L
        const val BPS_PER_PERCENT = 100

        /** Stage 6's `apr_range_pct[1]`, asserted but not mirrored (ADR-0037). */
        const val TYPICAL_GREY_TOP_PCT = 12

        /** Stage 4's `nps_1b_inr`, asserted but not mirrored — Stage 4 never computes. */
        const val NPS_1B_INR = 50_000
    }
}
