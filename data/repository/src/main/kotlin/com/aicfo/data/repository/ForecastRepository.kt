package com.aicfo.data.repository

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.dao.MonthlyCategorySpendRow
import com.aicfo.core.database.dao.NatureCandidateRow
import com.aicfo.core.database.entity.RecurringRuleEntity
import com.aicfo.core.model.Account
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.forecast.Cadence
import com.aicfo.domain.engines.forecast.CashFlowForecast
import com.aicfo.domain.engines.forecast.Commitment
import com.aicfo.domain.engines.forecast.DailySpend
import com.aicfo.domain.engines.forecast.ForecastEngine
import com.aicfo.domain.engines.forecast.ForecastInput
import com.aicfo.domain.engines.forecast.ForecastRules
import com.aicfo.domain.engines.forecast.ItemSource
import com.aicfo.domain.engines.forecast.ScheduledItem
import com.aicfo.domain.engines.seasonality.CategoryMonthSpend
import com.aicfo.domain.engines.seasonality.CategorySpend
import com.aicfo.domain.engines.seasonality.SeasonalityEngine
import com.aicfo.domain.engines.seasonality.SeasonalityInput
import com.aicfo.domain.engines.seasonality.SeasonalityRules
import com.aicfo.domain.engines.stream.StreamBasis
import com.aicfo.domain.engines.stream.StreamClass
import com.aicfo.domain.engines.stream.StreamProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import java.time.LocalDate
import java.time.YearMonth

/**
 * AI-FCT over the ledger (issue 9.2; SRS §9.1, §9.2, ARC-005).
 *
 * Why:  the engine projects and resamples; something has to decide what is **scheduled** and what is
 *       **everyday spending**, and that is a join over the accounts, the ledger, the recurring rules
 *       and AI-CLS Stage 2's streams — so it lives here, the only layer that touches a DAO.
 * What: the consolidated liquid balance today, the scheduled items (confirmed recurring rules, FIXED
 *       streams, future-dated transactions), the everyday outflows of the lookback with the scheduled
 *       ones taken out, and where the ledger starts — handed to the engine.
 * Result: the forecast the dashboard shows.
 * Changelog: 2026-09-19 — Created for issue 9.2.
 */
interface ForecastRepository {
    /**
     * Observes the active profile's 90-day forecast.
     * Why:    recomputed whenever a row it reads changes, so a new expense, a confirmed rule or a
     *         reconciled balance moves the forecast at once. The seed is today's epoch day, so a
     *         forecast is stable through the day and every reopen shows the same bands (P-08).
     * Result: `Ok(forecast)`; `Err` only for an input the engine refuses, which the ledger's own
     *         constraints should make unreachable.
     * Input:  none — the active profile. Output: `Flow<Result<CashFlowForecast, AppError>>`.
     */
    fun observeForecast(): Flow<Result<CashFlowForecast, AppError>>
}

/**
 * The Room-backed [ForecastRepository].
 * Why:    ARC-003 — one public interface, an internal implementation, assembled by the DI graph.
 *         Balances come from [accounts] and streams from [streams] rather than a second query each:
 *         two derivations of "the bank balance" would disagree the first time either changed
 *         (ADR-0007).
 * Result: the implementation injected into the dashboard.
 * Changelog: 2026-09-19 — Created for issue 9.2.
 *
 * Input:  [database]; [accounts] — balances; [streams] — AI-CLS Stage 2; [engine]; [clock] (TIM-001);
 *         [dispatchers]; [activeProfileId] — follows the demo (ADR-0006); [rules] — the lookback and
 *         horizon size the queries, so a test that moves them moves the window too.
 * Output: a working repository.
 */
