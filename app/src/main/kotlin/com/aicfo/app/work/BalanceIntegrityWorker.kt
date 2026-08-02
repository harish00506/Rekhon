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
import com.aicfo.data.repository.AccountRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import javax.inject.Provider

/**
 * Keeps `account.current_balance_minor` honest (issue 2.7; DB-001, SEC-002, P-04).
 *
 * Why:  DB-001 says the current balance is derivable and "never mutated ad hoc". Since issue 1.6
 *       the column has been written once, at create, and never again — so on any account with
 *       transactions it has been stale by design, and
 *       [ADR-0007](../../../../../../../docs/adr/0007-account-balances-derived-not-stored.md) said
 *       so in as many words: *"the two figures can disagree, and until issue 2.7 nothing notices."*
 *       **This is what notices.** Nothing reads the column yet — every balance the app shows is
 *       derived — so the value here is not a screen but an invariant: a cache that is either
 *       correct or known to be wrong, which is the precondition for ever switching the read path
 *       onto it when the per-account subquery stops being cheap enough.
 *
 *       **It must not touch the database while the app is locked.** `CoreModule.provideDatabase`
 *       *throws* when the session is locked (SEC-002); injecting a repository here on a locked
 *       device would take the whole process down from a job the user never started. So the lock is
 *       checked first, and the repository arrives as a [Provider] so the graph is not even built
 *       until that check passes. Issue 2.6 proved that sequence on real hardware
 *       (`SUCCESS → RETRY → SUCCESS` in logcat) for the first worker; this one follows it exactly.
 *
 *       Deferring is safe: the refresh is idempotent and reads no history, so a run that never
 *       happened costs nothing a later run cannot recover.
 * What: one suspending call into [AccountRepository.refreshCachedBalances].
 * Result: every cached balance in the active profile equals the derived one, daily.
 * Changelog: 2026-08-02 — Created for issue 2.7.
 *
 * **Its own worker, not a second job inside `NetWorthSnapshotWorker`.** Folding it in would be
 * fewer files; a worker named for snapshots that also repairs balance caches is the kind of thing
 * that costs an hour at 3am. They are independent — net worth derives from the same subquery, never
 * from the cache — so neither ordering nor sharing a run buys anything.
 *
 * **No network, on any path** (P-04): every input is a local row, so this works identically in
 * airplane mode.
 *
 * Input:  [context], [params] — supplied by WorkManager; [sessionLock] — the SEC-002 gate;
 *         [repository] — deliberately a `Provider`, see above.
 * Output: a worker WorkManager can run.
 */
@HiltWorker
class BalanceIntegrityWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val sessionLock: SessionLock,
        private val repository: Provider<AccountRepository>,
    ) : CoroutineWorker(context, params) {
        /**
         * Repairs the cached balances.
         * Result: `success()` when the refresh ran — including when it found nothing out of step,
         *         which is the healthy outcome and not a failure; `retry()` while the app is locked
         *         or when the write failed, since both are worth another attempt.
         * Input:  none. Output: [Result].
         */
        override suspend fun doWork(): Result {
            // Before anything injects the database. A locked session makes the gated provider throw.
            if (!sessionLock.isUnlocked.value) return Result.retry()

            // The drift count is deliberately dropped rather than logged: §21.6 bans amounts and
            // account detail from logs, and the repaired rows are themselves the record.
            return if (repository.get().refreshCachedBalances() is Err) Result.retry() else Result.success()
        }

        companion object {
            /** The unique name, so rescheduling on every launch replaces rather than accumulates. */
            const val WORK_NAME = "balance-integrity-daily"

            /**
             * Schedules the daily job, keeping any existing one.
             *
             * Why:    `KEEP`, not `REPLACE`, for the reason `NetWorthSnapshotWorker.schedule`
             *         documents — the app schedules on every process start, and replacing would
             *         reset the period each time, so a user who opens the app most mornings would
             *         push the run back indefinitely.
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
                    PeriodicWorkRequestBuilder<BalanceIntegrityWorker>(1, TimeUnit.DAYS).build(),
                )
            }
        }
    }
