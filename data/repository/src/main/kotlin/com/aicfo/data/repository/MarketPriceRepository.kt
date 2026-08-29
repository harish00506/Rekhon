package com.aicfo.data.repository

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.entity.InvestmentHoldingEntity
import com.aicfo.core.datastore.ConsentFeature
import com.aicfo.core.datastore.ConsentStore
import com.aicfo.core.model.AssetClass
import com.aicfo.core.model.PriceKey
import com.aicfo.core.network.MarketDataApi
import com.aicfo.core.network.MarketQuote
import com.aicfo.domain.engines.investment.InvestmentEngine
import com.aicfo.domain.engines.investment.PriceFreshnessInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Refreshes stored market prices (issue 6.5; FR-INV-004, §16.1, P-01, P-04).
 *
 * Why:  **its own file, and not a method on `InvestmentRepository`.** That class is 543 lines and
 *       sits exactly on detekt's eleven-function ceiling, but the real reason is ownership: this
 *       repository owns the *price columns* and nothing else, while `InvestmentRepository` owns the
 *       row — its name, class, lots and lifecycle. Two writers with disjoint columns cannot corrupt
 *       each other, and `updatePriceByKey` makes that structural rather than a convention someone
 *       has to remember.
 * What: one operation, [refresh], gated three times before it can reach a socket.
 * Result: prices brought up to date, or nothing at all — never a partial state the user has to see.
 * Changelog: 2026-08-29 — Created for issue 6.5.
 *
 * **A failed fetch is not an error the user is shown.** Every network failure path returns `Ok(0)`
 * with the stored prices untouched, because a price that could not be refreshed is still the best
 * price this app has and the screen already says how old it is (P-04). The only `Err` this returns
 * is a storage failure, which is the caller's problem rather than the network's.
 */
interface MarketPriceRepository {
    /**
     * Fetches and stores prices for the instruments that are due for one.
     * Why:    the gates run in this order because each is cheaper than the next and each makes the
     *         next unnecessary — consent, then whether anything is market-priced at all, then
     *         whether enough time has passed, and only then a request.
     * Result: how many holding rows were updated. Zero is the ordinary outcome — no consent, no
     *         price keys, nothing due, or a proxy that did not answer.
     * Input:  none; the active profile, clock and consent come from construction.
     * Output: `Result<Int, AppError>` — `Err` only when the database itself failed.
     * Changelog: 2026-08-29 — Created for issue 6.5.
     */
    suspend fun refresh(): Result<Int, AppError>
}

/**
 * The Room + HTTP implementation of [MarketPriceRepository] (issue 6.5).
 *
 * Why:  `internal`, built by `RepositoryFactory.marketPrice` (ARC-003).
 * What: consent gate, key gate, TTL gate, fetch, write.
 * Result: the price columns move; nothing else in the row does.
 * Changelog: 2026-08-29 — Created for issue 6.5.
 *
 * Input:  [database]; [api] — the market-data client, which in a shipping build is the unconfigured
 *         one and answers instantly without a socket; [engine] — owns the "is a refresh due"
 *         arithmetic, because the interval is a rulebook number (§6); [consents] — P-01;
 *         [clock] — TIM-001, the only clock read on this path; [dispatchers]; [activeProfileId].
 * Output: a [MarketPriceRepository].
 */
