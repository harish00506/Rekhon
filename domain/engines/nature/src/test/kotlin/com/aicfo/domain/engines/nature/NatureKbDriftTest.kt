package com.aicfo.domain.engines.nature

import com.aicfo.core.model.CategoryNature
import com.aicfo.core.model.RuleCitation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps [NatureRules] honest against `ai/knowledge/classification-kb.json` (§6, ADR-0016).
 *
 * Why:  CLAUDE.md §6 says the classification knowledge base is **data rows in `ai/`, never hardcoded
 *       logic**, and [NatureRules] is six hardcoded citations and eight hardcoded numbers. ADR-0016
 *       records the deferral; a deferral is only acceptable while the duplicate cannot drift, and
 *       "someone will remember to update both" is not a mechanism. This is the mechanism.
 *
 *       What drift would cost here is worse than for the merchant rules, because a nature is
 *       **invisible when it is wrong**. A misclassified merchant shows the user a category they can
 *       see is wrong; a misclassified nature shows them nothing at all — it moves a number inside
 *       the 50/30/20 ring, the savings rate and, later, the health score, all of which look
 *       perfectly ordinary while being built on a rule the published knowledge base no longer states.
 * What: parses the `CLS-NAT-*` steps and the `stage_nature` block out of the file and asserts every
 *       id, version, order and threshold still matches, with no step added or dropped on either side.
 * Result: the §6 deferral costs correctness nothing until a loader lands.
 * Changelog: 2026-08-10 — Created for issue 4.3, following `:core:model`'s and
 *            `:domain:engines:classification`'s copies of the same guard.
 *
 * **Parsed with regex, not a JSON library**, for the reason those copies state: this module is pure
 * Kotlin with no serialisation dependency (ARC-002), and adding one so a test can read a file would
 * be the tail wagging the dog. The parse is deliberately strict — a row that cannot be found fails
 * loudly rather than passing vacuously.
 *
 * **This test was verified to fail before it was trusted** — see the tracker's Verification Log.
 */
class NatureKbDriftTest {
    private val knowledgeBase: String by lazy { knowledgeBaseFile().readText() }

    /**
     * Input:  the repo's knowledge-base file.
     * Output: asserts it was found and holds both sections this test reads. Without this, every
     *         assertion below would pass vacuously against an empty string if the file ever moved.
     */
    @Test
    fun `the knowledge base is where this test thinks it is`() {
        assertTrue("classification KB looks empty or truncated", knowledgeBase.length > 1_000)
        assertTrue("nature_classification missing", "\"nature_classification\"" in knowledgeBase)
        assertTrue("stage_nature missing", "\"stage_nature\"" in knowledgeBase)
    }

    /**
     * Input:  `_meta.version`.
     * Output: asserts the file version [NatureRules] claims to have been copied from is the version
     *         on disk. A row-level change without a `_meta` bump is itself a governance failure
     *         (§29), so this catches the edit that forgot to record itself.
     */
    @Test
    fun `the rules name the knowledge-base version they were copied from`() {
        val meta = Regex("\"_meta\"\\s*:\\s*\\{(.*?)\\n {2}}", RegexOption.DOT_MATCHES_ALL).find(knowledgeBase)
        assertNotNull("_meta block not found — the knowledge base's shape changed", meta)
        assertEquals(NatureRules.KB_VERSION, meta!!.groupValues[1].stringField("version"))
    }

    /**
     * Input:  every `nature_classification.order` row and every mirrored citation.
     * Output: asserts the ids match **in order**, and that `step` says what the position says.
     *
     * §8.3.1 is a decision order and this file is the order's only published statement, so a
     * reordering of it is the single most consequential edit anyone can make here — it silently
     * changes which of two conflicting signals wins. Compared as an ordered list for that reason.
     */
    @Test
    fun `every step is mirrored, in order`() {
        val steps = orderRows()
        assertEquals("nature_classification.order holds a different number of steps", MIRRORED.size, steps.size)
        assertEquals(steps.map { it.ruleId }, MIRRORED.map { it.ruleId })
        steps.forEachIndexed { index, step ->
            assertEquals("${step.ruleId}: version", step.version, MIRRORED[index].ruleVersion)
            assertEquals("${step.ruleId}: step number", index + 1, step.step)
        }
    }

