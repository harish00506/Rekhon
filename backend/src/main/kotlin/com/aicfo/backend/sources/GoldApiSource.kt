package com.aicfo.backend.sources

import com.aicfo.backend.HttpFetch
import com.aicfo.backend.Paise
import com.aicfo.backend.PriceQuote
import com.aicfo.backend.PriceSource
import com.aicfo.backend.isoDateAt
import com.aicfo.backend.todayIso
import com.aicfo.core.model.PriceKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Clock

/**
 * `gold:` — rupees per gram, from a metals provider (issue 6.7; §16.1, EXT-001, FR-INV-004).
 *
 * Why:  §16.1 wants gold end-of-day, and `gold:inr.gram.24k` is the key the app's own holdings use.
 *       Unlike the other three upstreams this one **needs an API key**: there is no free, keyless,
 *       reliable source of an Indian gold price per gram, and deriving one from a tokenised-gold
 *       coin would quietly bake that token's premium into somebody's net worth.
 * What: one call, and a map from the requested key to the vendor's own per-gram field.
 * Result: a quote per purity asked for. **With no API key configured, this source is not registered
 *         at all** (see `ServerConfig`), so `gold:` keys come back unpriced — which is exactly what
 *         the client already handles by keeping its cached price and ageing the label (P-04,
 *         ADR-0030). An unconfigured vendor is a supported state here for the same reason an
 *         unconfigured backend is one there.
 * Changelog: 2026-08-30 — Created for issue 6.7.
 *
 * The purity is read from the vendor's own `price_gram_24k` / `price_gram_22k` rather than derived
 * from one by a 22/24 ratio. The ratio is right about metal content and wrong about what an Indian
 * jeweller quotes, and a valuation that is subtly wrong is worse than one that is absent.
 *
 * @property fetch the shared HTTP edge.
 * @property apiKey the provider's token, sent as `x-access-token`.
 * @property clock injected (TIM-001).
 * @property baseUrl overridable so the parser is tested against a recorded payload (P-08).
 */
class GoldApiSource(
    private val fetch: HttpFetch,
    private val apiKey: String,
    private val clock: Clock,
    private val baseUrl: String = "https://www.goldapi.io",
) : PriceSource {
    override val namespace: String = "gold"

    /**
     * Prices the purities in [keys].
     * Input:  [keys] — `gold:inr.gram.24k` / `gold:inr.gram.22k`. Output: the quotes obtained.
     * Result: empty when the call failed or no key named a purity this provider publishes.
     */
    override suspend fun quote(keys: Set<PriceKey>): List<PriceQuote> {
        val wanted = keys.mapNotNull { key -> PURITY_FIELDS[key.value]?.let { it to key } }
        if (wanted.isEmpty()) return emptyList()

        val payload = payload()
        return wanted.mapNotNull { (field, key) -> payload?.let { quoteFor(it, field, key) } }
    }

    /**
     * The provider's whole answer, as JSON.
     * Input:  none. Output: the parsed object, or null if the call failed or was not JSON.
     */
    private suspend fun payload(): JsonObject? =
        fetch.get("$baseUrl$PATH", mapOf("x-access-token" to apiKey))
            ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }

    /**
     * Reads one purity out of the payload.
     * Input:  [payload] — the provider's object; [field] — its per-gram field; [key] — the key to echo.
     * Output: the quote, or null when the field is absent or not a usable price.
     */
    private fun quoteFor(
        payload: JsonObject,
        field: String,
        key: PriceKey,
    ): PriceQuote? {
        val price =
            Paise.parseRupees(
                runCatching { payload.getValue(field).jsonPrimitive.content }.getOrNull(),
            ) ?: return null
        return PriceQuote(key.value, price.minor, asOf(payload))
    }

    /**
     * The day the quote belongs to.
     * Input:  [payload] — the provider's object, carrying `timestamp` as an epoch second.
     * Output: ISO `yyyy-MM-dd`, falling back to today when absent or unreadable.
     */
    private fun asOf(payload: JsonObject): String =
        runCatching { payload.getValue("timestamp").jsonPrimitive.content.toLong() }
            .map(clock::isoDateAt)
            .getOrElse { clock.todayIso() }

    private companion object {
        /** XAU quoted in INR — the provider's own path for "gold, in rupees". */
        const val PATH = "/api/XAU/INR"

        /**
         * Price key → the provider's per-gram field.
         *
         * Two entries, because two purities are what Indian gold is actually quoted in. A key naming
         * any other purity is unpriced rather than approximated.
         */
        val PURITY_FIELDS: Map<String, String> =
            mapOf(
                "gold:inr.gram.24k" to "price_gram_24k",
                "gold:inr.gram.22k" to "price_gram_22k",
            )
    }
}
