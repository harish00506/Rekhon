package com.aicfo.core.network

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * The Retrofit binding for §22.2's price endpoint (issue 6.5).
 *
 * Why:  `internal`. The app's contract is [MarketDataApi]; this is how one implementation of it
 *       happens to be built, and a caller that could name a Retrofit type is a caller that could
 *       come to depend on Retrofit being the answer.
 * What: one GET, one query parameter.
 * Result: [MarketPricesResponse], mapped to domain types by [RetrofitMarketDataApi].
 * Changelog: 2026-08-29 — Created for issue 6.5.
 *
 * **The request carries instrument identifiers and nothing else** (EXT-003) — no account id, no
 * profile id, no amount, no device identifier. There is nothing in this signature that could carry
 * one, which is the point.
 */
internal interface MarketPriceService {
    /**
     * Batch quotes.
     * Result: the proxy's response. Input: [ids] — comma-separated price keys.
     * Output: [MarketPricesResponse].
     */
    @GET("v1/market/prices")
    suspend fun prices(
        @Query("ids") ids: String,
    ): MarketPricesResponse
}
