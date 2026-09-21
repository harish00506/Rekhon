package com.aicfo.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aicfo.app.notification.BudgetAlertNotifier
import com.aicfo.core.common.Clock
import com.aicfo.core.common.Ok
import com.aicfo.core.common.getOrNull
import com.aicfo.core.crypto.SessionLock
import com.aicfo.core.datastore.SettingsStore
import com.aicfo.data.repository.BudgetRepository
import com.aicfo.data.repository.CategoryBudgetAlert
import com.aicfo.data.repository.NotificationRepository
import com.aicfo.domain.engines.budget.BudgetAlertBand
import com.aicfo.domain.engines.notification.NotificationCandidate
import com.aicfo.domain.engines.notification.NotificationKind
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.YearMonth
import java.util.concurrent.TimeUnit
import javax.inject.Provider

/**
 * Sends the budget alerts the user has not yet heard about (issue 4.5; FR-BUD-004, SEC-002, P-04).
 *
 * Why:  a band is crossed by a transaction, but the app is usually not open when it matters — and
 *       even when it is, interrupting someone mid-entry with a notification about the thing they are
 *       entering is the wrong moment. A daily job separates "the band was crossed" from "the user is
 *       told", which is also what makes issue 9.6's notification policy possible to add later
 *       without moving this logic.
 *
 *       **The lock is checked before anything injects**, exactly as [NetWorthSnapshotWorker]
 *       documents: `CoreModule.provideDatabase` *throws* when the session is locked (SEC-002), so
 *       reaching for the repository on a locked device would take the process down from a job the
 *       user never started. Hence [Provider], and hence `retry()` rather than `failure()` — nothing
 *       is wrong, the work simply cannot be done yet, and no alert is lost because the band stays
 *       crossed until it is sent.
 * What: read the pending alerts, claim each, notify the ones this run claimed.
 * Result: at most one notification per budget, per band, per month (`RULE-BUD-ALERT`).
 * Changelog: 2026-08-13 — Created for issue 4.5.
 *            2026-09-20 — Issue 9.6: every alert now asks AI-NTF's gate before it is claimed. Only a
 *            delivered one is claimed and posted; one held for the morning or over the day's ration
 *            stays pending, so tomorrow's run offers it again and the in-app banner shows it
 *            meanwhile (NTF-001/002, ADR-0047).
 *
 * **No network, on any path** (P-04): every input is a local row and the notification is local, so
 * this behaves identically in airplane mode. No constraints for the same reason — requiring a
 * charger or a connection would only make the warnings later and patchier.
 *
 * Input:  [context], [params] — supplied by WorkManager; [sessionLock] — the SEC-002 gate;
 *         [repository] — deliberately a `Provider`, see above; [notifier] — composes, guardrails and
 *         posts; [settingsStore] — the blur flag; [notifications] — the §17.2 gate, a `Provider` for
 *         the same reason as [repository]; [clock] — the month the gate's key names.
 * Output: a worker WorkManager can run.
 */
