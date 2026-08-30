package com.aicfo.backend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The response body of `GET /v1/market/prices` (issue 6.7; §22.2, MNY-001).
 *
 * Why:  the client for this endpoint shipped first (issue 6.5) and its parser is already in users'
 *       hands, so this shape is not up for negotiation — it is a mirror of `MarketPricesResponse`
 *       in `:core:network`, and `contracts/market-prices-v1.json` is the file that keeps the two
 *       honest. It is a *mirror* rather than a shared type because `:core:network` is an Android
 *       library and this module is plain JVM; the DTO there is `internal` besides.
 * What: a batch of quotes, one per instrument the proxy recognised.
 * Result: what [PricesRoute] serialises. Fewer entries than ids were asked for is normal.
 * Changelog: 2026-08-30 — Created for issue 6.7.
 *
 * @property quotes one entry per recognised instrument; empty when none were.
 */
@Serializable
data class PricesResponse(
    val quotes: List<PriceQuote> = emptyList(),
)

/**
 * One instrument's price, as it goes on the wire (issue 6.7; §22.2, MNY-001, TIM-002).
 *
 * **[unitPriceMinor] is an integer number of paise, and that is the whole contract.** MNY-001 makes
 * money `Long` minor units end to end. A server that sent `7890.12` here would route the client's
 * parse through a `Double`, and every figure derived from it — holding value, gain, XIRR, allocation
 * share — would inherit a rounding error that no test in the app could catch, because every test
 * would be reading the same wrong number. The corruption would be silent and total. `Long` is what
 * makes that impossible rather than merely discouraged.
 *
 * Changelog: 2026-08-30 — Created for issue 6.7.
 *
 * @property priceKey the instrument, echoed **exactly** as asked so the client can match a quote to
 *   a request without depending on array order. A quote the client did not ask for is dropped there.
 * @property unitPriceMinor paise per unit, integer, positive. A non-positive value is dropped
 *   client-side, so emitting one is the same as emitting nothing.
 * @property asOfIsoDate the day the **market** priced it, ISO `yyyy-MM-dd` (TIM-002) — not the day
 *   this service fetched it and not the day the device heard it. The app stores all three separately.
 */
@Serializable
data class PriceQuote(
    @SerialName("price_key") val priceKey: String,
    @SerialName("unit_price_minor") val unitPriceMinor: Long,
    @SerialName("as_of") val asOfIsoDate: String,
)