@Suppress("LongParameterList") // One argument per collaborator, as every other repository here.
internal class RoomMarketPriceRepository(
    private val database: CfoDatabase,
    private val api: MarketDataApi,
    private val engine: InvestmentEngine,
    private val consents: ConsentStore,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
) : MarketPriceRepository {
    override suspend fun refresh(): Result<Int, AppError> =
        withContext(dispatchers.io) {
            // Gate 1 — consent. An unreadable consent store reads as *not granted*, never as
            // permission, copying `RoomSmsRepository.observeAccess`. This is the gate that makes the
            // Settings toggle mean something: with it closed nothing below this line runs, so no
            // request is built and no socket is opened (P-01).
            if (!marketDataGranted()) {
                Ok(0)
            } else {
                // The one sanctioned catch (§21.6). It lets a cancellation and a failed `require`
                // through, which is right: neither is a storage failure to report to a caller.
                runCatchingToResult { fetchAndStore() }
            }
        }

    /**
     * The work, once consent is established.
     * Why:    split out so [refresh]'s body is the consent gate and nothing else, and so one
     *         `runCatching` covers every database call on the path rather than each one separately.
     *         Returns a plain count rather than a [Result] because none of its own exits is a
     *         failure — no consent, no keys, nothing due and no answer are all "zero rows updated".
     *         The only failure on this path is the database throwing, and that is [refresh]'s catch.
     * Result: the number of rows updated.
     * Input:  none. Output: [Int].
     * Changelog: 2026-08-29 — Created for issue 6.5.
     */
    private suspend fun fetchAndStore(): Int {
        val profileId = activeProfileId.first()
        val dao = database.investmentHoldingDao()

        // Gate 2 — is anything market-priced at all? A null price key is the opt-in switch, so a
        // profile of hand-typed holdings never reaches the network however often this is called.
        // The request payload is built from this query, whose result cannot hold anything but a
        // price key (EXT-003).
        val keyed = dao.distinctPriceKeys(profileId).mapNotNull { raw -> raw.asPriceKey() }

        // Gate 3 — TTL. The rows are read in full because the *decision* needs each holding's asset
        // class and fetch stamp, which the projection above deliberately cannot carry. That read
        // never leaves the device; only the intersection is ever sent. Skipped entirely when nothing
        // is keyed, so an unkeyed profile does not even pay for the read.
        val due = if (keyed.isEmpty()) emptySet() else dueKeys(dao.forProfile(profileId))
        val ask = keyed.filter { key -> key in due }.toSet()

        // Nothing due, no request. And when there is one, a dead proxy, a timeout or a 500 all mean
        // "keep what we have": the screen already labels the stored price with its age, so there is
        // nothing to tell the user (P-04).
        val answer = if (ask.isEmpty()) null else api.quotes(ask) as? Ok

        return answer?.let { quotes -> store(profileId, quotes.value) } ?: 0
    }

    /**
     * Which of the profile's instruments are worth a network call right now.
     * Why:    the interval is a rulebook number, so the comparison belongs to the engine and not to
     *         a `now - fetchedAt > TTL` written here — a threshold in this file would be a financial
     *         constant with no drift gate on it (§6, ADR-0017). The clock is read once and handed
     *         down, so every holding is judged against the same instant (TIM-001, P-08).
     *
     *         A key is due if **any** holding of that instrument is due. Two holdings of the same
     *         gold share one key and therefore one answer; asking twice would be one request twice.
     * Result: the set of keys with at least one holding past its refresh interval.
     * Input:  [rows] — the profile's live holdings. Output: `Set<PriceKey>`.
     * Changelog: 2026-08-29 — Created for issue 6.5.
     */
    private fun dueKeys(rows: List<InvestmentHoldingEntity>): Set<PriceKey> {
        val today = clock.today().toString()
        val now = clock.nowUtcMillis()

        return rows.mapNotNullTo(mutableSetOf()) { row -> row.dueKey(today, now) }
    }

    /**
     * Whether one row's price is past its refresh interval, and which instrument it is.
     * Why:    a row with no key is not refreshable and a class the app cannot read is not judgeable;
     *         both drop out here rather than becoming a special case in the caller.
     * Result: the row's key when a refresh is due, else `null`.
     * Input:  the receiver — one holding row; [today]; [now]. Output: [PriceKey]?.
     * Changelog: 2026-08-29 — Created for issue 6.5.
     */
    private fun InvestmentHoldingEntity.dueKey(
        today: String,
        now: Long,
    ): PriceKey? {
        val key = priceKey?.asPriceKey() ?: return null
        val assetClass = AssetClass.fromStored(assetClass) ?: return null
        val freshness =
            engine.priceFreshness(
                PriceFreshnessInput(
                    assetClass = assetClass,
                    pricedOnIsoDate = pricedOnIsoDate,
                    fetchedAtUtcMillis = priceFetchedAtUtcMillis,
                    todayIsoDate = today,
                    nowUtcMillis = now,
                ),
            )
        return if (freshness is Ok && freshness.value.refreshDue) key else null
    }

    /**
     * Writes the quotes onto the holdings that carry their keys.
     * Why:    one `UPDATE` per instrument, of four named columns. It cannot reach `name`,
     *         `asset_class` or a lot, so a refresh landing while the user is renaming a holding
     *         cannot undo the rename — the guarantee is in the statement, not in a lock.
     *
     *         The fetch stamp is this device's clock and the priced-on date is the proxy's, because
     *         they answer different questions: when we heard it, and which day the market meant it
     *         for (TIM-001, TIM-002).
     * Result: the number of rows updated across every quote.
     * Input:  [profileId]; [quotes] — already filtered by the network layer, which drops anything
     *         non-positive or unasked-for. Output: [Int].
     * Changelog: 2026-08-29 — Created for issue 6.5.
     */
    private suspend fun store(
        profileId: String,
        quotes: List<MarketQuote>,
    ): Int {
        val fetchedAt = clock.nowUtcMillis()
        var updated = 0
        for (quote in quotes) {
            updated +=
                database.investmentHoldingDao().updatePriceByKey(
                    profileId = profileId,
                    priceKey = quote.priceKey.value,
                    unitPriceMinor = quote.unitPrice.minor,
                    pricedOnIsoDate = quote.asOfIsoDate,
                    fetchedAtUtcMillis = fetchedAt,
                )
        }
        return updated
    }

    /**
     * Whether the user has allowed market-data requests.
     * Result: `true` only on an explicit, readable grant. Input: none. Output: [Boolean].
     * Changelog: 2026-08-29 — Created for issue 6.5.
     */
    private suspend fun marketDataGranted(): Boolean =
        (consents.observe(ConsentFeature.MARKET_DATA).first() as? Ok)?.value?.granted == true
}

/**
 * Reads a stored key back into its value class, dropping one this build cannot parse.
 * Why:    the column is plain TEXT and a row could predate a change to the accepted character set,
 *         or have been written by an import. A malformed key is not worth a crash and is certainly
 *         not worth putting in a request.
 * Result: the [PriceKey], or `null`. Input: the receiver — the raw column value. Output: [PriceKey]?
 * Changelog: 2026-08-29 — Created for issue 6.5.
 */
private fun String.asPriceKey(): PriceKey? = runCatching { PriceKey(this) }.getOrNull()
