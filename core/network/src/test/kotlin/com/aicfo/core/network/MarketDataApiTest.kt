package com.aicfo.core.network

import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.model.Money
import com.aicfo.core.model.PriceKey
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The HTTP edge, against something that actually speaks HTTP (issue 6.5; §16, §22, P-04).
 *
 * Why:  an interface fake would exercise none of what can really break here — JSON parsing, status
 *       mapping, timeouts, and a server that is not there. Every one of those turns into the same
 *       decision for the caller ("keep the cached price"), and the way that goes wrong is an
 *       exception escaping this module and crashing a screen about somebody's gold.
 *
 *       **Every case in this file is a backend-absent case in the sense that matters**: the app
 *       ships with no proxy, so the only server that will ever exist in a test is this one. The
 *       `shutdown` case is the literal version.
 * What: a good response, every failure mode, and the three refusals that protect the caller.
 * Result: proof that nothing gets past this module except a quote or a typed error.
 * Changelog: 2026-08-29 — Created for issue 6.5.
 */
class MarketDataApiTest {
    private lateinit var server: MockWebServer

    private val gold = PriceKey("gold:inr.gram.24k")
    private val crypto = PriceKey("crypto:btc.inr")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // --- the happy path -------------------------------------------------------------------------

    @Test
    fun `a quote arrives with its paise intact and its date`() =
        runTest {
            server.enqueue(json(body(row(minor = "783412"))))

            val quotes = (api().quotes(setOf(gold)) as Ok).value

            assertThat(quotes).hasSize(1)
            assertWithMessage("MNY-001: paise, and exactly the integer the server sent")
                .that(quotes.single().unitPrice).isEqualTo(Money(783_412))
            assertThat(quotes.single().asOfIsoDate).isEqualTo("2026-08-28")
            assertThat(quotes.single().priceKey).isEqualTo(gold)
        }

    @Test
    fun `the request carries the instrument identifiers and nothing else`() =
        runTest {
            server.enqueue(json(body()))

            api().quotes(setOf(crypto, gold))

            val request = server.takeRequest()
            // EXT-003: only instrument identifiers leave the device. Sorted, so the same portfolio
            // always produces the same URL — cacheable, reproducible in a bug report, and free of
            // anything set-iteration order might otherwise imply.
            assertThat(request.path).isEqualTo("/v1/market/prices?ids=crypto%3Abtc.inr%2Cgold%3Ainr.gram.24k")
            assertWithMessage("a price request has no body to hide anything in").that(request.bodySize).isEqualTo(0)
        }

    @Test
    fun `an empty key set never reaches the network`() =
        runTest {
            val quotes = (api().quotes(emptySet()) as Ok).value

            assertThat(quotes).isEmpty()
            assertWithMessage("asking about nothing still tells a server this device is awake")
                .that(server.requestCount).isEqualTo(0)
        }

    // --- failures, all of which mean "keep the cached price" (P-04) ------------------------------

