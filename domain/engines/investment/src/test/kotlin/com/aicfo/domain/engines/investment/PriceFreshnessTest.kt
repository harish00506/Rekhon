package com.aicfo.domain.engines.investment

import com.aicfo.core.common.Ok
import com.aicfo.core.model.AssetClass
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pins the freshness boundaries and the shape of what they return (issue 6.5; §16.1, P-02, P-03).
 *
 * Why:  every failure this guards is silent. A price labelled stale one day early accuses a number
 *       that was fine; one labelled fresh one day late tells the user something is current when it
 *       is not; and a refresh interval read as staleness would mark crypto permanently stale, since
 *       its cadence is fifteen minutes and its threshold is a day. None of those crash, and none
 *       looks wrong in a screenshot.
 * What: both boundaries from either side, per class, the two clocks kept apart, the never-priced
 *       case, and the clamp on a future date.
 * Result: the contract the holdings screen renders.
 * Changelog: 2026-08-29 — Created for issue 6.5.
 *
 * **Every threshold is read from [PriceFreshnessRules], never written as a literal**, so moving a
 * rulebook number moves these tests with it — the point of injecting the rules at all.
 */
class PriceFreshnessTest {
    private val engine = InvestmentEngineFactory.create()
    private val rules = PriceFreshnessRules()

    // --- the staleness boundary, in days --------------------------------------------------------

    @Test
    fun `a price exactly on its class's threshold is still fresh`() {
        val threshold = rules.staleAfterDaysFor(AssetClass.GOLD)

        val subject = freshness(AssetClass.GOLD, agedDays = threshold)

        assertWithMessage("the rule says older *than* three days, so three days is inside it")
            .that(subject.verdict).isEqualTo(PriceVerdict.FRESH)
        assertThat(subject.ageDays).isEqualTo(threshold)
    }

    @Test
    fun `a price one day past its threshold is stale`() {
        val threshold = rules.staleAfterDaysFor(AssetClass.GOLD)

        val subject = freshness(AssetClass.GOLD, agedDays = threshold + 1)

        assertThat(subject.verdict).isEqualTo(PriceVerdict.STALE)
        assertThat(subject.ageDays).isEqualTo(threshold + 1)
    }

    @Test
    fun `crypto goes stale sooner than gold, because it moves faster`() {
        val cryptoThreshold = rules.staleAfterDaysFor(AssetClass.CRYPTO)
        assertWithMessage("this test means nothing unless the rulebook bounds crypto tighter")
            .that(cryptoThreshold).isLessThan(rules.staleAfterDaysFor(AssetClass.GOLD))

        // An age legal for gold and not for crypto — the one place the two must not be swapped.
        val age = cryptoThreshold + 1

        assertThat(freshness(AssetClass.CRYPTO, agedDays = age).verdict).isEqualTo(PriceVerdict.STALE)
        assertThat(freshness(AssetClass.GOLD, agedDays = age).verdict).isEqualTo(PriceVerdict.FRESH)
    }

    @Test
    fun `a class the rulebook names no threshold for falls to the default`() {
        val fallback = rules.defaultStaleAfterDays

        assertThat(freshness(AssetClass.EQUITY, agedDays = fallback).verdict).isEqualTo(PriceVerdict.FRESH)
        assertThat(freshness(AssetClass.EQUITY, agedDays = fallback + 1).verdict).isEqualTo(PriceVerdict.STALE)
    }

    // --- absence and nonsense -------------------------------------------------------------------

    @Test
    fun `a holding that was never priced reports that, not an age of zero`() {
        val subject =
            (
                engine.priceFreshness(
                    PriceFreshnessInput(
                        assetClass = AssetClass.GOLD,
                        pricedOnIsoDate = null,
                        fetchedAtUtcMillis = null,
                        todayIsoDate = "2026-08-29",
                        nowUtcMillis = 1L,
                    ),
                ) as Ok
            ).value

        assertThat(subject.verdict).isEqualTo(PriceVerdict.NEVER_PRICED)
        assertWithMessage("absent is never zero (P-03)").that(subject.ageDays).isNull()
        assertThat(subject.pricedOnIsoDate).isNull()
    }

