package com.aicfo.backend

import com.aicfo.core.model.PriceKey

/**
 * One vendor, behind one shape (issue 6.7; EXT-001).
 *
 * Why:  EXT-001 says market data is scraped **here and never on-device**, and §22 says every
 *       instrument type comes back through the same response shape. Four upstreams that look nothing
 *       alike — a JSON price API, an 8 MB semicolon-delimited text file, a metals provider behind a
 *       key, a central bank's reference rate — have to become one list of [PriceQuote]s, and this is
 *       the seam where each one stops being special.
 * What: a source claims a namespace (the part of a price key before the colon) and answers for the
 *       keys in it.
 * Result: the list of quotes it could produce. **Fewer than asked for is the normal case**, not an
 *         error: an unrecognised instrument simply has no quote, and the client keeps its cached
 *         price (P-04).
 * Changelog: 2026-08-30 — Created for issue 6.7.
 */
interface PriceSource {
    /**
     * The price-key namespace this source answers for, without the colon — `"crypto"`, `"gold"`,
     * `"mf"`, `"fx"`. [PriceCatalogue] routes by this and nothing else.
     */
    val namespace: String

    /**
     * Fetches what this source can for [keys].
     *
     * Why:    the batch is the unit because every upstream charges (in rate limit or in latency) per
     *         call, not per instrument, and API-001 gives the whole request five seconds.
     * What:   asks the vendor and converts to the wire shape.
     * Result: zero or more quotes. A quote for a key not in [keys] is dropped by the catalogue, so a
     *         source cannot widen the response.
     * Input:  [keys] — price keys, all in this source's [namespace], never empty.
     * Output: the quotes obtained. May throw; [PriceCatalogue] isolates a failing source so one dead
     *         vendor cannot empty the whole batch.
     */
    suspend fun quote(keys: Set<PriceKey>): List<PriceQuote>
}