    @Test
    fun `a server error is a retryable-free network error, not an exception`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(500))

            val outcome = api().quotes(setOf(gold))

            assertThat(outcome).isInstanceOf(Err::class.java)
            assertWithMessage("repeating an identical request a server just refused will be refused again")
                .that(((outcome as Err).error as com.aicfo.core.common.AppError.Network).retryable).isFalse()
        }

    @Test
    fun `malformed JSON does not escape as a serialization exception`() =
        runTest {
            server.enqueue(json("""{"quotes":[{"price_key":}]}"""))

            // The assertion is that this returns at all. A SerializationException escaping here
            // would crash a screen about somebody's gold because a server sent a bad byte.
            assertThat(api().quotes(setOf(gold))).isInstanceOf(Err::class.java)
        }

    @Test
    fun `a slow server times out and says it is worth trying again`() =
        runTest {
            server.enqueue(json(body()).setBodyDelay(2, TimeUnit.SECONDS))

            val outcome = api(timeoutSeconds = 1).quotes(setOf(gold))

            val error = (outcome as Err).error as com.aicfo.core.common.AppError.Network
            assertWithMessage("a timeout is transient — the host may simply be busy").that(error.retryable).isTrue()
        }

    @Test
    fun `a backend that is not there fails without taking anything down`() =
        runTest {
            val api = api()
            // The literal backend-absent case, and the closest this suite can get to the app's real
            // situation: the §22 proxy does not exist, so there is nothing to disconnect from.
            server.shutdown()

            val outcome = api.quotes(setOf(gold))

            assertThat(((outcome as Err).error as com.aicfo.core.common.AppError.Network).retryable).isTrue()
        }

    // --- the three refusals that protect the caller ---------------------------------------------

    @Test
    fun `a quote for something nobody asked about is dropped`() =
        runTest {
            server.enqueue(json(body(row(key = "crypto:btc.inr"))))

            val quotes = (api().quotes(setOf(gold)) as Ok).value

            assertWithMessage("a proxy answering questions it was not asked is confused or hostile")
                .that(quotes).isEmpty()
        }

    @Test
    fun `a non-positive price is dropped rather than written`() =
        runTest {
            server.enqueue(json(body(row(minor = "0"))))

            val result = (api().quotes(setOf(gold)) as Ok).value

            assertWithMessage(
                "InvestmentHolding refuses a non-positive price, so writing one would turn a bad " +
                    "byte into a crash on the next read",
            ).that(result).isEmpty()
        }

    @Test
    fun `a key that is not a well-formed identifier is dropped`() =
        runTest {
            server.enqueue(json(body(row(key = "GOLD OR SOMETHING"))))

            assertThat((api().quotes(setOf(gold)) as Ok).value).isEmpty()
        }

    @Test
    fun `an unknown field does not break the parse`() =
        runTest {
            // §22.1 promises additive evolution within a version; this is the client half of it.
            server.enqueue(json(body(row(extra = SOURCE_FIELD), trailing = PAGE_FIELD)))

            assertThat((api().quotes(setOf(gold)) as Ok).value).hasSize(1)
        }

    // --- configuration --------------------------------------------------------------------------

    @Test
    fun `with no base url the factory builds no client at all`() {
        assertWithMessage("an unconfigured build must not merely fail to connect — it must not try")
            .that(MarketDataFactory.create(NetworkConfig.UNCONFIGURED))
            .isSameInstanceAs(UnconfiguredMarketDataApi)
    }

    @Test
    fun `the unconfigured api refuses without claiming a retry would help`() =
        runTest {
            val outcome = MarketDataFactory.create(NetworkConfig.UNCONFIGURED).quotes(setOf(gold))

            val error = (outcome as Err).error as com.aicfo.core.common.AppError.Network
            assertWithMessage("a missing backend will not fix itself, and a worker must not back off forever")
                .that(error.retryable).isFalse()
        }

    @Test
    fun `a host cannot be configured without certificate pins`() {
        val thrown =
            assertThrows(IllegalArgumentException::class.java) {
                NetworkConfig(baseUrl = "https://api.example.com/")
            }

        assertThat(thrown).hasMessageThat().contains("certificate pins")
    }

    @Test
    fun `a cleartext host is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            NetworkConfig(baseUrl = "http://api.example.com/", pins = listOf(PIN))
        }
    }

    @Test
    fun `a configured host becomes a pinned client with API-001's timeouts`() {
        val client =
            MarketDataFactory.client(
                NetworkConfig(baseUrl = "https://api.example.com/", pins = listOf(PIN)),
            )

        // The handshake itself is unreachable without a server, but that a NetworkConfig turns into
        // a pinned, bounded client is checkable today — and it is the half that a typo would break.
        assertThat(client.certificatePinner.pins.map { pin -> pin.pattern }).containsExactly("api.example.com")
        assertThat(client.callTimeoutMillis).isEqualTo(5_000)
        assertThat(client.connectTimeoutMillis).isEqualTo(5_000)
        assertThat(client.readTimeoutMillis).isEqualTo(5_000)
        assertThat(client.writeTimeoutMillis).isEqualTo(5_000)
        assertWithMessage("a disk cache would put this user's instrument list in plaintext outside SQLCipher")
            .that(client.cache).isNull()
        assertWithMessage("§21.6: an interceptor here would log a request naming the user's holdings")
            .that(client.interceptors).isEmpty()
    }

    // --- helpers --------------------------------------------------------------------------------

    /**
     * An api pointed at the mock server, over the production converter and mapper.
     *
     * Goes through [MarketDataFactory.retrofitApi], not `create`: MockWebServer serves cleartext and
     * [NetworkConfig] refuses a cleartext host, so the two cannot meet. Everything below the
     * handshake is the shipping path.
     *
     * Result: [MarketDataApi]. Input: [timeoutSeconds] — applied to this test client.
     */
    private fun api(timeoutSeconds: Long = NetworkConfig.DEFAULT_TIMEOUT_SECONDS): MarketDataApi =
        MarketDataFactory.retrofitApi(
            baseUrl = server.url("/").toString(),
            client =
                OkHttpClient.Builder()
                    .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .build(),
        )

    /**
     * A response body, assembled rather than written out.
     * Why:    the literals are long enough to break the line limit and, more to the point, long
     *         enough that a changed digit in one of them is hard to see. Every test below varies one
     *         field of the same shape, so the shape is written once.
     * Result: the JSON the proxy would send. Input: [rows] — pre-rendered quote objects;
     *         [trailing] — extra top-level JSON, already comma-prefixed. Output: [String].
     */
    private fun body(
        vararg rows: String,
        trailing: String = "",
    ): String = """{"quotes":[${rows.joinToString(separator = ",")}]$trailing}"""

    /**
     * One quote object.
     * Result: the JSON for a single row. Input: [key], [minor], [asOf] — each a wire value, so a
     *         test can send something [PriceKey] or [Money] would refuse; [extra] — extra fields,
     *         already comma-prefixed. Output: [String].
     */
    private fun row(
        key: String = "gold:inr.gram.24k",
        minor: String = "1",
        asOf: String = "2026-08-28",
        extra: String = "",
    ): String = """{"price_key":"$key","unit_price_minor":$minor,"as_of":"$asOf"$extra}"""

    /** Result: a 200 carrying [body] as JSON. Input: [body]. Output: [MockResponse]. */
    private fun json(body: String): MockResponse =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private companion object {
        /**
         * A syntactically valid pin. It matches nothing, which does not matter: MockWebServer serves
         * cleartext, so the pinner is never consulted. What these tests prove about pinning is that
         * a configuration without pins cannot be constructed at all.
         */
        const val PIN = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

        /** A field this build has never heard of, inside a quote. */
        const val SOURCE_FIELD = ""","source":"amfi""""

        /** A field this build has never heard of, beside the quotes. */
        const val PAGE_FIELD = ""","page":1"""
    }
}
