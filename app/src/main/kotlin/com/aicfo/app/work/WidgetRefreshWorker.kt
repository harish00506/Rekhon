package com.aicfo.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aicfo.core.common.getOrNull
import com.aicfo.core.crypto.SessionLock
import com.aicfo.core.datastore.SettingsStore
import com.aicfo.data.repository.NetWorthRepository
import com.aicfo.data.repository.SafeToSpendRepository
import com.aicfo.widget.CfoWidget
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Provider

/**
 * Fills the home-screen widget's cache with the current figures (issue 5.5; §5.2, §35, P-03/P-04).
 *
 * Why:  the widget renders from a cache and computes nothing itself (ADR-0024) — so something has
 *       to fill that cache, and it cannot be the widget. This is that something. It is the sixth
 *       worker in the app and the only one whose output is a *display* rather than a stored fact:
 *       if it never ran, no figure would be wrong, the widget would simply be stale. That is what
 *       makes deferring it safe.
 *
 *       **The lock check comes first, and the repositories are `Provider`s, for the reason
 *       `NetWorthSnapshotWorker` records:** `CoreModule.provideDatabase` *throws* while the session
 *       is locked (SEC-002), so resolving a repository on a locked device would take the process
 *       down from a job the user never started. `retry()` rather than `failure()` is the honest
 *       outcome — nothing is wrong, the figures simply cannot be read yet, and the widget keeps
 *       showing the last ones it had rather than blanking.
 *
 *       **Safe-to-Spend has no snapshot table**, unlike net worth. `SafeToSpendRepository`
 *       recomputes it from five Room reads; taking the first emission here is what turns that live
 *       computation into the cached value the widget needs.
 * What: reads both figures and the blur flag, writes them into the Glance state, redraws.
 * Result: every placed widget shows what the dashboard would show.
 * Changelog: 2026-08-17 — Created for issue 5.5.
 *
 * **No network, on any path** (P-04): every input is a local row or a local preference, so this
 * behaves identically in airplane mode — which is why the request carries no `Constraints`.
 *
 * Input:  [context], [params] — supplied by WorkManager; [sessionLock] — the SEC-002 gate;
 *         [safeToSpend], [netWorth] — deliberately `Provider`s, see above; [settingsStore] — the
 *         privacy-blur flag, which is *not* behind the lock and so is injected directly.
 * Output: a worker WorkManager can run.
 */
@HiltWorker
class WidgetRefreshWorker
    @AssistedInject
    constructor(
        @Assisted private val context: Context,
        @Assisted params: WorkerParameters,
        private val sessionLock: SessionLock,
        private val safeToSpend: Provider<SafeToSpendRepository>,
        private val netWorth: Provider<NetWorthRepository>,
        private val settingsStore: SettingsStore,
    ) : CoroutineWorker(context, params) {
        /**
         * Takes one reading of each figure and hands it to the widget.
         *
         * **Idempotent, and trivially so.** The Glance state is a set of keys rather than a log, and
         * nothing written here is derived from the current time, so two runs with unchanged data
         * write identical bytes — the second costs a redraw and nothing else. That is why this
         * worker needs no per-period claim of the kind `BudgetAlertWorker` uses.
         *
         * **The blur is written in the same pass** so a refresh cannot un-hide amounts the user
         * asked to hide: if the flag were left to the watcher alone, a refresh that landed between
         * the toggle and the watcher's write would repaint the widget with the old flag still in
         * state and the new figures beside it.
         *
         * Result: `success()` once the state is written — including when both figures are `null`,
         *         which is a real answer for a profile with no income basis and no snapshot, not a
         *         failure. `retry()` while the app is locked.
         * Input:  none. Output: [Result].
         * Changelog: 2026-08-17 — Created for issue 5.5.
         */
        override suspend fun doWork(): Result {
            // Before anything injects the database. A locked session makes the gated provider throw.
            if (!sessionLock.isUnlocked.value) return Result.retry()

            // Read failure reads as "not blurred" everywhere else in this feature (see MainViewModel);
            // it must read the same way here, or a transient DataStore hiccup would mask the widget.
            val blurred = settingsStore.observe().first().getOrNull()?.privacyBlurEnabled == true
            CfoWidget.writeBlurred(context, blurred)
            CfoWidget.writeFigures(
                context = context,
                safeToSpend = safeToSpend.get().observeSafeToSpend().first()?.amount,
                netWorth = netWorth.get().observeCurrent().first().netWorth,
            )
            return Result.success()
        }

        companion object {
            /** The unique name of the periodic job, so relaunching does not accumulate copies. */
            const val WORK_NAME = "home-widget-refresh"

            /** The unique name of the on-demand job, kept apart so it cannot cancel the periodic one. */
            const val REFRESH_NOW_WORK_NAME = "home-widget-refresh-now"

            /**
             * The cadence.
             *
             * Six hours, not the one day the other five workers use, because Safe-to-Spend moves
             * with every transaction while a net-worth snapshot moves once a day. A daily widget
             * would be wrong for most of the day it was right at the start of. It is not shorter
             * because [refreshNow] already covers the case that matters — the user has just been in
             * the app — and a tighter alarm would spend battery redrawing a figure nobody looked at.
             */
            private const val REFRESH_INTERVAL_HOURS = 6L

            /**
             * Schedules the periodic refresh, keeping any existing one.
             *
             * Why:    `KEEP`, not `REPLACE` — this is scheduled on every process start, and
             *         replacing would reset the six-hour period each time, so a user who opens the
             *         app often would push the periodic run back indefinitely.
             *
             *         No constraints: no network, no charger (P-04).
             * Result: the job exists and repeats.
             * Input:  [context] — any context; WorkManager resolves the singleton itself.
             * Output: none.
             * Changelog: 2026-08-17 — Created for issue 5.5.
             */
            fun schedule(context: Context) {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<WidgetRefreshWorker>(
                        REFRESH_INTERVAL_HOURS,
                        TimeUnit.HOURS,
                    ).build(),
                )
            }

            /**
             * Refreshes the widget once, now (issue 5.5).
             *
             * Why:    the periodic job alone would leave the widget up to six hours behind the app
             *         the user just closed — and the moment a figure most needs to be right is
             *         straight after they added a transaction. Enqueued from `CfoApplication`, so
             *         every launch freshens the home screen without a lifecycle observer anywhere.
             *
             *         `REPLACE`, unlike the periodic job: two pending refreshes would compute the
             *         same two figures twice, and the later one is the one worth keeping.
             * Result: the widget catches up as soon as the app is unlocked — while locked the work
             *         retries with backoff rather than failing, so nothing is lost.
             * Input:  [context]. Output: none.
             * Changelog: 2026-08-17 — Created for issue 5.5.
             */
            fun refreshNow(context: Context) {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    REFRESH_NOW_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build(),
                )
            }
        }
    }