@HiltWorker
@Suppress("LongParameterList") // WorkManager's two, the lock, and one per collaborator; each is a seam a test replaces
class BudgetAlertWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val sessionLock: SessionLock,
        private val repository: Provider<BudgetRepository>,
        private val notifier: BudgetAlertNotifier,
        private val settingsStore: SettingsStore,
        private val notifications: Provider<NotificationRepository>,
        private val clock: Clock,
    ) : CoroutineWorker(context, params) {
        /**
         * Claims each pending alert and notifies the ones this run won.
         *
         * Why:    **claim, then notify** — the insert is what makes a duplicate impossible, so it has
         *         to happen first. `markNotified` returning `false` means another run already claimed
         *         this band, and the only correct response is to say nothing.
         *
         *         A notifier that returns `false` (permission denied, or the guardrail refused the
         *         text) is **not** an error and does not retry. The claim stands: the in-app banner
         *         still shows the band, and retrying would either post nothing again or, worse,
         *         eventually post the same message twice. The user was not told *this way*, which is
         *         a state this feature is designed for rather than a failure to recover from.
         *
         *         **The gate comes before the claim** (issue 9.6): a claim is permanent, so claiming an
         *         alert the policy then held would lose it. Most severe first, so a day with one slot
         *         left spends it on the overspend rather than the warning.
         * Result: `success()` when the pending list was processed, including when it was empty or
         *         nothing could be posted; `retry()` while the app is locked or a read failed.
         * Input:  none. Output: [Result].
         */
        override suspend fun doWork(): Result {
            // Before anything injects the database. A locked session makes the gated provider throw.
            if (!sessionLock.isUnlocked.value) return Result.retry()

            val month = YearMonth.from(clock.today())
            val allowed = allowedAlerts(month) ?: return Result.retry()

            // Issue 5.3: read once for the whole batch, not per alert — the setting cannot
            // meaningfully change between two notifications posted in the same millisecond, and a
            // read per alert would be a DataStore round trip per notification. A read failure falls
            // back to `false`, matching `MainViewModel.isPrivacyBlurred`: the blur is a display
            // preference, and suppressing every figure because a settings read hiccuped would make
            // the alerts useless for a reason the user could never diagnose.
            val blurAmounts = settingsStore.observe().first().getOrNull()?.privacyBlurEnabled == true

            // Amounts and category names are deliberately not logged (§21.6, CfoPiiInLogs);
            // `budget_alert` is the record of what was sent.
            allowed.forEach { alert ->
                val claimed = repository.get().markNotified(alert)
                if (claimed is Ok && claimed.value) notifier.notify(alert, blurAmounts)
            }
            return Result.success()
        }

        /**
         * The pending alerts the gate lets through, most severe first (issue 9.6).
         * Why:    the overspend is offered before the warning, so a day with one slot left spends it
         *         on the worse news; only what the gate delivers comes back, so nothing held is claimed.
         * Result: the alerts to claim and post, or `null` when the alerts or the gate could not be
         *         read. Input: [month] — the key's month. Output: `List<CategoryBudgetAlert>?`.
         */
        private suspend fun allowedAlerts(month: YearMonth): List<CategoryBudgetAlert>? {
            val pending = repository.get().pendingAlerts().getOrNull() ?: return null
            val ordered = pending.sortedByDescending { it.alert.band == BudgetAlertBand.EXCEEDED }
            return notifications.get().decide(ordered.map { candidate(it, month) }).getOrNull()?.let { plan ->
                val delivered = plan.deliverable.map { it.key }.toSet()
                ordered.filter { key(it, month) in delivered }
            }
        }

        /**
         * The alert as the gate sees it (issue 9.6).
         * Result: a §17.1 "Budget & discipline" candidate. Input: [alert]; [month]. Output: the candidate.
         */
        private fun candidate(
            alert: CategoryBudgetAlert,
            month: YearMonth,
        ) = NotificationCandidate(key(alert, month), NotificationKind.BUDGET_DISCIPLINE)

        /**
         * What makes this message this message: `RULE-BUD-ALERT`'s once per budget, per band, per month.
         * Result: the key. Input: [alert]; [month]. Output: [String].
         */
        private fun key(
            alert: CategoryBudgetAlert,
            month: YearMonth,
        ) = "budget:${alert.budgetId}:${alert.alert.band.name}:$month"

        companion object {
            /** The unique name, so rescheduling on every launch replaces rather than accumulates. */
            const val WORK_NAME = "budget-threshold-alerts"

            /**
             * Schedules the daily job, keeping any existing one.
             *
             * Why:    `KEEP`, not `REPLACE` — the app schedules this on every process start, and
             *         replacing would reset the period each time, so a user who opens the app most
             *         days would push the run back indefinitely and never be warned at all.
             * Result: the job exists and repeats daily.
             * Input:  [context] — any context; WorkManager resolves the singleton itself.
             * Output: none.
             */
            fun schedule(context: Context) {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<BudgetAlertWorker>(1, TimeUnit.DAYS).build(),
                )
            }
        }
    }
