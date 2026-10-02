package com.aicfo.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared golden-file reader (issue 12.1; §21.5, P-08).
 *
 * Why:  twenty-four engines each wrote their own fixture reader, and the one thing they all had to
 *       get right is the thing a test cannot check about itself: **that the fixture was actually
 *       read**. A missing resource, an empty file, or a typo'd path turns a golden gate into a loop
 *       over zero records that passes in silence — the single most common way a frozen test stops
 *       being a test, and the shape this project has already found five times elsewhere.
 *
 *       So the reader's own tests are mostly about refusing to be vacuous.
 * What: the block split, the typed accessors, and the three guards (missing, empty, absent key).
 * Result: an engine can adopt the harness and inherit the guards instead of re-deriving them.
 * Changelog: 2026-10-02 — Created for issue 12.1.
 */
class GoldenFixtureTest {
    @Test
    fun `a fixture is split into one record per block`() {
        val records = GoldenFixture.load(this, FIXTURE)

        assertEquals(2, records.size)
        assertEquals(listOf("plain", "sparse"), records.map { it.required("label") })
    }

    @Test
    fun `the prose before the first record is ignored`() {
        // A golden file is read by people more often than by code; the format has to leave room for
        // the paragraph that explains what the numbers mean, without that prose becoming a record.
        val records = GoldenFixture.load(this, FIXTURE)

        assertTrue(records.none { it.keys.contains("sample") })
    }

    @Test
    fun `a record label comes from the heading, so a failure names the case`() {
        // The reason the heading exists at all: a bare `expected 3 but was 4` in a 60-record fixture
        // sends the reader hunting. `assertWithMessage(record.heading)` does not.
        assertEquals(
            listOf("a plain record", "a record with an absent field"),
            GoldenFixture.load(this, FIXTURE).map { it.heading },
        )
    }

    @Test
    fun `typed accessors parse what the fixture says`() {
        val record = GoldenFixture.load(this, FIXTURE).first()

        assertEquals("plain", record.required("label"))
        assertEquals(123_456L, record.long("amount_minor"))
        assertEquals(3, record.int("count"))
        assertTrue(record.boolean("flag"))
        assertEquals(listOf("RULE-A v1.0", "RULE-B v2.0"), record.list("cites"))
    }

    @Test
    fun `an absent key reads as null, not as zero`() {
        // Zero is a legitimate amount. Conflating "not in the fixture" with "zero" would let a
        // dropped field pass as a deliberate zero, which is exactly the diff a golden file exists
        // to show.
        val sparse = GoldenFixture.load(this, FIXTURE).last()

        assertNull(sparse.optional("cites"))
        assertNull(sparse.longOrNull("cites"))
        assertEquals(emptyList<String>(), sparse.list("cites"))
    }

    @Test
    fun `a required key that is absent fails loudly and names the record`() {
        val sparse = GoldenFixture.load(this, FIXTURE).last()

        val error = assertThrows(IllegalStateException::class.java) { sparse.required("cites") }

        assertTrue("the message must name the key", error.message!!.contains("cites"))
        assertTrue("and the record", error.message!!.contains("sparse"))
    }

    @Test
    fun `a boolean that is neither true nor false is an error, not false`() {
        // Found by a mutation: falling back to `toBoolean()` survived every other test here, and
        // `toBoolean()` maps every typo to `false`. A fixture line reading `# flag=ture` would then
        // assert the exact opposite of what its author meant, silently, for ever.
        val record = GoldenFixture.parse("=== r\n# label=typo\n# flag=ture\n", "/golden/typo.txt").single()

        val error = assertThrows(IllegalStateException::class.java) { record.boolean("flag") }

        assertTrue(error.message!!.contains("ture"))
        assertTrue(error.message!!.contains("expected"))
    }

    @Test
    fun `a missing fixture is an error, never an empty list`() {
        // The guard that matters most. A typo'd path would otherwise produce zero records and a
        // golden test that passes for ever while asserting nothing.
        val error =
            assertThrows(IllegalStateException::class.java) {
                GoldenFixture.load(this, "/golden/does-not-exist.txt")
            }

        assertTrue(error.message!!.contains("does-not-exist"))
    }

    @Test
    fun `a fixture with no records at all is an error`() {
        // The second way to be vacuous: the file exists, the path is right, and somebody emptied it.
        val error =
            assertThrows(IllegalStateException::class.java) {
                GoldenFixture.parse("prose only, no record markers", "/golden/empty.txt")
            }

        assertTrue(error.message!!.contains("no records"))
    }

    @Test
    fun `a malformed line inside a record is an error, not silently skipped`() {
        // Skipping it would drop an expectation. The fixture is hand-written, so a line that was
        // meant to be an assertion and is not parseable has to stop the test.
        val error =
            assertThrows(IllegalStateException::class.java) {
                GoldenFixture.parse("=== r\n# label=x\n# this-line-has-no-equals\n", "/golden/bad.txt")
            }

        assertTrue(error.message!!.contains("this-line-has-no-equals"))
    }

    private companion object {
        const val FIXTURE = "/golden/harness-sample.txt"
    }
}
