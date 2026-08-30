package com.aicfo.backend

import com.aicfo.core.model.PriceKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * A [PriceSource] that remembers what it was told, for a while (issue 6.7; §16.1, API-002).
 *
 * Why:  §16.1 gives gold a day and crypto fifteen minutes, and every vendor charges in rate limit
 *       rather than in rupees. Without this, every device asking for a bitcoin price is a CoinGecko
 *       call, and the free tier is gone by lunchtime. The app's own TTL gate (`RULE-PRICE-STALE`)
 *       already limits how often *one device* asks; this limits how often *this service* asks,
 *       across all of them.
 * What: a per-key TTL cache in front of a delegate, over an injected [Clock].
 * Result: a hit inside the window returns the stored quote and opens no socket.
 * Changelog: 2026-08-30 — Created for issue 6.7.
 *
 * **This does not make the service stateful in the sense EXT-003 means.** What is held is a public
 * fact about an instrument — the price of gold — keyed by an identifier the vendor published. There
 * is no device, no profile, no holding and no amount in it, and nothing in it distinguishes one
 * caller from another. It is a bandwidth optimisation with the lifetime of a process.
 *
 * @property delegate the real source.
 * @property ttl how long a quote may be reused. Matches the namespace's `refresh_minutes` in
 *   `RULE-PRICE-STALE` — one place the rulebook and this service have to agree, and the ADR says so.
 * @property clock injected, so a test can move time rather than sleep (P-08).
 */
class CachingPriceSource(
    private val delegate: PriceSource,
    private val ttl: Duration,
    private val clock: Clock,
) : PriceSource {
    override val namespace: String get() = delegate.namespace

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * Answers from the cache where it can, and asks [delegate] only for the rest.
     *
     * Why:    a partial hit is the common case — a device holding bitcoin and ether asks for both,
     *         and one of them was fetched by somebody else a minute ago. Asking the vendor for only
     *         the misses is the difference between a rate limit that holds and one that does not.
     * What:   splits the keys, delegates the misses, stores what comes back, merges.
     * Result: quotes for the keys that could be answered from either place.
     * Input:  [keys] — keys in this namespace. Output: the merged quotes.
     */
    override suspend fun quote(keys: Set<PriceKey>): List<PriceQuote> {
        val now = clock.instant()
        val fresh = keys.mapNotNull { key -> entries[key.value]?.takeIf { it.isFreshAt(now) }?.quote }
        val missing = keys.filterNot { key -> fresh.any { it.priceKey == key.value } }.toSet()
        if (missing.isEmpty()) return fresh

        val fetched = delegate.quote(missing)
        fetched.forEach { entries[it.priceKey] = Entry(it, now) }
        return fresh + fetched
    }

    /**
     * One remembered quote and when it was stored.
     * @property quote the stored answer. @property storedAt when this service heard it.
     */
    private inner class Entry(val quote: PriceQuote, val storedAt: Instant) {
        /**
         * Input:  [now] — the current instant. Output: whether the entry may still be served.
         * Result: false once [ttl] has elapsed, so the next request re-fetches. A failed re-fetch
         *         returns no quote rather than a stale one — the client, not this service, is the
         *         thing that holds a price past its window, and it labels it as old when it does.
         */
        fun isFreshAt(now: Instant): Boolean = Duration.between(storedAt, now) < ttl
    }
}
