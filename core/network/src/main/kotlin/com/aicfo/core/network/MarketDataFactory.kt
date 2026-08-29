package com.aicfo.core.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * The only place a market-data client is built (issue 6.5; ARC-003, §22.1, API-001).
 *
 * Why:  the same seam `RepositoryFactory` and `CfoDatabaseFactory` use — implementations stay
 *       `internal`, so `:app`'s Hilt module cannot name one and calls this instead.
 *
 *       **Concentrating construction here is what makes the offline claim checkable.** There is one
 *       expression in the whole repository that produces an `OkHttpClient`, and it is unreachable
 *       unless a base URL is configured. A reviewer verifying "this app cannot talk to anything but
 *       our proxy" reads this file and the manifest beside it, and is done.
 * What: an unconfigured stub, or a pinned client with API-001's timeouts.
 * Result: a [MarketDataApi] that is either inert or correct — never partly configured.
 * Changelog: 2026-08-29 — Created for issue 6.5.
 */
object MarketDataFactory {
    /**
     * Builds the client, or declines to.
     * Why:    the branch is first and total. With no base URL nothing below it runs — no client, no
     *         connection pool, no DNS, no socket. That is why an unconfigured build can be said to
     *         make no network calls rather than merely to make none that succeed.
     * Result: [UnconfiguredMarketDataApi] when [config] names no host, else a [RetrofitMarketDataApi]
     *         over a certificate-pinned client.
     * Input:  [config] — validated on construction; a host without pins cannot be expressed.
     * Output: [MarketDataApi].
     * Changelog: 2026-08-29 — Created for issue 6.5.
     */
    fun create(config: NetworkConfig): MarketDataApi {
        if (!config.isConfigured) return UnconfiguredMarketDataApi

        return retrofitApi(config.baseUrl, client(config))
    }

    /**
     * Binds a base URL and a client to the service, with the JSON reader this app ships.
     *
     * Why:    split out of [create], and `internal`, so the test suite can drive the real converter
     *         and the real mapping against MockWebServer. It has to be split because [create] cannot
     *         be: [NetworkConfig] refuses a cleartext base URL, and MockWebServer serves cleartext,
     *         so the two cannot meet. Trusting a test certificate instead would mean a seam for
     *         injecting an `SSLSocketFactory`, which is a hole in the one file whose job is to prove
     *         there are none.
     *
     *         **What this leaves untested is exactly TLS and pinning**, and nothing else: every
     *         line below is the production path. That gap is not a testing shortfall — there is no
     *         server and therefore no certificate — and it is recorded as such in ADR-0030.
     * Result: a live [MarketDataApi].
     * Input:  [baseUrl] — the root, ending in `/`; [client] — a built OkHttp client.
     * Output: [MarketDataApi].
     * Changelog: 2026-08-29 — Created for issue 6.5.
     */
    internal fun retrofitApi(
        baseUrl: String,
        client: OkHttpClient,
    ): MarketDataApi =
        RetrofitMarketDataApi(
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(JSON.asConverterFactory(CONTENT_TYPE.toMediaType()))
                .build()
                .create(MarketPriceService::class.java),
        )

    /**
     * The HTTP client: pinned, bounded, and with no cache.
     * Why:    **no `Cache`, deliberately.** OkHttp's disk cache would write the response — and with
     *         it the list of instruments this user owns — to a plaintext file outside SQLCipher.
     *         §22.2 mentions ETag caching; this app's cache is the encrypted price columns in the
     *         database instead, which is both private and the thing the UI actually reads.
     *
     *         **No logging interceptor**, for the reason `build.gradle.kts` records: it would log a
     *         request naming the user's holdings (§21.6).
     *
     *         `internal` rather than private so the suite can assert that a [NetworkConfig] really
     *         does turn into a pinned, bounded client — that wiring is checkable without a server
     *         even though the handshake is not.
     * Result: a client that can reach exactly one host, presenting one of the pinned certificates,
     *         within [NetworkConfig.timeoutSeconds].
     * Input:  [config]. Output: [OkHttpClient].
     * Changelog: 2026-08-29 — Created for issue 6.5.
     */
    internal fun client(config: NetworkConfig): OkHttpClient {
        val host = URI(config.baseUrl).host.orEmpty()
        val pinner =
            CertificatePinner.Builder()
                .apply { config.pins.forEach { pin -> add(host, pin) } }
                .build()

        return OkHttpClient.Builder()
            .certificatePinner(pinner)
            .connectTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
            .build()
    }

    /**
     * The JSON reader.
     *
     * `ignoreUnknownKeys` so the proxy can add a field without breaking every installed copy of the
     * app — §22.1 promises additive evolution within a version, and this is the client half of that
     * promise. `explicitNulls = false` keeps an absent optional absent rather than requiring the
     * server to send nulls.
     */
    private val JSON =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    private const val CONTENT_TYPE = "application/json"
}
