package com.aicfo.data.repository

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.marketsignal.Capacity
import com.aicfo.domain.engines.marketsignal.DailyClose
import com.aicfo.domain.engines.marketsignal.Instrument
import com.aicfo.domain.engines.marketsignal.MarketContext
import com.aicfo.domain.engines.marketsignal.MarketSignalEngine
import com.aicfo.domain.engines.marketsignal.MarketSignalInput
import com.aicfo.domain.engines.marketsignal.OpportunityAssessment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The opportunity screen's data side (issue 10.7; §30, AI-ARC-001, ARC-005).
 *
 * Why:  AI-MKT is pure and knows nothing about this household — not what it holds, not what it has
 *       spare, not whether a crunch day is coming. This is where the cached closes meet the
 *       capacity figures other engines already published, in the profile's own today. It reads
 *       **only what is cached** (P-04): nothing here fetches, so the screen works in airplane mode
 *       and says how old the data is.
 * What: the instruments the household actually holds, each with its assessment.
 * Result: a ViewModel sees [OpportunityView]s and never a DAO (ARC-005). Nothing is bought:
 *         a tranche suggestion is a number on a screen (P-07).
 * Changelog: 2026-09-27 — Created for issue 10.7.
 */
interface MarketSignalRepository {
    /**
     * Every held instrument with what AI-MKT makes of it today.
     * Why:    recomputed on every emission rather than stored, because it depends on today's date
     *         and on figures that move — and what is stored is the history it is made from.
     * Result: re-emits when a price is cached or the household's capacity changes. Input: none.
     * Output: `Flow<List<OpportunityView>>`.
     */
    fun observeOpportunities(): Flow<List<OpportunityView>>

    /**
     * Records one day's close for an instrument.
     * Why:    the series is built from the quotes the app already fetches, so this is called by the
     *         price refresh rather than by a screen. One row per day: a second call for the same
     *         day corrects the price instead of weighting that day twice.
     * Result: `Ok(Unit)`. Input: [priceKey]; [isoDate]; [close]. Output: `Result<Unit, AppError>`.
     */
    suspend fun recordClose(
        priceKey: String,
        isoDate: String,
        close: Money,
    ): Result<Unit, AppError>
}

/**
 * One instrument's assessment, with the label the user knows it by.
 * Input:  [assessment]; [holdingLabel] — the holding's own name.
 * Output: an immutable value.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 */
data class OpportunityView(
    val assessment: OpportunityAssessment,
    val holdingLabel: String,
)

/**
 * [MarketSignalRepository] over the cached closes (issue 10.7).
 *
 * Input:  [database]; [engine] — AI-MKT; [capacity] — the three gate figures other engines publish;
 *         [clock]; [dispatchers]; [activeProfileId]; [idGenerator]. Output: the repository.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 */
@Suppress("LongParameterList") // the database, the engine, the capacity flow and four seams
internal class CachedMarketSignalRepository(
    private val database: CfoDatabase,
    private val engine: MarketSignalEngine,
    private val capacity: Flow<Capacity>,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
    private val idGenerator: com.aicfo.core.common.IdGenerator,
) : MarketSignalRepository {
    override fun observeOpportunities(): Flow<List<OpportunityView>> =
        activeProfileId.flatMapLatest { profileId ->
            combine(
                database.marketCloseDao().observeCloses(profileId),
                database.investmentHoldingDao().observeForProfile(profileId),
                capacity,
            ) { closes, holdingRows, householdCapacity ->
                val byKey = closes.groupBy { it.priceKey }
                holdingRows
                    .mapNotNull { row -> row.priceKey?.let { key -> key to row.name } }
                    .distinctBy { it.first }
                    .map { (key, label) -> viewFor(key, label, byKey[key].orEmpty(), householdCapacity) }
            }
        }

    override suspend fun recordClose(
        priceKey: String,
        isoDate: String,
        close: Money,
    ): Result<Unit, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult {
                val now = clock.nowUtcMillis()
                database.marketCloseDao().upsert(
                    com.aicfo.core.database.entity.MarketCloseEntity(
                        id = idGenerator.newId("close"),
                        profileId = activeProfileId.first(),
                        priceKey = priceKey,
                        closeIsoDate = isoDate,
                        closeMinor = close.minor,
                        createdAtUtcMillis = now,
                        updatedAtUtcMillis = now,
                    ),
                )
            }
        }

    /**
     * Result: one instrument's view. Input: [key]; [label]; [rows] — its cached closes;
     * [householdCapacity]. Output: [OpportunityView].
     *
     * The two context percentiles are **not** supplied: this app has no valuation or volatility
     * feed, so the engine is told nothing rather than told a guess, and it reports both signals as
     * not evaluated (P-03). ADR-0055 records what it would take to have them.
     */
    private fun viewFor(
        key: String,
        label: String,
        rows: List<com.aicfo.core.database.entity.MarketCloseEntity>,
        householdCapacity: Capacity,
    ): OpportunityView {
        val input =
            MarketSignalInput(
                instrument = Instrument(key, label),
                closes = rows.map { DailyClose(it.closeIsoDate, Money(it.closeMinor)) },
                context = MarketContext(),
                capacity = householdCapacity,
                todayIsoDate = clock.today().toString(),
                nowUtcMillis = clock.nowUtcMillis(),
            )
        return OpportunityView(
            assessment = (engine.assess(input) as Ok).value,
            holdingLabel = label,
        )
    }
}

/**
 * The household's capacity to deploy anything, from the engines that already publish it (§30.4).
 * Why:    a flow rather than three repositories inside the market one, because AI-MKT needs three
 *         numbers from elsewhere and should not be able to reach for anything more.
 * Result: re-emits when any of the three moves. Input: [safeToSpend]; [emergencyFund]; [forecast].
 * Output: `Flow<Capacity>`.
 * Changelog: 2026-09-27 — Created for issue 10.7.
 */
internal fun capacityFlow(
    safeToSpend: SafeToSpendRepository,
    emergencyFund: EmergencyFundRepository,
    forecast: ForecastRepository,
): Flow<Capacity> =
    combine(
        safeToSpend.observeSafeToSpend(),
        emergencyFund.observeEmergencyFund(),
        forecast.observeForecast(),
    ) { spendable, fund, horizon ->
        Capacity(
            // RULE-IDLE-CASH: what AI-STS says is free to spend is the nearest published figure to
            // "idle". The engine only asks whether there is any.
            idleCash = spendable?.amount ?: Money.ZERO,
            // RULE-RUNWAY-M: AI-EMF's own verdict, not a re-derivation of it.
            runwayMeetsTarget = fund.isFunded,
            // AI-FCT's crunch days inside the horizon it already computed.
            crunchDaysAhead = (horizon as? Ok)?.value?.crunchDays?.size ?: 0,
        )
    }
