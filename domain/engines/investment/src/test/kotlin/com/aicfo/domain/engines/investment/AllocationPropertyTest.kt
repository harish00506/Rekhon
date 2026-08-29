package com.aicfo.domain.engines.investment

import com.aicfo.core.common.Ok
import com.aicfo.core.model.AssetClass
import com.aicfo.core.model.Money
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.random.Random

/**
 * The statements that must hold for the portfolios nobody thought of (issue 6.4; §11.2, P-08).
 *
 * Why:  the golden file pins nine cases somebody chose. These are the identities that have to be
 *       true for the rest. Each is here because breaking it is *plausible* and because none of the
 *       example tests would notice: rounding each slice independently instead of distributing the
 *       remainder passes every boundary test in `AllocationTest` and still shows a pie adding to
 *       99.97%; sorting positions before grouping them changes which class receives the leftover
 *       basis point without changing any total.
 * What: five invariants over a thousand generated portfolios, plus monotonicity and determinism.
 * Result: the arithmetic is checked over a space, not a list.
 * Changelog: 2026-08-28 — Created for issue 6.4.
 *
 * **Seeded, never `Random()`** (P-08). A property test that generates a different thousand cases
 * each run is a flake generator: it fails once on a machine nobody can reproduce, and passes on the
 * rerun. [SEED] is fixed, so a failure here is a failure everywhere, for ever, until it is fixed.
 */
class AllocationPropertyTest {
    private val engine = InvestmentEngineFactory.create()

    @Test
    fun `the identities hold across a thousand generated portfolios`() {
        generated().forEach { positions ->
            val case = positions.joinToString { "${it.assetClass.storedValue}=${it.value?.minor ?: "none"}" }
            val allocation = (engine.allocation(AllocationInput(positions, nowUtcMillis = 1L)) as Ok).value

            // 1. The shares are a partition of the portfolio. Rounding each slice on its own rather
            //    than distributing the remainder is the plausible bug, and it shows up only here.
            if (allocation.slices.isNotEmpty()) {
                assertWithMessage("shares must sum to 10 000 bps — %s", case)
                    .that(allocation.slices.sumOf { it.shareBps }).isEqualTo(BPS_FULL)
            }

            // 2. The slices account for exactly the money that went in. A class dropped by the
            //    `> ZERO` filter, or one counted twice by a grouping mistake, breaks this and
            //    nothing else.
            assertWithMessage("slice values must sum to the total — %s", case)
                .that(allocation.slices.fold(Money.ZERO) { running, slice -> running + slice.value })
                .isEqualTo(allocation.total)

            // 3. Every position is either counted or explicitly excluded; none is silently lost.
            assertWithMessage("every position is valued or unvalued — %s", case)
                .that(allocation.valuedCount + allocation.unvaluedCount).isEqualTo(positions.size)

            // 4. A flag is a breach. The type's `require` enforces it per instance; this asserts the
            //    engine never *wants* to raise one at the line, which is the off-by-one that would
            //    accuse a user who has done nothing wrong.
            allocation.flags.forEach { flag ->
                assertWithMessage("a flag must exceed its threshold — %s", case)
                    .that(flag.measuredBps).isGreaterThan(flag.thresholdBps)
            }

            // 5. Slices are ordered largest first, because the legend and the bar both render in
            //    this order and a chart whose biggest segment is not first reads as a bug.
            assertWithMessage("slices must be ordered largest first — %s", case)
                .that(allocation.slices.map { it.value.minor })
                .isInOrder(Comparator.reverseOrder<Long>())
        }
    }

    @Test
    fun `a class's share never falls when its holding grows`() {
        Random(SEED).let { random ->
            repeat(CASES) {
                val others =
                    List(random.nextInt(1, 6)) { index ->
                        position("other-$index", AssetClass.DEBT, random.nextLong(1, 1_000_000))
                    }
                val smaller = position("gold", AssetClass.GOLD, random.nextLong(1, 1_000_000))
                val larger = smaller.copy(value = Money(smaller.value!!.minor + random.nextLong(1, 100_000)))

                val before = shareOf(AssetClass.GOLD, others + smaller)
                val after = shareOf(AssetClass.GOLD, others + larger)

                assertWithMessage("adding money to gold cannot shrink gold's share")
                    .that(after).isAtLeast(before)
            }
        }
    }

    @Test
    fun `the order positions arrive in does not change the answer`() {
        Random(SEED).let { random ->
            repeat(CASES) {
                val positions = portfolio(random)
                val shuffled = positions.shuffled(random)

                assertWithMessage("a portfolio is a set, not a sequence")
                    .that(allocationOf(shuffled)).isEqualTo(allocationOf(positions))
            }
        }
    }

    @Test
    fun `the same portfolio gives the same answer twice`() {
        generated().take(DETERMINISM_CASES).forEach { positions ->
            assertThat(allocationOf(positions)).isEqualTo(allocationOf(positions))
        }
    }

    // --- generation -----------------------------------------------------------------------------

    /** Result: the share a class ended up with, or 0 when it holds nothing. */
    private fun shareOf(
        assetClass: AssetClass,
        positions: List<PortfolioPosition>,
    ): Int = allocationOf(positions).slices.firstOrNull { it.assetClass == assetClass }?.shareBps ?: 0

    /** Result: the allocation. Input: [positions]. Output: [PortfolioAllocation]. */
    private fun allocationOf(positions: List<PortfolioPosition>): PortfolioAllocation =
        (engine.allocation(AllocationInput(positions, nowUtcMillis = 1L)) as Ok).value

    /** Result: a position. Input: [name], [assetClass], [value] paise or `null`. */
    private fun position(
        name: String,
        assetClass: AssetClass,
        value: Long?,
    ) = PortfolioPosition("id-$name", "acc-1", name, assetClass, value?.let { Money(it) })

    /**
     * Result: one random portfolio — one to eight positions, any class, some unpriced.
     * Why:    unpriced positions are generated at roughly one in six because they are the case that
     *         changes the denominator, and a generator that never produced one would leave P-03's
     *         exclusion tested only by the examples.
     */
    private fun portfolio(random: Random): List<PortfolioPosition> =
        List(random.nextInt(1, 9)) { index ->
            position(
                name = "holding-$index",
                assetClass = AssetClass.entries[random.nextInt(AssetClass.entries.size)],
                value = if (random.nextInt(6) == 0) null else random.nextLong(0, 5_000_000),
            )
        }

    /** Result: a thousand seeded portfolios. Input: none. Output: the cases. */
    private fun generated(): List<List<PortfolioPosition>> {
        val random = Random(SEED)
        return List(CASES) { portfolio(random) }
    }

    private companion object {
        /** Fixed so a failure reproduces on every machine, for ever (P-08). */
        const val SEED = 6_4_2026L
        const val CASES = 1_000
        const val DETERMINISM_CASES = 50
    }
}
