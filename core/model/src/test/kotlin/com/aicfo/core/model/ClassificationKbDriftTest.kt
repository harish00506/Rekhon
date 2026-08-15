package com.aicfo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps [CategorySeed] honest against `ai/knowledge/classification-kb.json` (CLAUDE.md §6, ADR-0014).
 *
 * Why:  §6 says the classification knowledge base is **data rows in `ai/`, never hardcoded logic**,
 *       and [CategorySeed] is fifteen hardcoded rows — the deferral ADR-0014 records, for the reason
 *       ADR-0005 first recorded it: nothing in the app loads `ai/` at runtime yet. A deferral is only
 *       acceptable while the duplicate cannot drift, and "someone will remember to update both" is
 *       not a mechanism. This test is the mechanism.
 *
 *       What drift would cost here is specific. These rows are written **once**, when a profile is
 *       first seeded, and then they are the user's own taxonomy — renamed, re-natured, deleted. A
 *       nature that disagreed with the knowledge base would therefore not be corrected on the next
 *       launch; it would be baked into every budget, every 50/30/20 ring and every Purchase-Advisor
 *       verdict built on that profile, while the published knowledge base said something else.
 * What: parses `category_defaults` out of the knowledge base and asserts every id, version, key,
 *       name and nature still matches, in order, with no row added or dropped on either side.
 * Result: the §6 deferral costs correctness nothing until the loader lands (ADR-0014's trigger).
 * Changelog: 2026-08-08 — Created for issue 4.1, following `:domain:engines:sms`'s copy.
 *
 * **Parsed with regex, not a JSON library**, for the reason the SMS engine's copy of this test
 * states: `:core:model` is pure Kotlin with no serialisation dependency (ARC-002), and adding one so
 * a test can read a file would be the tail wagging the dog. The parse is deliberately strict — a row
 * that cannot be found fails loudly rather than passing vacuously.
 *
 * **This test was verified to fail before it was trusted.** Changing `CLS-CAT-009`'s `default_nature`
 * from `WANT` to `NEED` in the knowledge base was confirmed to turn `the seed rows match
 * category_defaults` red, and deleting a row was confirmed to turn `every category_defaults row is
 * seeded, and no others` red. This project has already shipped one gate that passed vacuously
 * (governance audit G-01's 0%-coverage `koverVerify`), so a guard is not counted as a guard here
 * until it has been seen to bite.
 */
class ClassificationKbDriftTest {
    private val knowledgeBase: String by lazy { knowledgeBaseFile().readText() }

    /**
     * Input:  the repo's knowledge-base file.
     * Output: asserts it was found and holds the section this test reads. Without this, every
     *         assertion below would pass vacuously against an empty string if the file ever moved.
     */
    @Test
    fun `the knowledge base is where this test thinks it is`() {
        assertTrue("classification KB looks empty or truncated", knowledgeBase.length > 1_000)
        assertTrue("category_defaults missing from the knowledge base", "\"category_defaults\"" in knowledgeBase)
    }

    /**
     * Input:  `_meta.version`.
     * Output: asserts the file version [CategorySeed] claims to have been copied from is the file
     *         version on disk. A row-level change without a `_meta` bump is itself a governance
     *         failure (§29), so this catches the edit that forgot to record itself.
     */
    @Test
    fun `the seed names the knowledge-base version it was copied from`() {
        val meta = Regex("\"_meta\"\\s*:\\s*\\{(.*?)\\n {2}}", RegexOption.DOT_MATCHES_ALL).find(knowledgeBase)
        assertNotNull("_meta block not found — the knowledge base's shape changed", meta)
        assertEquals(CategorySeed.KB_VERSION, meta!!.groupValues[1].stringField("version"))
    }

    /**
     * Input:  every `category_defaults` row and every [CategorySeed] row.
     * Output: asserts the two lists are the same length and the same rows in the same order.
     *         Length first, and separately, because an equality failure on two lists of different
     *         sizes reports the first differing element and hides the fact that a row was dropped.
     */
    @Test
    fun `every category_defaults row is seeded, and no others`() {
        val rows = categoryDefaultRows()
        assertEquals(
            "the knowledge base and CategorySeed hold different numbers of categories",
            rows.size,
            CategorySeed.rows.size,
        )
        assertEquals(rows.map { it.ruleId }, CategorySeed.rows.map { it.ruleId })
    }

    /**
     * Input:  every `category_defaults` row.
     * Output: asserts each row's version, key, display name and nature match the seed's. The nature
     *         is compared through [CategoryNature.kbValue], which is the only place the knowledge
     *         base's `INVESTMENT` and the column's `invest` are allowed to meet.
     */
    @Test
    fun `the seed rows match category_defaults`() {
        categoryDefaultRows().zip(CategorySeed.rows).forEach { (kb, seed) ->
            assertEquals("${kb.ruleId}: rule id", kb.ruleId, seed.ruleId)
            assertEquals("${kb.ruleId}: version", kb.version, seed.version)
            assertEquals("${kb.ruleId}: key", kb.key, seed.key)
            assertEquals("${kb.ruleId}: name", kb.name, seed.name)
            assertEquals("${kb.ruleId}: nature", kb.nature, seed.nature.kbValue)
        }
    }

    /**
     * Input:  every `category_defaults` row's `default_nature`.
     * Output: asserts each one is a nature this build knows. A knowledge base naming a sixth nature
     *         would otherwise reach [CategoryNature.fromKb], come back `null`, and be seeded as
     *         whatever the caller's fallback happened to be.
     */
    @Test
    fun `every nature in the knowledge base is a known nature`() {
        categoryDefaultRows().forEach { row ->
            assertNotNull("${row.ruleId}: '${row.nature}' is not a CategoryNature", CategoryNature.fromKb(row.nature))
        }
    }

    /**
     * Input:  every `merchant_rules` row.
     * Output: asserts each carries a `rule_id` and a `version`.
     *
     * **Nothing in the app reads these rows yet** — issue 4.2 builds the matcher, and ADR-0014
     * records why 4.1 ships the data without it. What can be checked today is the governance
     * property that makes shipping them early safe: AI-ARC-006 needs every row to be citable, and an
     * id added later than the row it names is an id that may already be missing from a stored
     * insight. Ids are also asserted unique, because a duplicate would make a citation ambiguous.
     */
    @Test
    fun `every merchant rule is citable by id and version`() {
        val rules = section("merchant_rules")
        val ids = Regex("\"rule_id\"\\s*:\\s*\"([^\"]+)\"").findAll(rules).map { it.groupValues[1] }.toList()
        val versions = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").findAll(rules).count()
        val rowCount = Regex("\"match\"\\s*:").findAll(rules).count()

        assertTrue("merchant_rules looks empty — the knowledge base's shape changed", rowCount > 0)
        assertEquals("a merchant rule has no rule_id (AI-ARC-006)", rowCount, ids.size)
        assertEquals("a merchant rule has no version (AI-ARC-006)", rowCount, versions)
        assertEquals("duplicate merchant rule_id", ids.size, ids.toSet().size)
    }

    /**
     * Parses `category_defaults` into comparable rows.
     * Why:    every assertion above reads the same five fields; parsing once keeps the strictness in
     *         one place, so a shape change fails in one message rather than four.
     * Result: the rows in file order. Input: none. Output: a list of [KbCategory].
     */
    private fun categoryDefaultRows(): List<KbCategory> =
        Regex("\\{[^}]*}").findAll(section("category_defaults")).map { row ->
            val text = row.value
            KbCategory(
                ruleId = text.stringField("rule_id"),
                version = text.stringField("version"),
                key = text.stringField("key"),
                name = text.stringField("category"),
                nature = text.stringField("default_nature"),
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
     * One `category_defaults` row as the file states it.
     * Input:  [ruleId], [version], [key], [name]; [nature] — the **knowledge base's** spelling,
     *         deliberately still a string so the test compares vocabularies rather than assuming
     *         they are the same one. Output: an immutable value.
     */
    private data class KbCategory(
        val ruleId: String,
        val version: String,
        val key: String,
        val name: String,
        val nature: String,
    )

    private companion object {
        const val KB_PATH = "ai/knowledge/classification-kb.json"
        const val FIELD_ERROR_EXCERPT = 120
    }
}
