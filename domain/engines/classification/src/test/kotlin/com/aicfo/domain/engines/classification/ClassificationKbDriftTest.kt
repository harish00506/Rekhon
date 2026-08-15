package com.aicfo.domain.engines.classification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps [ClassificationRules] honest against `ai/knowledge/classification-kb.json` (§6, ADR-0015).
 *
 * Why:  CLAUDE.md §6 says the classification knowledge base is **data rows in `ai/`, never hardcoded
 *       logic**, and [ClassificationRules] is thirteen hardcoded rows and four hardcoded numbers.
 *       ADR-0015 records the deferral and why it survives this issue; a deferral is only acceptable
 *       while the duplicate cannot drift, and "someone will remember to update both" is not a
 *       mechanism. This test is the mechanism.
 *
 *       What drift would cost here is specific and quiet. These rows decide where the app files the
 *       user's money by default. A merchant edited in the knowledge base but not here would keep
 *       classifying by the old rule while the published file said otherwise — and because a
 *       suggestion is *accepted* by the user simply not changing it, the divergence would be baked
 *       into the ledger rather than sitting in a cache someone could clear.
 * What: parses `merchant_rules` and `stage1` out of the knowledge base and asserts every id,
 *       version, match string, type, category and threshold still agrees, in order, with no row
 *       added or dropped on either side.
 * Result: the §6 deferral costs correctness nothing until a loader lands.
 * Changelog: 2026-08-10 — Created for issue 4.2, following `:core:model`'s and `:domain:engines:sms`'s
 *            copies of the same guard.
 *
 * **Parsed with regex, not a JSON library**, for the reason those copies state: this module is pure
 * Kotlin with no serialisation dependency (ARC-002), and adding one so a test can read a file would
 * be the tail wagging the dog. The parse is deliberately strict — a row that cannot be found fails
 * loudly rather than passing vacuously.
 *
 * **This test was verified to fail before it was trusted** — see the tracker's Verification Log for
 * the edits that were made to watch it go red. This project has already shipped one gate that passed
 * vacuously (governance audit G-01's 0 %-coverage `koverVerify`), so a guard is not counted as a
 * guard here until it has been seen to bite.
 */
class ClassificationKbDriftTest {
    private val knowledgeBase: String by lazy { knowledgeBaseFile().readText() }

    /**
     * Input:  the repo's knowledge-base file.
     * Output: asserts it was found and holds both sections this test reads. Without this, every
     *         assertion below would pass vacuously against an empty string if the file ever moved.
     */
    @Test
    fun `the knowledge base is where this test thinks it is`() {
        assertTrue("classification KB looks empty or truncated", knowledgeBase.length > 1_000)
        assertTrue("merchant_rules missing", "\"merchant_rules\"" in knowledgeBase)
        assertTrue("stage1 missing", "\"stage1\"" in knowledgeBase)
    }

    /**
     * Input:  `_meta.version`.
     * Output: asserts the file version [ClassificationRules] claims to have been copied from is the
     *         version on disk. A row-level change without a `_meta` bump is itself a governance
     *         failure (§29), so this catches the edit that forgot to record itself.
     */
    @Test
    fun `the rules name the knowledge-base version they were copied from`() {
        val meta = Regex("\"_meta\"\\s*:\\s*\\{(.*?)\\n {2}}", RegexOption.DOT_MATCHES_ALL).find(knowledgeBase)
        assertNotNull("_meta block not found — the knowledge base's shape changed", meta)
        assertEquals(ClassificationRules.KB_VERSION, meta!!.groupValues[1].stringField("version"))
    }

    /**
     * Input:  every `merchant_rules` row and every mirrored rule.
     * Output: asserts the two lists are the same length. Checked separately from, and before, the
     *         field comparison, because an equality failure on two lists of different sizes reports
     *         the first differing element and hides the fact that a row was dropped.
     */
    @Test
    fun `every merchant_rules row is mirrored, and no others`() {
        val rows = merchantRows()
        assertTrue("merchant_rules parsed as empty — the file's shape changed", rows.isNotEmpty())
        assertEquals(
            "the knowledge base and ClassificationRules hold different numbers of merchant rules",
            rows.size,
            ClassificationRules.KB_MERCHANT_RULES.size,
        )
        assertEquals(rows.map { it.ruleId }, ClassificationRules.KB_MERCHANT_RULES.map { it.ruleId })
    }

    /**
     * Input:  every `merchant_rules` row.
     * Output: asserts each row's version, match string, type and category match the mirror's. The
     *         match string is compared **verbatim**, alternation pipes and all: the engine splits it,
     *         but what governance cares about is that the file and the code hold the same rule.
     */
    @Test
    fun `the mirrored rules match merchant_rules`() {
        merchantRows().zip(ClassificationRules.KB_MERCHANT_RULES).forEach { (kb, mirrored) ->
            assertEquals("${kb.ruleId}: rule id", kb.ruleId, mirrored.ruleId)
            assertEquals("${kb.ruleId}: version", kb.version, mirrored.version)
            assertEquals("${kb.ruleId}: match", kb.match, mirrored.match)
            assertEquals("${kb.ruleId}: type", kb.type, mirrored.type.kbValue)
            assertEquals("${kb.ruleId}: category", kb.category, mirrored.category)
        }
    }

    /**
     * Input:  every `merchant_rules` row's `type`.
     * Output: asserts each one is a type this build knows. A knowledge base naming a third match
     *         type — `starts_with`, say — would otherwise be mirrored as whichever of the two the
     *         author guessed, and matched by rules the file never stated.
     */
    @Test
    fun `every match type in the knowledge base is a known type`() {
        merchantRows().forEach { row ->
            assertNotNull(
                "${row.ruleId}: '${row.type}' is not a MerchantMatchType",
                MerchantMatchType.fromKb(row.type),
            )
        }
    }

    /**
     * Input:  the `stage1` block.
     * Output: asserts every threshold the engine applies is the threshold the file publishes. These
     *         four numbers decide whether the app proposes a category at all; a mirror that drifted
     *         on them would defer where the file says it should suggest, or the reverse.
     */
    @Test
    fun `the mirrored thresholds match stage1`() {
        val stage1 = objectSection("stage1")
        val rules = ClassificationRules()

        assertEquals("min_confidence_bps", stage1.intField("min_confidence_bps"), rules.minConfidenceBps)
        assertEquals("exact_match_bps", stage1.intField("exact_match_bps"), rules.exactMatchBps)
        assertEquals("word_match_bps", stage1.intField("word_match_bps"), rules.wordMatchBps)
        assertEquals(
            "history_min_occurrences",
            stage1.intField("history_min_occurrences"),
            rules.historyMinOccurrences,
        )
        assertEquals("user_history_rule_id", stage1.stringField("user_history_rule_id"), rules.userHistory.ruleId)
        assertEquals("user_history_version", stage1.stringField("user_history_version"), rules.userHistory.ruleVersion)
    }

    /**
     * Input:  every mirrored rule's category name and the knowledge base's `category_defaults`.
     * Output: asserts each merchant rule points at a category the defaults actually define.
     *
     * **This is the gate on a whole class of silent failure.** The engine resolves a rule's category
     * by name against the profile's live taxonomy, which is seeded from `category_defaults`. A rule
     * naming `Groceries ` with a trailing space, or `Food` where the defaults say `Dining`, would
     * match merchants perfectly and then resolve to nothing — the tier would appear to work, in
     * tests written against the rule, and classify none of them on a real device.
     */
    @Test
    fun `every merchant rule points at a category the defaults define`() {
        val defined =
            Regex("\"category\"\\s*:\\s*\"([^\"]+)\"")
                .findAll(section("category_defaults"))
                .map { it.groupValues[1] }
                .toSet()

        assertTrue("category_defaults parsed as empty — the file's shape changed", defined.isNotEmpty())
        ClassificationRules.KB_MERCHANT_RULES.forEach { rule ->
            assertTrue(
                "${rule.ruleId} points at '${rule.category}', which category_defaults does not define",
                rule.category in defined,
            )
        }
    }

    /**
     * Parses `merchant_rules` into comparable rows.
     * Why:    every assertion above reads the same five fields; parsing once keeps the strictness in
     *         one place, so a shape change fails in one message rather than four.
     * Result: the rows in file order. Input: none. Output: a list of [KbMerchantRule].
     */
    private fun merchantRows(): List<KbMerchantRule> =
        Regex("\\{[^}]*}").findAll(section("merchant_rules")).map { row ->
            val text = row.value
            KbMerchantRule(
                ruleId = text.stringField("rule_id"),
                version = text.stringField("version"),
                match = text.stringField("match"),
                type = text.stringField("type"),
                category = text.stringField("category"),
            )
        }.toList()

    /**
     * Cuts one top-level array out of the knowledge base.
     * Result: the array's text. Input: [name] — the JSON key.
     * Output: [String]; fails the test when the section is missing rather than returning empty,
     *         which would make every assertion over it pass against nothing.
     */
    private fun section(name: String): String {
        val match = Regex("\"$name\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL).find(knowledgeBase)
        assertNotNull("section '$name' not found — the knowledge base's shape changed", match)
        return match!!.groupValues[1]
    }

    /**
     * Cuts one top-level object out of the knowledge base.
     * Why:    `stage1` is an object, not an array, so [section]'s bracket match cannot find it. The
     *         terminator is a closing brace at two-space indent, which is the shape the file is
     *         written in — the same anchor the `_meta` assertion above relies on.
     * Result: the object's text. Input: [name] — the JSON key. Output: [String]; fails when missing.
     */
    private fun objectSection(name: String): String {
        val match = Regex("\"$name\"\\s*:\\s*\\{(.*?)\\n {2}}", RegexOption.DOT_MATCHES_ALL).find(knowledgeBase)
        assertNotNull("object '$name' not found — the knowledge base's shape changed", match)
        return match!!.groupValues[1]
    }

    /**
     * Reads one string field out of a row's text.
     * Result: the value. Input: the receiver — a row; [name] — the JSON key.
     * Output: [String]; fails the test when the key is missing rather than defaulting.
     */
    private fun String.stringField(name: String): String {
        val match = Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(this)
        assertNotNull("field '$name' not found in: ${take(FIELD_ERROR_EXCERPT)}", match)
        return match!!.groupValues[1]
    }

    /**
     * Reads one integer field out of a block's text.
     * Result: the value. Input: the receiver — a block; [name] — the JSON key.
     * Output: [Int]; fails the test when the key is missing or is not a whole number, which for a
     *         basis-point threshold means someone has written it back as a fraction (MNY-002).
     */
    private fun String.intField(name: String): Int {
        val match = Regex("\"$name\"\\s*:\\s*(-?[0-9]+)\\s*[,\\n}]").find(this)
        assertNotNull("whole-number field '$name' not found in: ${take(FIELD_ERROR_EXCERPT)}", match)
        return match!!.groupValues[1].toInt()
    }

    /**
     * Finds `ai/knowledge/classification-kb.json` by walking up from the test's working directory.
     * Why:    Gradle runs JVM tests with the module directory as the working directory, but that is a
     *         default an IDE or a future convention-plugin change can move. Walking up finds the file
     *         from anywhere inside the repo, with a readable message rather than a
     *         `FileNotFoundException` naming a path nobody expected.
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
     * One `merchant_rules` row as the file states it.
     * Input:  [ruleId], [version], [match], [category]; [type] — the **knowledge base's** spelling,
     *         deliberately still a string so the test compares vocabularies rather than assuming they
     *         are the same one. Output: an immutable value.
     */
    private data class KbMerchantRule(
        val ruleId: String,
        val version: String,
        val match: String,
        val type: String,
        val category: String,
    )

    private companion object {
        const val KB_PATH = "ai/knowledge/classification-kb.json"
        const val FIELD_ERROR_EXCERPT = 120
    }
}
