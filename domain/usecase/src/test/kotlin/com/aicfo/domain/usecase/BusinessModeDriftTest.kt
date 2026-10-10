package com.aicfo.domain.usecase

import com.aicfo.domain.engines.receipt.ReceiptFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Holds ADR-0073's factual claims to the code they describe (issue 13.5; §33, CLAUDE.md §5).
 *
 * Why:  this issue's AC2 is "ADR only in v1", and an ADR is a document — which in this repository
 *       is the thing most likely to quietly stop being true. ADR-0073 does not only propose a
 *       design; it **audits three forward-compatibility promises the SRS made in §33** and reports
 *       that two of them were never kept. Those are claims about the code as it is today, and they
 *       will change: somebody will add a `tax_relevance` column, or a tax field on a transaction,
 *       and when they do this ADR becomes a document describing a problem that no longer exists.
 *
 *       So the findings are pinned. Each test below fails when a gap is **closed**, which is the
 *       unusual direction — the failure message is the instruction to go and delete the paragraph.
 * What: the three §33 promises, checked against the schema and the receipt engine, plus that the
 *       ADR still records them.
 * Result: the design document cannot drift away from the code it was written about.
 * Changelog: 2026-10-09 — Created for issue 13.5.
 */
class BusinessModeDriftTest {
    private val entities: String by lazy { repoFile(ENTITIES_PATH).readText() }
    private val adr: String by lazy { repoFile(ADR_PATH).readText() }

    // --- the flag ---------------------------------------------------------------------------------

    /**
     * Input:  the shipped constant.
     * Output: asserts business mode is off in v1 (AC2). It is a second profile, and ADR-0069 §5's
     *         precondition — the fourteen id-keyed queries — applies to it unchanged.
     */
    @Test
    fun `business mode is off in v1`() {
        assertFalse(BusinessMode.IS_ENABLED)
    }

    // --- §33's three promises ---------------------------------------------------------------------

    /**
     * Input:  the `tags` and `transaction_tags` tables.
     * Output: asserts §33's **kept** promise — "tags support business/personal from v1". A
     *         free-form tag named `business` is the interim separation, so this is what
     *         [BusinessMode.INTERIM_TAG] rests on. If tagging ever disappears, the interim design
     *         in ADR-0073 §2 loses its foundation.
     */
    @Test
    fun `the promise that was kept — tags still exist and can carry a business label`() {
        assertTrue("the tags table is gone", "tableName = \"tags\"" in entities)
        assertTrue("the transaction_tags join table is gone", "tableName = \"transaction_tags\"" in entities)
        assertTrue("a tag still has a free-form name", "name = \"name\"" in tableBlock("tags"))
        assertEquals("business", BusinessMode.INTERIM_TAG)
    }

    /**
     * Input:  the `category` table's columns.
     * Output: asserts the `tax_relevance` column §33 promised is **still absent**.
     *
     * §33's forward-compatibility table says "categories carry tax_relevance tag field
     * (80C/80D/HRA…) from v1 so history is analysable retroactively". They do not, and the cost
     * lands on AI-TAX (issue 13.4), which has to be *told* a household's deductions because no
     * category can say which spending was deductible.
     *
     * **This test fails when the column is added** — which is the point. At that moment ADR-0073's
     * finding is stale and the paragraph should go.
     */
    @Test
    fun `the first broken promise — categories still carry no tax relevance`() {
        val category = tableBlock("category")

        assertFalse(
            "a tax_relevance column now exists on `category` — §33's promise is finally kept, so " +
                "ADR-0073's finding is stale: delete it, and tell AI-TAX it can read deductions " +
                "from the taxonomy instead of being handed them",
            "tax_relevance" in category,
        )
    }

    /**
     * Input:  the `transactions` table and the receipt engine's own result type.
     * Output: asserts the GST figure is extracted **and now stored**.
     *
     * **This assertion was inverted on 2026-10-10, which is what it was built for.** It used to
     * assert the column was *absent*, failing the day the gap closed and telling whoever closed it
     * to delete ADR-0073's stale paragraph. That day came: schema 32 added `transactions.tax_minor`
     * and `TransactionDraft` carries the figure from the review screen to the row (ADR-0077).
     *
     * It is kept rather than deleted, pointing the other way. §33's promise is now real, and the
     * thing worth guarding is that it stays real — a column dropped, or a draft field quietly
     * removed, would silently restore the old behaviour where the number is shown and discarded,
     * and no other test would notice.
     *
     * The compile-time reference to [ReceiptFields.tax] still matters: if the engine ever stops
     * extracting GST, this stops compiling rather than passing.
     */
    @Test
    fun `the promise kept late — the GST figure is extracted, and now stored`() {
        // Compile-time: the engine still has somewhere to put a GST figure.
        val extracts = ReceiptFields::tax
        assertTrue("the receipt engine no longer extracts GST at all", extracts.name == "tax")

        assertTrue(
            "`transactions` lost its tax column — the GST figure would be shown to the user and " +
                "then discarded again, which is the behaviour ADR-0077 exists to end. Receipt " +
                "images stay out of backups, so a figure not stored at scan time is unrecoverable",
            TAX_COLUMNS.any { it in tableBlock("transactions") },
        )
    }

    // --- the ADR itself -----------------------------------------------------------------------------

    /**
     * Input:  ADR-0073.
     * Output: asserts it still records both findings and names the precondition it inherits. An ADR
     *         whose findings were edited out while the gaps remained would be worse than no ADR,
     *         because the next reader would conclude the work was done.
     */
    @Test
    fun `the ADR still records both broken promises and the precondition it inherits`() {
        assertTrue("ADR-0073 looks empty or truncated", adr.length > 3_000)
        assertTrue("the ADR no longer mentions tax_relevance", "tax_relevance" in adr)
        assertTrue("the ADR no longer mentions the discarded GST figure", "GST" in adr)
        assertTrue(
            "the ADR must keep naming ADR-0069's id-keyed precondition — business mode is a " +
                "second profile and inherits it unchanged",
            "ADR-0069" in adr,
        )
    }

    // --- parsing ----------------------------------------------------------------------------------

    /** Result: one entity's constructor text, so a column name cannot be found in the wrong table. */
    private fun tableBlock(table: String): String {
        val marker = entities.indexOf("tableName = \"$table\"")
        if (marker < 0) throw AssertionError("no table \"$table\" in the entity declarations")
        val start = entities.indexOf("data class", marker)
        val end = entities.indexOf("\n)", start)
        return entities.substring(start, end)
    }

    private fun repoFile(relative: String): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = File(directory, relative)
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("Could not find $relative walking up from ${File("").absolutePath}")
    }

    private companion object {
        const val ENTITIES_PATH = "core/database/src/main/kotlin/com/aicfo/core/database/entity/Entities.kt"

        const val ADR_PATH = "docs/adr/0073-the-business-book-is-a-second-profile-and-two-promises-were-broken.md"

        /** The names a stored GST figure would plausibly take. */
        val TAX_COLUMNS = listOf("tax_minor", "gst_minor", "tax_amount", "gst_amount")
    }
}