@Suppress("LongParameterList") // Ten, each one input the join needs; the last two are the rules seams.
internal class RoomForecastRepository(
    private val database: CfoDatabase,
    private val accounts: AccountRepository,
    private val streams: StreamRepository,
    private val engine: ForecastEngine,
    private val seasonality: SeasonalityEngine,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
    /**
     * AI-VEH's predicted costs (issue 10.4; §12). A flow rather than the whole repository, because
     * the forecast needs one thing from vehicles and should not be able to reach for more.
     */
    private val vehicleOutflows: Flow<List<VehicleOutflow>> = flowOf(emptyList()),
    private val rules: ForecastRules = ForecastRules(),
    private val seasonalityRules: SeasonalityRules = SeasonalityRules(),
) : ForecastRepository {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeForecast(): Flow<Result<CashFlowForecast, AppError>> =
        activeProfileId.flatMapLatest { profileId ->
            val today = clock.today()
            val ledger =
                database.transactionDao().observeNatureCandidates(
                    profileId,
                    today.minusDays(rules.lookbackDays.toLong()).toString(),
                    today.plusDays(rules.horizonDays.toLong()).toString(),
                )
            val context =
                combine(
                    accounts.observeAccounts(),
                    streams.observeStreams(),
                    database.recurringRuleDao().observeForProfile(profileId),
                    database.categoryDao().observeForProfile(profileId),
                    database.transactionDao().observeFirstBookedIsoDate(profileId),
                ) { accountList, streamProfile, recurring, categories, firstDate ->
                    Context(
                        accountList,
                        (streamProfile as? Ok)?.value,
                        recurring,
                        categories.associate { it.id to it.name },
                        firstDate,
                    )
                }
            val history =
                database.transactionDao().observeMonthlyCategorySpend(
                    profileId,
                    today.withDayOfMonth(1).minusMonths(seasonalityRules.historyMonths.toLong()).toString(),
                    today.withDayOfMonth(1).minusDays(1).toString(),
                )
            combine(context, ledger, history, vehicleOutflows) { ctx, rows, months, vehicles ->
                forecastOf(today, ctx, rows, months, vehicles)
            }.flowOn(dispatchers.io)
        }

    /**
     * One forecast from one consistent read: AI-SEAS first, then AI-FCT with its result (issue 9.3).
     * Why:    the seasonal factor is weighted by the **same** everyday rows the forecast predicts
     *         from, so the two engines cannot disagree about what "everyday" means.
     * Result: the forecast, or the first engine's refusal. Input: [today]; [ctx]; [rows] — the ledger
     *         from the lookback's start to the horizon's end; [history] — closed months per category.
     * Output: `Result<CashFlowForecast, AppError>`.
     */
    private fun forecastOf(
        today: LocalDate,
        ctx: Context,
        rows: List<NatureCandidateRow>,
        history: List<MonthlyCategorySpendRow>,
        vehicles: List<VehicleOutflow>,
    ): Result<CashFlowForecast, AppError> {
        val liquid = rows.filter(::movesLiquidMoney)
        val everyday = everydayRows(liquid, today, Obligations.of(ctx.recurring), fixedStreams(ctx.streams).keys)
        val input = inputOf(today, ctx, liquid, everyday)
        val withVehicles = input.copy(oneOffs = input.oneOffs + vehicleItems(vehicles))
        return when (val seasonal = seasonality.index(seasonalityInput(today, ctx, everyday, history))) {
            is Err -> Err(seasonal.error)
            is Ok -> engine.forecast(withVehicles.copy(seasonality = seasonal.value))
        }
    }

    /**
     * AI-SEAS's input: the closed-month history, and the lookback's everyday spend per category.
     * Why:    the lookback is the forecast's own — from the ledger's first day or ninety days back,
     *         whichever is later, to yesterday — so the factor divides out exactly the season the
     *         daily base was measured in. With no ledger yet there is no lookback, and every month
     *         is ×1.
     * Result: the input. Input: [today]; [ctx]; [everyday] rows; [history]. Output: [SeasonalityInput].
     */
    private fun seasonalityInput(
        today: LocalDate,
        ctx: Context,
        everyday: List<NatureCandidateRow>,
        history: List<MonthlyCategorySpendRow>,
    ): SeasonalityInput {
        val end = today.minusDays(1)
        val start = ctx.firstDate?.let { maxOf(LocalDate.parse(it), today.minusDays(rules.lookbackDays.toLong())) }
        val hasLookback = start != null && start <= end
        return SeasonalityInput(
            history =
                history.map {
                    CategoryMonthSpend(
                        it.categoryId,
                        it.categoryId?.let(ctx.categoryNames::get),
                        YearMonth.parse(it.monthKey),
                        Money(it.spentMinor),
                    )
                },
            lookback =
                if (!hasLookback) {
                    emptyList()
                } else {
                    everyday.groupBy { it.categoryId }.map { (id, spent) ->
                        CategorySpend(id, id?.let(ctx.categoryNames::get), Money(-spent.sumOf { it.amountMinor }))
                    }
                },
            lookbackStart = if (hasLookback) start!! else end,
            lookbackEnd = end,
            months = (1L..rules.horizonDays).map { YearMonth.from(today.plusDays(it)) }.distinct(),
            nowUtcMillis = clock.nowUtcMillis(),
            rules = seasonalityRules,
        )
    }

    /**
     * Builds the engine's input from one consistent read.
     * Result: the input, without seasonality (the caller adds AI-SEAS's result). Input: [today];
     *         [ctx]; [liquid] — the ledger's liquid-money rows from the lookback's start to the
     *         horizon's end; [everyday] — the lookback's everyday rows. Output: [ForecastInput].
     * Changelog: 2026-09-19 — Created for issue 9.2; issue 9.3 passes the rows in pre-filtered.
     */
    private fun inputOf(
        today: LocalDate,
        ctx: Context,
        liquid: List<NatureCandidateRow>,
        everyday: List<NatureCandidateRow>,
    ): ForecastInput {
        val live =
            ctx.recurring.filter {
                it.isConfirmed && it.dismissedAtUtcMillis == null && it.deletedAtUtcMillis == null
            }
        val fixed = fixedStreams(ctx.streams)
        val future = liquid.filter { LocalDate.parse(it.bookedOnIsoDate) > today }
        return ForecastInput(
            today = today,
            openingBalance =
                ctx.accounts.filter(
                    ::isLiquidAccount,
                ).fold(Money.ZERO) { sum, account -> sum + account.balance },
            commitments =
                live.mapNotNull(::commitmentOf) +
                    fixed.map {
                            (key, amount) ->
                        fixedCommitment(key, amount, today, ctx)
                    },
            oneOffs = future.map { row -> oneOffOf(row, ctx) },
            dailySpend =
                everyday.groupBy { it.bookedOnIsoDate }.map { (date, dayRows) ->
                    DailySpend(LocalDate.parse(date), Money(-dayRows.sumOf { it.amountMinor }))
                },
            historyStart = ctx.firstDate?.let(LocalDate::parse),
            seed = today.toEpochDay(),
            nowUtcMillis = clock.nowUtcMillis(),
            rules = rules,
        )
    }

    /**
     * The everyday outflows of the lookback, with the scheduled ones taken out.
     * Why:    a payment to a confirmed recurring merchant, or in a category AI-CLS scored FIXED, is
     *         projected as a scheduled item; leaving it in the everyday pool as well would count it
     *         twice — once on its day and once smeared across every day. Income is not spend, and
     *         today is not over, so both are out.
     *         Issue 9.3 made this return rows rather than per-day sums, so AI-SEAS can weight the same
     *         pool by category.
     * Result: the everyday rows. Input: [rows] — liquid-money rows; [today]; [obligations];
     *         [fixedCategories]. Output: `List<NatureCandidateRow>`.
     */
    private fun everydayRows(
        rows: List<NatureCandidateRow>,
        today: LocalDate,
        obligations: Obligations,
        fixedCategories: Set<String>,
    ): List<NatureCandidateRow> =
        rows
            .filter { it.amountMinor < 0L && LocalDate.parse(it.bookedOnIsoDate) < today }
            .filter { obligations.merchantKeyOf(it.merchant) == null && it.categoryId !in fixedCategories }

    /**
     * Whether a row moves money into or out of the liquid balance.
     * Why:    only rows on a bank or cash account touch it, and a transfer between two of them moves
     *         nothing — withdrawing cash is not spending it. A transfer to a card or an investment
     *         does leave (ADR-0043).
     * Result: `true` when the row counts. Input: [row]. Output: [Boolean].
     */
    private fun movesLiquidMoney(row: NatureCandidateRow): Boolean {
        val liquid = LIQUID_ACCOUNT_TYPES.map { it.storedValue }
        val internal = row.type == TRANSFER && row.counterpartAccountType in liquid
        return row.accountType in liquid && !internal
    }

    /**
     * The categories whose streams are projected as FIXED, and their typical month.
     * Why:    a stream measured, prior-estimated or pinned FIXED is an obligation the user has not
     *         confirmed as a rule; projecting it keeps the rent in the forecast even before the
     *         detector has proposed it. A **known obligation** is already a confirmed rule — projected
     *         as one — and a `recurring:` stream is that rule's merchant, so neither is projected twice.
     * Result: category id → typical month, positive. Input: [profile]. Output: `Map<String, Money>`.
     */
    private fun fixedStreams(profile: StreamProfile?): Map<String, Money> =
        profile?.streams.orEmpty()
            .filter { it.streamClass == StreamClass.FIXED && it.basis != StreamBasis.KNOWN_OBLIGATION }
            .filterNot { it.streamKey.startsWith(StreamRepository.RECURRING_PREFIX) }
            .associate { it.streamKey to it.typicalMonthly }

    /**
     * A FIXED stream as a monthly commitment on its usual day.
     * Why:    anchored on that day in a **January** a year back, which has 31 days, so a stream that
     *         lands on the 31st stays on the 31st wherever the month has one; the engine rolls the
     *         stale anchor forward. With no measured day (a prior-only stream) it lands on the 1st.
     * Result: the commitment. Input: [key] — category id; [typical]; [today]; [ctx].
     */
    private fun fixedCommitment(
        key: String,
        typical: Money,
        today: LocalDate,
        ctx: Context,
    ): Commitment {
        val verdict = ctx.streams?.streams?.firstOrNull { it.streamKey == key }
        val day = verdict?.metrics?.modalDayOfMonth ?: 1
        return Commitment(
            label = ctx.categoryNames[key],
            amount = Money(-typical.minor),
            cadence = Cadence.MONTHLY,
            nextDue = LocalDate.of(today.year - 1, 1, day),
            source = ItemSource.FIXED_STREAM,
        )
    }

    /**
     * A confirmed rule as a commitment, or `null` for a cadence the forecast cannot project.
     * Result: the commitment. Input: [rule]. Output: `Commitment?`.
     */
    private fun commitmentOf(rule: RecurringRuleEntity): Commitment? {
        val cadence = Cadence.entries.firstOrNull { it.name.equals(rule.cadence, ignoreCase = true) } ?: return null
        return Commitment(
            label = rule.name,
            amount = Money(rule.amountMinor),
            cadence = cadence,
            nextDue = LocalDate.parse(rule.nextDueIsoDate),
            source = ItemSource.RECURRING_RULE,
        )
    }

    /** A future-dated row as a scheduled one-off. Input: [row]; [ctx]. Output: [ScheduledItem]. */
    private fun oneOffOf(
        row: NatureCandidateRow,
        ctx: Context,
    ): ScheduledItem =
        ScheduledItem(
            date = LocalDate.parse(row.bookedOnIsoDate),
            amount = Money(row.amountMinor),
            label = row.merchant ?: row.categoryId?.let(ctx.categoryNames::get),
            source = ItemSource.FUTURE_DATED,
        )

    /** What one consistent read of the non-ledger sources gives. */
    private data class Context(
        val accounts: List<Account>,
        val streams: StreamProfile?,
        val recurring: List<RecurringRuleEntity>,
        val categoryNames: Map<String, String>,
        val firstDate: String?,
    )

    private companion object {
        const val TRANSFER = "transfer"
    }
}

