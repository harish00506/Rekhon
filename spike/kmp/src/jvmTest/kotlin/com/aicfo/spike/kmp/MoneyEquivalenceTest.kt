package com.aicfo.spike.kmp

import com.aicfo.core.model.Money
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Does the portable money agree with the one that ships? (issue 13.7; ADR-0075.)
 *
 * Why:  `PortableMoneyTest` proves the algorithm **runs** on Kotlin/Native. That is only half the
 *       question. The other half is whether it computes the **same answers** as `:core:model`'s
 *       `Money` — because a port that is portable and subtly different is worse than no port: it
 *       would give an iPhone user a different figure from an Android user for the same money.
 *
 *       So this runs on the JVM only, where both implementations exist, and compares them over
 *       seeded cases. `BigDecimal` with `HALF_EVEN` on one side, hand-rolled integer arithmetic on
 *       the other, asked the same questions.
 * What: `percentOf`, `split` and `allocate`, across thousands of amounts and rates.
 * Result: they agree. The port is a faithful one, within the bound `percentOf` documents.
 * Changelog: 2026-10-10 — Created for issue 13.7.
 */
class MoneyEquivalenceTest {
    /**
     * Input:  5 000 seeded amount/rate pairs, bounded well inside `percentOf`'s overflow guard.
     * Output: asserts the two implementations produce the identical paise figure.
     */
    @Test
    fun percentOf_agrees_with_the_shipped_Money() {
        val random = Random(13_705)
        repeat(5_000) {
            val minor = random.nextLong(-10_00_00_000_00L, 10_00_00_000_00L)
            val bps = random.nextInt(0, 10_001)

            assertEquals(
                Money(minor).percentOf(bps).minor,
                PortableMoney(minor).percentOf(bps).minor,
                "percentOf disagreed for $minor at $bps bps",
            )
        }
    }

    /**
     * Input:  the same, over a number of periods — the monthly-rest path issue 6.2 added.
     * Output: asserts they agree there too, where two divisions compound.
     */
    @Test
    fun percentOf_over_periods_agrees() {
        val random = Random(13_706)
        repeat(5_000) {
            val minor = random.nextLong(-1_00_00_000_00L, 1_00_00_000_00L)
            val bps = random.nextInt(0, 5_001)
            val periods = random.nextInt(1, 61)

            assertEquals(
                Money(minor).percentOf(bps, periods).minor,
                PortableMoney(minor).percentOf(bps, periods).minor,
                "percentOf disagreed for $minor at $bps bps over $periods periods",
            )
        }
    }

    /**
     * Input:  3 000 seeded splits.
     * Output: asserts the **whole list** matches, not just the sum — the remainder has to land on
     *         the same parts, or two users with the same bill would see different shares.
     */
    @Test
    fun split_agrees_part_for_part() {
        val random = Random(13_707)
        repeat(3_000) {
            val minor = random.nextLong(-1_000_000L, 1_000_000L)
            val parts = random.nextInt(1, 13)

            assertEquals(
                Money(minor).split(parts).map { it.minor },
                PortableMoney(minor).split(parts).map { it.minor },
                "split disagreed for $minor into $parts",
            )
        }
    }

    /**
     * Input:  3 000 seeded weighted allocations.
     * Output: asserts the whole list matches — the largest-remainder tie-breaking included.
     */
    @Test
    fun allocate_agrees_share_for_share() {
        val random = Random(13_708)
        repeat(3_000) {
            val minor = random.nextLong(-1_000_000L, 1_000_000L)
            val weights = List(random.nextInt(1, 7)) { random.nextInt(0, 20) }
            if (weights.sum() == 0) return@repeat

            assertEquals(
                Money(minor).allocate(weights).map { it.minor },
                PortableMoney(minor).allocate(weights).map { it.minor },
                "allocate disagreed for $minor over $weights",
            )
        }
    }

    /**
     * Input:  the exact half-way cases, where half-even is the only thing that distinguishes two
     *         plausible answers.
     * Output: asserts agreement precisely where a half-up implementation would diverge. The seeded
     *         runs above would mostly miss these; this is the case that would catch a wrong mode.
     */
    @Test
    fun the_exact_ties_agree_where_half_up_would_differ() {
        // 50 bps of an odd number of paise lands exactly on .5 for these.
        listOf(1L, 3L, 5L, 7L, 9L, 11L, 100L, 300L).forEach { minor ->
            assertEquals(
                Money(minor).percentOf(5_000).minor,
                PortableMoney(minor).percentOf(5_000).minor,
                "a tie disagreed for $minor",
            )
        }
        // And the direct division, where the tie is unmistakable.
        assertTrue(divideHalfEven(5L, 2L) == 2L && divideHalfEven(7L, 2L) == 4L)
    }
}