    /**
     * Input:  the `stage_nature` block.
     * Output: asserts every threshold the engine applies is the threshold the file publishes. These
     *         numbers decide how sure a verdict is and therefore what reaches §8.3's review flow; a
     *         mirror that drifted on them would flag things the file says are settled, or hide
     *         things it says are not.
     */
    @Test
    fun `the mirrored thresholds match stage_nature`() {
        val stage = objectSection("stage_nature")
        val rules = NatureRules()

        assertEquals("min_confidence_bps", stage.intField("min_confidence_bps"), rules.minConfidenceBps)
        assertEquals("account_override_bps", stage.intField("account_override_bps"), rules.accountOverrideBps)
        assertEquals("category_default_bps", stage.intField("category_default_bps"), rules.categoryDefaultBps)
        assertEquals("fallback_bps", stage.intField("fallback_bps"), rules.fallbackBps)
        assertEquals("unusual_amount_bps", stage.intField("unusual_amount_bps"), rules.unusualAmountBps)
        assertEquals("unusual_amount_multiple", stage.intField("unusual_amount_multiple"), rules.unusualAmountMultiple)
        assertEquals("min_history_for_median", stage.intField("min_history_for_median"), rules.minHistoryForMedian)
        assertEquals("fallback_nature", stage.stringField("fallback_nature"), rules.fallbackNature.kbValue)
    }

    /**
     * Input:  `stage_nature.true_spend_natures` and `conversion_natures`.
     * Output: asserts both lists match the mirror, compared through [CategoryNature.kbValue] — the
     *         one place the knowledge base's `INVESTMENT` and the column's `invest` are allowed to
     *         meet. These two lists are §8.3's `trueSpend` formula in data form, so a drift here
     *         changes what the app tells the user they spent.
     */
    @Test
    fun `the true-spend split matches the knowledge base`() {
        val stage = objectSection("stage_nature")
        val rules = NatureRules()

        assertEquals(stage.stringList("true_spend_natures"), rules.trueSpendNatures.map { it.kbValue }.sorted())
        assertEquals(stage.stringList("conversion_natures"), rules.conversionNatures.map { it.kbValue }.sorted())
    }

    /**
     * Input:  every nature named anywhere in `stage_nature`.
     * Output: asserts each is a nature this build knows. A knowledge base naming a sixth nature would
     *         otherwise reach [CategoryNature.fromKb], come back `null`, and be silently dropped from
     *         whichever list it was in.
     */
    @Test
    fun `every nature the knowledge base names is a known nature`() {
        val stage = objectSection("stage_nature")
        val named =
            stage.stringList("true_spend_natures") + stage.stringList("conversion_natures") +
                listOf(stage.stringField("fallback_nature"))

        named.forEach { assertNotNull("'$it' is not a CategoryNature", CategoryNature.fromKb(it)) }
    }

    /**
     * Parses `nature_classification.order` into comparable rows.
     * Result: the steps in file order. Input: none. Output: a list of [KbStep].
     */
    private fun orderRows(): List<KbStep> =
        Regex("\\{[^}]*}").findAll(section("order")).map { row ->
            val text = row.value
            KbStep(
                ruleId = text.stringField("rule_id"),
                version = text.stringField("version"),
                step = text.intField("step"),
            )
        }.toList()

