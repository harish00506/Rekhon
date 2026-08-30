package com.aicfo.backend

import com.aicfo.core.model.PriceKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [PriceCatalogue] — routing, and the two guarantees a source cannot hold on its own (issue 6.7).
 *
 * Why:  the response-widening guard and the dead-vendor isolation are both invisible from the app —
 *       the client drops an unasked-for quote itself, and a missing price looks like a missing price
 *       whatever caused it. They are only observable here, so they are only testable here.
 * What: routing by namespace, the intersection with what was asked, non-positive filtering,
 *       de-duplication, and a throwing source.
 * Result: a source that starts answering for keys it was not asked about, or a failure that takes
 *         the batch down with it, goes red.
 * Changelog: 2026-08-30 — Created for issue 6.7.
 */
class PriceCatalogueTest {
    private val gold = PriceKey("gold:inr.gram.24k")
    private val btc = PriceKey("crypto:btc.inr")
    private val eth = PriceKey("crypto:eth.inr")

    @Test
    fun `each namespace is asked only for its own keys`() =
        runTest {
            val goldSource = RecordingSource("gold", quote(gold, 789_012))
            val cryptoSource = RecordingSource("crypto", quote(btc, 750_000_000))

            val quotes = PriceCatalogue(listOf(goldSource, cryptoSource)).quote(setOf(gold, btc))

            assertThat(goldSource.asked).containsExactly(gold)
            assertThat(cryptoSource.asked).containsExactly(btc)
            assertThat(quotes.map { it.priceKey }).containsExactly(gold.value, btc.value)
        }

    @Test
    fun `a source cannot widen the response beyond what was asked`() =
        runTest {
            // The client drops an unasked-for quote on arrival, so a widening bug would be invisible
            // there. This is the only place it shows.
            val rogue = RecordingSource("crypto", quote(btc, 1), quote(eth, 1))

            val quotes = PriceCatalogue(listOf(rogue)).quote(setOf(btc))

            assertThat(quotes.map { it.priceKey }).containsExactly(btc.value)
        }

    @Test
    fun `a dead vendor costs its own namespace and nothing else`() =
        runTest {
            val catalogue =
                PriceCatalogue(
                    listOf(ThrowingSource("gold"), RecordingSource("crypto", quote(btc, 750_000_000))),
                )

            val quotes = catalogue.quote(setOf(gold, btc))

            assertThat(quotes.map { it.priceKey }).containsExactly(btc.value)
        }

    @Test
    fun `a non-positive price is not a quote`() =
        runTest {
            val catalogue = PriceCatalogue(listOf(RecordingSource("crypto", quote(btc, 0), quote(eth, -1))))

            assertThat(catalogue.quote(setOf(btc, eth))).isEmpty()
        }

    @Test
    fun `a key answered twice appears once`() =
        runTest {
            val catalogue = PriceCatalogue(listOf(RecordingSource("crypto", quote(btc, 1), quote(btc, 2))))

            val quotes = catalogue.quote(setOf(btc))

            assertThat(quotes).hasSize(1)
            assertThat(quotes.single().unitPriceMinor).isEqualTo(1L)
        }

    @Test
    fun `a namespace with no source is not an error`() =
        runTest {
            // This is how an unconfigured vendor behaves — the same supported state the client ships in
            // (ADR-0030). It must not be a 502.
            val catalogue = PriceCatalogue(listOf(RecordingSource("crypto", quote(btc, 1))))

            val quotes = catalogue.quote(setOf(gold, btc))

            assertThat(quotes.map { it.priceKey }).containsExactly(btc.value)
        }

    @Test
    fun `an empty request asks no vendor anything`() =
        runTest {
            val source = RecordingSource("crypto")

            assertThat(PriceCatalogue(listOf(source)).quote(emptySet())).isEmpty()
            assertThat(source.asked).isEmpty()
        }

    @Test
    fun `a key with no namespace matches nothing`() =
        runTest {
            val bare = PriceKey("btc")
            val source = RecordingSource("crypto", quote(btc, 1))

            assertThat(PriceCatalogue(listOf(source)).quote(setOf(bare))).isEmpty()
            assertThat(source.asked).isEmpty()
        }

    @Test
    fun `no sources at all yields no quotes`() =
        runTest {
            assertThat(PriceCatalogue(emptyList()).quote(setOf(gold))).isEmpty()
        }

    private fun quote(
        key: PriceKey,
        minor: Long,
    ) = PriceQuote(key.value, minor, "2026-08-30")

    /** Records what it was asked, so routing is asserted on the request and not only the response. */
    private class RecordingSource(
        override val namespace: String,
        private vararg val answers: PriceQuote,
    ) : PriceSource {
        val asked = mutableListOf<PriceKey>()

        override suspend fun quote(keys: Set<PriceKey>): List<PriceQuote> {
            asked += keys
            return answers.toList()
        }
    }

    /** Stands in for a vendor that is down, rate-limiting, or has changed its payload. */
    private class ThrowingSource(override val namespace: String) : PriceSource {
        override suspend fun quote(keys: Set<PriceKey>): List<PriceQuote> = error("upstream is down")
    }
}
