package com.aicfo.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The wire shape of `GET /v1/market/prices` (issue 6.5; §22.2, MNY-001).
 *
 * Why:  `internal`, and mapped to [MarketQuote] before it leaves this module. A DTO that reached
 *       `:data:repository` would make the wire format part of the app's internal contract, and the
 *       next change to the proxy's JSON would ripple through three modules instead of one.
 * What: a batch of quotes.
 * Result: what the converter parses; never what a caller sees.
 * Changelog: 2026-08-29 — Created for issue 6.5.
 *
 * @property quotes one entry per instrument the proxy recognised.
 */
@Serializable
internal data class MarketPricesResponse(
    val quotes: List<MarketPriceDto> = emptyList(),
)

/**
 * One quote, as the proxy sends it (issue 6.5; §22.2, MNY-001).
 *
 * **[unitPriceMinor] is an integer number of paise, and this is the single most important thing
 * about the whole backend contract.** MNY-001 makes money `Long` minor units end to end. If the
 * proxy ever sent `78.34` as a JSON number, parsing it would go through a `Double` and every
 * downstream figure — value, gain, XIRR, allocation share — would inherit a rounding error that no
 * test in this app would catch, because every test would be reading the same wrong number. A server
 * that gets this wrong corrupts the client silently. It must send paise as an integer.
 *
 * @property priceKey the instrument, echoed so a response can be matched to its request without
 *   depending on array order.
 * @property unitPriceMinor paise per unit, integer.
 * @property asOfIsoDate the day the market priced it, ISO `yyyy-MM-dd` (TIM-002).
 */
@Serializable
internal data class MarketPriceDto(
    @SerialName("price_key") val priceKey: String,
    @SerialName("unit_price_minor") val unitPriceMinor: Long,
    @SerialName("as_of") val asOfIsoDate: String,
)
