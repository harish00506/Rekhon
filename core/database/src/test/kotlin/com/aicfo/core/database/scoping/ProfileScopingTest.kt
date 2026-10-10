package com.aicfo.core.database.scoping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every query on a profile-scoped table is accounted for (issue 13.1; §27, §33, P-01).
 *
 * Why:  the app is one household member's today, and §27 asks it to become a household's: several
 *       profiles under one roof, each seeing only their own money. The foundation for that already
 *       exists — thirty-three tables carry `profile_id` and ninety-five queries filter on it — but
 *       "strict scoping, no cross-leak" was a property nobody had ever checked, which in this
 *       repository has reliably meant a property that was partly false.
 *
 *       Measuring it found the shape of the problem. Fifteen queries touch a profile-scoped table
 *       **without** a profile filter. One of them is deliberately device-wide and says so. The other
 *       fourteen are keyed by id — `WHERE id = :id` — and they are safe today only because the
 *       caller already holds an id it got from a scoped read. **That assumption is exactly what
 *       household mode breaks:** with more than one profile under one database, holding an id stops
 *       being proof of entitlement to the row.
 *
 *       So this test does not demand that every query take a `profileId`. It demands that every
 *       query be *one of three understood kinds*, so that adding a fourth — an unscoped read that
 *       nobody noticed — fails here rather than leaking somebody's money into a relative's screen.
 * What: classifies every `@Query` touching a profile-scoped table as profile-filtered, id-keyed, or
 *       explicitly device-wide, and fails on anything else.
 * Result: the scoping foundation household mode needs is checked rather than asserted.
 * Changelog: 2026-10-03 — Created for issue 13.1.
 *
 * **It reads the DAO source rather than the database.** The property is about what queries *can*
 * express, not about what one seeded database happens to contain — a runtime test proves the rows it
 * was given, and this proves the shape of all ninety-five.
 */
class ProfileScopingTest {
    private val entities: String by lazy { source("entity/Entities.kt") }
    private val daos: String by lazy { source("dao/Daos.kt") }

    @Test
    fun `the sources are found, so the rest of this suite is not scanning an empty string`() {
        // The guard that keeps the others honest: a moved file would otherwise make every
        // assertion below pass over nothing.
        assertTrue("Entities.kt is empty or missing", entities.length > MIN_LENGTH)
        assertTrue("Daos.kt is empty or missing", daos.length > MIN_LENGTH)
    }

    @Test
    fun `the profile-scoped tables are the ones this test thinks they are`() {
        // Pinned so that adding a scoped table without a query to match is visible. If this number
        // moves, the new table's queries need classifying below — which is the point.
        assertEquals(
            "a table gained or lost `profileId`; its queries need classifying",
            EXPECTED_SCOPED_TABLES,
            scopedTables().size,
        )
    }

    @Test
    fun `every query on a scoped table is profile-filtered, id-keyed, or explicitly device-wide`() {
        // The gate. A query that is none of the three is an unscoped read of somebody's money.
        val unclassified =
            queries().filter { query ->
                query.touchesScopedTable && !query.filtersByProfile && !query.isKeyed && !query.isDeviceWide
            }

        assertEquals(
            "these queries touch a profile-scoped table and are neither profile-filtered, keyed by " +
                "an id the caller already holds, nor marked device-wide with a reason: " +
                unclassified.joinToString { it.name },
            emptyList<String>(),
            unclassified.map { it.name },
        )
    }

    @Test
    fun `a device-wide query must say why in its own documentation`() {
        // The one escape hatch, and it is only an escape hatch if using it costs an explanation.
        // `deleteAllPending` earns it: SMS consent is device-wide, so a revocation scoped to whichever
        // profile happened to be showing would leave the other's drafts on disk.
        queries().filter { it.isDeviceWide }.forEach { query ->
            assertTrue(
                "${query.name} is marked DEVICE-WIDE but gives no reason in the marker's own " +
                    "comment block",
                markerReason(query.documentation).length > MIN_REASON_LENGTH,
            )
        }
    }

    @Test
    fun `the count of device-wide queries is pinned, so a new one is a deliberate act`() {
        // One today. A second should be a decision somebody makes and explains, not a line that
        // slipped in — which is what this assertion turns it into.
        val deviceWide = queries().filter { it.isDeviceWide }.map { it.name }

        assertEquals("device-wide queries changed: $deviceWide", EXPECTED_DEVICE_WIDE, deviceWide.size)
    }

    // --- parsing ------------------------------------------------------------------------------------

    /**
     * The reason given in the `// DEVICE-WIDE:` comment block itself.
     *
     * Why:  measuring "everything after the marker" is what this did first, and it was wrong in a
     *       way only a mutation showed. The marker sits above the query's KDoc (ktlint will not
     *       allow an EOL comment directly below one), so "after the marker" swept up the whole
     *       KDoc — and a marker gutted to `// DEVICE-WIDE: on purpose.` still passed, because the
     *       prose underneath it was long. The reason has to come from the marker's own contiguous
     *       `//` lines, which is the only text whoever typed the marker actually wrote.
     * What: takes the marker line and the `//` lines immediately following it, strips the comment
     *       syntax, and joins them.
     * Result: the explanation, as one string; empty when the marker stands alone.
     * Input:  [documentation] — the preamble between the previous query and this one.
     * Output: the trimmed reason text.
     * Changelog: 2026-10-03 — Created for issue 13.1, after mutation M3 survived.
     */
    private fun markerReason(documentation: String): String {
        val lines = documentation.lines().map(String::trim)
        val start = lines.indexOfFirst { it.startsWith(DEVICE_WIDE_MARKER) }
        if (start < 0) return ""
        val block =
            lines.drop(start).takeWhile { it.startsWith("//") }
                .joinToString(" ") { it.removePrefix("//").trim() }
        return block.removePrefix(DEVICE_WIDE_MARKER.removePrefix("//").trim()).trim()
    }

