package com.aicfo.backend.sources

import com.aicfo.backend.TestClock
import com.aicfo.backend.fixture
import com.aicfo.backend.okText
import com.aicfo.backend.serverError
import com.aicfo.backend.testFetch
import com.aicfo.core.model.PriceKey
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Duration

/**
 * [AmfiNavSource] against a recorded NAV file (issue 6.7; §16.1, EXT-001, MNY-001, TIM-002).
 *
 * Why:  the real file is eight megabytes of loosely structured text with banners, blank lines and
 *       rows that carry no NAV at all. A parser for it is mostly a list of things to ignore, and the
 *       only way to know it ignores the right ones is to feed it a sample containing each. The
 *       caching matters just as much: without it, one device asking for one fund would pull the
 *       whole country's NAVs.
 * What: the index, the lowercasing that makes an AMFI ISIN reachable as a price key, every skipped
 *       row shape, and the TTL.
 * Result: a change to the parse or the cache goes red.
 * Changelog: 2026-08-30 — Created for issue 6.7.
 */
class AmfiNavSourceTest {
    private lateinit var server: MockWebServer
    private val clock = TestClock()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a NAV arrives as exact integer paise, dated ISO`() =
        runTest {
            server.enqueue(okText(fixture("amfi-navall.txt")))

            val quote = source().quote(setOf(PriceKey("mf:inf109k01z48"))).single()

            assertWithMessage("MNY-001: 209.15 rupees is 20915 paise, exactly")
                .that(quote.unitPriceMinor).isEqualTo(20_915L)
            // AMFI serves CRLF, so this also proves the trailing carriage return is trimmed off the
            // date before it is parsed.
            assertWithMessage("TIM-002: 28-Aug-2026 is a date, written the one way")
                .that(quote.asOfIsoDate).isEqualTo("2026-08-28")
        }

    @Test
    fun `a four-decimal NAV rounds to paise the same way every other price does`() =
        runTest {
            server.enqueue(okText(fixture("amfi-navall.txt")))

            val quote = source().quote(setOf(PriceKey("mf:inf879o01019"))).single()

            assertThat(quote.unitPriceMinor).isEqualTo(8_308L)
        }

    @Test
    fun `an AMFI ISIN is reachable under the lowercase key a device can hold`() =
        runTest {
            // AMFI publishes INF879O01019; PriceKey forbids uppercase, so the key on a holding is
            // mf:inf879o01019. If the index were not lowercased the two would never meet.
            server.enqueue(okText(fixture("amfi-navall.txt")))

            val quote = source().quote(setOf(PriceKey("mf:inf879o01019"))).single()

            assertThat(quote.priceKey).isEqualTo("mf:inf879o01019")
        }

    @Test
    fun `both of a scheme's ISINs price the same NAV`() =
        runTest {
            // A scheme publishes a payout ISIN and a reinvestment ISIN; a holding may name either.
            server.enqueue(okText(fixture("amfi-navall.txt")))

            val quotes =
                source().quote(
                    setOf(PriceKey("mf:inf846k01ws2"), PriceKey("mf:inf846k01wq6")),
                )

            assertThat(quotes.map { it.unitPriceMinor }.distinct()).containsExactly(2_818L)
        }

    @Test
    fun `the NAV is read from the end of the row, not from the column the header claims`() =
        runTest {
            // AMFI's published header names six columns; the file has eight — Plan and Option were
            // inserted in the middle and the header was never updated. Indexing NAV at column 4
            // reads "Direct Plan", makes no number of it, and silently prices nothing at all. This
            // was written the wrong way first and found only by pointing it at the live file.
            server.enqueue(okText(fixture("amfi-navall.txt")))

            val quotes = source().quote(setOf(PriceKey("mf:inf109k01z48")))

            assertWithMessage("an eight-column row must still price")
                .that(quotes).hasSize(1)
        }

    @Test
    fun `banners, blank lines and the header are not funds`() =
        runTest {
            server.enqueue(okText(fixture("amfi-navall.txt")))

            val quotes =
                source().quote(
                    setOf(PriceKey("mf:scheme-code"), PriceKey("mf:parag-parikh-mutual-fund")),
                )

            assertThat(quotes).isEmpty()
        }

    @Test
    fun `a scheme with no published NAV or an unreadable date is skipped, not fatal`() =
        runTest {
            // The fixture holds one of each alongside good rows. The good rows must still price.
            server.enqueue(okText(fixture("amfi-navall.txt")))
            val source = source()

            assertThat(source.quote(setOf(PriceKey("mf:inf000bad001")))).isEmpty()
            assertThat(source.quote(setOf(PriceKey("mf:inf000bad002")))).isEmpty()
            assertThat(source.quote(setOf(PriceKey("mf:inf109k01z48")))).hasSize(1)
        }

    @Test
    fun `the file is fetched once and served from the index until it ages out`() =
        runTest {
            server.enqueue(okText(fixture("amfi-navall.txt")))
            val source = source()
            val key = setOf(PriceKey("mf:inf109k01z48"))

            source.quote(key)
            source.quote(key)
            clock.advance(Duration.ofHours(5))
            source.quote(key)

            assertWithMessage("eight megabytes, once — not once per request")
                .that(server.requestCount).isEqualTo(1)
        }

    @Test
    fun `once the index ages out the file is fetched again`() =
        runTest {
            server.enqueue(okText(fixture("amfi-navall.txt")))
            server.enqueue(okText(fixture("amfi-navall.txt")))
            val source = source()
            val key = setOf(PriceKey("mf:inf109k01z48"))

            source.quote(key)
            clock.advance(Duration.ofHours(7))
            source.quote(key)

            assertThat(server.requestCount).isEqualTo(2)
        }

    @Test
    fun `a failed refresh keeps the index it already had`() =
        runTest {
            // AMFI being briefly unreachable is not a reason for a portfolio to lose its value. The
            // client labels a stale NAV as old; it cannot label one that is not there.
            server.enqueue(okText(fixture("amfi-navall.txt")))
            server.enqueue(serverError())
            val source = source()
            val key = setOf(PriceKey("mf:inf109k01z48"))

            source.quote(key)
            clock.advance(Duration.ofHours(7))

            assertThat(source.quote(key).single().unitPriceMinor).isEqualTo(20_915L)
        }

    @Test
    fun `an upstream that has never answered prices nothing`() =
        runTest {
            server.enqueue(serverError())

            assertThat(source().quote(setOf(PriceKey("mf:inf109k01z48")))).isEmpty()
        }

    private fun source() =
        AmfiNavSource(
            fetch = testFetch(),
            clock = clock,
            ttl = Duration.ofHours(6),
            url = server.url("/spages/NAVAll.txt").toString(),
        )
}
