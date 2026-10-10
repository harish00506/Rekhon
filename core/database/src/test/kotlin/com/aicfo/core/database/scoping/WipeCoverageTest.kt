package com.aicfo.core.database.scoping

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * That the profile wipe reaches **every** profile-scoped table (issues 2.4, 5.4; ADR-0006, P-01).
 *
 * Why:  `DemoDao`'s own documentation states the rule and records breaking it twice — issue 7.4
 *       found `goal`, `investment_holding` and `investment_lot` had been left behind since 7.1 and
 *       6.3, and issue 10.4 found four more. Its words: *"A count that omits a table is not a
 *       weaker assertion; it is a false one."*
 *
 *       Both times the omission was found by a person happening to look. **Nothing checked it**,
 *       so it happened a third time: `chat_message` (10.5) and the three `appliance*` tables (13.2)
 *       were invisible to both the wipe and the residue count, and `countRowsFor` therefore
 *       returned `0` for a profile that still had rows. That is not a weaker guarantee than "no
 *       residue" — it is the opposite of one, reported as success.
 *
 *       This is the check that was missing. It derives the scoped tables from the entities rather
 *       than from a list somebody maintains, because a list somebody maintains is the thing that
 *       went stale.
 * What: every table carrying a `profile_id` has a `DELETE` in `DemoDao`, has a term in
 *       `countRowsFor`, and that those two sets are identical.
 * Result: adding a profile-scoped table without wiring it into the wipe fails the build.
 * Changelog: 2026-10-10 — Created while adding `import_batches` (ADR-0078).
 *
 * Reads this module's own `src/main`, which issue 13.1's `configureOwnSourceAsTestInput()`
 * declares as a test input — so the edit this watches cannot leave the task UP-TO-DATE.
 */
class WipeCoverageTest {
    /**
     * Input:  the entity declarations.
     * Output: asserts every profile-scoped table is deleted by the wipe.
     *
     * A scoped table the wipe cannot reach is residue in two places at once: the demo exit leaves a
     * sample household's rows under a dead profile (ADR-0006), and `ArchiveRepository.import` — the
     * wipe's second caller — leaves the *previous* owner's rows under a restored profile.
     */
    @Test
    fun `every profile-scoped table is deleted by the wipe`() {
        assertEquals(
            "these tables carry a profile_id and the wipe never deletes them. A profile-scoped " +
                "table the wipe cannot reach survives both the demo exit and a restore",
            emptySet<String>(),
            scopedTables() - deletedTables(),
        )
    }

    /**
     * Input:  the entity declarations and `countRowsFor`.
     * Output: asserts every profile-scoped table is a term in the residue count.
     *
     * The count is the *assertion* that the wipe worked. A table missing from it is a profile
     * reported clean while it still holds rows — a false negative in the one query whose whole job
     * is to make "no residue" provable.
     */
    @Test
    fun `every profile-scoped table is counted by the residue query`() {
        assertEquals(
            "these tables carry a profile_id and countRowsFor never counts them, so it reports 0 " +
                "for a profile that still has rows",
            emptySet<String>(),
            scopedTables() - countedTables(),
        )
    }

    /**
     * Input:  the wipe's deletes and its count terms.
     * Output: asserts the two sets are identical.
     *
     * The halves fail independently: a delete without a count is an unprovable wipe, and a count
     * without a delete is a residue total that can never reach zero. Checking each against the
     * entities separately would let the pair disagree; this pins them to each other.
     */
    @Test
    fun `the wipe and its residue count name exactly the same tables`() {
        assertEquals(
            "the wipe deletes tables the residue count does not mention, or the reverse",
            deletedTables(),
            countedTables(),
        )
    }

    // --- parsing ------------------------------------------------------------------------------------

    /** Every `@Entity` whose data class declares a `profile_id` column. */
    private fun scopedTables(): Set<String> =
        ENTITY_SPLIT.split(sourceOf(ENTITIES_PATH)).drop(1).mapNotNull { block ->
            val name = TABLE_NAME.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val body = block.substringAfter("data class", missingDelimiterValue = "")
            name.takeIf { PROFILE_COLUMN in body.substringBefore("\n)") }
        }.toSet()

    /** The tables `DemoDao` deletes, excluding the `profile` row itself. */
    private fun deletedTables(): Set<String> =
        DELETE_FROM.findAll(wipeSource()).map { it.groupValues[1] }.toSet() - PROFILE_TABLE

    /** The tables `countRowsFor` sums over, excluding the `profile` row itself. */
    private fun countedTables(): Set<String> =
        COUNT_FROM.findAll(wipeSource()).map { it.groupValues[1] }.toSet() - PROFILE_TABLE

    /**
     * `DemoDao`'s source, bounded to the interface.
     *
     * Bounded deliberately: the DAO file holds forty interfaces and most of them contain a
     * `DELETE FROM` or a `COUNT(*)` of their own, so reading the whole file would report every
     * table as covered and the test would pass for ever.
     */
    private fun wipeSource(): String {
        val source = sourceOf(DAOS_PATH)
        val start = source.indexOf(WIPE_DAO)
        check(start >= 0) { "$WIPE_DAO is gone — the wipe this test guards no longer exists" }
        val end = source.indexOf("\ninterface ", start)
        return if (end < 0) source.substring(start) else source.substring(start, end)
    }

    private fun sourceOf(path: String): String {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = File(directory, path)
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile
        }
        error("Could not find $path walking up from ${File("").absolutePath}")
    }

    private companion object {
        const val ENTITIES_PATH = "core/database/src/main/kotlin/com/aicfo/core/database/entity/Entities.kt"
        const val DAOS_PATH = "core/database/src/main/kotlin/com/aicfo/core/database/dao/Daos.kt"

        /** The one DAO that hard-deletes — the demo exit and `ArchiveRepository.import`. */
        const val WIPE_DAO = "interface DemoDao {"

        /** Not scoped to itself: `profile` is the parent row, deleted last and counted by `id`. */
        const val PROFILE_TABLE = "profile"
        const val PROFILE_COLUMN = "name = \"profile_id\""

        val ENTITY_SPLIT = Regex("""@Entity\(""")
        val TABLE_NAME = Regex("""tableName\s*=\s*"(\w+)"""")
        val DELETE_FROM = Regex("""DELETE FROM (\w+)""")
        val COUNT_FROM = Regex("""COUNT\(\*\) FROM (\w+)""")
    }
}