    @Test
    fun `a price dated in the future is clamped to zero, never a negative age`() {
        // Clock skew, or a user typing tomorrow into the editor. "-1 days old" is not a thing to
        // put in front of anyone.
        val subject = freshness(AssetClass.GOLD, agedDays = -5)

        assertThat(subject.ageDays).isEqualTo(0)
        assertThat(subject.verdict).isEqualTo(PriceVerdict.FRESH)
    }

    // --- the other clock: refresh due, in minutes -----------------------------------------------

    @Test
    fun `a price never fetched is always due, so a new key gets its first quote immediately`() {
        assertThat(freshness(AssetClass.CRYPTO, agedDays = 0, fetchedAtUtcMillis = null).refreshDue)
            .isTrue()
    }

    @Test
    fun `a fetch inside the interval is not due, and one past it is`() {
        val minutes = rules.refreshMinutesFor(AssetClass.CRYPTO)
        val now = 10_000_000_000L

        val justFetched = freshness(AssetClass.CRYPTO, 0, fetchedAtUtcMillis = now, now = now)
        val stale = freshness(AssetClass.CRYPTO, 0, fetchedAtUtcMillis = now - minutes * 60_000L, now = now)

        assertThat(justFetched.refreshDue).isFalse()
        assertThat(stale.refreshDue).isTrue()
    }

    @Test
    fun `the two clocks are independent — fresh to read can still be due to fetch`() {
        val now = 10_000_000_000L
        // Priced today, so nothing to warn about; fetched two days ago, so worth asking again.
        val subject = freshness(AssetClass.CRYPTO, agedDays = 0, fetchedAtUtcMillis = now - 172_800_000L, now = now)

        assertWithMessage("a price can be current and still be worth re-fetching")
            .that(subject.verdict).isEqualTo(PriceVerdict.FRESH)
        assertThat(subject.refreshDue).isTrue()
    }

    // --- provenance and citation (P-02, AI-ARC-003) ---------------------------------------------

    @Test
    fun `the verdict cites the one rule that decided it`() {
        val subject = freshness(AssetClass.GOLD, agedDays = 0)

        assertThat(subject.citation).isEqualTo(InvestmentRules.PRICE_STALE)
        assertThat(subject.provenance.evidence).containsExactly(InvestmentRules.PRICE_STALE)
        assertWithMessage("the allocation citations must not have grown a staleness rule")
            .that(InvestmentRules.CITATIONS).doesNotContain(InvestmentRules.PRICE_STALE)
    }

    @Test
    fun `the engine reads the instant it is given and never a clock`() {
        assertThat(freshness(AssetClass.GOLD, 0, now = 42L).provenance.computedAtUtcMillis).isEqualTo(42L)
    }

    // --- the rules type's own guards ------------------------------------------------------------

    @Test
    fun `a non-positive refresh interval or threshold is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            PriceFreshnessRules(defaultRefreshMinutes = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PriceFreshnessRules(staleAfterDays = mapOf(AssetClass.GOLD to 0))
        }
    }

    // --- helper ---------------------------------------------------------------------------------

    /**
     * Result: the freshness of a price observed [agedDays] before a fixed today.
     * Input:  [assetClass]; [agedDays] — negative dates the price in the future; [fetchedAtUtcMillis];
     *         [now]. Output: [PriceFreshness].
     */
    private fun freshness(
        assetClass: AssetClass,
        agedDays: Int,
        fetchedAtUtcMillis: Long? = null,
        now: Long = 1L,
    ): PriceFreshness {
        val today = java.time.LocalDate.parse(TODAY)
        return (
            engine.priceFreshness(
                PriceFreshnessInput(
                    assetClass = assetClass,
                    pricedOnIsoDate = today.minusDays(agedDays.toLong()).toString(),
                    fetchedAtUtcMillis = fetchedAtUtcMillis,
                    todayIsoDate = TODAY,
                    nowUtcMillis = now,
                ),
            ) as Ok
        ).value
    }

    private companion object {
        /** A fixed today, so every case is reproducible for ever (P-08). */
        const val TODAY = "2026-08-29"
    }
}
