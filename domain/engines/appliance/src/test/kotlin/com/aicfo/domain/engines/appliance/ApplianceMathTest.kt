package com.aicfo.domain.engines.appliance

import com.aicfo.core.common.SeededCases
import com.aicfo.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic under [ApplianceMath], tested where the golden file cannot reach (issue 13.2).
 *
 * Why:  the golden file pins the engine against an independent oracle, which is the strongest check
 *       this engine has — but it can only exercise what the shipped knowledge base contains.
 *       Mutation G5 proved the gap: changing the midpoint's rounding from half-even to **half-up**
 *       left every golden scenario passing, because every cost range in the KB happens to sum to an
 *       even number, so the rounding mode never applies. A KB row added next year with an odd sum
 *       would then round the other way with nothing to notice. The rules live here, where they can
 *       be tested on the inputs that distinguish them rather than on the inputs that happen to ship.
 * What: the half-even midpoint, the running-cost identities, the month-end clamp and the seasonal
 *       anchor's boundary.
 * Result: the two places money and dates can go wrong are each pinned on their own terms.
 * Changelog: 2026-10-03 — Created for issue 13.2.
 */
class ApplianceMathTest {
    // --- the midpoint ------------------------------------------------------------------------------

    /**
     * Input:  ranges whose sum is **odd**, so the rounding mode decides the answer.
     * Output: asserts half-even — ties go to the even neighbour, so `..5` rounds down from 2 and up
     *         from 3. Half-up would answer 3 to both.
     */
    @Test
    fun `an odd midpoint rounds half-even, not half-up`() {
        assertEquals(Money(2L), ApplianceMath.midpoint(range(1L, 4L)))
        assertEquals(Money(4L), ApplianceMath.midpoint(range(3L, 6L)))
        assertEquals(Money(0L), ApplianceMath.midpoint(range(0L, 1L)))
        assertEquals(Money(2L), ApplianceMath.midpoint(range(1L, 2L)))
    }

    /**
     * Input:  2 000 seeded ranges.
     * Output: asserts the midpoint never leaves the range it came from. A midpoint outside its own
     *         bounds would be a figure no evidence line could justify (P-02).
     */
    @Test
    fun `a midpoint always lies within its range`() {
        SeededCases(seed = 13_201L, count = 2_000).forEach { random ->
            val low = random.nextLong(0L, 10_000_000L)
            val high = low + random.nextLong(0L, 10_000_000L)
            val mid = ApplianceMath.midpoint(range(low, high))

            assertTrue("midpoint $mid below $low", mid.minor >= low)
            assertTrue("midpoint $mid above $high", mid.minor <= high)
        }
    }

    /** Input: a range with no width. Output: asserts the midpoint is that exact figure. */
    @Test
    fun `a range of one value is its own midpoint`() {
        assertEquals(Money(7_777L), ApplianceMath.midpoint(range(7_777L, 7_777L)))
    }

    // --- the running cost --------------------------------------------------------------------------

    /**
     * Input:  a 2 000 W geyser, 60 minutes a day, 30 days, at ₹8 a unit.
     * Output: asserts ₹480.00 — worked by hand: 2 kW × 1 h × 30 = 60 kWh; 60 × 800p = 48 000 p.
     *         A figure computed rather than recorded, so the test fails if the formula changes.
     */
    @Test
    fun `a worked running cost matches the arithmetic by hand`() {
        assertEquals(Money(48_000L), ApplianceMath.runningCostPerMonth(2_000, 60, 30, 800))
    }

    /** Input: an appliance never switched on. Output: asserts exactly zero, not a rounded-down figure. */
    @Test
    fun `an appliance that never runs costs exactly zero`() {
        assertEquals(Money.ZERO, ApplianceMath.runningCostPerMonth(2_000, 0, 30, 800))
    }

