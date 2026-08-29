package com.aicfo.core.network

/**
 * Where the backend is, and what certificate it must present (issue 6.5; §22.1, API-001).
 *
 * Why:  §22.1 requires TLS 1.3 with certificate pinning. There is no server yet, so there is no
 *       certificate to pin and no real pin value can be written today. What **can** be guaranteed,
 *       and is, is that a base URL cannot be configured without one: [init] refuses a non-blank
 *       host that is not `https` or that carries no pins. A placeholder pin would be worse than
 *       none — it would read as a control while protecting nothing.
 * What: the host, its pins, and the timeouts API-001 sets.
 * Result: the value [MarketDataFactory] decides on. Blank host means the app ships with no backend
 *         at all, which is the current state and a perfectly good one (P-04).
 * Changelog: 2026-08-29 — Created for issue 6.5.
 *
 * @property baseUrl the proxy's root, `https://…/`. **Blank means unconfigured**, which is not an
 *   error: the app is offline-first and a missing backend is a supported state, not a broken one.
 * @property pins SHA-256 SPKI pins in OkHttp's `sha256/…` form, for [baseUrl]'s host. At least two
 *   in production — one for the current certificate and one for its replacement — or a rotation
 *   bricks every installed copy of the app until it is updated.
 * @property timeoutSeconds connect, read, write and call timeout. API-001 says five seconds: this
 *   is a price label, and a user waiting longer than that for one has been failed either way.
 */
data class NetworkConfig(
    val baseUrl: String,
    val pins: List<String> = emptyList(),
    val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) {
    /** Whether a backend has been configured at all. */
    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    init {
        require(!isConfigured || baseUrl.startsWith(HTTPS_PREFIX)) {
            "A market-data host must be https. Cleartext would let anyone on the path see which " +
                "instruments this device asked about, which is exactly the leak EXT-003 exists to " +
                "prevent. Was '$baseUrl'"
        }
        require(!isConfigured || pins.isNotEmpty()) {
            "A configured host must carry certificate pins (§22.1). Shipping a base URL without " +
                "them would mean any CA the device trusts could impersonate the proxy — and the " +
                "point of a proxy is that it is the only party the app talks to"
        }
        require(timeoutSeconds > 0) {
            "A timeout must be positive, was $timeoutSeconds: at zero the call never gives up and " +
                "the refresh worker would hold a wakelock until the system killed it"
        }
    }

    companion object {
        /** API-001's five seconds. */
        const val DEFAULT_TIMEOUT_SECONDS = 5L

        private const val HTTPS_PREFIX = "https://"

        /**
         * The state this app actually ships in: no backend.
         *
         * The §22 Market Data API is specified and unbuilt, and there is no issue in the backlog
         * that builds it. Rather than point the client at a third-party endpoint as a stopgap —
         * which would violate EXT-001 outright and be very hard to unpick later — the client ships
         * inert. [MarketDataFactory] never constructs an HTTP stack from this, so no socket is
         * opened and no permission is exercised.
         *
         * When a proxy exists, one `@Provides` in `:app` changes and nothing else does.
         */
        val UNCONFIGURED = NetworkConfig(baseUrl = "")
    }
}
