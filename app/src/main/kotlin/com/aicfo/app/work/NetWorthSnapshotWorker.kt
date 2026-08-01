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
         * Takes today's snapshot, backfilling anything missed.
         * Result: `success()` when the days were written (including zero — today already recorded is
         *         not a failure); `retry()` while the app is locked or when the write failed, since
         *         both are worth another attempt rather than giving up on the series.
         * Input:  none. Output: [Result].
         */
        override suspend fun doWork(): Result {
            // Before anything injects the database. A locked session makes the gated provider throw.
            if (!sessionLock.isUnlocked.value) return Result.retry()

            // The day count it returns is deliberately dropped rather than logged: §21.6 bans
            // amounts from logs, and `net_worth_snapshot` is the record of what was written.
            return if (repository.get().snapshotUpToToday() is Err) Result.retry() else Result.success()
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
