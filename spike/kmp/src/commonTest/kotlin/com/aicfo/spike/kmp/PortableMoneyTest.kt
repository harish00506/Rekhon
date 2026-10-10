package com.aicfo.spike.kmp

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [PortableMoney]'s properties — run on **both** the JVM and Kotlin/Native (issue 13.7; ADR-0075).
 *
 * Why:  this is the half of the spike that answers the portability question directly. These tests
 *       live in `commonTest`, so Gradle runs them twice: once as `jvmTest` and once as
 *       `linuxX64Test`, compiled by Kotlin/Native. A green `linuxX64Test` is the evidence — not an
 *       argument — that half-even money arithmetic works on the platform an iOS port needs.
 * What: the invariants `Money` is held to in `:core:model`, restated against the portable one.
 * Result: the algorithm is portable. What it costs is on [PortableMoney.percentOf].
 * Changelog: 2026-10-10 — Created for issue 13.7.
 */
class PortableMoneyTest {
    // --- half-even, the rule everything rests on -------------------------------------------------

    /**
     * Output: asserts ties go to the **even** neighbour in both directions, which is what stops a
     *         long run of roundings drifting upward the way half-up does (MNY-001).
     */
    @Test
    fun ties_round_to_even() {
        assertEquals(2L, divideHalfEven(5L, 2L))
        assertEquals(2L, divideHalfEven(3L, 2L))
        assertEquals(4L, divideHalfEven(7L, 2L))
        assertEquals(0L, divideHalfEven(1L, 2L))
    }

    /** Output: asserts the sign survives, and that negatives round symmetrically. */
    @Test
    fun rounding_is_symmetric_about_zero() {
        assertEquals(-2L, divideHalfEven(-5L, 2L))
        assertEquals(-2L, divideHalfEven(-3L, 2L))
        assertEquals(-2L, divideHalfEven(5L, -2L))
    }

    /** Output: asserts an exact division is left alone. */
    @Test
    fun exact_division_is_untouched() {
        assertEquals(50L, divideHalfEven(100L, 2L))
        assertEquals(-50L, divideHalfEven(-100L, 2L))
    }

    /**
     * Output: asserts the quotient is never more than one away from the truncating one — the
     *         definition of rounding, and the first thing a wrong comparison breaks.
     */
    @Test
    fun rounding_never_moves_more_than_one() {
        val random = Random(13_701)
        repeat(5_000) {
            val numerator = random.nextLong(-1_000_000_000L, 1_000_000_000L)
            val denominator = random.nextLong(1L, 100_000L)
            val truncated = numerator / denominator

            assertTrue(divideHalfEven(numerator, denominator) - truncated in -1L..1L)
        }
    }

    // --- allocation loses nothing -----------------------------------------------------------------

    /**
     * Output: asserts the shares sum **exactly** back to the amount, for any weights. This is the
     *         property MNY-001 cares about most: a split that loses a paise is money that vanished.
     */
    @Test
    fun allocation_always_sums_back_exactly() {
        val random = Random(13_702)
        repeat(2_000) {
            val amount = PortableMoney(random.nextLong(-10_000_000L, 10_000_000L))
            val weights = List(random.nextInt(1, 7)) { random.nextInt(0, 20) }
            if (weights.sum() == 0) return@repeat

            assertEquals(amount, amount.allocate(weights).sum())
        }
    }

    /** Output: asserts an equal split sums back and the parts differ by at most one paise. */
    @Test
    fun equal_split_sums_back_and_stays_even() {
        val random = Random(13_703)
        repeat(2_000) {
            val amount = PortableMoney(random.nextLong(-1_000_000L, 1_000_000L))
            val parts = random.nextInt(1, 13)
            val shares = amount.split(parts)

            assertEquals(parts, shares.size)
            assertEquals(amount, shares.sum())
            val spread = shares.maxOf { it.minor } - shares.minOf { it.minor }
            assertTrue(spread <= 1L, "parts differed by $spread")
        }
    }

    /** Output: ₹1.00 into 3 is 34+33+33, the worked example `Money` documents. */
    @Test
    fun the_classic_split_keeps_the_odd_paise() {
        assertEquals(listOf(34L, 33L, 33L), PortableMoney(100L).split(3).map { it.minor })
    }

    // --- rates -------------------------------------------------------------------------------------

    /** Output: a worked rate, by hand — 18% of ₹1,000.00 is ₹180.00. */
    @Test
    fun a_worked_rate() {
        assertEquals(PortableMoney(18_000L), PortableMoney(100_000L).percentOf(1_800))
    }

    /** Output: asserts a rate over periods divides, as a monthly share of an annual rate does. */
    @Test
    fun a_rate_over_periods() {
        assertEquals(PortableMoney(1_500L), PortableMoney(100_000L).percentOf(1_800, overPeriods = 12))
    }

    /** Output: asserts zero and full rates behave. */
    @Test
    fun boundary_rates() {
        assertEquals(PortableMoney.ZERO, PortableMoney(12_345L).percentOf(0))
        assertEquals(PortableMoney(12_345L), PortableMoney(12_345L).percentOf(10_000))
    }

    /** Output: asserts a negative rate is refused (MNY-002). */
    @Test
    fun a_negative_rate_is_refused() {
        assertFailsWith<IllegalArgumentException> { PortableMoney(100L).percentOf(-1) }
    }

    // --- the bound this port costs -------------------------------------------------------------------

    /**
     * Output: asserts the overflow guard fires rather than wrapping silently.
     *
     * This is the spike's headline cost, made concrete: without `BigDecimal` there is no unbounded
     * intermediate, so a large enough amount times a rate overflows `Long`. It **throws** instead of
     * returning a wrong number — but a production port still owes the decision ADR-0075 records.
     */
    @Test
    fun an_amount_large_enough_to_overflow_is_refused_not_wrapped() {
        assertFailsWith<IllegalArgumentException> { PortableMoney(Long.MAX_VALUE / 2L).percentOf(10_000) }
    }

    /** Output: asserts the realistic range is comfortably inside the bound — ₹1 crore at 18%. */
    @Test
    fun realistic_household_amounts_are_well_inside_the_bound() {
        val oneCrore = PortableMoney(1_00_00_000_00L)

        assertEquals(PortableMoney(18_00_000_00L), oneCrore.percentOf(1_800))
    }

    // --- arithmetic ------------------------------------------------------------------------------

    /** Output: asserts overflow throws rather than wrapping — `Math.addExact`'s job, reimplemented. */
    @Test
    fun addition_overflow_throws() {
        assertFailsWith<IllegalArgumentException> { PortableMoney(Long.MAX_VALUE) + PortableMoney(1L) }
    }

    /** Output: asserts multiplication overflow throws. */
    @Test
    fun multiplication_overflow_throws() {
        assertFailsWith<IllegalArgumentException> { PortableMoney(Long.MAX_VALUE) * 2 }
    }

    /** Output: asserts addition and subtraction round-trip over seeded cases. */
    @Test
    fun addition_and_subtraction_round_trip() {
        val random = Random(13_704)
        repeat(2_000) {
            val a = PortableMoney(random.nextLong(-1_000_000_000L, 1_000_000_000L))
            val b = PortableMoney(random.nextLong(-1_000_000_000L, 1_000_000_000L))

            assertEquals(a, a + b - b)
        }
    }
}
