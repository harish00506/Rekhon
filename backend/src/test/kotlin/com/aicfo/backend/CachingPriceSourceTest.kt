package com.aicfo.backend

import com.aicfo.core.model.PriceKey
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Duration

/**
 * [CachingPriceSource] — how often this service asks a vendor anything (issue 6.7; §16.1, API-002).
 *
 * Why:  the app's own TTL gate limits how often **one device** asks. Nothing limits how often **this
 *       service** asks, and on a free vendor tier that is the difference between working and being
 *       rate-limited by lunchtime. The behaviour worth pinning down is not "it caches" but *what it
 *       asks for on a partial hit* — getting that wrong turns every request into a full fetch and
 *       the cache into decoration.
 * What: hits, misses, partial hits, and expiry over an injected clock.
 * Result: a change that widens the upstream request or loses the expiry goes red.
 * Changelog: 2026-08-30 — Created for issue 6.7.
 */
class CachingPriceSourceTest {
    private val btc = PriceKey("crypto:btc.inr")
    private val eth = PriceKey("crypto:eth.inr")
    private val clock = TestClock()

    @Test
    fun `the namespace is the delegate's, so routing is unaffected by caching`() {
        assertThat(cache(RecordingSource("crypto")).namespace).isEqualTo("crypto")
    }

    @Test
    fun `a second ask inside the window opens no socket`() =
        runTest {
            val delegate = RecordingSource("crypto", quote(btc, 750_000_000))
            val source = cache(delegate)

            source.quote(setOf(btc))
            val second = source.quote(setOf(btc))

            assertThat(delegate.calls).isEqualTo(1)
            assertThat(second.single().unitPriceMinor).isEqualTo(750_000_000L)
        }

    @Test
    fun `once the window passes the vendor is asked again`() =
        runTest {
            val delegate = RecordingSource("crypto", quote(btc, 750_000_000))
            val source = cache(delegate)

            source.quote(setOf(btc))
            clock.advance(Duration.ofMinutes(16))
            source.quote(setOf(btc))

            assertThat(delegate.calls).isEqualTo(2)
        }

    @Test
    fun `the window is exclusive at its boundary`() =
        runTest {
            // Exactly at the TTL the entry is due, not fresh. Erring the other way would serve a quote
            // one tick older than the rulebook's refresh window on every single boundary.
            val delegate = RecordingSource("crypto", quote(btc, 1))
            val source = cache(delegate)

            source.quote(setOf(btc))
            clock.advance(Duration.ofMinutes(15))
            source.quote(setOf(btc))

            assertThat(delegate.calls).isEqualTo(2)
        }

    @Test
    fun `a partial hit asks the vendor only for what is missing`() =
        runTest {
            // The common case: a device holding two coins asks for both, and one of them was fetched for
            // somebody else a minute ago. Asking for both anyway would make the cache worthless.
            val delegate = RecordingSource("crypto", quote(btc, 1), quote(eth, 2))
            val source = cache(delegate)

            source.quote(setOf(btc))
            val merged = source.quote(setOf(btc, eth))

            assertWithMessage("the second call must name eth alone")
                .that(delegate.asked.last()).containsExactly(eth)
            assertThat(merged.map { it.priceKey }).containsExactly(btc.value, eth.value)
        }

    @Test
    fun `a vendor that answers nothing caches nothing`() =
        runTest {
            // Caching an absence would turn one failed fetch into fifteen minutes of guaranteed silence.
            val delegate = RecordingSource("crypto")
            val source = cache(delegate)

            source.quote(setOf(btc))
            source.quote(setOf(btc))

            assertThat(delegate.calls).isEqualTo(2)
        }

    private fun cache(delegate: PriceSource) = CachingPriceSource(delegate, Duration.ofMinutes(15), clock)

    private fun quote(
        key: PriceKey,
        minor: Long,
    ) = PriceQuote(key.value, minor, "2026-08-30")

    /** Counts calls and records what each one asked for. */
    private class RecordingSource(
        override val namespace: String,
        private vararg val answers: PriceQuote,
    ) : PriceSource {
        var calls = 0
        val asked = mutableListOf<Set<PriceKey>>()

        override suspend fun quote(keys: Set<PriceKey>): List<PriceQuote> {
            calls++
            asked += keys
            return answers.filter { answer -> keys.any { it.value == answer.priceKey } }
        }
    }
}
