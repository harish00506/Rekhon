package com.aicfo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [CategoryNature], [Category] and [CategorySeed] (issue 4.1; AI-CLSN-001, FR-SET-001).
 *
 * Why:  the nature vocabulary is written to a column and compared against a knowledge base that
 *       spells one of its five values differently, so the two spellings are the thing most likely to
 *       be got wrong — by this issue or by 4.3, which reads them next. The seed is checked for the
 *       properties the repository will rely on rather than re-listing its contents, which
 *       [ClassificationKbDriftTest] already compares against the file they came from.
 * What: round-trips both vocabularies, the unknown-value paths, and the seed's uniqueness invariants.
 * Result: a nature cannot be silently mistranslated, and a duplicate seed key cannot ship.
 * Changelog: 2026-08-08 — Created for issue 4.1.
 */
class CategoryTest {
    /**
     * Input:  every nature's [CategoryNature.storedValue].
     * Output: asserts each parses back to itself — the property `category.nature` round-tripping
     *         through the database depends on.
     */
    @Test
    fun `every nature round-trips through its stored value`() {
        CategoryNature.entries.forEach { nature ->
            assertEquals(nature, CategoryNature.fromStored(nature.storedValue))
        }
    }

    /**
     * Input:  every nature's [CategoryNature.kbValue].
     * Output: asserts each parses back to itself, and that the two vocabularies really do differ for
     *         `INVEST` — the assertion that fails if a later edit "tidies" one side to match the
     *         other, which would be a silent schema change.
     */
    @Test
    fun `every nature round-trips through its knowledge-base value`() {
        CategoryNature.entries.forEach { nature ->
            assertEquals(nature, CategoryNature.fromKb(nature.kbValue))
        }
        assertEquals("invest", CategoryNature.INVEST.storedValue)
        assertEquals("INVESTMENT", CategoryNature.INVEST.kbValue)
    }

    /**
     * Input:  values from neither vocabulary, plus each vocabulary's value offered to the other
     *         parser.
     * Output: asserts `null` every time. The cross-vocabulary cases are the point: `fromStored`
     *         accepting `INVESTMENT` would let the knowledge base's spelling reach a column, which
     *         is exactly the bug the two-field enum exists to prevent.
     */
    @Test
    fun `an unknown or cross-vocabulary value parses to null`() {
        assertNull(CategoryNature.fromStored("savings"))
        assertNull(CategoryNature.fromStored(""))
        assertNull(CategoryNature.fromStored("NEED"))
        assertNull(CategoryNature.fromStored("INVESTMENT"))
        assertNull(CategoryNature.fromKb("need"))
        assertNull(CategoryNature.fromKb("invest"))
    }

    /**
     * Input:  [CategorySeed.rows].
     * Output: asserts the ids and the keys are each unique. The keys matter more than they look:
     *         the `category` row's primary key is derived from one, so a duplicate would make the
     *         seed write fourteen rows and silently overwrite the fifteenth.
     */
    @Test
    fun `seed ids and keys are unique`() {
        val rows = CategorySeed.rows
        assertTrue("the seed is empty", rows.isNotEmpty())
        assertEquals("duplicate rule id in the seed", rows.size, rows.map { it.ruleId }.toSet().size)
        assertEquals("duplicate key in the seed", rows.size, rows.map { it.key }.toSet().size)
        assertEquals("duplicate name in the seed", rows.size, rows.map { it.name }.toSet().size)
    }

    /**
     * Input:  [CategorySeed.rows].
     * Output: asserts no key is blank or carries whitespace or upper case. The key ends up inside a
     *         row id, so a stray space would produce an id no one can type into a query and a
     *         capital would produce two ids for one category on a case-sensitive comparison.
     */
    @Test
    fun `every seed key is a usable slug`() {
        CategorySeed.rows.forEach { row ->
            assertTrue("blank key for ${row.ruleId}", row.key.isNotBlank())
            assertEquals("key is not lower case for ${row.ruleId}", row.key.lowercase(), row.key)
            assertTrue("key has whitespace for ${row.ruleId}", row.key.none { it.isWhitespace() })
            assertTrue("blank name for ${row.ruleId}", row.name.isNotBlank())
        }
    }

    /**
     * Input:  the seed's natures.
     * Output: asserts the taxonomy a new profile starts with actually spans the bands the dashboard
     *         will draw. Seeding fifteen NEEDs would satisfy every other test in this file and leave
     *         the 50/30/20 ring with two empty thirds on day one.
     */
    @Test
    fun `the seed spans the natures the 50-30-20 view needs`() {
        val natures = CategorySeed.rows.map { it.nature }.toSet()
        assertTrue("no NEED categories seeded", CategoryNature.NEED in natures)
        assertTrue("no WANT categories seeded", CategoryNature.WANT in natures)
        assertTrue("no INVEST categories seeded", CategoryNature.INVEST in natures)
    }

    /**
     * Input:  a [Category] built with only the fields issue 3.1's callers supplied.
     * Output: asserts the two fields 4.1 added default to a top-level, user-owned category — so the
     *         widening did not quietly turn every existing construction site into a system row or a
     *         child of something.
     */
    @Test
    fun `a category defaults to top-level and user-owned`() {
        val category = Category(id = "category:x", name = "Chai", nature = CategoryNature.WANT)
        assertNull(category.parentId)
        assertEquals(false, category.isSystem)
        assertNotNull(CategoryNature.fromStored(category.nature.storedValue))
    }
}
