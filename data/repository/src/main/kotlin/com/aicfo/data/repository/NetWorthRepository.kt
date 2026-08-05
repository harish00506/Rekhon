package com.aicfo.data.repository

import androidx.room.withTransaction
import com.aicfo.core.common.AppError
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.Result
import com.aicfo.core.common.runCatchingToResult
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.dao.AccountWithBalance
import com.aicfo.core.database.entity.NetWorthSnapshotEntity
import com.aicfo.core.model.AccountType
import com.aicfo.core.model.EngineProvenance
import com.aicfo.core.model.Money
import com.aicfo.domain.engines.networth.AccountBalance
import com.aicfo.domain.engines.networth.NetWorthEngine
import com.aicfo.domain.engines.networth.NetWorthInput
import com.aicfo.domain.engines.networth.NetWorthResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Computes and stores the daily net worth (issue 2.6; FR-ACC-005, ARC-005, DB-003).
 *
 * Why:  FR-ACC-005 asks for net worth to be "snapshotted daily", and the word that does the work is
 *       *stored*. Recomputing history on demand would let it move under the user — archive an
 *       account, correct an opening balance, back-date a transaction, and last March's figure
 *       silently becomes a different number. A row written once and never recomputed is what makes
 *       issue 6.6's trend chart a record rather than a re-derivation.
 * What: one read for the dashboard, one write for the daily job, and the as-of computation both use.
 * Result: an exact, reproducible series, and a headline figure with a derivation.
 * Changelog: 2026-08-01 — Created for issue 2.6.
 *
 * **The only class in this issue allowed to touch a DAO (ARC-005).** The engine above it is pure
 * Kotlin and never sees Room; the ViewModel below it sees [NetWorthResult] and never sees an entity.
 *
 * **It computes nothing itself (P-03).** Every figure comes from [NetWorthEngine]; this class
 * decides only *which accounts* to hand it and *when*.
 */
interface NetWorthRepository {
    /**
     * Observes the newest stored snapshot for whichever profile is active.
     * Why:    the same `activeProfileId` seam the two repositories beside it use, so the demo gets
     *         its own net worth and the dashboard follows without knowing demo mode exists.
     * Result: emits on every change. **`null` until the first snapshot has been taken** — a real
     *         state for a user who onboarded a minute ago, and one the screen must render as "not
     *         computed yet" rather than as ₹0 (P-03).
     * Input:  none. Output: `Flow<NetWorthResult?>`.
     */
    fun observeLatest(): Flow<NetWorthResult?>

    /**
     * Observes net worth **as it stands right now**, recomputed as accounts and transactions change.
     *
     * Why:    this is what a screen showing a headline figure wants, and [observeLatest] is not.
     *         The stored snapshot is a historical record — correct, and a day stale by design. A
     *         user who deletes an account and watches the total sit unchanged concludes the app is
     *         broken, which is exactly what happened the first time this was driven on a device.
     *
     *         Both go through the same DAO filter, so the figure on screen and the figure tonight's
     *         job stores can never disagree about which accounts count.
     * Result: emits on every change to an account or a transaction. `null` only if the accounts
     *         cannot be read; a profile with no accounts is a zero, not an absence.
     * Input:  none (the day comes from the injected `Clock`, TIM-001).
     * Output: `Flow<NetWorthResult>`.
     */
    fun observeCurrent(): Flow<NetWorthResult>

    /**
     * Computes net worth for one day without storing it.
     * Why:    what the dashboard would use for a live figure, and what [snapshotUpTo] calls per day.
     *         Exposed separately so a caller can ask "what is it right now?" without writing a row.
     * Result: `Ok(result)` — a zero when the profile has no counted accounts — or `Err(Storage)`.
     * Input:  [asOfIsoDate] — ISO `yyyy-MM-dd` in the profile zone (TIM-002).
     * Output: `Result<NetWorthResult, AppError>`.
     */
    suspend fun computeAsOf(asOfIsoDate: String): Result<NetWorthResult, AppError>

    /**
     * Writes a snapshot for every day since the last one, up to today (FR-ACC-005).
     *
     * Why:    **the backfill is what makes a daily job survive a phone that was off.** WorkManager
     *         guarantees a job runs, not that it runs on any particular day — a device idle over a
     *         weekend, or an app locked when the worker fired, leaves gaps. Walking forward from the
     *         last stored day closes them, and each day is recomputed from the transactions booked on
     *         or before it, so a backfilled figure is the same one that day would have produced.
     *
     *         **A first ever run writes today only.** There is no earlier snapshot to walk from, and
     *         reconstructing an arbitrary stretch of history the user never had the app for would be
     *         inventing data (P-03).
     * Result: `Ok(n)` — how many days were written, `0` when today is already recorded — or
     *         `Err(Storage)` with **nothing** written; the days land in one transaction.
     * Input:  none (the day comes from the injected `Clock`, TIM-001).
     * Output: `Result<Int, AppError>`.
     */
    suspend fun snapshotUpToToday(): Result<Int, AppError>

