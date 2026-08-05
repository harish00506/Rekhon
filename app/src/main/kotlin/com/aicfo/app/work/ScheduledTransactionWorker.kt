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
import com.aicfo.data.repository.TransactionRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import javax.inject.Provider

/**
 * Records that scheduled transactions have posted (issue 3.4; FR-TXN-010, SEC-002, P-04).
 *
 * Why:  FR-TXN-010 requires future-dated transactions to be supported and "excluded from actuals"
 *       until their date. This job is what marks the moment that stops being true — it stamps
 *       `posted_at_utc_millis` on every row whose booked day has arrived.
 *
 *       **It does not move money, and it must never be made to.** Every balance derives from
 *       `booked_on_iso_date <= today`, so a scheduled row starts counting on its own date whether
 *       or not this worker ever runs. That is deliberate: WorkManager can defer a job for hours
 *       under Doze, indefinitely on a powered-off device, and this one additionally declines to run
 *       at all while the app is locked. A design where the balance waited for the job would show a
 *       user the wrong number for every one of those reasons. See
 *       `docs/adr/0010-future-dated-posting.md`.
 *
 *       **The lock check comes first, exactly as in [NetWorthSnapshotWorker].**
 *       `CoreModule.provideDatabase` *throws* while the session is locked (SEC-002), so the
 *       repository arrives as a [Provider] and the graph is not built until that check passes.
 *       `retry()` rather than `failure()` while locked is the honest outcome — nothing is wrong,
 *       the work simply cannot be done yet, and nothing is lost because the statement behind
 *       [TransactionRepository.postDueTransactions] catches up on every day it missed.
 * What: one suspending call, whose SQL is idempotent by construction.
 * Result: a posted row is durably marked as posted, for issue 3.7's recurring series and for any
 *       later "posted today" surface to read.
 * Changelog: 2026-08-03 — Created for issue 3.4 (FR-TXN-010).
 *
 * **No network, on any path** (P-04): every input is a local row, so this works identically in
 * airplane mode.
 *
 * Input:  [context], [params] — supplied by WorkManager; [sessionLock] — the SEC-002 gate;
 *         [repository] — deliberately a `Provider`, see above.
 * Output: a worker WorkManager can run.
 */
@HiltWorker
class ScheduledTransactionWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val sessionLock: SessionLock,
        private val repository: Provider<TransactionRepository>,
    ) : CoroutineWorker(context, params) {
        /**
         * Stamps everything due, including days a switched-off device missed.
         * Result: `success()` when the statement ran — **including when it stamped zero rows**, which
         *         is the normal outcome of a second run in one day and of the overwhelmingly common
         *         case where the user has scheduled nothing. `retry()` while the app is locked or
         *         when the write failed, since both are worth another attempt.
         * Input:  none. Output: [Result].
         */
        override suspend fun doWork(): Result {
            // Before anything injects the database. A locked session makes the gated provider throw.
            if (!sessionLock.isUnlocked.value) return Result.retry()

            // The row count it returns is deliberately dropped rather than logged: §21.6 bans amounts
            // and PII from logs, and how many payments a user has scheduled is both.
            return if (repository.get().postDueTransactions() is Err) Result.retry() else Result.success()
        }

        companion object {
            /** The unique name, so rescheduling on every launch replaces rather than accumulates. */
            const val WORK_NAME = "scheduled-transaction-posting"

            /**
             * Schedules the daily job, keeping any existing one.
             *
             * Why:    `KEEP`, not `REPLACE` — the app schedules this on every process start, and
             *         replacing would reset the period each time, so a user who opens the app most
             *         mornings would push the run back indefinitely. The same reasoning
             *         [NetWorthSnapshotWorker.schedule] gives, and it matters less here only because
             *         nothing about the user's money depends on this having run.
             *
             *         No constraints: this needs no network and no charger (P-04).
             * Result: the job exists and repeats daily.
             * Input:  [context] — any context; WorkManager resolves the singleton itself.
             * Output: none.
             */
            fun schedule(context: Context) {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<ScheduledTransactionWorker>(1, TimeUnit.DAYS).build(),
                )
            }
        }
    }
