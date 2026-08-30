package com.aicfo.backend.sources

import com.aicfo.backend.HttpFetch
import com.aicfo.backend.Paise
import com.aicfo.backend.PriceQuote
import com.aicfo.backend.PriceSource
import com.aicfo.core.model.PriceKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `mf:` — mutual-fund NAVs by ISIN, from AMFI (issue 6.7; §16.1, EXT-001, P-06).
 *
 * Why:  AMFI is the authoritative daily NAV for every Indian mutual fund, it is free, and it needs
 *       no key. EXT-001 says the device may not fetch it: this file is about eight megabytes of
 *       semicolon-delimited text covering every scheme in the country, which is exactly the sort of
 *       thing a proxy exists to absorb.
 * What: fetches the whole file, indexes it by **lowercased** ISIN, and answers from the index.
 * Result: a quote per ISIN found. An unknown ISIN is simply absent.
 * Changelog: 2026-08-30 — Created for issue 6.7.
 *
 * **This source caches itself rather than being wrapped in [com.aicfo.backend.CachingPriceSource].**
 * That decorator caches per key, so the first request for each new ISIN would pull eight megabytes
 * again. Here the unit of fetch is the whole file, so the unit of cache has to be the whole index.
 * Where the shape of the upstream dictates the cache, the cache lives with the upstream.
 *
 * **AMFI serves an incomplete certificate chain**, which the JDK will not complete on its own. See
 * `enableIncompleteChainRecovery` in `Main.kt` — without it every fetch here fails the handshake and
 * every `mf:` key comes back unpriced, quickly and quietly.
 *
 * **ISINs are lowercased on the way in.** AMFI publishes `INF109K01Z48`; `PriceKey` forbids
 * uppercase, so the key a device can hold is `mf:inf109k01z48`. Indexing in the key's own case is
 * what makes the two meet — and doing it here rather than at lookup means it happens once per file
 * instead of once per request.
 *
 * @property fetch the shared HTTP edge.
 * @property clock injected (TIM-001), so the suite can age the index without sleeping.
 * @property ttl how long an index may be served. AMFI publishes once a day around 23:30 IST; six
 *   hours picks the new file up within a quarter of a day without re-downloading it hourly.
 * @property url overridable so the parser is tested against a recorded file (P-08).
 */
