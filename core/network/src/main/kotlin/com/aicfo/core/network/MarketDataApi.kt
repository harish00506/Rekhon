package com.aicfo.core.network

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import com.aicfo.core.model.PriceKey

/**
 * The one way to ask what something is worth (issue 6.5; §16 EXT-001/EXT-003, §22.2).
 *
 * Why:  EXT-001 says the client never calls a third-party source directly — every feed goes through
 *       our own read-only Market Data API, so sources can change without an app release and no
 *       third party ever sees a user's IP. This interface is the whole of that contract: there is
 *       one implementation that speaks HTTP to one configured host, and one that speaks to nothing.
 *
 *       **The parameter type is the privacy control.** EXT-003 says a request carries "zero
 *       personal financial data — only instrument identifiers". A `Set<PriceKey>` cannot hold a
 *       rupee amount, a holding name, or anything else the user typed: `PriceKey`'s character set
 *       refuses it at construction. So the guarantee is a property of the signature rather than a
 *       rule the next caller has to remember.
 * What: a batch quote lookup, returning what came back and nothing about who asked.
 * Result: what `MarketPriceRepository` writes into the price columns.
 * Changelog: 2026-08-29 — Created for issue 6.5.
 *
 * **Never called from a ViewModel or a worker** (ARC-005) — a repository owns it, checks the
 * consent before touching it, and maps its failures. And **nothing on a core path may await it**:
 * every caller treats a failure as "keep the cached price", never as an error to show (P-04).
 */
interface MarketDataApi {
    /**
     * Asks the proxy for the current price of each instrument.
     * Why:    a batch rather than one call per holding — API-002 says the client batches, and a
     *         portfolio of thirty instruments must not become thirty requests.
     * Result: [Ok] with a quote for each key the proxy recognised — **possibly fewer than asked
     *         for, and never more**. An unknown key simply has no quote, which is the right failure
     *         and needs no error. [Err] with [AppError.Network] when the call could not be made or
     *         understood; the caller keeps whatever price it already had.
     * Input:  [keys] — the instruments to price. An empty set must not produce a request.
     * Output: `Result<List<MarketQuote>, AppError>`.
     * Changelog: 2026-08-29 — Created for issue 6.5.
     */
    suspend fun quotes(keys: Set<PriceKey>): Result<List<MarketQuote>, AppError>
}

/**
 * One instrument's price as the proxy reported it (issue 6.5; §16 EXT-002, MNY-001).
 *
 * Why:  [asOfIsoDate] travels with the price because a price without the day it applies to cannot
 *       be aged, and EXT-002 requires the UI to say "as of <date>" once a datum is past its TTL.
 *       The two are stored together for the same reason `InvestmentHolding` makes them
 *       both-or-neither.
 * What: the key asked about, the price per unit, and the day it applies to.
 * Result: the value the repository writes to `unit_price_minor` and `priced_on_iso_date`.
 * Changelog: 2026-08-29 — Created for issue 6.5.
 *
 * **No fetched-at here, deliberately.** When the device heard this is a fact about the device, not
 * about the instrument, and this module reads no clock (TIM-001). The repository stamps it.
 *
 * @property priceKey the instrument this prices — echoed back so a batch response can be matched to
 *   its request without relying on ordering.
 * @property unitPrice paise per unit (MNY-001). Never zero or negative; the repository rejects a
 *   quote that is, because a holding cannot hold a non-positive price.
 * @property asOfIsoDate the day the market priced it, ISO `yyyy-MM-dd` (TIM-002).
 */
data class MarketQuote(
    val priceKey: PriceKey,
    val unitPrice: Money,
    val asOfIsoDate: String,
)
