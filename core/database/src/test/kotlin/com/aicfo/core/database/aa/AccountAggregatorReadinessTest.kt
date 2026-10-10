package com.aicfo.core.database.aa

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the schema still owes Account Aggregator ingest (issue 13.6; §33, ADR-0074).
 *
 * Why:  §33's forward-compatibility table makes two promises on AA's behalf. Issue 13.6 found that
 *       **neither was kept**, and kept one of them — the reserved `source = 'aa'` value, which had
 *       to ship because omitting it would make an older build silently drop every AA-imported row.
 The other, `import_batches`, was recorded rather than built, because that issue's AC asked
 *       for an ADR and an interface stub.
 *
 *       **Both promises are now kept.** The table arrived with schema 33 (ADR-0078), so the pin
 *       that used to fail when the table was *added* — 13.5's "unusual direction" — has done its
 *       job and been **turned around** rather than deleted: the behaviour worth guarding has
 *       changed from "this is missing" to "this must not go missing again".
 * What: that `import_batches` exists, that `transactions` can name a batch, and that the two never
 *       exist without each other in either direction.
 * Result: neither half of §33's AA promise can be removed without a test saying so.
 * Changelog: 2026-10-09 — Created for issue 13.6.
 *            2026-10-10 — Inverted when ADR-0078 built the table.
 *
 * Reads this module's own `Entities.kt`, which issue 13.1's `configureOwnSourceAsTestInput()`
 * already declares as a test input — so a comment-only edit cannot leave this UP-TO-DATE.
 */
class AccountAggregatorReadinessTest {
    private val entities: String by lazy { entitiesFile().readText() }

    /**
     * Input:  the entity declarations.
     * Output: asserts `import_batches` exists.
     *
     * §33 says "import_batches supports statement-grade provenance", and for thirteen schema
     * versions there was no such table, so there was nowhere to record **which fetch a row came
     * from** — a duplicate or partial import could not be traced to the pull that caused it, and a
     * user who imported twice had no way to tell the app which copy to keep.
     *
     * This test used to fail when the table was added. It now fails if the table is removed.
     */
    @Test
    fun `the import_batches table §33 promised exists`() {
        assertTrue(
            "the `import_batches` table is gone — §33's second AA promise was kept by ADR-0078 " +
                "and removing the table unkeeps it, leaving imported rows with no traceable origin",
            "tableName = \"import_batches\"" in entities,
        )
    }

    /**
     * Input:  the entity declarations.
     * Output: asserts the pointer and the table it points at exist together.
     *
     * The original form of this test refused a `batch_id` column with no batch table behind it,
     * because provenance that holds nothing is worse than the honest absence. That hazard has not
     * gone away — it has only changed direction: with the table built, the broken state is now the
     * **table without the column**, which would be an import history nothing can be traced to.
     * Both halves are asserted, so neither can be removed alone.
     */
    @Test
    fun `the batch table and the column pointing at it exist together`() {
        assertTrue(
            "`transactions.import_batch_id` is gone while `import_batches` remains — an import " +
                "history no transaction can be traced to answers nothing (ADR-0078)",
            "name = \"import_batch_id\"" in entities,
        )
        assertTrue(
            "`import_batches` is gone while `transactions.import_batch_id` remains — that is " +
                "provenance that holds nothing, the half-built state ADR-0074 refused",
            "tableName = \"import_batches\"" in entities,
        )
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
