package com.aicfo.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aicfo.core.common.Err
import com.aicfo.core.crypto.SessionLock
import com.aicfo.data.repository.NetWorthRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import javax.inject.Provider

/**
 * Writes the daily net-worth snapshot (issue 2.6; FR-ACC-005, SEC-002, P-04).
 *
 * Why:  FR-ACC-005 requires net worth "snapshotted daily", and a snapshot nobody takes is a trend
 *       that does not exist. This is the app's **first background work** — everything before it ran
 *       because a screen asked.
 *
 *       **It must not touch the database while the app is locked, and that is not a style
 *       preference.** `CoreModule.provideDatabase` *throws* when the session is locked (SEC-002):
 *       injecting a repository here on a locked device would take the whole process down from a
 *       background job the user never started. So the lock is checked first, and the repository
 *       arrives as a [Provider] so the graph is not even built until that check passes.
 *
 *       Returning `retry()` rather than `failure()` while locked is the honest outcome: nothing is
 *       wrong, the work simply cannot be done yet. And nothing is lost — the repository backfills
 *       every day it missed the next time it does run, which is what makes deferring safe.
 * What: one suspending call into [NetWorthRepository.snapshotUpToToday].
 * Result: the dashboard has a figure to show, and issue 6.6 has a series to chart.
 * Changelog: 2026-08-01 — Created for issue 2.6.
 *
 * **No network, on any path** (P-04): every input is a local row, so this works identically in
 * airplane mode.
 *
 * Input:  [context], [params] — supplied by WorkManager; [sessionLock] — the SEC-002 gate;
 *         [repository] — deliberately a `Provider`, see above.
 * Output: a worker WorkManager can run.
 */
@HiltWorker
class NetWorthSnapshotWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val sessionLock: SessionLock,
        private val repository: Provider<NetWorthRepository>,
    ) : CoroutineWorker(context, params) {
        /**
         * Repairs any invalidated history, then takes today's snapshot, backfilling anything missed.
         *
         * **The repair runs first, and the order matters.** A back-dated transaction makes past days
         * wrong, and `snapshotUpToToday` will not rewrite a day it has already recorded (ADR-0012).
         * Correcting the past before extending it means the new day is computed against a series
         * that is already consistent; doing it the other way would leave one run's gap between the
         * two halves of the same figure.
         *
         * **This is the only thing that calls the repair, deliberately.** The alternative — having
         * every write path that can back-date tell the net-worth series about it — would put
         * knowledge of a reporting table into the ledger, and would be one more thing a future write
         * path could forget. The repair derives what is stale from the rows themselves, so it does
         * not need telling.
         * Result: `success()` when both steps ran (including zero days — nothing to do is not a
         *         failure); `retry()` while the app is locked or when either write failed, since
         *         both are worth another attempt rather than giving up on the series.
         * Input:  none. Output: [Result].
         * Changelog: 2026-08-06 — Added the repair step; see ADR-0012.
         */
        override suspend fun doWork(): Result {
            // Before anything injects the database. A locked session makes the gated provider throw.
            if (!sessionLock.isUnlocked.value) return Result.retry()

            // The day counts they return are deliberately dropped rather than logged: §21.6 bans
            // amounts from logs, and `net_worth_snapshot` is the record of what was written.
            val store = repository.get()
            if (store.repairStaleHistory() is Err) return Result.retry()
            return if (store.snapshotUpToToday() is Err) Result.retry() else Result.success()
        }

        companion object {
            /** The unique name, so rescheduling on every launch replaces rather than accumulates. */
            const val WORK_NAME = "net-worth-daily-snapshot"

            /**
             * Schedules the daily job, keeping any existing one.
             *
             * Why:    `KEEP`, not `REPLACE` — the app schedules this on every process start, and
             *         replacing would reset the period each time, so a user who opens the app most
             *         mornings would push the run back indefinitely and never get a snapshot.
             *
             *         No constraints: this needs no network and no charger (P-04), and adding one
             *         would only make the series patchier.
             * Result: the job exists and repeats daily.
             * Input:  [context] — any context; WorkManager resolves the singleton itself.
             * Output: none.
             */
            fun schedule(context: Context) {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<NetWorthSnapshotWorker>(1, TimeUnit.DAYS).build(),
                )
            }
        }
    }
