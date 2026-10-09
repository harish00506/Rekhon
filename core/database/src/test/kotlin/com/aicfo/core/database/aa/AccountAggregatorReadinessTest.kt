package com.aicfo.core.database.aa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the schema still owes Account Aggregator ingest (issue 13.6; §33, ADR-0074).
 *
 * Why:  §33's forward-compatibility table makes two promises on AA's behalf. Issue 13.6 found that
 *       **neither was kept**, and kept one of them — the reserved `source = 'aa'` value, which had
 *       to ship because omitting it would make an older build silently drop every AA-imported row.
 *       The other, `import_batches`, is recorded rather than built, because this issue's AC asks
 *       for an ADR and an interface stub.
 *
 *       This pins the remaining gap **in the direction 13.5 established**: the test fails when the
 *       table is *added*, and says so — because at that moment ADR-0074's finding is stale and the
 *       paragraph should go.
 * What: that `import_batches` is still absent, and that the schema has not grown a half-built
 *       version of it under another name.
 * Result: the ADR cannot describe a gap that somebody has quietly closed.
 * Changelog: 2026-10-09 — Created for issue 13.6.
 *
 * Reads this module's own `Entities.kt`, which issue 13.1's `configureOwnSourceAsTestInput()`
 * already declares as a test input — so a comment-only edit cannot leave this UP-TO-DATE.
 */
class AccountAggregatorReadinessTest {
    private val entities: String by lazy { entitiesFile().readText() }

    /**
     * Input:  the entity declarations.
     * Output: asserts `import_batches` is still absent.
     *
     * §33 says "import_batches supports statement-grade provenance". There is no such table. The
     * consequence is concrete: when AA ingest lands there is nowhere to record **which fetch a row
     * came from**, so a duplicate or a partial import cannot be traced back to the pull that caused
     * it — and a user who imports twice has no way to tell the app which copy to keep.
     *
     * **This test fails when the table is added**, which is the point: the gap is closed, and
     * ADR-0074's finding should be deleted rather than left describing solved work.
     */
    @Test
    fun `the import_batches table §33 promised is still absent`() {
        assertFalse(
            "an `import_batches` table now exists — §33's second AA promise is finally kept, so " +
                "ADR-0074's finding is stale: delete it, and point AA ingest at the new provenance",
            "tableName = \"import_batches\"" in entities,
        )
    }

    /**
     * Input:  the entity declarations.
     * Output: asserts no table has grown a half-built substitute under a near-miss name. A
     *         `batch_id` column on `transactions` with no batch table behind it would be worse
     *         than the honest absence — it would look like provenance and hold nothing.
     */
    @Test
    fun `no table carries a batch reference with nothing behind it`() {
        val nearMisses = listOf("import_batch_id", "batch_id", "fetch_id")

        nearMisses.forEach { column ->
            assertFalse(
                "a `$column` column exists without an `import_batches` table to point at — that is " +
                    "provenance that holds nothing. Either build the table or drop the column",
                "name = \"$column\"" in entities,
            )
        }
    }

    /**
     * Input:  the entity declarations.
     * Output: asserts the `transactions` table still records a source at all. The reserved `aa`
     *         value is worth nothing if the column it is stored in disappears, and AC1 requires
     *         AA-imported rows to be tagged with it.
     */
    @Test
    fun `transactions still record a source, which is where the reserved value lands`() {
        // From the data class, not the marker: the `@Entity(...)` annotation closes with its own
        // `)` before the constructor starts, so bounding on the marker alone reads the annotation.
        val marker = entities.indexOf("tableName = \"transactions\"")
        val start = entities.indexOf("data class", marker)
        val block = entities.substring(start, entities.indexOf("\n)", start))

        assertTrue("the transactions table no longer records a source", "name = \"source\"" in block)
    }

    private fun entitiesFile(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = File(directory, ENTITIES_PATH)
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("Could not find $ENTITIES_PATH walking up from ${File("").absolutePath}")
    }

    private companion object {
        const val ENTITIES_PATH = "core/database/src/main/kotlin/com/aicfo/core/database/entity/Entities.kt"
    }
}
