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
 * [GoldApiSource] against a recorded payload (issue 6.7; §16.1, FR-INV-004, MNY-001).
 *
 * Why:  `gold:inr.gram.24k` is the key the app's own holdings and tests use, so this is the source
 *       that closes issue 6.5's first acceptance criterion for gold. It is also the only keyed
 *       upstream, which makes "the token is actually sent" a thing worth asserting rather than
 *       assuming.
 * What: both purities, the auth header, and the failure paths.
 * Result: a change to the parse, the field mapping or the header goes red.
 * Changelog: 2026-08-30 — Created for issue 6.7.
 */
class GoldApiSourceTest {
    private lateinit var server: MockWebServer

    private val gold24 = PriceKey("gold:inr.gram.24k")
    private val gold22 = PriceKey("gold:inr.gram.22k")

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
    fun `a gram of gold arrives as exact integer paise`() =
        runTest {
            server.enqueue(ok(fixture("goldapi-xau-inr.json")))

            val quote = source().quote(setOf(gold24)).single()

            assertWithMessage("MNY-001: 7890.12 rupees is 789012 paise, exactly")
                .that(quote.unitPriceMinor).isEqualTo(789_012L)
            assertThat(quote.priceKey).isEqualTo(gold24.value)
            assertThat(quote.asOfIsoDate).isEqualTo(FIXTURE_AS_OF)
        }

    @Test
    fun `each purity reads the vendor's own field rather than a derived ratio`() =
        runTest {
            // 22/24 of the 24k price would be 7232.61 only by coincidence. The metal-content ratio is
            // right about metal and wrong about what a jeweller quotes, and a valuation that is subtly
            // wrong is worse than one that is absent.
            server.enqueue(ok(fixture("goldapi-xau-inr.json")))

            val quotes = source().quote(setOf(gold24, gold22)).associateBy { it.priceKey }

            assertThat(quotes.getValue(gold24.value).unitPriceMinor).isEqualTo(789_012L)
            assertThat(quotes.getValue(gold22.value).unitPriceMinor).isEqualTo(723_261L)
        }

    @Test
    fun `the provider's token is sent, and the path asks for gold in rupees`() =
        runTest {
            server.enqueue(ok(fixture("goldapi-xau-inr.json")))

            source().quote(setOf(gold24))

            val request = server.takeRequest()
            assertThat(request.path).isEqualTo("/api/XAU/INR")
            assertThat(request.getHeader("x-access-token")).isEqualTo("test-token")
        }

    @Test
    fun `a purity this provider does not publish is never asked about`() =
        runTest {
            val quotes = source().quote(setOf(PriceKey("gold:inr.gram.18k")))

            assertThat(quotes).isEmpty()
            assertWithMessage("no upstream call should have been made at all")
                .that(server.requestCount).isEqualTo(0)
        }

    @Test
    fun `a broken upstream is no quote rather than an exception`() =
        runTest {
            server.enqueue(serverError())

            assertThat(source().quote(setOf(gold24))).isEmpty()
        }

    @Test
    fun `a payload missing the field we read is no quote`() =
        runTest {
            server.enqueue(ok("""{"timestamp":1787000000,"price":245400.5}"""))

            assertThat(source().quote(setOf(gold24))).isEmpty()
        }

    @Test
    fun `a price with no timestamp is dated the day we asked`() =
        runTest {
            server.enqueue(ok("""{"price_gram_24k":7890.12}"""))

            assertThat(source().quote(setOf(gold24)).single().asOfIsoDate).isEqualTo("2026-08-30")
        }

    private fun source() =
        GoldApiSource(
            fetch = testFetch(),
            apiKey = "test-token",
            clock = frozenClock(),
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
}