    /**
     * Cuts one array out of the knowledge base.
     * Result: the array's text. Input: [name] — the JSON key. Output: [String]; fails when missing
     *         rather than returning empty, which would make every assertion over it pass vacuously.
     */
    private fun section(name: String): String {
        val match = Regex("\"$name\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL).find(knowledgeBase)
        assertNotNull("section '$name' not found — the knowledge base's shape changed", match)
        return match!!.groupValues[1]
    }

    /**
     * Cuts one object out of the knowledge base.
     * Why:    `stage_nature` is an object, so [section]'s bracket match cannot find it. The
     *         terminator is a closing brace at four-space indent, which is the shape the file is
     *         written in for a nested block.
     * Result: the object's text. Input: [name]. Output: [String]; fails when missing.
     */
    private fun objectSection(name: String): String {
        val match = Regex("\"$name\"\\s*:\\s*\\{(.*?)\\n {4}}", RegexOption.DOT_MATCHES_ALL).find(knowledgeBase)
        assertNotNull("object '$name' not found — the knowledge base's shape changed", match)
        return match!!.groupValues[1]
    }

    /**
     * Reads one string field out of a block's text.
     * Result: the value. Input: the receiver; [name]. Output: [String]; fails when the key is missing.
     */
    private fun String.stringField(name: String): String {
        val match = Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(this)
        assertNotNull("field '$name' not found in: ${take(FIELD_ERROR_EXCERPT)}", match)
        return match!!.groupValues[1]
    }

    /**
     * Reads one integer field out of a block's text.
     * Result: the value. Input: the receiver; [name]. Output: [Int]; fails when the key is missing or
     *         is not a whole number, which for a basis-point threshold means someone wrote it back as
     *         a fraction (MNY-002).
     */
    private fun String.intField(name: String): Int {
        val match = Regex("\"$name\"\\s*:\\s*(-?[0-9]+)\\s*[,\\n}]").find(this)
        assertNotNull("whole-number field '$name' not found in: ${take(FIELD_ERROR_EXCERPT)}", match)
        return match!!.groupValues[1].toInt()
    }

    /**
     * Reads one array-of-strings field out of a block's text.
     * Result: the values, **sorted**, so the comparison is about membership rather than about the
     *         order two sets happen to iterate in. Input: the receiver; [name]. Output: `List<String>`.
     */
    private fun String.stringList(name: String): List<String> {
        val match = Regex("\"$name\"\\s*:\\s*\\[([^]]*)]").find(this)
        assertNotNull("array field '$name' not found in: ${take(FIELD_ERROR_EXCERPT)}", match)
        return Regex("\"([^\"]+)\"").findAll(match!!.groupValues[1]).map { it.groupValues[1] }.sorted().toList()
    }

    /**
     * Finds `ai/knowledge/classification-kb.json` by walking up from the test's working directory.
     * Why:    Gradle runs JVM tests with the module directory as the working directory, but that is a
     *         default an IDE or a convention-plugin change can move. Walking up finds the file from
     *         anywhere inside the repo, with a readable message rather than a `FileNotFoundException`.
     * Result: the knowledge-base file. Input: none. Output: [File].
     */
    private fun knowledgeBaseFile(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = File(directory, KB_PATH)
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("Could not find $KB_PATH walking up from ${File("").absolutePath}")
    }

    /**
     * One `order` row as the file states it.
     * Input:  [ruleId], [version]; [step] — the file's own step number, checked against its position.
     * Output: an immutable value.
     */
    private data class KbStep(
        val ruleId: String,
        val version: String,
        val step: Int,
    )

    private companion object {
        const val KB_PATH = "ai/knowledge/classification-kb.json"
        const val FIELD_ERROR_EXCERPT = 120

        /** The six citations [NatureRules] holds, in §8.3.1's order. */
        val MIRRORED: List<RuleCitation> =
            listOf(
                NatureRules.LOAN_ACCOUNT,
                NatureRules.INVESTMENT_TRANSFER,
                NatureRules.ASSET_TRANSFER,
                NatureRules.USER_HISTORY,
                NatureRules.CATEGORY_DEFAULT,
                NatureRules.UNUSUAL_AMOUNT,
            )
    }
}
