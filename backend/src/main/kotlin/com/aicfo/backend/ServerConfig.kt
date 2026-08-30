package com.aicfo.backend

import com.aicfo.backend.sources.AmfiNavSource
import com.aicfo.backend.sources.CoinGeckoCryptoSource
import com.aicfo.backend.sources.FxReferenceRateSource
import com.aicfo.backend.sources.GoldApiSource
import java.io.File
import java.time.Clock
import java.time.Duration

/**
 * Everything this service reads from its environment, and what it assembles from it (issue 6.7).
 *
 * Why:  a deployment differs from a laptop in a handful of ways — a port, whether there is a
 *       certificate, and which vendor keys exist — and every one of them has to be a value rather
 *       than a branch scattered through the code. Reading the environment into a data class also
 *       makes the assembly testable without setting an environment variable, which is the only way
 *       to assert the thing that actually matters here: **which sources get registered.**
 * What: the typed config, its reader, and the catalogue it builds.
 * Result: `fromEnv(...).catalogue(...)` is the whole of the service's wiring.
 * Changelog: 2026-08-30 — Created for issue 6.7.
 *
 * @property port the cleartext port. Behind a TLS terminator (which is how this is deployed) it is
 *   the only one used; locally it is the fallback when no keystore is configured.
 * @property tls the certificate to serve, or null for plain HTTP. Local development sets this so the
 *   app can perform a **real pinned handshake** — the one thing issue 6.5 could not test (ADR-0030).
 * @property goldApiKey the metals provider's token, or null. **Null is a supported state**: the gold
 *   source is then not registered and `gold:` keys come back unpriced, which the client already
 *   handles by keeping its cached price (P-04). It mirrors the client shipping unconfigured.
 */
data class ServerConfig(
    val port: Int = DEFAULT_PORT,
    val tls: TlsConfig? = null,
    val goldApiKey: String? = null,
) {
    /**
     * Builds the catalogue this config describes.
     *
     * Why:    the set of registered sources IS the service's behaviour, and it depends on what is
     *         configured. Making it a function of the config rather than of `System.getenv` is what
     *         lets a test assert "with no gold key, gold is unpriced" without touching the process.
     * What:   four sources, each wrapped in the cache its upstream's shape calls for.
     * Result: a [PriceCatalogue]. A namespace whose vendor is unconfigured is simply absent from it.
     * Input:  [fetch] — the HTTP edge; [clock] — injected (TIM-001, P-08).
     * Output: the catalogue the route queries.
     */
    fun catalogue(
        fetch: HttpFetch,
        clock: Clock,
    ): PriceCatalogue =
        PriceCatalogue(
            listOfNotNull(
                CachingPriceSource(CoinGeckoCryptoSource(fetch, clock), CRYPTO_TTL, clock),
                goldApiKey?.let { CachingPriceSource(GoldApiSource(fetch, it, clock), GOLD_TTL, clock) },
                // Not wrapped: its unit of fetch is an eight-megabyte file, so it caches the whole
                // parsed index itself. See AmfiNavSource.
                AmfiNavSource(fetch, clock),
                CachingPriceSource(FxReferenceRateSource(fetch, clock), FX_TTL, clock),
            ),
        )

    /**
     * A keystore to serve TLS from.
     *
     * @property keyStore the PKCS#12 file. @property alias the key's alias inside it.
     * @property password the store and key password. @property port the HTTPS port.
     */
    data class TlsConfig(
        val keyStore: File,
        val alias: String,
        val password: String,
        val port: Int = DEFAULT_TLS_PORT,
    )

    companion object {
        /**
         * Reads the environment.
         *
         * Why:    twelve-factor, and because a deployment target sets environment variables and
         *         nothing else. Taking the map as a parameter rather than calling `System.getenv`
         *         directly is what makes every branch below testable.
         * What:   ports, the optional keystore triple, the optional gold key.
         * Result: a [ServerConfig]. **TLS is configured only when all three of keystore, alias and
         *         password are present** — a partial triple is treated as no TLS rather than as an
         *         error, so a half-set environment starts a working cleartext server instead of
         *         refusing to boot behind a terminator that was going to provide TLS anyway.
         * Input:  [env] — the environment, e.g. `System.getenv()`.
         * Output: the config.
         */
        fun fromEnv(env: Map<String, String>): ServerConfig {
            val keyStorePath = env[ENV_TLS_KEYSTORE]?.takeIf(String::isNotBlank)
            val alias = env[ENV_TLS_ALIAS]?.takeIf(String::isNotBlank)
            val password = env[ENV_TLS_PASSWORD]?.takeIf(String::isNotBlank)
            val tls =
                if (keyStorePath != null && alias != null && password != null) {
                    TlsConfig(
                        keyStore = File(keyStorePath),
                        alias = alias,
                        password = password,
                        port = env[ENV_TLS_PORT]?.toIntOrNull() ?: DEFAULT_TLS_PORT,
                    )
                } else {
                    null
                }
            return ServerConfig(
                port = env[ENV_PORT]?.toIntOrNull() ?: DEFAULT_PORT,
                tls = tls,
                goldApiKey = env[ENV_GOLD_API_KEY]?.takeIf(String::isNotBlank),
            )
        }

        const val ENV_PORT = "CFO_PORT"
        const val ENV_TLS_PORT = "CFO_TLS_PORT"
        const val ENV_TLS_KEYSTORE = "CFO_TLS_KEYSTORE"
        const val ENV_TLS_ALIAS = "CFO_TLS_ALIAS"
        const val ENV_TLS_PASSWORD = "CFO_TLS_PASSWORD"
        const val ENV_GOLD_API_KEY = "CFO_GOLD_API_KEY"

        const val DEFAULT_PORT = 8080
        const val DEFAULT_TLS_PORT = 8443

        /**
         * How long this service reuses a vendor's answer, per namespace.
         *
         * These **mirror `RULE-PRICE-STALE`'s `refresh_minutes`** in `ai/rules/rules-kb.json` — the
         * same numbers the app's own TTL gate uses, for the same reason (§16.1: crypto every fifteen
         * minutes, gold end-of-day). Nothing enforces the mirror, and deliberately so: the two
         * caches answer different questions, and a mismatch here costs an extra upstream call or an
         * answer that is at worst as old as the client's own window. It cannot produce a stale price
         * the client fails to label, because the client labels from `as_of`, which this never
         * rewrites.
         */
        private val CRYPTO_TTL: Duration = Duration.ofMinutes(15)
        private val GOLD_TTL: Duration = Duration.ofMinutes(1440)
        private val FX_TTL: Duration = Duration.ofMinutes(1440)
    }
}
