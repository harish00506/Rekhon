package com.aicfo.data.repository

import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.database.CfoDatabase
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
import com.aicfo.domain.engines.stream.StreamBasis
import com.aicfo.domain.engines.stream.StreamClass
import com.aicfo.domain.engines.stream.StreamProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import java.time.LocalDate

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
@Suppress("LongParameterList") // Eight, each one input the join needs; the last is the rules seam.
internal class RoomForecastRepository(
    private val database: CfoDatabase,
    private val accounts: AccountRepository,
    private val streams: StreamRepository,
    private val engine: ForecastEngine,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
    private val rules: ForecastRules = ForecastRules(),
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
            combine(context, ledger) { ctx, rows -> engine.forecast(inputOf(today, ctx, rows)) }.flowOn(dispatchers.io)
        }

    /**
     * Builds the engine's input from one consistent read.
     * Result: the input. Input: [today]; [ctx]; [rows] — the ledger from the lookback's start to the
     * horizon's end. Output: [ForecastInput].
     */
    private fun inputOf(
        today: LocalDate,
        ctx: Context,
        rows: List<NatureCandidateRow>,
    ): ForecastInput {
        val live =
            ctx.recurring.filter {
                it.isConfirmed && it.dismissedAtUtcMillis == null && it.deletedAtUtcMillis == null
            }
        val obligations = Obligations.of(ctx.recurring)
        val fixed = fixedStreams(ctx.streams)
        val (past, future) = rows.filter(::movesLiquidMoney).partition { LocalDate.parse(it.bookedOnIsoDate) <= today }
        return ForecastInput(
            today = today,
            openingBalance = ctx.accounts.filter(::isLiquid).fold(Money.ZERO) { sum, account -> sum + account.balance },
            commitments =
                live.mapNotNull(::commitmentOf) +
                    fixed.map {
                            (key, amount) ->
                        fixedCommitment(key, amount, today, ctx)
                    },
            oneOffs = future.map { row -> oneOffOf(row, ctx) },
            dailySpend = everydaySpend(past, today, obligations, fixed.keys),
            historyStart = ctx.firstDate?.let(LocalDate::parse),
            seed = today.toEpochDay(),
            nowUtcMillis = clock.nowUtcMillis(),
            rules = rules,
        )
    }

    /**
     * The everyday outflows of the lookback, per day, with the scheduled ones taken out.
     * Why:    a payment to a confirmed recurring merchant, or in a category AI-CLS scored FIXED, is
     *         projected as a scheduled item; leaving it in the everyday pool as well would count it
     *         twice — once on its day and once smeared across every day. Income is not spend, and
     *         today is not over, so both are out.
     * Result: one [DailySpend] per day with any. Input: [rows] — liquid-money rows up to today;
     *         [today]; [obligations]; [fixedCategories]. Output: `List<DailySpend>`.
     */
    private fun everydaySpend(
        rows: List<NatureCandidateRow>,
        today: LocalDate,
        obligations: Obligations,
        fixedCategories: Set<String>,
    ): List<DailySpend> =
        rows
            .filter { it.amountMinor < 0L && LocalDate.parse(it.bookedOnIsoDate) < today }
            .filter { obligations.merchantKeyOf(it.merchant) == null && it.categoryId !in fixedCategories }
            .groupBy { it.bookedOnIsoDate }
            .map { (date, dayRows) -> DailySpend(LocalDate.parse(date), Money(-dayRows.sumOf { it.amountMinor })) }

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

    /** Whether [account] is a live, counted, liquid account (bank or cash). */
    private fun isLiquid(account: Account): Boolean =
        account.type in LIQUID_ACCOUNT_TYPES && account.includeInNetWorth && !account.isArchived

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