    companion object {
        /**
         * How many days a single backfill will write at most.
         *
         * Why: a device restored from a year-old backup, or a clock jumped forward, would otherwise
         *      make one job run compute and write hundreds of rows. The cap keeps the worker's
         *      cost bounded; the next run continues from wherever this one stopped, so nothing is
         *      lost — it simply takes more than one run to catch up.
         */
        const val MAX_BACKFILL_DAYS = 90
    }
}

/**
 * The Room-backed [NetWorthRepository].
 * Why:    ARC-003 — one public interface, an internal implementation, assembled by the DI graph.
 * Result: the implementation injected into the dashboard and the snapshot worker.
 * Changelog: 2026-08-01 — Created for issue 2.6.
 *
 * Input:  [database] — taken whole because the backfill writes several days in one
 *         `withTransaction`; [engine] — computes every figure (P-03); [clock] — supplies today in
 *         the profile zone and the provenance instant, never a wall-clock read (TIM-001);
 *         [dispatchers]; [activeProfileId] — which profile to read and write.
 * Output: a working repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RoomNetWorthRepository(
    private val database: CfoDatabase,
    private val engine: NetWorthEngine,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    private val activeProfileId: Flow<String>,
) : NetWorthRepository {
    override fun observeCurrent(): Flow<NetWorthResult> =
        activeProfileId
            .flatMapLatest { profileId ->
                // clock.today() inside the lambda, not captured outside: the flow may outlive
                // midnight, and re-reading is what makes it roll over with the profile's day.
                val today = clock.today().toString()
                database.accountDao().observeBalancesForNetWorth(profileId, today)
                    .map { rows -> computeFrom(rows, today) }
            }
            .flowOn(dispatchers.io)

    override fun observeLatest(): Flow<NetWorthResult?> =
        // flatMapLatest, not map: entering or leaving the demo must *switch* which query is being
        // observed, cancelling the previous one — the same reasoning as the repositories beside it.
        activeProfileId
            .flatMapLatest { profileId -> database.netWorthSnapshotDao().observeLatest(profileId) }
            .map { row -> row?.toResult() }
            .flowOn(dispatchers.io)

    override suspend fun computeAsOf(asOfIsoDate: String): Result<NetWorthResult, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult { balancesFor(activeProfileId.first(), asOfIsoDate) }
                .flatMapToResult(asOfIsoDate)
        }

    override suspend fun snapshotUpToToday(): Result<Int, AppError> =
        withContext(dispatchers.io) {
            runCatchingToResult {
                val profileId = activeProfileId.first()
                val today = clock.today()
                val days = missingDaysUpTo(profileId, today)
                if (days.isEmpty()) return@runCatchingToResult 0

                val now = clock.nowUtcMillis()
                val rows = days.map { day -> snapshotRow(profileId, day.toString(), now) }
                // One transaction: a backfill that landed half its days would leave a gap the next
                // run cannot see, because `findLatestAsOfDate` would report the newest of them.
                database.withTransaction { database.netWorthSnapshotDao().upsertAll(rows) }
                rows.size
            }
        }

    /**
     * The days that still need a snapshot, oldest first.
     * Why:    the backfill's whole decision, in one place. It starts the day *after* the last stored
     *         one — that day is already recorded and recomputing it would overwrite a historical
     *         figure with one derived from today's accounts, which is the drift the stored row
     *         exists to prevent.
     * Result: `[today]` on a first ever run; empty when today is already stored; at most
     *         [NetWorthRepository.MAX_BACKFILL_DAYS] entries.
     * Input:  [profileId]; [today] — from the injected `Clock`. Output: the days to write.
     */
    private suspend fun missingDaysUpTo(
        profileId: String,
        today: LocalDate,
    ): List<LocalDate> {
        val latest =
            database.netWorthSnapshotDao().findLatestAsOfDate(profileId)
                ?: return listOf(today)
        val lastStored = runCatching { LocalDate.parse(latest) }.getOrNull() ?: return listOf(today)
        // A stored date in the future means the clock moved backwards (a manual change, or a time
        // zone correction). Recording nothing is the honest response: the future rows are already
        // there, and rewriting them would replace a figure with an older one.
        if (!lastStored.isBefore(today)) return emptyList()

        return generateSequence(lastStored.plusDays(1)) { it.plusDays(1) }
            .takeWhile { !it.isAfter(today) }
            .take(NetWorthRepository.MAX_BACKFILL_DAYS)
            .toList()
    }

    /**
     * Computes one day and turns it into a row.
     * Why:    the derived id — `<profile>:networth:<date>` — is what makes the write idempotent
     *         under REPLACE, so a job that runs twice in a day updates one row rather than leaving
     *         two figures for one date. The same mechanism `budgetId()` uses.
     * Result: a [NetWorthSnapshotEntity] for that day.
     * Input:  [profileId], [asOfIsoDate], [nowUtcMillis]. Output: the row.
     */
    private suspend fun snapshotRow(
        profileId: String,
        asOfIsoDate: String,
        nowUtcMillis: Long,
    ): NetWorthSnapshotEntity {
        val result = computeFrom(balancesFor(profileId, asOfIsoDate), asOfIsoDate)
        return NetWorthSnapshotEntity(
            id = netWorthSnapshotId(profileId, asOfIsoDate),
            profileId = profileId,
            asOfIsoDate = asOfIsoDate,
            assetsMinor = result.assets.minor,
            liabilitiesMinor = result.liabilities.minor,
            netWorthMinor = result.netWorth.minor,
            engineId = result.provenance.engineId,
            engineVersion = result.provenance.engineVersion,
            computedAtUtcMillis = result.provenance.computedAtUtcMillis,
            createdAtUtcMillis = nowUtcMillis,
            updatedAtUtcMillis = nowUtcMillis,
        )
    }

    /** Result: the counted balances as at [asOfIsoDate]. Input: [profileId], [asOfIsoDate]. */
    private suspend fun balancesFor(
        profileId: String,
        asOfIsoDate: String,
    ): List<AccountWithBalance> = database.accountDao().balancesForNetWorth(profileId, asOfIsoDate)

    /**
     * Hands the balances to the engine.
     * Why:    **`mapNotNull`, not `map`** — a row whose stored `type` this build does not recognise
     *         is dropped rather than thrown on, so an old build reading a newer database reports a
     *         net worth over the accounts it understands instead of crashing the dashboard. It is
     *         also the honest failure: a figure missing one unknown account is closer than none.
     * Result: the engine's answer.
     * Input:  [rows]; [asOfIsoDate]. Output: [NetWorthResult].
     */
    private fun computeFrom(
        rows: List<AccountWithBalance>,
        asOfIsoDate: String,
    ): NetWorthResult {
        val balances =
            rows.mapNotNull { row ->
                AccountType.fromStored(row.account.type)?.let { type ->
                    AccountBalance(
                        accountId = row.account.id,
                        type = type,
                        balance = Money(row.account.openingBalanceMinor) + Money(row.movementMinor),
                    )
                }
            }
        val outcome =
            engine.compute(
                NetWorthInput(
                    balances = balances,
                    asOfIsoDate = asOfIsoDate,
                    nowUtcMillis = clock.nowUtcMillis(),
                ),
            )
        // check, not a silent fallback: the engine fails only on an overflow no real portfolio can
        // reach, and §21.6 reserves crashes for programmer errors — which that would be.
        return (outcome as? Ok)?.value ?: error("The net-worth engine refused a computed portfolio: $outcome")
    }

    /** Result: `Ok(result)` for a successful read, else the read's error. Input: the receiver, [asOf]. */
    private fun Result<List<AccountWithBalance>, AppError>.flatMapToResult(
        asOf: String,
    ): Result<NetWorthResult, AppError> =
        when (this) {
            is Ok -> Ok(computeFrom(value, asOf))
            is Err -> this
        }
}