/**
 * Whether [account] is a live, counted, liquid account (bank or cash).
 * Why:    a pure test of one row, so it sits outside the repository class (issue 9.3 moved it here
 *         when the class gained AI-SEAS's input builder).
 * Result: `true` when the account's balance is part of the opening. Input: [account]. Output: [Boolean].
 */
private fun isLiquidAccount(account: Account): Boolean =
    account.type in LIQUID_ACCOUNT_TYPES && account.includeInNetWorth && !account.isArchived

/**
 * AI-VEH's predictions as scheduled outflows (issue 10.4; §12 into 9.2's horizon).
 *
 * Why:  a service everyone knows is coming, and that nobody put in the forecast, is exactly the
 *       crunch day the forecast exists to warn about. They are **negative** amounts, because the
 *       horizon signs an outflow that way, and they carry the vehicle's name so the lowest-day
 *       explanation can say which vehicle it was.
 * Result: one item per prediction. **Nothing is filtered by date here**: the engine builds only the
 *         days inside its own horizon, so an item beyond it is already dropped — and a second
 *         horizon test in this file would be a second definition of the window. The first draft had
 *         one, and a deliberate break of it passed every test, which is how it was found to be
 *         unreachable rather than merely untested.
 * Input:  [vehicles]. Output: `List<ScheduledItem>`.
 * Changelog: 2026-09-26 — Created for issue 10.4.
 */
private fun vehicleItems(vehicles: List<VehicleOutflow>): List<ScheduledItem> =
    vehicles.map { entry ->
        ScheduledItem(
            date = LocalDate.parse(entry.outflow.isoDate),
            amount = Money(-entry.outflow.amount.minor),
            label = entry.vehicleLabel,
            source = ItemSource.VEHICLE_PREDICTION,
        )
    }
