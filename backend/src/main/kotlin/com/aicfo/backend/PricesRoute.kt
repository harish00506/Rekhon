package com.aicfo.backend

import com.aicfo.core.model.PriceKey
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

/**
 * `GET /v1/market/prices?ids=` — the whole of the §22 Market Data API (issue 6.7; API-001, EXT-003).
 *
 * Why:  issue 6.5 shipped a client pointed at this path and nothing served it, so its first
 *       acceptance criterion could not be demonstrated (ADR-0030). This is the other half.
 * What: one route. It parses a comma-separated id list, validates each id with the **same**
 *       [PriceKey] class the client validates with, asks [quote], and writes the JSON.
 * Result: `200` with zero or more quotes; `400` for a request that names no ids or too many. There
 *         is no `404` and no per-instrument error — an unknown instrument is an absent quote.
 * Changelog: 2026-08-30 — Created for issue 6.7.
 *
 * **No error response ever echoes a requested identifier.** That is the testable half of EXT-003 on
 * this side of the wire: what the device asked about must not come back out of this service in a
 * body, and must not go into a log (see `logback.xml` — no `CallLogging` plugin is installed at all).
 * The bodies below are therefore constant strings, chosen over the more helpful
 * `"unknown key: gold:inr.gram.24k"` deliberately.
 *
 * @param quote what prices a set of keys — [PriceCatalogue.quote] in production. Taken as a function
 *   rather than as the class so the route can be driven against a failing lookup: the catalogue
 *   isolates a dead vendor by design, so the `502` below is unreachable through it and would
 *   otherwise be untestable dead code.
 */
fun Application.marketPrices(quote: suspend (Set<PriceKey>) -> List<PriceQuote>) {
    routing {
        get(PRICES_PATH) {
            val raw = call.request.queryParameters[IDS_PARAM]
            val requested = raw.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)

            when {
                requested.isEmpty() ->
                    call.respondText(ERROR_IDS_REQUIRED, ContentType.Application.Json, HttpStatusCode.BadRequest)

                requested.size > MAX_IDS ->
                    call.respondText(ERROR_TOO_MANY_IDS, ContentType.Application.Json, HttpStatusCode.BadRequest)

                else -> {
                    // An id that is not a valid price key is dropped, not rejected. Failing the batch
                    // would let one malformed id cost the caller every other price in it, and the
                    // client already drops a malformed key on arrival.
                    val keys = requested.mapNotNull { runCatching { PriceKey(it) }.getOrNull() }.toSet()
                    val quotes = runCatching { quote(keys) }.getOrNull()
                    if (quotes == null) {
                        call.respondText(ERROR_UPSTREAM, ContentType.Application.Json, HttpStatusCode.BadGateway)
                    } else {
                        call.response.headers.append(HttpHeaders.CacheControl, CACHE_CONTROL)
                        call.respondText(
                            WIRE_JSON.encodeToString(PricesResponse(quotes)),
                            ContentType.Application.Json,
                            HttpStatusCode.OK,
                        )
                    }
                }
            }
        }
    }
}

/** The path §22.2 fixes and `MarketPriceService` already calls. */
const val PRICES_PATH: String = "/v1/market/prices"

/** The one query parameter. */
const val IDS_PARAM: String = "ids"

/**
 * The serializer for the wire.
 *
 * `encodeDefaults = true` so an empty batch is `{"quotes":[]}` rather than `{}`. Both parse on the
 * client, but a response whose shape changes with its contents is the kind of thing that is fine
 * until the day something else reads it.
 */
private val WIRE_JSON = Json { encodeDefaults = true }

/**
 * How long a caller may reuse a response.
 *
 * The tightest cadence in `RULE-PRICE-STALE` is crypto's fifteen minutes, and this is one number for
 * all namespaces rather than one per namespace: under-caching a daily instrument costs an occasional
 * redundant request, while over-caching a quarter-hourly one hands back a price the rulebook already
 * considers due. Cheap in the wrong direction beats wrong in the right one.
 *
 * **No `ETag`.** §22.2 mentions conditional caching, but ADR-0030 rejected an OkHttp disk cache — it
 * would write the device's instrument list to a plaintext file outside SQLCipher — so no client will
 * ever send `If-None-Match` back. An ETag nothing validates is decoration.
 */
private const val CACHE_CONTROL = "public, max-age=900"

/**
 * The most instruments one request may name.
 *
 * A batch is one upstream call per namespace, so the cost of a large batch falls on the vendor's rate
 * limit rather than on this service. A hundred is far above what any real device holds (the app's own
 * query returns the distinct price keys of one profile's holdings — tens of rows) and far below what
 * would make this endpoint a useful amplifier.
 */
private const val MAX_IDS = 100

/** Constant bodies. None of them names an identifier — see the class comment. */
private const val ERROR_IDS_REQUIRED = """{"error":"ids required"}"""
private const val ERROR_TOO_MANY_IDS = """{"error":"too many ids"}"""
private const val ERROR_UPSTREAM = """{"error":"upstream unavailable"}"""
