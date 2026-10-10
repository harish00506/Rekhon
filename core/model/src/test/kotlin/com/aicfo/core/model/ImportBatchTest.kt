package com.aicfo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ImportBatch] — §33's statement-grade provenance, as the app sees it (ADR-0078).
 *
 * Why:  `:core:model` is held to **100% line coverage** (CLAUDE.md §4), and for a reason this class
 *       illustrates: it carries no behaviour except its invariants, so an untested line here is an
 *       invariant nobody has ever seen fire. The ones that matter are the two that decide whether a
 *       provenance record can lie — a batch that kept more lines than it was offered, and a run
 *       with no identity or no instant.
 * What: the derived gap, the three refusals, and the absences that are legitimate.
 * Result: an impossible batch cannot be constructed, so `skippedCount` is never negative in front
 *         of a user.
 * Changelog: 2026-10-10 — Created (ADR-0078).
 */
class ImportBatchTest {
    /**
     * Input:  a run offered 47 lines that kept 42.
     * Output: asserts the gap is 5.
     *
     * The number a user actually wants when an import "looks short". Derived once here rather than
     * at each call site, which is where it would eventually be got wrong.
     */
    @Test
    fun `the skipped count is what was offered minus what was kept`() {
        val batch = batch(lineCount = 47, acceptedCount = 42)

        assertEquals(5, batch.skippedCount)
    }

    /** Input: a run that kept everything. Output: asserts nothing was skipped. */
    @Test
    fun `a run that kept everything skipped nothing`() {
        assertEquals(0, batch(lineCount = 12, acceptedCount = 12).skippedCount)
    }

    /** Input: an empty run. Output: asserts the counts default to zero and the gap with them. */
    @Test
    fun `an empty run is legitimate and skips nothing`() {
        val batch = batch()

        assertEquals(0, batch.lineCount)
        assertEquals(0, batch.acceptedCount)
        assertEquals(0, batch.skippedCount)
    }

    /**
     * Input:  the optional facts left out.
     * Output: asserts each is `null` or its documented default, not a fabricated value.
     *
     * A file import has no fetch instant and may state no window. Those absences are real, and
     * defaulting them to anything — zero, today, the start of the epoch — would be the app
     * inventing a fact about someone's statement (P-03).
     */
    @Test
    fun `a run that states no window and no fetch instant says so, rather than guessing`() {
        val batch = batch()

        assertNull(batch.fetchedAtUtcMillis)
        assertNull(batch.windowStartIsoDate)
        assertNull(batch.windowEndIsoDate)
        assertTrue("a run is whole unless it says otherwise", batch.complete)
    }

    /**
     * Input:  a batch claiming it kept more than it was offered.
     * Output: asserts construction is refused.
     *
     * The invariant that keeps [ImportBatch.skippedCount] from going negative. The repository
     * refuses the same thing first and returns a `Result`, so this guard is the programmer-error
     * backstop rather than the user-facing path (§21.6).
     */
    @Test
    fun `a run cannot keep more lines than it was offered`() {
        val thrown =
            assertThrows(IllegalArgumentException::class.java) {
                batch(lineCount = 3, acceptedCount = 4)
            }

        assertTrue(thrown.message.orEmpty().contains("more lines than it was offered"))
    }

    /** Input: a negative count. Output: asserts construction is refused. */
    @Test
    fun `a run cannot have been offered a negative number of lines`() {
        assertThrows(IllegalArgumentException::class.java) { batch(lineCount = -1) }
        assertThrows(IllegalArgumentException::class.java) {
            batch(lineCount = 5, acceptedCount = -1)
        }
    }

    /** Input: a blank id. Output: asserts construction is refused — a run must be citable. */
    @Test
    fun `a run must be identified`() {
        assertThrows(IllegalArgumentException::class.java) { batch(id = "  ") }
    }

    /**
     * Input:  a start instant of zero.
     * Output: asserts construction is refused.
     *
     * TIM-001: zero is the epoch, not "unknown". A provenance record that cannot say *when* is not
     * a provenance record, and the one way to be sure a caller supplies the clock's value is to
     * make the default impossible.
     */
    @Test
    fun `a run must say when it happened`() {
        assertThrows(IllegalArgumentException::class.java) { batch(startedAtUtcMillis = 0L) }
    }

    private fun batch(
        id: String = "imp:1",
        startedAtUtcMillis: Long = 1_790_000_000_000L,
        lineCount: Int = 0,
        acceptedCount: Int = 0,
    ) = ImportBatch(
        id = id,
        source = TransactionSource.ACCOUNT_AGGREGATOR,
        startedAtUtcMillis = startedAtUtcMillis,
        lineCount = lineCount,
        acceptedCount = acceptedCount,
    )
}
