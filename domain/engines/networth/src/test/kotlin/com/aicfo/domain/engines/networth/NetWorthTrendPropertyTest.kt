package com.aicfo.domain.engines.networth

import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.random.Random

/**
 * The identities the trend must hold for *any* series (issue 6.6; FR-ACC-005, MNY-002, P-03, P-08).
 *
 * Why:  the golden file pins ten hand-chosen series; this asserts the same rules over a few hundred
 *       generated ones, which is where an off-by-one in a boundary or a sign error on a mixed series
 *       shows up. Money math, so the coverage gate is 100% rather than 85%.
 *
 *       **The refusal is the property worth generating for.** "Never report a percentage when the
 *       series starts at or below zero" is a rule about a whole class of inputs, and the negative
 *       half of that class is not exotic — it is every user with a home loan and a young portfolio.
 *       A hand-written case can be right by accident; a generated sweep across the sign boundary
 *       cannot.
 * What: the change identity, the refusal rule, the extremes' bounds, and determinism.
 * Result: proof the arithmetic holds off the ten paths the fixture happens to walk.
 * Changelog: 2026-08-29 — Created for issue 6.6.
 *
 * Seeded [Random] (P-08): a failure names the seed and reproduces exactly.
 */
class NetWorthTrendPropertyTest {
    private val engine = NetWorthEngineFactory.create()

    /**
     * Input:  generated series spanning negative, zero and positive net worth.
     * Output: asserts `change == last − first` wherever a change is reported at all.
     */
    @Test
    fun `the change is always the difference between the endpoints`() {
        forEachSeries { seed, points ->
            val trend = trendOf(points)
            if (points.size < NetWorthTrend.MIN_POINTS_FOR_CHANGE) return@forEachSeries

            val expected = points.last().netWorth.minor - points.first().netWorth.minor
            assertWithMessage("seed %s — change must be last minus first", seed)
                .that(trend.change?.minor).isEqualTo(expected)
        }
    }

    /**
     * Input:  the same generated series.
     * Output: asserts a percentage appears **only** when the starting value is strictly positive.
     *
     * Both directions are asserted. Reporting one that should be absent is the harm (P-03); omitting
     * one that should be present would quietly hollow the feature out, and a rule tested in one
     * direction only is half a rule.
     */
    @Test
    fun `a percentage is reported exactly when the series starts above zero`() {
        forEachSeries { seed, points ->
            val trend = trendOf(points)
            val start = points.firstOrNull()?.netWorth?.minor
            val sound = start != null && start > 0L && points.size >= NetWorthTrend.MIN_POINTS_FOR_CHANGE

            assertWithMessage(
                "seed %s — starts at %s, so a percentage is %s",
                seed,
                start,
                if (sound) "sound" else "a lie",
            ).that(trend.changeBps != null).isEqualTo(sound)
        }
    }

    /**
     * Input:  the same generated series.
     * Output: asserts the extremes bound every point, and that they are points that actually occur.
     */
    @Test
    fun `the high and low bound every reading and are readings themselves`() {
        forEachSeries { seed, points ->
            val trend = trendOf(points)
            if (points.isEmpty()) return@forEachSeries
            val high = checkNotNull(trend.high)
            val low = checkNotNull(trend.low)

            assertWithMessage("seed %s — a value above the high", seed)
                .that(points.none { it.netWorth.minor > high.netWorth.minor }).isTrue()
            assertWithMessage("seed %s — a value below the low", seed)
                .that(points.none { it.netWorth.minor < low.netWorth.minor }).isTrue()
            assertWithMessage("seed %s — the high must be a day that happened", seed)
                .that(points).contains(high)
            assertWithMessage("seed %s — the low must be a day that happened", seed)
                .that(points).contains(low)
        }
    }

    /**
     * Input:  the same series measured twice.
     * Output: asserts a fixed input gives a fixed output (P-08) — including the tie-breaking on
     *         extremes, which is the part an unstable sort would silently change.
     */
    @Test
    fun `the same series measures the same way twice`() {
        forEachSeries { seed, points ->
            assertWithMessage("seed %s — not deterministic", seed)
                .that(trendOf(points)).isEqualTo(trendOf(points))
        }
    }

    /**
     * Runs [check] over a spread of generated series.
     * Why:    one generator, so every property sees the same distribution — including the empty and
     *         single-point series, which are the two the summary figures are absent for.
     * Result: [check] is called once per series with its seed. Input: [check]. Output: none.
     * Changelog: 2026-08-29 — Created for issue 6.6.
     */
    private fun forEachSeries(check: (Int, List<NetWorthPoint>) -> Unit) {
        for (seed in 0 until SERIES_COUNT) {
            val random = Random(seed)
            val size = random.nextInt(0, MAX_POINTS)
            val points =
                (0 until size).map { day ->
                    NetWorthPoint(
                        // Dates only have to be distinct and ordered; the arithmetic never parses them.
                        asOfIsoDate = "2026-%02d-%02d".format(1 + day / DAYS_PER_MONTH, 1 + day % DAYS_PER_MONTH),
                        // Spans the sign boundary deliberately, so roughly a third of series start
                        // at or below zero and exercise the refusal.
                        netWorth = Money(random.nextLong(-SPREAD, SPREAD)),
                    )
                }
            check(seed, points)
        }
    }

    /** Result: the trend, unwrapped. Input: [points]. Output: [NetWorthTrend]. */
    private fun trendOf(points: List<NetWorthPoint>): NetWorthTrend =
        (
            engine.trend(
                NetWorthTrendInput(points = points, range = NetWorthRange.ALL, nowUtcMillis = FIXED_MILLIS),
            ) as Ok
        ).value

    private companion object {
        const val SERIES_COUNT = 300
        const val MAX_POINTS = 40
        const val DAYS_PER_MONTH = 28

        /** ±₹50,00,000 in paise — large enough to cross zero often, small enough never to overflow. */
        const val SPREAD = 500_000_000L

        const val FIXED_MILLIS = 1_785_542_400_000L
    }
}
