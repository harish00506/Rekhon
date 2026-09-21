package com.aicfo.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aicfo.app.notification.InsightNotifications
import com.aicfo.app.notification.InsightNotifier
import com.aicfo.core.common.Err
import com.aicfo.core.common.getOrNull
import com.aicfo.core.crypto.SessionLock
import com.aicfo.core.datastore.SettingsStore
import com.aicfo.data.repository.InsightRepository
import com.aicfo.data.repository.InsightStatus
import com.aicfo.data.repository.NotificationRepository
import com.aicfo.domain.engines.insight.Insight
import com.aicfo.domain.engines.notification.NotificationCandidate
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
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
 *            2026-09-20 — Issue 9.6: §7.2's stage 6. After the refresh, the live feed's notifiable
 *            insights (`InsightNotifications.kindFor`) are offered to AI-NTF's gate in the feed's
 *            own order, and only what it delivers is posted. The policy is the gate's, not this
 *            worker's: this only chooses what to *offer* (ADR-0047).
 *
 * Input:  [context], [params] — WorkManager's; [sessionLock] — the SEC-002 gate; [repository] and
 *         [notifications] — `Provider`s, because both reach the database the lock guards;
 *         [settingsStore] — the blur flag; [notifier] — composes, guardrails and posts.
 * Output: a worker WorkManager can run.
 */
@HiltWorker
@Suppress("LongParameterList") // WorkManager's two, the lock, and one per collaborator; each is a seam a test replaces
class InsightRefreshWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val sessionLock: SessionLock,
        private val repository: Provider<InsightRepository>,
        private val notifications: Provider<NotificationRepository>,
        private val settingsStore: SettingsStore,
        private val notifier: InsightNotifier,
    ) : CoroutineWorker(context, params) {
        /**
         * Recomputes the feed, then offers what is worth an interruption to the gate.
         * Result: `retry` while the session is locked (the database is unreachable), the pipeline
         *         refuses its input, or the gate cannot read its log; `success` otherwise — including
         *         when nothing was posted, the ordinary outcome. Input: none. Output: a [Result].
         */
        override suspend fun doWork(): Result {
            // Before anything injects the database. A locked session makes the gated provider throw.
            if (!sessionLock.isUnlocked.value) return Result.retry()

            // The count is deliberately not logged: how many things are wrong with someone's money
            // is exactly the kind of fact §21.6 keeps out of logs.
            if (repository.get().refresh() is Err) return Result.retry()
            return if (offerToGate()) Result.success() else Result.retry()
        }

        /**
         * §7.2's stage 6: the live feed's notifiable insights, through the gate, onto the phone.
         * Why:    **in the feed's order**, which is RULE-INS-RANK's — so a day with one slot left
         *         spends it on the worst finding. Only `ACTIVE` cards: a dismissed or snoozed one was
         *         the user saying "not now", and a notification would overrule them.
         * Result: `true` when the gate answered (whatever it decided); `false` when it could not.
         * Input:  none. Output: [Boolean].
         */
        private suspend fun offerToGate(): Boolean {
            val offered = offered()
            val delivered = if (offered.isEmpty()) emptySet() else deliveredKeys(offered) ?: return false
            // Read once, and a failed read reads as "not blurred", as in every sibling (ADR-0022).
            val blurAmounts = settingsStore.observe().first().getOrNull()?.privacyBlurEnabled == true
            offered.filter { it.first.key in delivered }.forEach { (candidate, insight) ->
                notifier.notify(insight, candidate.kind, blurAmounts)
            }
            return true
        }

        /**
         * The live feed's notifiable cards, as the gate sees them, in the feed's order.
         * Result: each candidate with the insight it stands for. Input: none.
         * Output: `List<Pair<NotificationCandidate, Insight>>`.
         */
        private suspend fun offered(): List<Pair<NotificationCandidate, Insight>> =
            repository.get().observeFeed().first()
                .filter { it.status == InsightStatus.ACTIVE }
                .mapNotNull { card ->
                    InsightNotifications.kindFor(card.insight.type)?.let { kind ->
                        NotificationCandidate(InsightNotifications.keyFor(card.insight), kind) to card.insight
                    }
                }

        /**
         * Asks the gate. Result: the keys it delivers, or `null` when it could not answer.
         * Input: [offered]. Output: `Set<String>?`.
         */
        private suspend fun deliveredKeys(offered: List<Pair<NotificationCandidate, Insight>>): Set<String>? =
            notifications.get().decide(offered.map { it.first }).getOrNull()?.deliverable?.map { it.key }?.toSet()

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
