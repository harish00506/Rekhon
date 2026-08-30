package com.aicfo.backend

import com.aicfo.core.model.PriceKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Routes price keys to the sources that know them, and merges the answers (issue 6.7; §22.2).
 *
 * Why:  the route should not know that gold comes from a metals API and mutual funds from an 8 MB
 *       text file. It should know that it asked for four identifiers and got back some quotes. This
 *       class is the whole of that translation, and it is also where two guarantees are enforced
 *       that a source cannot be trusted to hold on its own.
 * What: an index of [PriceSource] by namespace, queried concurrently, results filtered and merged.
 * Result: quotes for the keys that were asked for and could be priced — never more.
 * Changelog: 2026-08-30 — Created for issue 6.7.
 *
 * The two guarantees:
 *
 * 1. **A source cannot widen the response.** Whatever it returns is intersected with what was asked.
 *    The client already drops an unasked-for quote (`RetrofitMarketDataApi`), so a widening bug
 *    would be invisible in the app and visible only here.
 * 2. **A dead vendor does not empty the batch.** Each source is isolated, so a metals API that is
 *    down costs the caller its gold price and nothing else. Failing the whole request instead would
 *    make every instrument's freshness hostage to the least reliable upstream.
 *
 * @property sources every configured source. A namespace with no source is not an error — it is how
 *   an unconfigured vendor behaves, which mirrors the client shipping unconfigured (ADR-0030).
 */
class PriceCatalogue(sources: List<PriceSource>) {
    private val byNamespace: Map<String, PriceSource> = sources.associateBy { it.namespace }

    /**
     * Prices what it can of [keys].
     *
     * Why:    one call per namespace rather than one per key — see [PriceSource.quote].
     * What:   groups by namespace, queries the matching sources **concurrently** (API-001 allows the
     *         client five seconds for the whole request, so four sequential upstreams would not fit),
     *         then filters and de-duplicates.
     * Result: the merged quotes. Empty when nothing could be priced, which is a `200` with an empty
     *         list, not an error.
     * Input:  [keys] — the validated keys from the query string; may be empty.
     * Output: quotes whose `price_key` is in [keys], each key at most once, each price positive.
     */
    suspend fun quote(keys: Set<PriceKey>): List<PriceQuote> {
        if (keys.isEmpty()) return emptyList()
        val asked = keys.mapTo(mutableSetOf()) { it.value }
        val batches =
            keys.groupBy { namespaceOf(it) }
                .mapNotNull { (namespace, group) -> byNamespace[namespace]?.to(group.toSet()) }
        if (batches.isEmpty()) return emptyList()

        val answers =
            coroutineScope {
                batches.map { (source, group) ->
                    // Isolated per source: a vendor that throws costs its own namespace, nothing else.
                    async { runCatching { source.quote(group) }.getOrDefault(emptyList()) }
                }.awaitAll()
            }

        return answers.flatten()
            .filter { it.priceKey in asked && it.unitPriceMinor > 0 }
            .distinctBy { it.priceKey }
    }

    /**
     * The namespace of a key — everything before the first colon.
     * Input:  [key] — a validated price key. Output: the namespace, or `""` when it carries no colon
     *   (which matches no source, so such a key is simply unpriced).
     */
    private fun namespaceOf(key: PriceKey): String = key.value.substringBefore(':', missingDelimiterValue = "")
}
