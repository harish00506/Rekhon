package com.aicfo.core.network

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Result

/**
 * The [AccountAggregatorApi] for an app with no AA backend — which is every build (issue 13.6).
 *
 * Why:  §16's AA ingest is specified and unbuilt, and this issue's AC asks for an interface stub.
 *       The same three options applied as for market data (issue 6.5): point at a third-party
 *       endpoint and violate EXT-001, leave a null client to crash far from the cause, or answer
 *       honestly. This answers honestly.
 *
 *       **No client is constructed to reach this.** There is no OkHttp instance, no socket and no
 *       permission behind it — "the app has no bank connection" is true here in the strongest
 *       sense: there is nowhere for a statement to come from.
 * What: an immediate, non-retryable failure.
 * Result: every caller keeps whatever it already had (P-04).
 * Changelog: 2026-10-09 — Created for issue 13.6.
 */
internal object UnconfiguredAccountAggregatorApi : AccountAggregatorApi {
    /**
     * Refuses, without trying.
     * Why:    `retryable = false` is the load-bearing half, exactly as it is for market data. A
     *         missing backend is not transient, so a worker that treated it as retryable would back
     *         off and re-run for ever against a host that does not exist.
     * Result: `Err(Network(retryable = false))`, always. Input: [consent], ignored.
     * Output: `Result<FetchedStatements, AppError>`.
     */
    override suspend fun statements(consent: ConsentHandle): Result<FetchedStatements, AppError> =
        Err(AppError.Network(retryable = false))
}

/**
 * Builds the AA client (ARC-003; mirrors `MarketDataFactory`).
 *
 * Why:    one place decides whether a build has an AA backend, so the "is it configured?" question
 *         is answered once rather than at every call site.
 * Result: today, always [UnconfiguredAccountAggregatorApi] — there is no configured path to
 *         return, and adding one is the first thing a real AA integration does.
 * Changelog: 2026-10-09 — Created for issue 13.6.
 */
object AccountAggregatorFactory {
    /** Result: the client this build has. Input: none. Output: [AccountAggregatorApi]. */
    fun create(): AccountAggregatorApi = UnconfiguredAccountAggregatorApi
}
