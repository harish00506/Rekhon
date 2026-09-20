package com.aicfo.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aicfo.core.common.Err
import com.aicfo.core.common.Ok
import com.aicfo.core.crypto.SessionLock
import com.aicfo.data.repository.InsightRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import javax.inject.Provider

/**
 * Runs the Insight Orchestrator once a day (issue 9.5; §7.2's `day_rollover` trigger, AI-ARC-005).
 *
 * Why:  §7.2 lists five triggers. This is the one that has to exist for the feed to be **true when
 *       the user next looks**: a crunch day arrives, a budget's month rolls over, a goal's date
 *       passes, and none of that is a tap the user made. The other triggers ADR-0046 records:
 *       opening the dashboard refreshes as well (the "manual" one), and the debounced
 *       transaction-committed trigger waits for the notification engine (9.6), which needs the same
 *       machinery for its own reasons.
 * What: one call to [InsightRepository.refresh] — the pipeline, and the writes it makes.
 * Result: the persisted feed is recomputed daily, off the UI thread, with the screen reading rows.
 * Changelog: 2026-09-20 — Created for issue 9.5.
 *
 * **Nothing is notified here.** §7.2's stage 6 hands off to AI-NTF, which issue 9.6 builds; raising
 * a notification from this worker now would be that policy invented in the wrong place.
 */
@HiltWorker
class InsightRefreshWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val sessionLock: SessionLock,
        private val repository: Provider<InsightRepository>,
    ) : CoroutineWorker(context, params) {
        /**
         * Recomputes the feed.
         * Result: `retry` while the session is locked (the database is unreachable) or the pipeline
         *         refuses its input; `success` otherwise. Input: none. Output: a [Result].
         */
        override suspend fun doWork(): Result {
            // Before anything injects the database. A locked session makes the gated provider throw.
            if (!sessionLock.isUnlocked.value) return Result.retry()

            // The count is deliberately not logged: how many things are wrong with someone's money
            // is exactly the kind of fact §21.6 keeps out of logs.
            return when (repository.get().refresh()) {
                is Ok -> Result.success()
                is Err -> Result.retry()
            }
        }

        companion object {
            /** The unique name, so rescheduling on every launch replaces rather than accumulates. */
            const val WORK_NAME = "insight-refresh"

            /**
             * Enqueues the daily run.
             * Why:    `KEEP`, like every sibling: rescheduling on each launch must not reset the
             *         cadence, or an app opened often would never actually run it.
             * Result: the work is scheduled. Input: [context]. Output: none.
             */
            fun schedule(context: Context) {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<InsightRefreshWorker>(1, TimeUnit.DAYS).build(),
                )
            }
        }
    }
