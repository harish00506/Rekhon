package com.aicfo.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aicfo.core.common.Err
import com.aicfo.core.crypto.SessionLock
import com.aicfo.data.repository.MarketPriceRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import javax.inject.Provider

/**
 * Brings gold and crypto prices up to date in the background (issue 6.5; §16.1, API-002, P-01, P-04).
 *
 * Why:  a price the user typed in July still reads as fact in September unless something refreshes
 *       it. This is that something — and it is the **only** scheduled job in the app that can reach
 *       a network at all.
 *
 *       **It checks no consent itself.** `MarketPriceRepository` is the single chokepoint, exactly
 *       as `SmsRepository` is for the inbox: this worker calling `refresh()` on a phone whose owner
 *       has not granted MARKET_DATA returns `Ok(0)` having opened no socket. Duplicating the gate
 *       here would be a second place for it to drift, and the one that drifts is always the copy.
 *
 *       **The lock check comes first, as in [SmsScanWorker].** `CoreModule.provideDatabase` throws
 *       when the session is locked (SEC-002), so injecting the repository on a locked device would
 *       take the process down from a job the user never started — which is why it arrives as a
 *       [Provider].
 * What: one suspending call into [MarketPriceRepository.refresh].
 * Result: a stored price that ages against the market rather than against nothing.
 * Changelog: 2026-08-29 — Created for issue 6.5.
 *
 * **In a shipping build this does nothing, by construction.** There is no backend proxy, so
 * `NetworkModule` binds the unconfigured client and `refresh()` returns without a socket. The job
 * exists so that the day a proxy is configured, no scheduling code has to be written under time
 * pressure — and so its behaviour is under test now rather than then.
 *
 * Input:  [context], [params] — supplied by WorkManager; [sessionLock] — the SEC-002 gate;
 *         [repository] — deliberately a `Provider`, see above.
 * Output: a worker WorkManager can run.
 */
@HiltWorker
class MarketPriceWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val sessionLock: SessionLock,
        private val repository: Provider<MarketPriceRepository>,
    ) : CoroutineWorker(context, params) {
        /**
         * Refreshes whatever is due.
         *
         * Result: `success()` when the refresh ran, **including when it updated nothing** — no
         *         consent, no keyed holdings, nothing past its interval, and a proxy that did not
         *         answer are all the feature correctly doing nothing, and reporting them as failures
         *         would have WorkManager backing off for ever against a permanent condition.
         *         `retry()` while the app is locked or when the database itself failed; in both
         *         cases nothing was written, so nothing was missed.
         * Input:  none. Output: [Result].
         */
        override suspend fun doWork(): Result {
            // Before anything injects the database. A locked session makes the gated provider throw.
            if (!sessionLock.isUnlocked.value) return Result.retry()

            // The count is deliberately dropped rather than logged. §21.6 bans PII from logs, and
            // "how many instruments were repriced" is a fact about what this user owns.
            return if (repository.get().refresh() is Err) Result.retry() else Result.success()
        }

        companion object {
            /** The unique name of the daily job, so rescheduling replaces rather than accumulates. */
            const val WORK_NAME = "market-price-refresh"

            /** The unique name of the on-open refresh, kept distinct so one cannot cancel the other. */
            const val OPEN_WORK_NAME = "market-price-refresh-now"

            /**
             * Schedules the daily refresh, keeping any existing one.
             *
             * Why:    `KEEP`, not `REPLACE`, for the reason [SmsScanWorker.schedule] gives — the app
             *         schedules this on every process start, and replacing would reset the period
             *         each time, so a user who opens the app most mornings would never reach a run.
             *
             *         **Scheduled unconditionally, even for a user who has not opted in**, for the
             *         same reason as the SMS scan: scheduling on grant and cancelling on revoke puts
             *         the consent rule in a second place and orphans a job on any path that forgets
             *         to cancel. A job that finds the gate closed opens no socket.
             *
             *         **Daily, not every fifteen minutes.** §16.1 gives crypto a fifteen-minute
             *         cadence, but it gives it *while the app is open* — which is what [refreshNow]
             *         covers. A quarter-hourly background job would wake the phone ninety-six times
             *         a day to reprice something nobody is looking at, and WorkManager's floor is
             *         fifteen minutes anyway. The daily period matches gold's end-of-day print; the
             *         rulebook's per-class interval then decides what is actually worth a call.
             *
             *         **The first job in this app to carry a constraint** ([NetworkType.CONNECTED]).
             *         The other seven must not have one: every one of them is pure local
             *         computation, and gating a net-worth snapshot or a budget alert on connectivity
             *         would stop the app working in airplane mode, which is the whole of P-04. This
             *         one is the exception precisely because a run with no network cannot do its job
             *         — and the constraint is a courtesy, not a gate, since a refresh without
             *         connectivity would simply fail and keep the stored price.
             * Result: the job exists and repeats daily.
             * Input:  [context] — any context; WorkManager resolves the singleton itself.
             * Output: none.
             */
            fun schedule(context: Context) {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<MarketPriceWorker>(1, TimeUnit.DAYS)
                        .setConstraints(connected())
                        .build(),
                )
            }

            /**
             * Asks for a refresh now — once per unlock (API-002).
             *
             * Why:    API-002 wants a refresh when the app is opened, and this is where crypto's
             *         fifteen-minute cadence actually lives: the user is looking at the screen, so
             *         the price in front of them is worth being current.
             *
             *         **No "already refreshed this session" flag**, deliberately. The repository's
             *         TTL gate is strictly tighter than any such flag — it knows when each
             *         instrument was last fetched, across process deaths, which a session flag does
             *         not. Adding one would be a second, weaker copy of a decision already made in
             *         the place that has the data to make it.
             *
             *         `KEEP` so that opening the app twice in a second enqueues one job rather than
             *         two racing for the same rows.
             * Result: a one-time job is enqueued; it will run when there is a network.
             * Input:  [context] — any context. Output: none.
             */
            fun refreshNow(context: Context) {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    OPEN_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<MarketPriceWorker>()
                        .setConstraints(connected())
                        .build(),
                )
            }

            /**
             * The one constraint in the app.
             * Result: work that waits for any connection. Input: none. Output: [Constraints].
             * Changelog: 2026-08-29 — Created for issue 6.5.
             */
            private fun connected(): Constraints =
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        }
    }
