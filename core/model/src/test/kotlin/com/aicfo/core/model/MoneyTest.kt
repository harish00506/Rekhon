package com.aicfo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour tests for [Money] — the T1-T8 table of task 1.1.2 (SRS §21.4, MNY-001/MNY-002).
 *
 * Why:  money math is the one place `CLAUDE.md` §4 demands 100% coverage, because a rounding or
 *       remainder bug here silently corrupts every engine, budget and forecast downstream.
 * What: arithmetic + overflow, HALF_EVEN `percentOf`, exact `split`/`allocate` (incl. negatives),
 *       ordering, and the value-class members (`equals`/`hashCode`/`toString`).
 * Result: `Money` is proven exact and fail-fast, or the build goes red.
 * Changelog: 2026-07-25 — Created for issue 1.2 (written red before Money.kt existed).
 */
class MoneyTest {
    // --- T1 · arithmetic ------------------------------------------------------------------

    /** Input: 500p + 250p. Output: asserts plus adds minor units exactly. */
    @Test
    fun `plus adds minor units`() {
        assertEquals(Money(750), Money(500) + Money(250))
    }

    /** Input: 750p - 250p. Output: asserts minus subtracts minor units exactly. */
    @Test
    fun `minus subtracts minor units`() {
        assertEquals(Money(500), Money(750) - Money(250))
    }

    /** Input: 250p x 4. Output: asserts times scales by a whole count (never a rate). */
    @Test
    fun `times multiplies by a whole count`() {
        assertEquals(Money(1_000), Money(250) * 4)
    }

    /** Input: negative and zero operands. Output: asserts refunds arithmetic behaves normally. */
    @Test
    fun `arithmetic holds for negative and zero amounts`() {
        assertEquals(Money(-250), Money(-500) + Money(250))
        assertEquals(Money(-1_000), Money(-250) * 4)
        assertEquals(Money.ZERO, Money(500) - Money(500))
    }

    // --- T2, T3 · percentOf (HALF_EVEN, basis points) -------------------------------------

    /** Input: 1.5% (150 bps) of ₹100.00. Output: asserts the documented AC2/T2 result. */
    @Test
    fun `percentOf applies basis points`() {
        assertEquals(Money(150), Money(10_000).percentOf(150))
        assertEquals(Money(250), Money(100_00).percentOf(250))
    }

    /**
     * Input: amounts whose bps product lands exactly on .5 of a paise.
     * Output: asserts banker's rounding — ties go to the EVEN neighbour, not always up.
     */
    @Test
    fun `percentOf breaks exact ties to even`() {
        // 5p x 50% = 2.5p -> 2 (2 is even); 15p x 50% = 7.5p -> 8 (8 is even).
        assertEquals(Money(2), Money(5).percentOf(5_000))
        assertEquals(Money(8), Money(15).percentOf(5_000))
        // Symmetric for refunds: -2.5p -> -2, -7.5p -> -8.
        assertEquals(Money(-2), Money(-5).percentOf(5_000))
        assertEquals(Money(-8), Money(-15).percentOf(5_000))
    }

    /** Input: 0 bps and 10 000 bps. Output: asserts the rate boundaries are exact. */
    @Test
    fun `percentOf handles the rate boundaries`() {
        assertEquals(Money.ZERO, Money(12_345).percentOf(0))
        assertEquals(Money(12_345), Money(12_345).percentOf(10_000))
    }

    /** Input: a negative rate. Output: asserts it is rejected rather than silently inverting. */
    @Test
    fun `percentOf rejects a negative rate`() {
        assertThrows(IllegalArgumentException::class.java) { Money(100).percentOf(-1) }
    }

    /** Input: a rate large enough to overflow Long. Output: asserts it throws, never truncates. */
    @Test
    fun `percentOf throws instead of overflowing`() {
        assertThrows(ArithmeticException::class.java) { Money(Long.MAX_VALUE).percentOf(20_000) }
    }

    // --- T4, T5, T6 · split and allocate --------------------------------------------------

    /** Input: ₹1.00 into 3. Output: asserts the remainder lands on the earliest parts (34/33/33). */
    @Test
    fun `split spreads the remainder to the earliest parts`() {
        assertEquals(listOf(Money(34), Money(33), Money(33)), Money(100).split(3))
    }

    /** Input: an evenly divisible amount. Output: asserts no remainder logic kicks in. */
    @Test
    fun `split divides evenly when there is no remainder`() {
        assertEquals(listOf(Money(25), Money(25), Money(25), Money(25)), Money(100).split(4))
    }

    /** Input: split into 1. Output: asserts the single part is the whole amount. */
    @Test
    fun `split into one part returns the original`() {
        assertEquals(listOf(Money(4_237)), Money(4_237).split(1))
    }