class AmfiNavSource(
    private val fetch: HttpFetch,
    private val clock: Clock,
    private val ttl: Duration = Duration.ofHours(DEFAULT_TTL_HOURS),
    private val url: String = "https://portal.amfiindia.com/spages/NAVAll.txt",
) : PriceSource {
    override val namespace: String = "mf"

    private val lock = Mutex()
    private var index: Map<String, PriceQuote> = emptyMap()
    private var indexedAt: Instant? = null

    /**
     * Prices the ISINs in [keys].
     * Input:  [keys] — `mf:<lowercased isin>` keys. Output: the quotes found in the current index.
     * Result: empty when the file could not be fetched and none was cached.
     */
    override suspend fun quote(keys: Set<PriceKey>): List<PriceQuote> {
        val current = currentIndex()
        return keys.mapNotNull { key ->
            current[key.value.substringAfter(':', missingDelimiterValue = "")]
                ?.copy(priceKey = key.value)
        }
    }

    /**
     * The index, refreshed if it has aged out.
     *
     * Why:    one download serves every caller for [ttl], and the mutex means a burst of requests on
     *         a cold process produces one download rather than one per request.
     * Result: the ISIN → quote map. **A failed refresh keeps the previous index** rather than
     *         emptying it: a stale NAV the client will label as old beats no NAV at all, and AMFI
     *         being briefly unreachable is not a reason for a portfolio to lose its value.
     * Input:  none. Output: the map, empty only if nothing has ever been fetched.
     */
    private suspend fun currentIndex(): Map<String, PriceQuote> {
        val now = clock.instant()
        if (isFreshAt(now)) return index
        return lock.withLock {
            if (isFreshAt(now)) return@withLock index
            val body = fetch.get(url)
            if (body != null) {
                index = parse(body)
                indexedAt = now
            }
            index
        }
    }

    /** Input: [now]. Output: whether the held index may still be served. */
    private fun isFreshAt(now: Instant): Boolean = indexedAt?.let { Duration.between(it, now) < ttl } == true

    /**
     * Reads the NAV file.
     *
     * Why:    the file interleaves a header row, fund-house names, scheme-type banners and blank
     *         lines with the data. Rather than track which is which, this takes any line whose first
     *         column is a scheme code and which has the six fields a data row has, and ignores
     *         everything else — the banners carry no semicolons, so they cannot be mistaken for data.
     * What:   splits, reads both ISIN columns (a scheme publishes payout and reinvestment ISINs that
     *         share a NAV), converts the NAV to paise and the date to ISO.
     *
     *         **The NAV and the date are read from the END of the row, not from fixed indices.** The
     *         header AMFI publishes names six columns; the file it publishes has eight — `Plan` and
     *         `Option` were inserted in the middle at some point and the header was not updated. A
     *         parser indexing NAV at column 4 therefore reads a scheme's plan name, fails to make a
     *         number of it, and silently prices nothing at all. Counting from the right survives the
     *         next column AMFI adds; it was written the other way first and this is what it cost.
     * Result: ISIN (lowercase) → quote. A row whose NAV or date will not parse is skipped, not fatal.
     * Input:  [body] — the file's text. Output: the index.
     */
    private fun parse(body: String): Map<String, PriceQuote> {
        val built = mutableMapOf<String, PriceQuote>()
        body.lineSequence().forEach { line ->
            val parts = line.split(FIELD_SEPARATOR)
            if (parts.size < MIN_FIELDS) return@forEach
            if (parts[SCHEME_CODE_COLUMN].trim().toIntOrNull() == null) return@forEach
            val price = Paise.parseRupees(parts[parts.size - 2]) ?: return@forEach
            val asOf = isoDate(parts.last()) ?: return@forEach
            listOf(parts[ISIN_PAYOUT_COLUMN], parts[ISIN_REINVEST_COLUMN])
                .map { it.trim().lowercase(Locale.ROOT) }
                .filter { it.isNotEmpty() && it != NO_ISIN }
                .forEach { isin -> built[isin] = PriceQuote(isin, price.minor, asOf) }
        }
        return built
    }

    /**
     * The AMFI date `29-Aug-2026` as ISO `yyyy-MM-dd` (TIM-002).
     * Input:  [raw] — the date column. Output: the ISO date, or null when it will not parse.
     */
    private fun isoDate(raw: String): String? =
        runCatching { LocalDate.parse(raw.trim(), AMFI_DATE).toString() }.getOrNull()

    private companion object {
        /**
         * How long an index is served before it is re-downloaded.
         *
         * AMFI publishes once a day, around 23:30 IST. Six hours picks the new file up within a
         * quarter of a day without pulling eight megabytes every hour.
         */
        const val DEFAULT_TTL_HOURS = 6L

        /** `d` rather than `dd`, so a single-digit day parses too. */
        val AMFI_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH)

        /** The file is semicolon-delimited: fund names contain commas, so a CSV it is not. */
        const val FIELD_SEPARATOR = ';'

        /** What AMFI writes in an ISIN column for a scheme that has none. */
        const val NO_ISIN = "-"

        // Scheme Code;ISIN Div Payout/ISIN Growth;ISIN Div Reinvestment;Scheme Name;Plan;Option;NAV;Date
        // The first three are counted from the left because they have never moved; NAV and Date are
        // counted from the right because they have.
        const val SCHEME_CODE_COLUMN = 0
        const val ISIN_PAYOUT_COLUMN = 1
        const val ISIN_REINVEST_COLUMN = 2

        /** Fewest fields a data row can have: the header's own six. Anything shorter is a banner. */
        const val MIN_FIELDS = 6
    }
}
