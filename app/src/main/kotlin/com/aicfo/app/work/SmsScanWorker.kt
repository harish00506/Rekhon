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
import com.aicfo.data.repository.SmsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import javax.inject.Provider

/**
 * Picks up new bank alerts in the background (issue 3.9; §18, §23, P-01, P-04).
 *
 * Why:  without this the feature only works when the user remembers to open a screen and tap, which
 *       for a capture path means the alerts they most need are the ones they never see. **A periodic
 *       scan rather than a `RECEIVE_SMS` broadcast receiver**: a receiver would wake the app on
 *       every message the phone receives and would need a second Play-restricted permission on top
 *       of `READ_SMS` (ADR-0013), to buy latency nobody needs. Reading from a stored cursor once a
 *       day reaches the same messages.
 *
 *       **It reads nothing without the consent**, and it does not check that itself. `SmsRepository`
 *       is the single chokepoint (see its class doc), so this worker calling `scan()` on a phone
 *       whose owner has not opted in returns `Ok(0)` having touched no inbox. Duplicating the gate
 *       here would be a second place for it to drift.
 *
 *       **The lock check comes first, exactly as in [NetWorthSnapshotWorker].**
 *       `CoreModule.provideDatabase` throws when the session is locked (SEC-002), so injecting the
 *       repository on a locked device would take the process down from a background job the user
 *       never started — which is why it arrives as a [Provider].
 * What: one suspending call into [SmsRepository.scan].
 * Result: drafts are waiting on the review screen when the user next looks.
 * Changelog: 2026-08-07 — Created for issue 3.9.
 *
 * **No network, on any path** (P-04): the inbox is a local `ContentResolver` query and the parser is
 * pure Kotlin, so this works identically in airplane mode.
 *
 * Input:  [context], [params] — supplied by WorkManager; [sessionLock] — the SEC-002 gate;
 *         [repository] — deliberately a `Provider`, see above.
 * Output: a worker WorkManager can run.
 */
@HiltWorker
class SmsScanWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val sessionLock: SessionLock,
        private val repository: Provider<SmsRepository>,
    ) : CoroutineWorker(context, params) {
        /**
         * Scans from the stored cursor.
         *
         * Result: `success()` when the scan ran, **including when it read nothing and including when
         *         the consent is off** — a phone whose owner has not opted in is the feature
         *         correctly doing nothing, and reporting that as a failure would have WorkManager
         *         retrying it with backoff for ever. `retry()` while the app is locked or when the
         *         inbox could not be read, both of which are worth another attempt: the cursor did
         *         not move, so nothing was missed.
         * Input:  none. Output: [Result].
         */
        override suspend fun doWork(): Result {
            // Before anything injects the database. A locked session makes the gated provider throw.
            if (!sessionLock.isUnlocked.value) return Result.retry()

            // The count is deliberately dropped rather than logged. §21.6 bans PII from logs, and
            // "how many bank alerts arrived today" is a fact about the user's spending — a number
            // that on its own says whether they had a busy week.
            return if (repository.get().scan() is Err) Result.retry() else Result.success()
        }

        companion object {
            /** The unique name, so rescheduling on every launch replaces rather than accumulates. */
            const val WORK_NAME = "sms-inbox-scan"

            /**
             * Schedules the daily scan, keeping any existing one.
             *
             * Why:    `KEEP`, not `REPLACE`, for the reason [NetWorthSnapshotWorker.schedule] gives:
             *         the app schedules this on every process start, and replacing would reset the
             *         period each time, so a user who opens the app most mornings would push the run
             *         back indefinitely and never get a background scan at all.
             *
             *         **Scheduled unconditionally, even for a user who has not opted in.** The
             *         alternative — scheduling on grant and cancelling on revoke — would put the
             *         consent rule in a second place and would leave a job orphaned by any path that
             *         forgot to cancel. A scheduled job that finds the consent off does nothing and
             *         reads nothing, which costs a wake-up a day and keeps one gate.
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
                    PeriodicWorkRequestBuilder<SmsScanWorker>(1, TimeUnit.DAYS).build(),
                )
            }
        }
    }
