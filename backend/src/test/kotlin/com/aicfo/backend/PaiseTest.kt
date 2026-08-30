package com.aicfo.backend

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal

/**
 * [Paise] — the only place a vendor's decimal becomes money (issue 6.7; MNY-001).
 *
 * Why:  CLAUDE.md §4 holds money math to 100% coverage, and this is the money math of the whole
 *       service. Every upstream is a chance to lose exactness, and this class is the single point
 *       where that either happens or does not.
 * What: exhaustive cases — normal, both HALF_EVEN tie directions, vendor scales, overflow, and every
 *       shape of junk a payload can carry.
 * Result: a change that rounds differently, or that starts tolerating a bad price, goes red.
 * Changelog: 2026-08-30 — Created for issue 6.7.
 */
class PaiseTest {
    @Test
    fun `whole rupees become paise`() {
        assertThat(Paise.fromRupees(BigDecimal("7890")).minor).isEqualTo(789_000L)
    }

    @Test
    fun `two decimals are exact`() {
        assertThat(Paise.fromRupees(BigDecimal("7890.12")).minor).isEqualTo(789_012L)
    }

    @Test
    fun `a four-decimal NAV rounds to paise`() {
        // AMFI publishes NAVs to four places. 123.4567 -> 123.46.
        assertThat(Paise.fromRupees(BigDecimal("123.4567")).minor).isEqualTo(12_346L)
    }

    @Test
    fun `a tie rounds to the even digit, not upward`() {
        // The whole point of HALF_EVEN: 0.005 has an even digit before it, so it goes down; 0.015
        // has an odd one, so it goes up. HALF_UP would round both up and drift a portfolio upward
        // over thousands of conversions.
        assertThat(Paise.fromRupees(BigDecimal("0.005")).minor).isEqualTo(0L)
        assertThat(Paise.fromRupees(BigDecimal("0.015")).minor).isEqualTo(2L)
    }

    @Test
    fun `a value too large for a Long throws rather than wrapping`() {
        // Wrapping would produce a negative price, the client would silently drop it as non-positive,
        // and the user would see a stale figure nobody could explain.
        assertThrows(ArithmeticException::class.java) {
            Paise.fromRupees(BigDecimal("999999999999999999999"))
        }
    }

    @Test
    fun `parsing takes the vendor's own characters`() {
        assertThat(Paise.parseRupees("7890.12")?.minor).isEqualTo(789_012L)
        assertThat(Paise.parseRupees("  123.4567  ")?.minor).isEqualTo(12_346L)
        assertThat(Paise.parseRupees("1e3")?.minor).isEqualTo(100_000L)
    }

    @Test
    fun `junk is no quote rather than an exception`() {
        // A vendor that changes its payload shape must degrade to "no price", which the client
        // handles by keeping the cached one (P-04) — not bring the batch down.
        assertThat(Paise.parseRupees(null)).isNull()
        assertThat(Paise.parseRupees("")).isNull()
        assertThat(Paise.parseRupees("   ")).isNull()
        assertThat(Paise.parseRupees("N.A.")).isNull()
        assertThat(Paise.parseRupees("₹100")).isNull()
    }

    @Test
    fun `a free or negative instrument is not a price`() {
        assertThat(Paise.parseRupees("0")).isNull()
        assertThat(Paise.parseRupees("0.00")).isNull()
        assertThat(Paise.parseRupees("-5.00")).isNull()
    }

    @Test
    fun `an unparseable magnitude is null rather than a thrown parse`() {
        assertThat(Paise.parseRupees("999999999999999999999")).isNull()
    }
}