/**
 * Rebuilds an engine result from a stored row.
 * Why:    the dashboard renders [NetWorthResult], and the row holds every field it needs — including
 *         the engine id and version, so a figure from an older engine still says which one produced
 *         it (AI-ARC-006). Nothing is recomputed here: that is the whole point of storing it.
 * Result: the [NetWorthResult] that was computed on that day.
 * Input:  the receiver. Output: [NetWorthResult].
 * Changelog: 2026-08-01 — Created for issue 2.6.
 */
internal fun NetWorthSnapshotEntity.toResult(): NetWorthResult =
    NetWorthResult(
        asOfIsoDate = asOfIsoDate,
        assets = Money(assetsMinor),
        liabilities = Money(liabilitiesMinor),
        netWorth = Money(netWorthMinor),
        provenance =
            EngineProvenance(
                engineId = engineId,
                engineVersion = engineVersion,
                computedAtUtcMillis = computedAtUtcMillis,
                inputWindow = asOfIsoDate,
            ),
    )

/**
 * Result: the derived id for one day's snapshot. Input: [profileId], [asOfIsoDate].
 * Output: a stable [String] — the same day always names the same row, which is what makes the
 *         daily job idempotent.
 * Changelog: 2026-08-01 — Created for issue 2.6.
 */
internal fun netWorthSnapshotId(
    profileId: String,
    asOfIsoDate: String,
): String = "$profileId:networth:$asOfIsoDate"
