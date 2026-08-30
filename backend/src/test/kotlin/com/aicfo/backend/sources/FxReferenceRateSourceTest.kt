package com.aicfo.backend.sources

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
 * [FxReferenceRateSource] against a recorded payload (issue 6.7; §16, MNY-001, MNY-002).
 *
 * Why:  a rate looks like a number that could go anywhere, and this endpoint's only money field is
 *       paise. The tests below pin down what this source will and will not put in it — a price per
 *       unit of currency, yes; a percentage, never.
 * What: the parse, the request, the currency guard and the failure paths.
 * Result: a change to any of them goes red.
 * Changelog: 2026-08-30 — Created for issue 6.7.
 */
class FxReferenceRateSourceTest {
    private lateinit var server: MockWebServer

    private val usd = PriceKey("fx:usd.inr")

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
    fun `a rate arrives as exact paise per unit of the foreign currency`() =
        runTest {
            server.enqueue(ok(fixture("frankfurter-usd-inr.json")))

            val quote = source().quote(setOf(usd)).single()

            assertWithMessage("MNY-001: 88.5 rupees to the dollar is 8850 paise, exactly")
                .that(quote.unitPriceMinor).isEqualTo(8_850L)
            assertThat(quote.priceKey).isEqualTo(usd.value)
        }

    @Test
    fun `the vendor's own date is used, not the day we asked`() =
        runTest {
            server.enqueue(ok(fixture("frankfurter-usd-inr.json")))

            assertThat(source().quote(setOf(usd)).single().asOfIsoDate).isEqualTo("2026-08-30")
        }

    @Test
    fun `the request names the base currency and asks for rupees`() =
        runTest {
            server.enqueue(ok(fixture("frankfurter-usd-inr.json")))

            source().quote(setOf(usd))

            val request = requireNotNull(server.takeRequest().requestUrl)
            assertThat(request.encodedPath).isEqualTo("/latest")
            assertThat(request.queryParameter("base")).isEqualTo("USD")
            assertThat(request.queryParameter("symbols")).isEqualTo("INR")
        }

    @Test
    fun `a key quoting anything but the rupee is not this source's business`() =
        runTest {
            // Every price this service serves is in paise, so every rate has to be against INR.
            val quotes = source().quote(setOf(PriceKey("fx:usd.eur")))

            assertThat(quotes).isEmpty()
            assertThat(server.requestCount).isEqualTo(0)
        }

    @Test
    fun `one dead currency costs its own quote and no other`() =
        runTest {
            // The two calls go out concurrently and are answered in whatever order the server pleases;
            // asserting on the set rather than a sequence is what makes this deterministic.
            server.dispatcher =
                object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                        if (request.requestUrl?.queryParameter("base") == "USD") {
                            ok(fixture("frankfurter-usd-inr.json"))
                        } else {
                            serverError()
                        }
                }

            val quotes = source().quote(setOf(usd, PriceKey("fx:eur.inr")))

            assertThat(quotes.map { it.priceKey }).containsExactly(usd.value)
        }

    @Test
    fun `a broken upstream is no quote rather than an exception`() =
        runTest {
            server.enqueue(serverError())

            assertThat(source().quote(setOf(usd))).isEmpty()
        }

    @Test
    fun `a payload without the rate we asked for is no quote`() =
        runTest {
            server.enqueue(ok("""{"base":"USD","date":"2026-08-30","rates":{}}"""))

            assertThat(source().quote(setOf(usd))).isEmpty()
        }

    @Test
    fun `a payload that is not JSON at all is no quote`() =
        runTest {
            server.enqueue(ok("service unavailable"))

            assertThat(source().quote(setOf(usd))).isEmpty()
        }

    private fun source() = FxReferenceRateSource(testFetch(), frozenClock(), server.url("/").toString().trimEnd('/'))
}
