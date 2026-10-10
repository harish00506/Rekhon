package com.aicfo.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Holds `ai/orchestrator/engine-registry.yaml` to the engines that actually exist (AI-ARC-006).
 *
 * Why:  the registry calls itself "the catalogue of AI engines the orchestrator can run" and says
 *       plainly: *"One row per engine (module `:domain:engines:*`)"*. **Nothing checked that.**
 *       Issue 13.2 measured it while adding AI-APP and found nine modules with no entry at all, plus
 *       an entry naming `:domain:engines:growth` — a module that has never existed. ADR-0070
 *       recorded both and fixed neither; issue 13.4 removed the ghost in passing, and this closes
 *       the rest (ADR-0076).
 *
 *       The gap mattered because AI-ARC-006 requires every stored result to name the engine and
 *       version that produced it, and the registry is what resolves that name. All nine missing
 *       engines had been stamping a provenance id into real results the whole time — the catalogue
 *       simply did not know them, so a stored insight citing `budget-planner` resolved to nothing.
 * What: every `:domain:engines:*` module has an entry; every id an engine stamps is resolvable from
 *       the registry; and no entry names a module that does not exist.
 * Result: the catalogue cannot silently diverge from the code again.
 * Changelog: 2026-10-10 — Created for the registry reconciliation (ADR-0076).
 *
 * `ai/` is already a declared input of every test task (issue 11.5's `configureCheckedDataAsTestInput`),
 * so a registry edit cannot leave this UP-TO-DATE. The module sources are declared in this module's
 * build file for the same reason.
 */
class EngineRegistryDriftTest {
    private val registry: String by lazy { repoFile(REGISTRY).readText() }

    /**
     * Output: asserts the audit is reading a real registry. A check over zero entries would report
     *         perfect agreement and mean nothing — the vacuity this repo keeps finding.
     */
    @Test
    fun `the registry is where this test thinks it is`() {
        assertTrue("the registry looks empty or truncated", registry.length > 2_000)
        assertTrue("no engine entries found", entriesByModule().isNotEmpty())
    }

    /**
     * Output: asserts **every** `:domain:engines:*` module has a registry entry.
     *
     * This is the drift itself. The failure message names the modules, because the work of fixing
     * it is writing one accurate contract line each — which is why it went unfixed for so long.
     */
    @Test
    fun `every engine module has a registry entry`() {
        val listed = entriesByModule().keys
        val missing = engineModules().filterNot { it in listed }

        assertEquals(
            "these engine modules have no entry in $REGISTRY. The registry's own header promises " +
                "'one row per engine (module :domain:engines:*)', and AI-ARC-006 cannot resolve a " +
                "stored result's engine id without one: $missing",
            emptyList<String>(),
            missing,
        )
    }

    /**
     * Output: asserts no entry names a module that does not exist.
     *
     * The registry carried `:domain:engines:growth` from its creation until issue 13.4 — a ghost
     * nobody noticed because nothing looked.
     */
    @Test
    fun `no registry entry names a module that does not exist`() {
        val real = engineModules().toSet()
        val ghosts = entriesByModule().keys.filterNot { it in real }.sorted()

        assertEquals("the registry names engine modules that do not exist: $ghosts", emptyList<String>(), ghosts)
    }

    /**
     * Output: asserts every id an engine **stamps into provenance** can be resolved from the
     *         registry — as an entry's `id`, or as its `provenance_id`.
     *
     * Three engines stamp a slug while the registry lists them under the SRS's name: `AI-CLS` stamps
     * `auto-categoriser`, `AI-INV` stamps `investment-xirr`, `AI-STS` stamps `safe-to-spend`. Those
     * `AI-*` names are cited by `rules-kb.json`'s `consumed_by`, so **renaming them would break
     * those citations**, and renaming what the engine stamps would orphan every stored result —
     * the same rule the rulebook applies to its own rule ids. Hence `provenance_id`: both names
     * resolve, neither moves.
     */
    @Test
    fun `every id an engine stamps is resolvable from the registry`() {
        val byModule = entriesByModule()
        val provenanceIds = provenanceIdsByEntryId()
        val unresolvable =
            engineModules().mapNotNull { module ->
                val ids = byModule[module] ?: return@mapNotNull null
                val resolvable = ids.toSet() + ids.mapNotNull { provenanceIds[it] }
                val stamped = stampedIdsIn(module) - resolvable
                if (stamped.isEmpty()) null else module to stamped.sorted()
            }

        assertEquals(
            "these engines stamp a provenance id the registry cannot resolve. Add it as the " +
                "entry's `id`, or as a `provenance_id` beside it — never rename what an engine " +
                "stamps, because stored results already carry it (AI-ARC-006): $unresolvable",
            emptyList<Pair<String, List<String>>>(),
            unresolvable,
        )
    }

    /**
     * Output: asserts the count, pinned.
     *
     * A new engine must be registered in the same change that creates it. Without this, the next
     * module could be added and simply not listed, which is exactly how nine accumulated.
     */
    @Test
    fun `the number of engine modules is pinned, so a new one must be registered with it`() {
        assertEquals(
            "the engine-module count changed. If you added an engine, add its registry entry in " +
                "the same commit and raise this number; if you removed one, remove its entry.",
            EXPECTED_ENGINE_MODULES,
            engineModules().size,
        )
    }

    // --- parsing ----------------------------------------------------------------------------------

    /**
     * Result: every `:domain:engines:*` module named by a `module:` field, mapped to the entry ids
     *         that name it — a **list**, because `goals` and `purchase` each have two entries
     *         (`AI-GOAL`/`AI-GOAL.waterfall`, `AI-PA`/`AI-PA-INT`). Keeping only the first was the
     *         bug in the original 13.2 measurement, which is why that one reported ten missing
     *         rather than nine: a compound `module:` string hid `:domain:engines:chat`.
     */
    private fun entriesByModule(): Map<String, List<String>> {
        val byModule = mutableMapOf<String, MutableList<String>>()
        var current: String? = null
        registry.lineSequence().forEach { line ->
            ENTRY_ID.find(line)?.let { current = it.groupValues[1] }
            if ("module:" in line) {
                val id = current ?: return@forEach
                MODULE_REF.findAll(line).forEach { match ->
                    byModule.getOrPut(":" + match.groupValues[1]) { mutableListOf() }.add(id)
                }
            }
        }
        return byModule
    }

    /** Result: entry id → the `provenance_id` beside it, where one is declared. */
    private fun provenanceIdsByEntryId(): Map<String, String> {
        val found = mutableMapOf<String, String>()
        var current: String? = null
        registry.lineSequence().forEach { line ->
            ENTRY_ID.find(line)?.let { current = it.groupValues[1] }
            PROVENANCE_ID.find(line)?.let { match -> current?.let { found[it] = match.groupValues[1] } }
        }
        return found
    }

    /** Result: every `:domain:engines:*` module with Kotlin main sources, from settings.gradle.kts. */
    private fun engineModules(): List<String> =
        INCLUDE.findAll(repoFile("settings.gradle.kts").readText())
            .map { ":" + it.groupValues[1] }
            .filter { File(repoRoot(), it.drop(1).replace(':', '/') + "/src/main/kotlin").isDirectory }
            .toList()
            .sorted()

    /** Result: every id a module stamps as `engineId` or `ENGINE_ID`. */
    private fun stampedIdsIn(module: String): Set<String> =
        File(repoRoot(), module.drop(1).replace(':', '/') + "/src/main/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { STAMPED_ID.findAll(it.readText()).map { match -> match.groupValues[1] } }
            .toSet()

    private fun repoFile(relative: String): File = File(repoRoot(), relative)

    private fun repoRoot(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("Could not find the repository root from ${File("").absolutePath}")
    }

    private companion object {
        const val REGISTRY = "ai/orchestrator/engine-registry.yaml"

        /** 30 at the 2026-10-10 reconciliation. */
        const val EXPECTED_ENGINE_MODULES = 30

        val ENTRY_ID = Regex("""^\s*- id:\s*(\S+)""")
        val PROVENANCE_ID = Regex("""^\s*provenance_id:\s*"([^"]+)"""")
        val MODULE_REF = Regex("""(domain:engines:[a-z]+)""")
        val INCLUDE = Regex("""include\(":(domain:engines:[a-z]+)"\)""")
        val STAMPED_ID = Regex("""(?:engineId|ENGINE_ID)\s*=\s*"([^"]+)"""")
    }
}
