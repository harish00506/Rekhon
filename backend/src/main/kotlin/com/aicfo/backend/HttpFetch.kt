package com.aicfo.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The one way this service reaches a vendor (issue 6.7; EXT-001).
 *
 * Why:  four sources, four upstreams, and one place where "the network happened" is expressed. That
 *       makes each source a pure function of a payload, so every one of them is tested against a
 *       recorded fixture with no socket involved (P-08) — and it makes the timeout, which is the
 *       only thing standing between a slow vendor and API-001's five-second budget, a single number
 *       rather than four.
 * What: a suspend GET returning the body text, or null.
 * Result: **null on every failure** — a dead host, a timeout, a 404, a 500. A source that gets null
 *         returns no quote, and the client keeps its cached price (P-04). No failure of a vendor is
 *         ever an exception here, because a vendor being down is normal.
 * Changelog: 2026-08-30 — Created for issue 6.7.
 *
 * A plain interface rather than a `fun interface`: the default on [headers] is worth more than
 * SAM-conversion sugar, since three of the four sources need no headers at all and only the keyed
 * gold provider does.
 */
interface HttpFetch {
    /**
     * Fetches [url].
     * Input:  [url] — an absolute URL; [headers] — vendor auth, empty for the keyless sources.
     * Output: the response body, or null if anything at all went wrong.
     */
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): String?
}

/**
 * [HttpFetch] over OkHttp (issue 6.7).
 *
 * Why:  OkHttp is already pinned in the catalogue for `:core:network`, so the server adds no second
 *       HTTP client — and MockWebServer, its matching test double, is already pinned too.
 * What: one client, one timeout, blocking calls moved to the IO dispatcher.
 * Result: the body text or null.
 * Changelog: 2026-08-30 — Created for issue 6.7.
 *
 * @property client the shared client. One instance, so the connection pool is reused across
 *   requests — four sources opening their own pools would be four times the sockets for no gain.
 */
class OkHttpFetch(private val client: OkHttpClient) : HttpFetch {
    /**
     * Input:  [url], [headers]. Output: the body, or null on any failure.
     * Result: never throws. `use` closes the response on every path, including the failure ones.
     */
    override suspend fun get(
        url: String,
        headers: Map<String, String>,
    ): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request =
                    Request.Builder().url(url)
                        .apply { headers.forEach { (name, value) -> header(name, value) } }
                        .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) response.body?.string() else null
                }
            }.getOrNull()
        }

    companion object {
        /**
         * The production client.
         *
         * Why:    API-001 gives the *client* five seconds for the whole round trip, so this service's
         *         own upstream budget has to be comfortably inside it or a slow vendor becomes a
         *         client timeout. Three seconds leaves room for the response to be assembled and sent.
         * Result: an [OkHttpFetch]. Input: none. Output: the fetch.
         */
        fun create(): OkHttpFetch =
            OkHttpFetch(
                OkHttpClient.Builder()
                    .callTimeout(UPSTREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .connectTimeout(UPSTREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(UPSTREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build(),
            )

        /** Inside API-001's five seconds, with room to spare for serialising the answer. */
        private const val UPSTREAM_TIMEOUT_SECONDS = 3L
    }
}