    /** One `@Query` and what it does about profile scoping. */
    private data class ScopedQuery(
        val name: String,
        val sql: String,
        val parameters: String,
        val documentation: String,
        val touchesScopedTable: Boolean,
    ) {
        /** Takes a profile and filters on it — the ordinary, safe case. */
        val filtersByProfile: Boolean
            get() = "profileId" in parameters && ("profile_id" in sql || "profileId" in sql)

        /**
         * Keyed by an id the caller already holds, e.g. `WHERE id = :id`.
         *
         * Safe **today** because the id came from a scoped read. ADR-0069 records that household mode
         * breaks that assumption, and what to do about it then.
         */
        val isKeyed: Boolean
            get() = KEYED.containsMatchIn(sql)

        /**
         * Deliberately crosses profiles, declared by a `// DEVICE-WIDE:` marker above the query.
         *
         * A marker rather than a phrase inferred from the surrounding prose: issue 13.1's first
         * version matched on wording and was one rewrite away from silently reclassifying a query.
         * Intent this consequential has to be declared, not guessed.
         */
        val isDeviceWide: Boolean
            get() = DEVICE_WIDE_MARKER in documentation
    }

    /**
     * Result: the table names whose entity declares a `profileId`. Input: none. Output: a [Set].
     * Why:    derived from the source rather than listed here, so a new scoped table is picked up
     *         without anyone remembering to add it — the failure this project has found repeatedly.
     * Changelog: 2026-10-03 — Created for issue 13.1.
     */
    private fun scopedTables(): Set<String> =
        entities
            .split("@Entity")
            .mapNotNull { block ->
                TABLE_NAME.find(block)?.groupValues?.get(1)?.takeIf { "val profileId" in block }
            }.toSet()

    /**
     * Result: every `@Query` in the DAO file, classified. Input: none. Output: a list.
     * Why:    one parser, so the four assertions above cannot disagree about what a query is.
     * Changelog: 2026-10-03 — Created for issue 13.1.
     */
    private fun queries(): List<ScopedQuery> {
        val tables = scopedTables()
        val matches = QUERY.findAll(daos).toList()
        return matches.mapIndexed { index, match ->
            val sql = (match.groupValues[1] + match.groupValues[2]).replace(Regex("\\s+"), " ")
            // The preamble is bounded by the **previous query**, not by a fixed number of characters.
            // A fixed window was the first implementation and it spilled: a query written below
            // `deleteAllPending` inherited its `// DEVICE-WIDE:` marker and was silently classified as
            // intentional. Found by seeding exactly that query and watching the wrong test fail.
            val preambleStart = if (index == 0) 0 else matches[index - 1].range.last + 1
            ScopedQuery(
                name = match.groupValues[4],
                sql = sql,
                parameters = match.groupValues[5],
                documentation = daos.substring(preambleStart, match.range.first),
                touchesScopedTable =
                    tables.any {
                        Regex("\\b(FROM|JOIN|INTO|UPDATE)\\s+$it\\b", RegexOption.IGNORE_CASE).containsMatchIn(sql)
                    },
            )
        }
    }

    /**
     * Result: a source file from this module. Input: [relative] — a path under the DAO/entity root.
     * Output: its text.
     * Why:    walks up to the repository root, the same lookup the other drift tests here use, so the
     *         test does not depend on which directory Gradle ran it from.
     * Changelog: 2026-10-03 — Created for issue 13.1.
     */
    private fun source(relative: String): String {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = File(directory, "$SOURCE_ROOT/$relative")
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile
        }
        error("Could not find $SOURCE_ROOT/$relative walking up from ${File("").absolutePath}")
    }

    private companion object {
        const val SOURCE_ROOT = "core/database/src/main/kotlin/com/aicfo/core/database"
        const val MIN_LENGTH = 2_000

        /**
         * Tables carrying `profileId` today. Pinned so a new one has to be classified.
         *
         * 33 at issue 13.1; **36 at issue 13.2**, which added `appliance`, `appliance_service` and
         * `appliance_consumable`. Raising this number is the deliberate act the pin exists to force:
         * it is the moment somebody confirms the new tables' queries were looked at, which for 13.2
         * they were — all six filter on `profile_id`, so none of them needed a marker.
         */
        const val EXPECTED_SCOPED_TABLES = 37

        /** `sms_draft`'s revocation sweep — see `SmsRepository.onConsentRevoked`. */
        const val EXPECTED_DEVICE_WIDE = 1

        /** The marker a query crossing profiles must carry, with its reason after it. */
        const val DEVICE_WIDE_MARKER = "// DEVICE-WIDE:"

        /** Long enough that "x" does not count as an explanation. */
        const val MIN_REASON_LENGTH = 40

        val TABLE_NAME = Regex("""tableName\s*=\s*"(\w+)"""")

        /** `@Query("…")` or `@Query(""" … """)`, then the function it annotates. */
        val QUERY =
            Regex(
                """@Query\(\s*(?:"{3}(.*?)"{3}|"([^"]*)")\s*\)\s*""" +
                    """((?:@\w+(?:\([^)]*\))?\s*)*)(?:suspend\s+)?fun\s+(\w+)\(([^)]*)\)""",
                RegexOption.DOT_MATCHES_ALL,
            )

        /** `WHERE id = :x`, `WHERE account_id = :x`, `WHERE … IN (:ids)` — keyed by a held id. */
        val KEYED = Regex("""WHERE[^;]*\b\w*_?id\s*(=\s*:|IN\s*\()""", RegexOption.IGNORE_CASE)
    }
}
