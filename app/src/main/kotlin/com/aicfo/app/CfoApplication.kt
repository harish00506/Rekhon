package com.aicfo.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.aicfo.app.di.ProfileZoneProvider
import com.aicfo.app.work.BalanceIntegrityWorker
import com.aicfo.app.work.NetWorthSnapshotWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point and Hilt dependency-injection root.
 *
 * Why:  §21.2 / ARC-003 — the app is a single-Activity Compose host whose object graph is built by
 *       Hilt; `@HiltAndroidApp` bootstraps that graph once at process start.
 * What: the custom [Application], plus the one piece of startup work the app must do: begin
 *       tracking the profile time zone.
 * Result: constructor injection everywhere, and a `Clock` that resolves in the user's zone.
 * Changelog: 2026-07-19 — Created for issue 1.1.
 *            2026-07-25 — Issue 1.10: starts [ProfileZoneProvider].
 *            2026-08-01 — Issue 2.6: became a `Configuration.Provider` and schedules the daily
 *            net-worth snapshot — the app's first background work.
 *
 * Starting the collector here rather than in the provider's constructor keeps object construction
 * free of side effects — Hilt may build the graph earlier than the app is ready, and a test must be
 * able to create the provider without also launching a coroutine.
 */
@HiltAndroidApp
class CfoApplication : Application(), Configuration.Provider {
    @Inject
    lateinit var profileZoneProvider: ProfileZoneProvider

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /**
     * WorkManager's configuration, using Hilt's worker factory (issue 2.6).
     *
     * Why: `NetWorthSnapshotWorker` takes constructor dependencies, which WorkManager's default
     *      factory cannot supply — it only knows the two-argument constructor. Providing the
     *      factory here is what lets a worker be injected like everything else (ARC-003).
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    /**
     * Input:  none. Output: none (starts the profile-zone collector and schedules the daily jobs).
     *
     * Scheduling on every launch is safe and deliberate: each request is enqueued as unique work
     * with `KEEP`, so an existing job survives untouched and a user who has never had one gets it.
     *
     * Changelog: 2026-08-02 — Issue 2.7 added the balance-integrity job beside the snapshot one.
     *            They are independent and carry separate unique names; sharing one would mean only
     *            whichever was enqueued first ever ran.
     */
    override fun onCreate() {
        super.onCreate()
        profileZoneProvider.start()
        NetWorthSnapshotWorker.schedule(this)
        BalanceIntegrityWorker.schedule(this)
    }
}
