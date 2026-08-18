package com.aicfo.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aicfo.app.notification.CardAlertNotifier
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.common.getOrNull
import com.aicfo.core.crypto.SessionLock
import com.aicfo.core.datastore.SettingsStore
import com.aicfo.data.repository.CreditCardRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Provider

/**
 * Tells the user about a card payment coming due, or a limit being leaned on (issue 6.1;
 * FR-ACC-002, §17.1, SEC-002, P-04).
 *
 * Why:  the app is usually not open on the day a card falls due, and that is the entire point of
 *       this job. It is the seventh worker and, unlike the widget refresh, it is **load-bearing**:
 *       a run that never happens is a reminder the user never got, and a missed card payment costs
 *       a late fee plus interest on the whole statement at 36-42% APR plus a credit-report mark.
 *
 *       **The lock check comes first and the repository is a `Provider`**, for the reason
 *       `BudgetAlertWorker` records: `CoreModule.provideDatabase` *throws* while the session is
 *       locked (SEC-002), so resolving the repository on a locked device would take the process
 *       down from a job the user never started. `retry()` rather than `failure()` — nothing is
 *       wrong, the cards simply cannot be read yet, and the reminder window is three days wide so
 *       a deferral costs nothing.
 *
 *       **Claim, then notify.** The order is the whole safety of the feature and it belongs to
 *       `CreditCardRepository.markNotified`; this loop only respects it. A notifier that returns
 *       `false` is deliberately **not** retried: the claim stands, because the alternative is a
 *       reminder that re-fires daily until it gets through, on the one channel §17.1 calls
 *       Critical — the channel a user must not learn to mute.
 * What: read what is pending, claim each, notify what this run claimed.
 * Result: at most one notification per card, per cycle, per kind.
 * Changelog: 2026-08-17 — Created for issue 6.1.
 *
 * **No network, on any path** (P-04): every input is a local row, so this behaves identically in
 * airplane mode — which is why the request carries no `Constraints`.
 *
 * **Nothing is logged** (`CfoPiiInLogs`, §21.6): the rows in `card_alert` are the record of what
 * was sent, and logcat is not the place for a user's card balances.
 *
 * Input:  [context], [params] — supplied by WorkManager; [sessionLock] — the SEC-002 gate;
 *         [repository] — deliberately a `Provider`, see above; [settingsStore] — the privacy-blur
 *         flag, which is not behind the lock; [notifier] — composes and posts.
 * Output: a worker WorkManager can run.
 */
@HiltWorker
class CardAlertWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val sessionLock: SessionLock,
        private val repository: Provider<CreditCardRepository>,
        private val settingsStore: SettingsStore,
        private val notifier: CardAlertNotifier,
    ) : CoroutineWorker(context, params) {
        /**
         * Sends whatever is due today and has not been sent.
         *
         * Result: `success()` when every pending alert was claimed and offered — including when
         *         there were none, which is the ordinary outcome. `retry()` while the app is locked
         *         or when the read failed, since both are worth another attempt rather than losing
         *         a payment reminder.
         * Input:  none. Output: [Result].
         */
        override suspend fun doWork(): Result {
            // Before anything injects the database. A locked session makes the gated provider throw.
            if (!sessionLock.isUnlocked.value) return Result.retry()

            val pending =
                when (val result = repository.get().pendingAlerts()) {
                    is Ok -> result.value
                    is Err -> return Result.retry()
                }

            // Read once per batch, not per alert: the flag cannot change mid-run in any way that
            // matters, and a DataStore read per notification would be work for nothing. A read
            // failure reads as "not blurred" here exactly as it does in MainViewModel (ADR-0022).
            val blurAmounts = settingsStore.observe().first().getOrNull()?.privacyBlurEnabled == true

            pending.forEach { alert ->
                val claimed = repository.get().markNotified(alert.alert)
                if (claimed is Ok && claimed.value) notifier.notify(alert, blurAmounts)
            }
            return Result.success()
        }

        companion object {
            /** The unique name, so rescheduling on every launch replaces rather than accumulates. */
            const val WORK_NAME = "card-payment-alerts"

            /**
             * Schedules the daily job, keeping any existing one.
             *
             * Why:    `KEEP`, not `REPLACE` — the app schedules this on every process start, and
             *         replacing would reset the period each time, so a user who opens the app most
             *         mornings would push the run back indefinitely and never be reminded.
             *
             *         Daily rather than more often, and the rulebook is why: `RULE-CC-DUE`'s window
             *         is three days wide and includes the due day, so a daily job cannot miss it
             *         even if one run is deferred by the lock. Anything tighter would spend battery
             *         re-deciding a question whose answer changes once a day.
             *
             *         No constraints: no network and no charger (P-04).
             * Result: the job exists and repeats daily.
             * Input:  [context] — any context; WorkManager resolves the singleton itself.
             * Output: none.
             */
            fun schedule(context: Context) {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<CardAlertWorker>(1, TimeUnit.DAYS).build(),
                )
            }
        }
    }
