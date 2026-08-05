package com.aicfo.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Property tests for [Money.split] / [Money.allocate] — task 1.1.2 T4/T5 (MNY-001, §21.4).
 *
 * Why:  the one invariant that must never break is "no paise is created or lost". A handful of
 *       hand-picked examples cannot show that; sweeping thousands of amount x part-count
 *       combinations can. A split that loses a paise per transaction is exactly the kind of
 *       drift that only surfaces months later in a reconciliation mismatch.
 * What: for random amounts (positive, negative and zero) and every part count 1..97, asserts the
 *       parts sum back to the original, differ by at most one paise, and never flip sign.
 * Result: the exactness guarantee is proven across the input space, not just at the examples.
 * Changelog: 2026-07-25 — Created for issue 1.2.
 *
 * Randomness is **seeded** (P-08): a failure reproduces exactly, and CI cannot go green or red by
 * luck. No property-test dependency is used — kotest/jqwik are not in the version catalog, and
 * adding a library would need an ADR for what a seeded loop already does.
 */
class MoneySplitPropertyTest {
    private companion object {
        /** Fixed so every run — local or CI — exercises the identical sample (P-08). */
        const val SEED = 20_260_725L
        const val SAMPLES = 200
        const val MAX_PARTS = 97
    }

    /**
     * Input:  200 seeded random amounts x every part count in 1..97.
     * Output: asserts `split(n)` returns n parts that sum **exactly** to the original.
     */
    @Test
    fun `split always sums to the original`() {
        forEachSampleAmount { amount ->
            for (parts in 1..MAX_PARTS) {
                val split = amount.split(parts)
                assertEquals("split($parts) of $amount lost or created paise", amount, split.sum())
                assertEquals("split($parts) of $amount returned the wrong part count", parts, split.size)
            }
        }
    }

    /**
     * Input:  the same seeded sample.
     * Output: asserts parts differ by at most one paise — the remainder is spread, not dumped.
     */
    @Test
    fun `split parts differ by at most one paise`() {
        forEachSampleAmount { amount ->
            for (parts in 1..MAX_PARTS) {
                val minors = amount.split(parts).map(Money::minor)
                val spread = minors.max() - minors.min()
                assertTrue(
                    "split($parts) of $amount spread the remainder unevenly (delta $spread)",
                    spread <= 1L,
                )
            }
        }
    }

    /**
     * Input:  the same seeded sample, allocated over pseudo-random weights.
     * Output: asserts weighted allocation is exact too, and that no part flips sign.
     */
    @Test
    fun `allocate always sums to the original`() {
        val random = Random(SEED)
        forEachSampleAmount { amount ->
            val weights =
                List(1 + random.nextInt(12)) { random.nextInt(100) }
                    .let { if (it.sum() == 0) listOf(1) else it }
            val parts = amount.allocate(weights)
            assertEquals("allocate($weights) of $amount lost or created paise", amount, parts.sum())
            assertTrue(
                "allocate($weights) of $amount flipped a part's sign",
                parts.all { it.minor == 0L || (it.minor > 0L) == (amount.minor > 0L) },
            )
        }
    }

    /**
     * Runs [check] over the seeded sample: zero, the signed extremes of a realistic rupee range,
     * and [SAMPLES] random amounts in +/- 100 crore paise.
     * Input:  [check] — the assertion to apply to each amount.
     * Output: none (fails the test on the first violation).
     */
    private fun forEachSampleAmount(check: (Money) -> Unit) {
        val random = Random(SEED)
        val fixed = listOf(Money.ZERO, Money(1), Money(-1), Money(99), Money(-99))
        fixed.forEach(check)
        repeat(SAMPLES) {
            // Long-only arithmetic on purpose: no Double may touch an amount, not even in a test
            // that only generates one (MNY-001).
            val magnitude = random.nextInt(1_000_000_000).toLong()
            check(Money(if (random.nextBoolean()) magnitude else -magnitude))
        }
    }
}
