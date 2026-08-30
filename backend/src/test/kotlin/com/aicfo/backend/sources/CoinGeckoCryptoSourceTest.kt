package com.aicfo.backend.sources

import com.aicfo.backend.FIXTURE_AS_OF
import com.aicfo.backend.fixture
import com.aicfo.backend.frozenClock
import com.aicfo.backend.ok
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

/**
 * [CoinGeckoCryptoSource] against a recorded payload (issue 6.7; §16.1, MNY-001, TIM-001).
 *
 * Why:  this is one of the two namespaces issue 6.5 actually consumes, and the parser is where a
 *       `Double` would get in. Driving it over MockWebServer rather than a stubbed parser also
 *       asserts the **request** — which coins were asked for is the payload leaving this service,
 *       and an over-broad one is a bandwidth and rate-limit problem nobody would otherwise see.
 * What: the happy path, the request, and every way a payload can disappoint.
 * Result: a change to the parse, the request, or the date resolution goes red.
 * Changelog: 2026-08-30 — Created for issue 6.7.
 */
class CoinGeckoCryptoSourceTest {
    private lateinit var server: MockWebServer

    private val btc = PriceKey("crypto:btc.inr")
    private val eth = PriceKey("crypto:eth.inr")
    private val usdt = PriceKey("crypto:usdt.inr")

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
    fun `a coin price arrives as exact integer paise`() =
        runTest {
            server.enqueue(ok(fixture("coingecko-simple-price.json")))

            val quotes = source().quote(setOf(btc, eth)).associateBy { it.priceKey }

            assertWithMessage("MNY-001: 7500000.0 rupees is 750000000 paise, exactly")
                .that(quotes.getValue(btc.value).unitPriceMinor).isEqualTo(750_000_000L)
            assertThat(quotes.getValue(eth.value).unitPriceMinor).isEqualTo(28_543_275L)
        }

    @Test
    fun `the day a quote belongs to is resolved in the Indian market's zone`() =
        runTest {
            // The fixture's timestamp falls on the 17th in UTC and the 18th in Asia/Kolkata. A source
            // that used the host's zone, or UTC, would date this a day early and the client would show
            // it as a day staler than it is (TIM-001).
            server.enqueue(ok(fixture("coingecko-simple-price.json")))

            assertThat(source().quote(setOf(btc)).single().asOfIsoDate).isEqualTo(FIXTURE_AS_OF)
        }

    @Test
    fun `only the coins asked for are named to the vendor, and in a stable order`() =
        runTest {
            server.enqueue(ok(fixture("coingecko-simple-price.json")))

            source().quote(setOf(eth, btc))

            val request = requireNotNull(server.takeRequest().requestUrl)
            assertThat(request.encodedPath).isEqualTo("/api/v3/simple/price")
            assertThat(request.queryParameter("ids")).isEqualTo("bitcoin,ethereum")
            assertThat(request.queryParameter("vs_currencies")).isEqualTo("inr")
        }

    @Test
    fun `a coin the vendor prices at zero is not a quote`() =
        runTest {
            // The client drops a non-positive price on arrival, so emitting one is the same as emitting
            // nothing — except that it costs a row in the response and hides the vendor's problem.
            server.enqueue(ok(fixture("coingecko-simple-price.json")))

            assertThat(source().quote(setOf(usdt))).isEmpty()
        }

    @Test
    fun `a symbol this source does not map is never asked about`() =
        runTest {
            val quotes = source().quote(setOf(PriceKey("crypto:notacoin.inr")))

            assertThat(quotes).isEmpty()
            assertWithMessage("no upstream call should have been made at all")
                .that(server.requestCount).isEqualTo(0)
        }

    @Test
    fun `a key asking for a currency other than the rupee is not this source's business`() =
        runTest {
            val quotes = source().quote(setOf(PriceKey("crypto:btc.usd")))

            assertThat(quotes).isEmpty()
            assertThat(server.requestCount).isEqualTo(0)
        }

    @Test
    fun `a broken upstream is no quote rather than an exception`() =
        runTest {
            // A vendor being down is normal, and the client handles it by keeping its cached price.
            server.enqueue(serverError())

            assertThat(source().quote(setOf(btc))).isEmpty()
        }

    @Test
    fun `a payload that is not the shape we expect is no quote`() =
        runTest {
            server.enqueue(ok("""{"bitcoin":"suddenly a string"}"""))

            assertThat(source().quote(setOf(btc))).isEmpty()
        }

    @Test
    fun `a payload that is not JSON at all is no quote`() =
        runTest {
            server.enqueue(ok("<html>rate limited</html>"))

            assertThat(source().quote(setOf(btc))).isEmpty()
        }

    @Test
    fun `a price with no timestamp is dated the day we asked`() =
        runTest {
            // Claiming a date the vendor did not give would be worse; the client ages the value from
            // whatever this says, and "today" is at least true about when it was observed.
            server.enqueue(ok("""{"bitcoin":{"inr":7500000.00}}"""))

            assertThat(source().quote(setOf(btc)).single().asOfIsoDate).isEqualTo("2026-08-30")
        }

    private fun source() = CoinGeckoCryptoSource(testFetch(), frozenClock(), server.url("/").toString().trimEnd('/'))
}
