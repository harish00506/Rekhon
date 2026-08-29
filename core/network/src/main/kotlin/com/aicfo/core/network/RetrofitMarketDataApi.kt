package com.aicfo.core.network

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.model.Money
import com.aicfo.core.model.PriceKey
import java.io.IOException

/**
 * The [MarketDataApi] that actually speaks to the proxy (issue 6.5; §22, API-001).
 *
 * Why:  `internal`, behind [MarketDataFactory], for the reason every other implementation here is:
 *       the contract is the interface. This class exists to do exactly two things the interface
 *       promises and nothing more — turn a set of keys into one request, and turn whatever comes
 *       back into domain types or a typed error.
 *
 *       **Every failure becomes `AppError.Network`, and none escapes as an exception.** A parse
 *       failure, a 500, a timeout and a dead host are all the same thing to a caller whose only
 *       decision is "keep the cached price" (P-04). Letting a `SerializationException` out would
 *       make a malformed byte from a server crash a screen about somebody's gold.
 * What: request, map, or fail.
 * Result: quotes the repository can write, or an error it can ignore.
 * Changelog: 2026-08-29 — Created for issue 6.5.
 *
 * **Reads no clock** (TIM-001). When the device heard a price is the repository's business; this
 * only reports what the proxy said the price was and which day it applies to.
 *
 * Input:  [service] — the Retrofit binding, built by the factory with pinning and timeouts already
 *         applied.
 * Output: an implementation of the app's market-data contract.
 */
internal class RetrofitMarketDataApi(
    private val service: MarketPriceService,
) : MarketDataApi {
    // `TooGenericExceptionCaught`: the arms below ARE the classification. Retrofit raises
    // HttpException and kotlinx.serialization raises SerializationException, both RuntimeException
    // and neither nameable here without a compile dependency on internals — and §21.6 forbids either
    // escaping this boundary whatever its type.
    // `SwallowedException`: the exception is discarded on purpose (P-01). Its message routinely
    // holds the request URL, and this app's URLs name the instruments the user owns; carrying it
    // into an AppError would put that in whatever eventually logs the error. `AppError.Network`
    // records the one thing a caller acts on — whether retrying is worth it.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override suspend fun quotes(keys: Set<PriceKey>): Result<List<MarketQuote>, AppError> {
        // No keys, no request. Asking the proxy about nothing would still cost a round trip and
        // still tell it this device is awake, and the answer is knowable without asking.
        if (keys.isEmpty()) return Ok(emptyList())

        return try {
            // Sorted, so the same portfolio produces the same URL every time. That makes the
            // request cacheable by an ETag (§22.2) and reproducible in a bug report, and it stops
            // set iteration order leaking anything about insertion history.
            val ids = keys.map { it.value }.sorted().joinToString(separator = ",")
            Ok(service.prices(ids).quotes.mapNotNull { it.toQuote(keys) })
        } catch (failure: IOException) {
            // A dead host, a refused connection, a timeout — all worth trying again later.
            Err(AppError.Network(retryable = true))
        } catch (failure: RuntimeException) {
            // A non-2xx status, or JSON this build cannot parse. Retrofit raises HttpException and
            // kotlinx.serialization raises SerializationException, both RuntimeExceptions, and both
            // mean the same thing here: the proxy answered with something unusable. Not retryable —
            // repeating an identical request against a server that just refused it will refuse it
            // again, and a worker that retried would back off against a permanent condition.
            Err(AppError.Network(retryable = false))
        }
    }

    /**
     * Turns one wire row into a domain quote, dropping anything that does not belong.
     * Why:    three refusals, each guarding something a caller would otherwise have to. A key that
     *         fails `PriceKey`'s character set is not something this app asked for; a key nobody
     *         asked about must not be written to a holding, because a proxy answering questions it
     *         was not asked is either confused or hostile; and a non-positive price would make
     *         `InvestmentHolding`'s constructor throw on the next read, turning a bad byte from a
     *         server into a crash on a screen.
     * Result: the quote, or `null` to drop the row.
     * Input:  the receiver — one wire row; [asked] — the keys actually requested.
     * Output: [MarketQuote]?.
     * Changelog: 2026-08-29 — Created for issue 6.5.
     */
    private fun MarketPriceDto.toQuote(asked: Set<PriceKey>): MarketQuote? {
        val key = runCatching { PriceKey(priceKey) }.getOrNull() ?: return null
        if (key !in asked) return null
        if (unitPriceMinor <= 0L) return null
        return MarketQuote(priceKey = key, unitPrice = Money(unitPriceMinor), asOfIsoDate = asOfIsoDate)
    }
}
