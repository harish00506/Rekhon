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
 * `crypto:` — coin prices in rupees, from CoinGecko (issue 6.7; §16.1, EXT-001).
 *
 * Why:  §16.1 wants crypto priced every fifteen minutes, and EXT-001 says the device may not ask an
 *       exchange itself. CoinGecko's public `simple/price` needs no key, quotes directly in INR (so
 *       no second conversion to round), and carries the timestamp the quote belongs to.
 * What: maps the symbols in the requested keys to CoinGecko's own ids, makes one call, and reads
 *       each price **out of its raw text**.
 * Result: a quote per coin it recognised. Anything else — an unknown symbol, a currency other than
 *         INR, a payload that changed shape — is simply no quote.
 * Changelog: 2026-08-30 — Created for issue 6.7.
 *
 * @property fetch the shared HTTP edge; returns null on every upstream failure.
 * @property clock injected (TIM-001), used to date a quote the vendor did not date.
 * @property baseUrl overridable so the suite can point this at MockWebServer and test the parser
 *   against a recorded payload rather than against the live internet (P-08).
 */
class CoinGeckoCryptoSource(
    private val fetch: HttpFetch,
    private val clock: Clock,
    private val baseUrl: String = "https://api.coingecko.com",
) : PriceSource {
    override val namespace: String = "crypto"

    /**
     * Prices the coins in [keys].
     * Input:  [keys] — `crypto:<symbol>.inr` keys. Output: the quotes CoinGecko returned.
     * Result: empty when nothing was recognised or the call failed.
     */
    override suspend fun quote(keys: Set<PriceKey>): List<PriceQuote> {
        val wanted = keys.mapNotNull { key -> COIN_IDS[symbolOf(key)]?.let { it to key } }
        if (wanted.isEmpty()) return emptyList()

        val prices = prices(wanted.map { it.first })
        return wanted.mapNotNull { (id, key) -> quoteFor(key, prices?.get(id)) }
    }

    /**
     * The vendor's whole answer for [ids], as JSON.
     * Input:  [ids] — CoinGecko's own coin ids. Output: the parsed object, or null if the call failed
     *   or the payload was not JSON.
     * Result: ids are sorted so the request is byte-identical for the same set of coins, which makes
     *   the vendor's own caching (and the test's assertion) deterministic.
     */
    private suspend fun prices(ids: List<String>): JsonObject? {
        val query = ids.distinct().sorted().joinToString(",")
        val body = fetch.get("$baseUrl$PATH?ids=$query&vs_currencies=inr&include_last_updated_at=true")
        return body?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
    }

    /**
     * Reads one coin's entry.
     * Input:  [key] — the key to echo; [entry] — CoinGecko's object for that coin, possibly absent.
     * Output: the quote, or null when the entry is missing, mis-shaped, or not a usable price.
     */
    private fun quoteFor(
        key: PriceKey,
        entry: kotlinx.serialization.json.JsonElement?,
    ): PriceQuote? {
        val coin = runCatching { entry?.jsonObject }.getOrNull() ?: return null
        // The RAW TEXT of the number, never `.double` — see Paise.parseRupees. This is the line that
        // keeps MNY-001 true, and it is one character away from being the line that breaks it.
        val price =
            Paise.parseRupees(runCatching { coin.getValue("inr").jsonPrimitive.content }.getOrNull())
                ?: return null
        return PriceQuote(key.value, price.minor, asOf(coin))
    }

    /**
     * The day a quote belongs to.
     * Input:  [coin] — CoinGecko's object, which carries `last_updated_at` as an epoch second.
     * Output: ISO `yyyy-MM-dd`, falling back to today when the field is absent or unreadable.
     */
    private fun asOf(coin: JsonObject): String =
        runCatching { coin.getValue("last_updated_at").jsonPrimitive.content.toLong() }
            .map(clock::isoDateAt)
            .getOrElse { clock.todayIso() }

    /**
     * The symbol in a `crypto:<symbol>.inr` key.
     * Input:  [key]. Output: the lowercase symbol, or null if the key is not shaped that way or asks
     *   for a currency this source does not quote.
     */
    private fun symbolOf(key: PriceKey): String? {
        val rest = key.value.substringAfter(':', missingDelimiterValue = "")
        val symbol = rest.substringBefore('.', missingDelimiterValue = "")
        val currency = rest.substringAfter('.', missingDelimiterValue = "")
        return symbol.takeIf { it.isNotEmpty() && currency == "inr" }
    }

    private companion object {
        const val PATH = "/api/v3/simple/price"

        /**
         * Symbol → CoinGecko id.
         *
         * A vendor's own naming, not a financial number, so it belongs in code rather than in
         * `ai/rules/` (CLAUDE.md §6 governs thresholds). It is a **map, not a list of what exists**:
         * a symbol absent here is simply unpriced, which is the same outcome as an instrument
         * CoinGecko has never heard of, and needs no validation to produce.
         */
        val COIN_IDS: Map<String, String> =
            mapOf(
                "btc" to "bitcoin",
                "eth" to "ethereum",
                "usdt" to "tether",
                "usdc" to "usd-coin",
                "bnb" to "binancecoin",
                "sol" to "solana",
                "xrp" to "ripple",
                "ada" to "cardano",
                "doge" to "dogecoin",
                "trx" to "tron",
                "ltc" to "litecoin",
                "dot" to "polkadot",
                "matic" to "matic-network",
                "shib" to "shiba-inu",
            )
    }
}
