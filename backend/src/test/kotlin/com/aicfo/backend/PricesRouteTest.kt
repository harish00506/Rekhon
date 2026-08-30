package com.aicfo.backend

import com.aicfo.core.model.PriceKey
import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Test

/**
 * The §22 route, driven over real HTTP (issue 6.7; API-001, EXT-003).
 *
 * Why:  every acceptance criterion of 6.7 that is not about deployment is observable here — the
 *       integer-paise body, the constant error bodies that name no identifier, and the fact that an
 *       unknown instrument is an absent quote rather than a failure.
 * What: `testApplication` against the production route function, with a fake catalogue.
 * Result: a change to the wire, the status codes, or the error bodies goes red.
 * Changelog: 2026-08-30 — Created for issue 6.7.
 */
class PricesRouteTest {
    private val gold = PriceKey("gold:inr.gram.24k")
    private val btc = PriceKey("crypto:btc.inr")

    @Test
    fun `a batch comes back as integer paise with an as-of date`() =
        testApplication {
            serve(
                PriceCatalogue(
                    listOf(FixedSource("gold", gold to 789_012L), FixedSource("crypto", btc to 750_000_000L)),
                ),
            )

            val response = client.get("$PRICES_PATH?ids=crypto%3Abtc.inr%2Cgold%3Ainr.gram.24k")

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.bodyAsText()).isEqualTo(
                """{"quotes":[{"price_key":"crypto:btc.inr","unit_price_minor":750000000,"as_of":"2026-08-30"},""" +
                    """{"price_key":"gold:inr.gram.24k","unit_price_minor":789012,"as_of":"2026-08-30"}]}""",
            )
        }

    @Test
    fun `a price is never emitted as a decimal`() =
        testApplication {
            // MNY-001 on the wire. A float here would route the client's parse through a Double and
            // corrupt every derived figure silently.
            serve(PriceCatalogue(listOf(FixedSource("gold", gold to 789_012L))))

            val body = client.get("$PRICES_PATH?ids=gold%3Ainr.gram.24k").bodyAsText()

            assertThat(body).contains(""""unit_price_minor":789012""")
            assertThat(body).doesNotContain("789012.")
        }

    @Test
    fun `an unknown instrument is an absent quote, not an error`() =
        testApplication {
            serve(PriceCatalogue(listOf(FixedSource("crypto", btc to 1L))))

            val response = client.get("$PRICES_PATH?ids=gold%3Ainr.gram.24k")

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.bodyAsText()).isEqualTo("""{"quotes":[]}""")
        }

    @Test
    fun `a malformed id is dropped and the rest of the batch still prices`() =
        testApplication {
            // Failing the batch would let one bad id cost the caller every other price in it.
            serve(PriceCatalogue(listOf(FixedSource("crypto", btc to 750_000_000L))))

            val response = client.get("$PRICES_PATH?ids=MY%20GOLD%2Ccrypto%3Abtc.inr")

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.bodyAsText()).contains("crypto:btc.inr")
        }

    @Test
    fun `a request naming no ids is refused without naming anything`() =
        testApplication {
            serve(PriceCatalogue(emptyList()))

            for (query in listOf("", "?ids=", "?ids=%20%2C%20")) {
                val response = client.get("$PRICES_PATH$query")
                assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
                assertThat(response.bodyAsText()).isEqualTo("""{"error":"ids required"}""")
            }
        }

    @Test
    fun `an oversized batch is refused and the ids are not echoed back`() =
        testApplication {
            serve(PriceCatalogue(emptyList()))
            val ids = (1..101).joinToString(",") { "crypto:coin$it.inr" }

            val response = client.get("$PRICES_PATH?ids=$ids")

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.bodyAsText()).isEqualTo("""{"error":"too many ids"}""")
            assertThat(response.bodyAsText()).doesNotContain("coin1")
        }

    @Test
    fun `an upstream failure is a generic 502 that names no instrument`() =
        testApplication {
            // EXT-003's testable half on this side of the wire: what the device asked about must not come
            // back out of this service in a body.
            application { marketPrices { error("boom") } }

            val response = client.get("$PRICES_PATH?ids=gold%3Ainr.gram.24k")

            assertThat(response.status).isEqualTo(HttpStatusCode.BadGateway)
            assertThat(response.bodyAsText()).isEqualTo("""{"error":"upstream unavailable"}""")
            assertThat(response.bodyAsText()).doesNotContain("gold")
        }

    @Test
    fun `a successful response tells the caller how long it may be reused`() =
        testApplication {
            serve(PriceCatalogue(listOf(FixedSource("gold", gold to 789_012L))))

            val response = client.get("$PRICES_PATH?ids=gold%3Ainr.gram.24k")

            assertThat(response.headers[HttpHeaders.CacheControl]).isEqualTo("public, max-age=900")
        }

    /** Mounts the production route over a catalogue. */
    private fun ApplicationTestBuilder.serve(catalogue: PriceCatalogue) {
        application { marketPrices(catalogue::quote) }
    }

    /** Answers with fixed prices for the keys it is given, in the order asked. */
    private class FixedSource(
        override val namespace: String,
        private vararg val prices: Pair<PriceKey, Long>,
    ) : PriceSource {
        override suspend fun quote(keys: Set<PriceKey>): List<PriceQuote> =
            prices.filter { it.first in keys }.map { PriceQuote(it.first.value, it.second, "2026-08-30") }
    }
}
