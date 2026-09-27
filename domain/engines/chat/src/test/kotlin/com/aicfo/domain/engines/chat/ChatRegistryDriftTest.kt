package com.aicfo.domain.engines.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Holds [ToolRegistry] to `ai/skills/tool-registry.json` (§6, §19.2, ADR-0017).
 *
 * Why:  §19.2's claim — "tools are the only way chat touches data" — is only true while the mirror
 *       and the file agree about what the tools are. The failure this prevents is the quiet one: a
 *       tool added to the file and never mirrored is a capability nobody can use, and a keyword
 *       changed in the file and not here means the app routes by a table that no longer exists in
 *       the place people edit.
 * What: the registry version, every tool name and access level, every intent with its tools,
 *       keywords and chips, the routing numbers, and the out-of-scope triggers.
 * Result: the two cannot silently disagree.
 * Changelog: 2026-09-26 — Created for issue 10.5.
 *
 * The JSON is read with small regular expressions rather than a parser, for the reason the other
 * drift tests give: a JSON dependency in a pure-Kotlin engine's test source set, to check a list of
 * names, would be the more expensive mistake.
 */
class ChatRegistryDriftTest {
    private val json: String by lazy { registryFile().readText() }
    private val mirror = ToolRegistry.BUNDLED

    @Test
    fun `the registry is where this test thinks it is`() {
        assertTrue("the tool registry looks empty or truncated", json.length > 2_000)
        assertTrue("no tools in the registry", "\"tools\"" in json)
    }

    @Test
    fun `the mirror names the revision it copied from`() {
        assertEquals(
            "ToolRegistry.BUNDLED.version must restate the registry's _meta.version",
            valueOf("version"),
            mirror.version,
        )
    }

    @Test
    fun `every tool in the file is mirrored, in order, with its access level`() {
        // Order matters here only because a reordered list is a sign someone edited the file
        // without reading it; the engine itself does not depend on it.
        val inFile = Regex("\"name\"\\s*:\\s*\"([a-z_]+)\"").findAll(json).map { it.groupValues[1] }.toList()

        assertEquals(ToolName.entries.map { it.registryName }, inFile)
        ToolName.entries.forEach { tool ->
            val block = blockFor(tool.registryName)
            val access = Regex("\"access\"\\s*:\\s*\"([a-z_]+)\"").find(block)?.groupValues?.get(1)

            assertEquals("${tool.registryName} access", tool.access.name.lowercase(), access)
        }
    }

    @Test
    fun `no tool writes to the ledger without the user`() {
        // The registry's own write policy: chat never writes directly, and the only mutating tools
        // produce a draft the user confirms (P-07). A tool with any other access is a design
        // change, not a data change, and must not slip in through the JSON.
        val accesses = Regex("\"access\"\\s*:\\s*\"([a-z_]+)\"").findAll(json).map { it.groupValues[1] }.toSet()

        assertEquals(setOf("read", "write_draft"), accesses)
    }

    @Test
    fun `every intent matches the file — its tools, its keywords and its chips`() {
        val ids = Regex("\"id\"\\s*:\\s*\"([A-Z_]+)\"").findAll(intentsBlock()).map { it.groupValues[1] }.toList()

        assertEquals(mirror.intents.map { it.intent.name }, ids)
        mirror.intents.forEach { route ->
            val block = intentBlockFor(route.intent.name)

            assertEquals("${route.intent} tools", route.tools.map { it.registryName }, listIn(block, "tools"))
            assertEquals("${route.intent} keywords", route.keywords, listIn(block, "keywords"))
            assertEquals("${route.intent} chips", route.chips.map { it.name }, listIn(block, "chips"))
        }
    }

    @Test
    fun `every intent routes to a tool that exists`() {
        // A route to a tool nobody declared would plan a call the executor cannot run.
        mirror.intents.flatMap { it.tools }.forEach { tool ->
            assertTrue("${tool.registryName} is not in the registry", "\"${tool.registryName}\"" in json)
        }
    }

