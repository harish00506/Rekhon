package com.aicfo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks [Quantity]'s arithmetic to the same exactness [Money] keeps (issue 6.3; MNY-001's argument
 * applied to units).
 *
 * Why:  a holding's units are a unit-of-measure exactly like paise, and a bare `Long` invites the
 *       precise confusion [Money] exists to prevent — nano-units added to whole units, or a `Double`
 *       creeping in because "0.001 units of a mutual fund" reads like a decimal. Mutual funds quote
 *       three decimals and crypto quotes eight, so the scale must cover eight and the type must make
 *       the scale impossible to forget. Overflow matters too: [Long] holds about 9.2e18 nano-units,
 *       which is ~9.2 billion whole units — ample, but a silent wrap would corrupt a portfolio
 *       rather than fail it.
 * What: the scale, the identity element, addition, subtraction, ordering, and the overflow refusal.
 * Result: unit arithmetic is exact and fails loudly, or the build goes red.
 * Changelog: 2026-08-24 — Created for issue 6.3 (written red before Investment.kt existed).
 */
class QuantityTest {
    /**
     * Input: none.
     * Output: asserts the scale is 10^9 — the number every persisted `quantity_nano` is divided by
     * to read as units, and the one constant a migration could never repair if it changed.
     */
    @Test
    fun `one whole unit is a billion nano-units`() {
        assertEquals(1_000_000_000L, Quantity.SCALE)
    }

    /** Input: none. Output: asserts the identity element holds no units. */
    @Test
    fun `zero holds nothing`() {
        assertEquals(0L, Quantity.ZERO.nano)
    }

    /** Input: two quantities. Output: asserts addition is exact nano arithmetic. */
    @Test
    fun `adding two quantities is exact`() {
        val subject = Quantity(1_500_000_000) + Quantity(2_250_000_000)

        assertEquals(3_750_000_000L, subject.nano)
    }

    /** Input: a quantity and a smaller one. Output: asserts subtraction is exact. */
    @Test
    fun `subtracting a quantity is exact`() {
        val subject = Quantity(3_750_000_000) - Quantity(2_250_000_000)

        assertEquals(1_500_000_000L, subject.nano)
    }

    /**
     * Input: a sell larger than the buys before it.
     * Output: asserts the result is negative rather than clamped. The type permits it because
     * `netQuantity` is a running difference the engine computes; it is the *stored lot* that
     * requires a non-negative magnitude, and that is [InvestmentLot]'s `require`, not this one's.
     */
    @Test
    fun `subtracting past zero gives a negative quantity`() {
        val subject = Quantity(1_000_000_000) - Quantity(2_500_000_000)

        assertEquals(-1_500_000_000L, subject.nano)
    }

    /** Input: the eight decimals crypto quotes. Output: asserts the scale represents them exactly. */
    @Test
    fun `eight decimal places survive the scale`() {
        assertEquals(1L, Quantity(1).nano)
        assertEquals(10L, Quantity(10).nano)
    }

    /** Input: quantities either side of each other. Output: asserts the ordering. */
    @Test
    fun `quantities order by their units`() {
        assertTrue(Quantity(2_000_000_000) > Quantity(1_000_000_000))
        assertTrue(Quantity(1_000_000_000) < Quantity(2_000_000_000))
        assertEquals(0, Quantity(1_000_000_000).compareTo(Quantity(1_000_000_000)))
        assertTrue(Quantity(-1) < Quantity.ZERO)
    }

    /**
     * Input: two quantities whose sum exceeds [Long].
     * Output: asserts an [ArithmeticException] rather than a wrapped negative — the same
     * `Math.addExact` discipline [Money] keeps, because a silently negative portfolio is worse than
     * a crash that names the row.
     */
    @Test
    fun `overflowing addition throws instead of wrapping`() {
        assertThrows(ArithmeticException::class.java) {
            Quantity(Long.MAX_VALUE) + Quantity(1)
        }
    }

    /** Input: two quantities whose difference underflows [Long]. Output: asserts it throws. */
    @Test
    fun `underflowing subtraction throws instead of wrapping`() {
        assertThrows(ArithmeticException::class.java) {
            Quantity(Long.MIN_VALUE) - Quantity(1)
        }
    }
}
