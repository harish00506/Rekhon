package com.aicfo.domain.engines.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the calendar priors — the windows, the overlap rule and the shrinkage.
 *
 * Why:  every bug available in this file is silent. A window that fails to wrap simply never fires;
 *       an overlap resolved by multiplication produces a number that looks reasonable; a shrinkage
 *       that ignores its cap produces one that looks generous. None of them throw, and none of them
 *       are visible in a single month's suggestion.
 * What: month-boundary tests on both window shapes, the max-not-product rule, and the three points
 *       of the shrinkage curve that matter (nothing observed, the cap, past the cap).
 * Result: the seasonal half of FR-BUD-002 is pinned independently of the engine that calls it.
 * Changelog: 2026-08-11 — Created for issue 4.4.
 */
class SeasonalityPriorsTest {
    // --- ordinary windows -----------------------------------------------------------------------

    /** Input: months around a non-wrapping window. Output: asserts an inclusive `start..end`. */
    @Test
    fun `a non-wrapping window is inclusive at both ends`() {
        val diwali = event("diwali")

        assertTrue("October is the first month of Oct-Nov", diwali.applies("Shopping", 10))
        assertTrue("November is the last month of Oct-Nov", diwali.applies("Shopping", 11))
        assertTrue("September is outside Oct-Nov", !diwali.applies("Shopping", 9))
        assertTrue("December is outside Oct-Nov", !diwali.applies("Shopping", 12))
    }

    // --- wrapping windows: the case four of the nine events depend on --------------------------

    /**
     * Input:  every month, against the Nov-Feb wedding window.
     * Output: asserts exactly November, December, January and February fire. A naive
     *         `month in 11..2` is an empty range and would match none of them — silently removing
     *         the prior from the four months a gifting and travel budget most needs it.
     */
    @Test
    fun `a window that wraps the year end covers both sides of December`() {
        val wedding = event("wedding_season")
        val firing = (1..12).filter { wedding.applies("Travel", it) }

        assertEquals(listOf(1, 2, 11, 12), firing)
    }

    /** Input: the Aug-Jan window. Output: asserts the long wrap covers six months, not none. */
    @Test
    fun `the longest wrapping window covers every month it spans`() {
        val onam = event("onam_pongal")
        val firing = (1..12).filter { onam.applies("Dining", it) }

        assertEquals(listOf(1, 8, 9, 10, 11, 12), firing)
    }

    // --- the overlap rule -----------------------------------------------------------------------

    /**
     * Input:  October shopping, where Diwali (1.38) and Dussehra (1.20) both apply.
     * Output: asserts the strongest is taken, never the product. 1.38 x 1.20 = 1.66 is a claim no
     *         row in the knowledge base makes, and it would grow every time an editor added another
     *         event to the same month — an unbounded number produced by a data edit.
     */
    @Test
    fun `overlapping events resolve to the strongest, never the product`() {
        val winner = SeasonalityPriors.strongestFor("Shopping", 10)

        assertEquals("diwali", winner?.id)
        assertEquals(13_800, winner?.priorMultiplierBps)
    }

    /** Input: a month with no event for the category. Output: asserts null rather than a neutral event. */
    @Test
    fun `an ordinary month has no strongest event`() {
        assertNull(SeasonalityPriors.strongestFor("Groceries", 7))
    }

    /** Input: a category no event names. Output: asserts null in every month of the year. */
    @Test
    fun `a category the knowledge base never names is never adjusted`() {
        assertTrue((1..12).all { SeasonalityPriors.strongestFor("Aquarium upkeep", it) == null })
    }

    // --- shrinkage ------------------------------------------------------------------------------

    /** Input: nothing observed. Output: asserts the prior is shrunk entirely away — `k = 0`. */
    @Test
    fun `no observed history means no adjustment at all`() {
        assertEquals(BPS_FULL, SeasonalityPriors.seasonalIndexBps(13_800, monthsObserved = 0, denominatorMonths = 24))
    }

    /** Input: exactly the denominator. Output: asserts the full prior — `k = 1`. */
    @Test
    fun `history equal to the denominator applies the prior in full`() {
        assertEquals(13_800, SeasonalityPriors.seasonalIndexBps(13_800, monthsObserved = 24, denominatorMonths = 24))
    }

    /** Input: far past the denominator. Output: asserts `k` is capped, so the prior never amplifies. */
    @Test
    fun `history past the denominator is capped, not extrapolated`() {
        assertEquals(13_800, SeasonalityPriors.seasonalIndexBps(13_800, monthsObserved = 600, denominatorMonths = 24))
    }

    /** Input: half the denominator. Output: asserts half the excess is applied. */
    @Test
    fun `half the denominator applies half the excess`() {
        assertEquals(11_900, SeasonalityPriors.seasonalIndexBps(13_800, monthsObserved = 12, denominatorMonths = 24))
    }

    /**
     * Input:  every event, at every point on the shrinkage curve.
     * Output: asserts the index never drops below "no change" and never exceeds the raw prior. Those
     *         two bounds are what stop a seasonal adjustment from ever *cutting* a budget or from
     *         inflating one past what the knowledge base actually claims.
     */
    @Test
    fun `the index always sits between no adjustment and the raw prior`() {
        val outOfBounds =
            SeasonalityPriors.events.flatMap { seasonalEvent ->
                (0..48).map { observed ->
                    seasonalEvent to SeasonalityPriors.seasonalIndexBps(seasonalEvent.priorMultiplierBps, observed, 24)
                }
            }.filter { (seasonalEvent, index) -> index < BPS_FULL || index > seasonalEvent.priorMultiplierBps }

        assertEquals("$outOfBounds", 0, outOfBounds.size)
    }

    // --- invariants -----------------------------------------------------------------------------

    /** Input: event values the knowledge base could never legitimately hold. Output: asserts rejection. */
    @Test
    fun `events that could not describe a real seasonal effect are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { SeasonalEvent("x", 0, 5, setOf("Travel"), 11_000) }
        assertThrows(IllegalArgumentException::class.java) { SeasonalEvent("x", 1, 13, setOf("Travel"), 11_000) }
        assertThrows(IllegalArgumentException::class.java) { SeasonalEvent("x", 1, 5, emptySet(), 11_000) }
        // Below 10 000 bps the "prior" would predict a cheaper festival than an ordinary month.
        assertThrows(IllegalArgumentException::class.java) { SeasonalEvent("x", 1, 5, setOf("Travel"), 9_000) }
        assertThrows(IllegalArgumentException::class.java) { event("diwali").applies("Shopping", 0) }
        assertThrows(IllegalArgumentException::class.java) {
            SeasonalityPriors.seasonalIndexBps(13_800, monthsObserved = -1, denominatorMonths = 24)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SeasonalityPriors.seasonalIndexBps(13_800, monthsObserved = 1, denominatorMonths = 0)
        }
    }

    private fun event(id: String): SeasonalEvent = SeasonalityPriors.events.first { it.id == id }
}