    /**
     * Input:  1 000 seeded appliances, each costed at one tariff and at double it.
     * Output: asserts doubling the tariff doubles the cost (within the one paise rounding can move
     *         it). Linearity is the property a user would assume, and it is the first thing a wrong
     *         divisor or a stray `Int` overflow breaks.
     */
    @Test
    fun `doubling the tariff doubles the running cost`() {
        SeededCases(seed = 13_202L, count = 1_000).forEach { random ->
            val watts = random.nextInt(1, 5_000)
            val minutes = random.nextInt(1, 1_440)
            val tariff = random.nextInt(1, 2_000)

            val single = ApplianceMath.runningCostPerMonth(watts, minutes, 30, tariff).minor
            val doubled = ApplianceMath.runningCostPerMonth(watts, minutes, 30, tariff * 2).minor

            assertTrue(
                "doubling $tariff gave $doubled, not about ${single * 2} (W=$watts, min=$minutes)",
                doubled in (single * 2 - 1)..(single * 2 + 1),
            )
        }
    }

    /**
     * Input:  1 000 seeded appliances, run for longer.
     * Output: asserts the cost never falls when the appliance runs longer. Monotonicity is what
     *         makes "use it less and it costs less" true, and a sign error would break it silently.
     */
    @Test
    fun `running something longer never costs less`() {
        SeededCases(seed = 13_203L, count = 1_000).forEach { random ->
            val watts = random.nextInt(1, 5_000)
            val minutes = random.nextInt(0, 1_000)
            val longer = minutes + random.nextInt(1, 400)

            assertTrue(
                "running $longer min cost less than $minutes min",
                ApplianceMath.runningCostPerMonth(watts, longer, 30, 800) >=
                    ApplianceMath.runningCostPerMonth(watts, minutes, 30, 800),
            )
        }
    }

    /**
     * Input:  the largest appliance and tariff the KB's own bounds allow, run around the clock.
     * Output: asserts it computes rather than overflowing. The intermediate here is
     *         watts × minutes × days × tariff before any division — the one place this engine can
     *         exceed `Long` — and `BigDecimal` is what keeps it exact.
     */
    @Test
    fun `an absurdly large appliance still computes rather than overflowing`() {
        val cost = ApplianceMath.runningCostPerMonth(Int.MAX_VALUE, 1_440, 31, Int.MAX_VALUE)
        assertTrue("an overflow would have wrapped negative", cost.minor > 0L)
    }

    // --- the calendar ------------------------------------------------------------------------------

    /**
     * Input:  month-ends that do not exist in the target month.
     * Output: asserts the day clamps to the month's last rather than spilling into the next one — a
     *         service due "a month after 31 January" is due at the end of February, not on 3 March.
     */
    @Test
    fun `adding months clamps to the end of a shorter month`() {
        assertEquals("2026-02-28", ApplianceMath.plusMonths("2026-01-31", 1))
        assertEquals("2024-02-29", ApplianceMath.plusMonths("2024-01-31", 1))
        assertEquals("2026-04-30", ApplianceMath.plusMonths("2026-03-31", 1))
    }

    /** Input: a date and zero months. Output: asserts it does not move. */
    @Test
    fun `adding no months changes nothing`() {
        assertEquals("2026-10-03", ApplianceMath.plusMonths("2026-10-03", 0))
    }

    /**
     * Input:  the day before, the day of, and the day after a seasonal anchor.
     * Output: asserts the anchor day itself resolves to **today**, not to next year. A service due
     *         today is due today; rolling it forward would hide it for a year, which for an AC
     *         means through the summer it exists for.
     */
    @Test
    fun `the seasonal anchor includes the anchor day itself`() {
        assertEquals("2026-03-01", ApplianceMath.nextSeasonal("2026-02-28", 3, 1))
        assertEquals("2026-03-01", ApplianceMath.nextSeasonal("2026-03-01", 3, 1))
        assertEquals("2027-03-01", ApplianceMath.nextSeasonal("2026-03-02", 3, 1))
    }

    /** Input: days either side of a date. Output: asserts the sign says past or future. */
    @Test
    fun `days between is negative once the date has passed`() {
        assertEquals(5L, ApplianceMath.daysBetween("2026-10-03", "2026-10-08"))
        assertEquals(0L, ApplianceMath.daysBetween("2026-10-03", "2026-10-03"))
        assertEquals(-5L, ApplianceMath.daysBetween("2026-10-08", "2026-10-03"))
    }

    private fun range(
        low: Long,
        high: Long,
    ) = ApplianceCostRange(Money(low), Money(high))
}
