package com.aicfo.backend.sources

import com.aicfo.backend.HttpFetch
import com.aicfo.backend.Paise
import com.aicfo.backend.PriceQuote
import com.aicfo.backend.PriceSource
import com.aicfo.backend.todayIso
import com.aicfo.core.model.PriceKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Clock
import java.util.Locale

/**
 * `fx:` — rupees per unit of a foreign currency (issue 6.7; §16, design spec §11).
 *
 * Why:  the design spec names "rates (RBI)" among the things this proxy serves. **RBI publishes no
 *       stable machine-readable feed** — its reference rates live on an ASPX page whose markup is a
 *       layout, not an interface, and a parser built on it breaks the day somebody moves a table.
 *       So this source reads a published daily reference rate from a documented JSON API instead.
 *       The two differ by a few paise on the rupee; for valuing a holding that is immaterial, and
 *       the substitution is recorded in the ADR rather than left for a reader to discover.
 * What: one call per base currency, concurrently, reading each rate out of its raw text.
 * Result: a quote per `fx:<base>.inr` key that resolved.
 * Changelog: 2026-08-30 — Created for issue 6.7.
 *
 * **Policy rates are deliberately not served here.** A repo rate is a percentage, which MNY-002
 * makes integer basis points, and this endpoint's only money field is `unit_price_minor` — paise.
 * Bending a rate into a price field so it fits would be exactly the quiet type confusion MNY-001 and
 * MNY-002 exist to prevent; a rate endpoint would need its own shape.
 *
 * @property fetch the shared HTTP edge.
 * @property clock injected (TIM-001).
 * @property baseUrl overridable so the parser is tested against a recorded payload (P-08).
 */
class FxReferenceRateSource(
    private val fetch: HttpFetch,
    private val clock: Clock,
    private val baseUrl: String = "https://api.frankfurter.app",
) : PriceSource {
    override val namespace: String = "fx"

    /**
     * Prices the currencies in [keys].
     * Input:  [keys] — `fx:<base>.inr` keys. Output: the quotes obtained.
     * Result: empty when nothing resolved. One dead call costs its own currency only.
     */
    override suspend fun quote(keys: Set<PriceKey>): List<PriceQuote> {
        val wanted = keys.mapNotNull { key -> baseOf(key)?.let { it to key } }
        if (wanted.isEmpty()) return emptyList()

        return coroutineScope {
            wanted.map { (base, key) -> async { quoteFor(base, key) } }.awaitAll().filterNotNull()
        }
    }

    /**
     * Fetches and reads one currency's rate.
     * Input:  [base] — the ISO currency code; [key] — the key to echo.
     * Output: the quote, or null on any failure or unusable rate.
     */
    private suspend fun quoteFor(
        base: String,
        key: PriceKey,
    ): PriceQuote? {
        val body = fetch.get("$baseUrl$PATH?base=$base&symbols=$QUOTE_CURRENCY") ?: return null
        val payload = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        // Raw text, never `.double` — the same MNY-001 guard every source in this package holds.
        val rate =
            Paise.parseRupees(
                runCatching {
                    payload.getValue("rates").jsonObject.getValue(QUOTE_CURRENCY).jsonPrimitive.content
                }.getOrNull(),
            ) ?: return null
        val asOf =
            runCatching { payload.getValue("date").jsonPrimitive.content }.getOrNull()
                ?: clock.todayIso()
        return PriceQuote(key.value, rate.minor, asOf)
    }

    /**
     * The base currency in an `fx:<base>.inr` key, upper-cased for the vendor.
     * Input:  [key]. Output: e.g. `"USD"`, or null when the key is shaped otherwise or asks for a
     *   quote currency other than the rupee — this proxy prices in paise, so every rate is per rupee.
     */
    private fun baseOf(key: PriceKey): String? {
        val rest = key.value.substringAfter(':', missingDelimiterValue = "")
        val base = rest.substringBefore('.', missingDelimiterValue = "")
        val quote = rest.substringAfter('.', missingDelimiterValue = "")
        return base.takeIf { it.isNotEmpty() && quote == "inr" }?.uppercase(Locale.ROOT)
    }

    private companion object {
        const val PATH = "/latest"

        /** The rupee. Every price this service serves is in paise, so every rate is against INR. */
        const val QUOTE_CURRENCY = "INR"
    }
}