    @Test
    fun `the routing numbers match the file`() {
        val block = namedBlock("routing")

        assertEquals(numberIn(block, "min_keyword_hits"), mirror.routing.minKeywordHits)
        assertEquals(numberIn(block, "max_tools_per_turn"), mirror.routing.maxToolsPerTurn)
        assertEquals(numberIn(block, "max_chips"), mirror.routing.maxChips)
    }

    @Test
    fun `the out-of-scope triggers match the file`() {
        val block = namedBlock("out_of_scope")

        assertEquals(mirror.outOfScope.keywords, listIn(block, "keywords"))
        assertEquals(mirror.outOfScope.offerChips.map { it.name }, listIn(block, "offer_chips"))
    }

    @Test
    fun `the behaviour rules the engine implements are still the file's`() {
        // CHT-001 and CHT-002 are the two this engine enforces. If either is rewritten in the file,
        // this test is where someone finds out that code depends on the wording.
        assertTrue("CHT-001 is gone from the registry", "CHT-001" in json)
        assertTrue("CHT-002 is gone from the registry", "CHT-002" in json)
        assertTrue(
            "the registry no longer says every figure comes from a tool",
            "guardrail blocks fabricated figures" in json,
        )
    }

    // --- parsing ----------------------------------------------------------------------------------

    /** Result: a top-level string value. Input: [key]. Output: [String]. */
    private fun valueOf(key: String): String =
        Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
            ?: throw AssertionError("no \"$key\" in the registry")

    /** Result: a named object's text. Input: [name]. Output: [String]. */
    private fun namedBlock(name: String): String =
        Regex("\"$name\"\\s*:\\s*\\{(.+?)\\n  }", RegexOption.DOT_MATCHES_ALL).find(json)?.groupValues?.get(1)
            ?: throw AssertionError("no \"$name\" block in the registry")

    /** Result: the `intents` array's text. Input: none. Output: [String]. */
    private fun intentsBlock(): String =
        Regex("\"intents\"\\s*:\\s*\\[(.+?)\\n  ]", RegexOption.DOT_MATCHES_ALL).find(json)?.groupValues?.get(1)
            ?: throw AssertionError("no intents block in the registry")

    /** Result: one intent's object. Input: [id]. Output: [String]. */
    private fun intentBlockFor(id: String): String =
        Regex("\\{[^{}]*\"id\"\\s*:\\s*\"$id\"[^{}]*}", RegexOption.DOT_MATCHES_ALL)
            .find(intentsBlock())?.value
            ?: throw AssertionError("no intent \"$id\" in the registry")

    /** Result: one tool's object. Input: [name]. Output: [String]. */
    private fun blockFor(name: String): String =
        Regex("\\{\\s*\"name\"\\s*:\\s*\"$name\".+?\"srs\"", RegexOption.DOT_MATCHES_ALL).find(json)?.value
            ?: throw AssertionError("no tool \"$name\" in the registry")

    /** Result: a string array's items. Input: [block]; [key]. Output: the items in file order. */
    private fun listIn(
        block: String,
        key: String,
    ): List<String> =
        Regex("\"$key\"\\s*:\\s*\\[([^]]*)]").find(block)?.groupValues?.get(1)
            ?.split(",")
            ?.map { it.trim().trim('"') }
            ?.filter { it.isNotEmpty() }
            ?: throw AssertionError("no \"$key\" list in the block")

    /** Result: a number under [key] inside [block]. Input: [block]; [key]. Output: [Int]. */
    private fun numberIn(
        block: String,
        key: String,
    ): Int =
        Regex("\"$key\"\\s*:\\s*(\\d+)").find(block)?.groupValues?.get(1)?.toInt()
            ?: throw AssertionError("no \"$key\" in the block")

    private fun registryFile(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = File(directory, REGISTRY_PATH)
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("Could not find $REGISTRY_PATH walking up from ${File("").absolutePath}")
    }

    private companion object {
        const val REGISTRY_PATH = "ai/skills/tool-registry.json"
    }
}
