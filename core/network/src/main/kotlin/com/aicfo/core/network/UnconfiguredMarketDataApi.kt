package com.aicfo.core.network

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Result
import com.aicfo.core.model.PriceKey

/**
 * The [MarketDataApi] for an app with no backend — which is every build today (issue 6.5).
 *
 * Why:  §22's Market Data API is specified and unbuilt. The alternatives were to point the client
 *       at a third-party endpoint, which violates EXT-001 outright, or to let a null client crash
 *       somewhere far from the cause. This does neither: it is a real implementation of the real
 *       interface that answers honestly and instantly.
 *
 *       **No client is constructed to reach this.** [MarketDataFactory] returns this object before
 *       any OkHttp or Retrofit instance exists, so an unconfigured build allocates no connection
 *       pool, opens no socket, and exercises no permission. "Prices come only through our proxy" is
 *       true here in the strongest possible way: there is nowhere else for a price to come from.
 * What: an immediate failure.
 * Result: every caller keeps whatever price it already had (P-04).
 * Changelog: 2026-08-29 — Created for issue 6.5.
 */
internal object UnconfiguredMarketDataApi : MarketDataApi {
    /**
     * Refuses, without trying.
     * Why:    `retryable = false` is the load-bearing half. A missing backend is not a transient
     *         failure and will not fix itself on the next attempt, so a worker that treated it as
     *         retryable would back off and re-run for ever against a host that does not exist.
     * Result: `Err(Network(retryable = false))`, always. Input: [keys], ignored.
     * Output: `Result<List<MarketQuote>, AppError>`.
     * Changelog: 2026-08-29 — Created for issue 6.5.
     */
    override suspend fun quotes(keys: Set<PriceKey>): Result<List<MarketQuote>, AppError> =
        Err(AppError.Network(retryable = false))
}