    /** Input: ZERO into 3 (T6). Output: asserts three zero parts, no division-by-zero drama. */
    @Test
    fun `split of zero yields zero parts`() {
        assertEquals(listOf(Money.ZERO, Money.ZERO, Money.ZERO), Money.ZERO.split(3))
    }

    /** Input: a negative amount into 3. Output: asserts refunds split exactly and symmetrically. */
    @Test
    fun `split of a negative amount sums exactly`() {
        val parts = Money(-100).split(3)
        assertEquals(listOf(Money(-34), Money(-33), Money(-33)), parts)
        assertEquals(Money(-100), parts.sum())
    }

    /** Input: split(0) and split(-1) (T8). Output: asserts both are rejected up front. */
    @Test
    fun `split rejects a non-positive part count`() {
        assertThrows(IllegalArgumentException::class.java) { Money(100).split(0) }
        assertThrows(IllegalArgumentException::class.java) { Money(100).split(-1) }
    }

    /** Input: equal weights (T5). Output: asserts allocate matches split's remainder order. */
    @Test
    fun `allocate with equal weights matches split`() {
        assertEquals(listOf(Money(34), Money(33), Money(33)), Money(100).allocate(listOf(1, 1, 1)))
    }

    /** Input: uneven weights. Output: asserts the largest remainder wins the spare paise. */
    @Test
    fun `allocate distributes by weight and largest remainder`() {
        // 100p over 1:1:1:1:1:1:1 -> 14.28p each (98p), so the two spare paise go to the first two.
        val parts = Money(100).allocate(List(7) { 1 })
        assertEquals(listOf(15, 15, 14, 14, 14, 14, 14).map(Int::toLong), parts.map(Money::minor))
        assertEquals(Money(100), parts.sum())
        // 100p over 2:1 -> 66.67 / 33.33; the larger remainder (0.33 vs 0.67) decides the paise.
        assertEquals(listOf(Money(67), Money(33)), Money(100).allocate(listOf(2, 1)))
    }

    /** Input: a zero weight. Output: asserts a zero-weight party is allocated nothing. */
    @Test
    fun `allocate gives nothing to a zero weight`() {
        assertEquals(listOf(Money(100), Money.ZERO), Money(100).allocate(listOf(1, 0)))
    }

    /** Input: empty, negative and all-zero weights. Output: asserts each is rejected. */
    @Test
    fun `allocate rejects unusable weights`() {
        assertThrows(IllegalArgumentException::class.java) { Money(100).allocate(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { Money(100).allocate(listOf(1, -1)) }
        assertThrows(IllegalArgumentException::class.java) { Money(100).allocate(listOf(0, 0)) }
    }

    // --- T7 · overflow --------------------------------------------------------------------

    /** Input: Long.MAX_VALUE + 1p (T7). Output: asserts a fail-fast throw, never a silent wrap. */
    @Test
    fun `plus throws on overflow`() {
        assertThrows(ArithmeticException::class.java) { Money(Long.MAX_VALUE) + Money(1) }
    }

    /** Input: Long.MIN_VALUE - 1p. Output: asserts underflow throws too. */
    @Test
    fun `minus throws on underflow`() {
        assertThrows(ArithmeticException::class.java) { Money(Long.MIN_VALUE) - Money(1) }
    }

    /** Input: Long.MAX_VALUE x 2. Output: asserts multiplication overflow throws. */
    @Test
    fun `times throws on overflow`() {
        assertThrows(ArithmeticException::class.java) { Money(Long.MAX_VALUE) * 2 }
    }

    // --- ordering, equality and the value-class members ------------------------------------

    /** Input: two amounts. Output: asserts Comparable orders by minor units. */
    @Test
    fun `compares by minor units`() {
        assertTrue(Money(100) > Money(99))
        assertTrue(Money(-100) < Money.ZERO)
        assertEquals(0, Money(100).compareTo(Money(100)))
        assertEquals(Money(250), listOf(Money(250), Money(100), Money(175)).max())
    }

    /** Input: equal and unequal amounts. Output: asserts value equality + hashCode agree. */
    @Test
    fun `equal amounts are equal and hash alike`() {
        assertEquals(Money(100), Money(100))
        assertEquals(Money(100).hashCode(), Money(100).hashCode())
        assertNotEquals(Money(100), Money(101))
    }

    /** Input: an amount. Output: asserts toString reports paise, not a formatted rupee string. */
    @Test
    fun `toString reports the raw minor units`() {
        assertEquals("Money(minor=12345)", Money(12_345).toString())
    }

    /** Input: ZERO. Output: asserts the constant is what it claims to be. */
    @Test
    fun `zero is zero paise`() {
        assertEquals(0L, Money.ZERO.minor)
    }

    /** Input: a list of amounts. Output: asserts the sum helper totals them exactly. */
    @Test
    fun `sum totals a list of amounts`() {
        assertEquals(Money(300), listOf(Money(100), Money(250), Money(-50)).sum())
        assertEquals(Money.ZERO, emptyList<Money>().sum())
    }
}
