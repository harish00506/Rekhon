package com.aicfo.app.di

import android.content.Context
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DefaultDispatcherProvider
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.SystemClock
import com.aicfo.core.common.errorOrNull
import com.aicfo.core.common.getOrNull
import com.aicfo.core.crypto.SessionLock
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.database.CfoDatabaseFactory
import com.aicfo.core.datastore.CfoDataStoreFactory
import com.aicfo.core.datastore.ConsentStore
import com.aicfo.core.datastore.SettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * The application object graph (ARC-003, ARC-006).
 *
 * Why:  §21.2 requires constructor injection with no service locators. Everything the app needs at
 *       the singleton level is declared here once, so no feature constructs its own database,
 *       clock or dispatcher — which is how the one-way module graph erodes in practice. A missing
 *       binding then fails at **compile** time rather than at first use.
 * What: dispatchers, the application scope, the clock, and the stores from issues 1.6 and 1.9.
 * Result: features declare what they need in a constructor and Hilt supplies it.
 * Changelog: 2026-07-25 — Created for issue 1.10.
 */
@Module
@InstallIn(SingletonComponent::class)
object CoreModule {
    /**
     * The dispatchers every suspending call site takes (ARC-006).
     * Result: the production provider. Input: none. Output: [DispatcherProvider].
     */
    @Provides
    @Singleton
    fun provideDispatchers(): DispatcherProvider = DefaultDispatcherProvider()

    /**
     * The scope for work that outlives any screen.
     * Why:    structured concurrency needs an owner (ARC-006). A `SupervisorJob` means one failed
     *         child — a settings collector, say — does not take down every other background task.
     *         This is the injectable alternative that makes `GlobalScope` unnecessary, which is
     *         what the `CfoGlobalScope` lint rule from issue 1.5 enforces.
     * Result: an application-lifetime [CoroutineScope].
     * Input:  [dispatchers]. Output: [CoroutineScope].
     */
    @Provides
    @Singleton
    fun provideApplicationScope(dispatchers: DispatcherProvider): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatchers.default)

    /**
     * The app's only source of time (TIM-001).
     * Why:    reads the profile zone through [ProfileZoneProvider], so every calendar answer
     *         resolves in the user's zone rather than the device's.
     * Result: the production [Clock].
     * Input:  [zoneProvider]. Output: [Clock].
     */
    @Provides
    @Singleton
    fun provideClock(zoneProvider: ProfileZoneProvider): Clock = SystemClock(zoneProvider)

    /**
     * Settings and consents (issue 1.9), which share one atomic file.
     * Result: both stores. Input: [context], [clock], [dispatchers], [scope]. Output: the holder.
     */
    @Provides
    @Singleton
    fun provideDataStores(
        @ApplicationContext context: Context,
        clock: Clock,
        dispatchers: DispatcherProvider,
        scope: CoroutineScope,
    ) = CfoDataStoreFactory.create(context, clock, dispatchers, scope)

    /** Result: the settings store. Input: the holder. Output: [SettingsStore]. */
    @Provides
    @Singleton
    fun provideSettingsStore(stores: com.aicfo.core.datastore.CfoDataStores): SettingsStore = stores.settings

    /** Result: the consent ledger (P-01). Input: the holder. Output: [ConsentStore]. */
    @Provides
    @Singleton
    fun provideConsentStore(stores: com.aicfo.core.datastore.CfoDataStores): ConsentStore = stores.consents

    /**
     * The encrypted database, **before** the session gate (issue 1.6, 2.2).
     * Why:    **fails loudly if it cannot be opened.** `CfoDatabaseFactory.open` returns a
     *         `Result`, and the honest response to `Err` here is to stop: an app that ran without
     *         its database would show a user an empty net worth and no transactions, which looks
     *         like data loss rather than a fault. Better a crash report than a silently empty app.
     *         Hilt constructs this lazily, so nothing opens the Keystore until something asks.
     *
     *         Qualified [AuditDatabase] because it is **not** gated on the app lock. Exactly one
     *         thing may use it: the audit log, which has to record a *failed* unlock, and a failed
     *         unlock by definition happens while the app is locked. Everything else injects the
     *         unqualified [CfoDatabase] below and goes through the gate.
     * Result: the open database, or a thrown [IllegalStateException] naming the error code.
     * Input:  [context]. Output: [CfoDatabase].
     * Changelog: 2026-07-25 — Created for issue 1.10.
     *            2026-07-26 — Issue 2.2: qualified, and the gated binding added beside it.
     */
    @Provides
    @Singleton
    @AuditDatabase
    fun provideUngatedDatabase(
        @ApplicationContext context: Context,
    ): CfoDatabase {
        val result = CfoDatabaseFactory.open(context)
        return result.getOrNull()
            ?: error("Could not open the encrypted database: ${result.errorOrNull()?.code}")
    }

    /**
     * The encrypted database as feature code sees it — gated on the app lock (issue 2.2, SEC-002).
     *
     * Why:    "the lock gates the encrypted store" has to be something the build can fail on, not a
     *         sentence in a document. The lock screen is a composable, and a composable is not a
     *         security boundary — it can be bypassed by a deep link, a restored back stack, or a
     *         future screen wired in above it. This is the check that does not depend on the UI
     *         being right.
     * What:   the same singleton instance, with an assertion in front of it.
     * Result: [CfoDatabase] when the session is open; otherwise a thrown [IllegalStateException].
     *         A throw and not a `Result`: a repository asking for financial data while the app is
     *         locked is a **programmer error**, and §21.6 reserves crashes for exactly those.
     * Input:  [database] — the ungated instance; [sessionLock] — the session gate.
     * Output: [CfoDatabase].
     * Changelog: 2026-07-26 — Created for issue 2.2.
     *
     * **Known limitation, recorded in ADR-0003.** Hilt caches this per `@Singleton`, so the check
     * runs the first time feature code asks and not on every later access. It therefore proves "no
     * financial data was read before the first unlock" — real, and the property that matters on a
     * cold start — but it does not re-assert after an idle re-lock. Closing that gap needs the
     * auth-bound Keystore key SEC-001 describes, which is deferred.
     */
    @Provides
    @Singleton
    fun provideDatabase(
        @AuditDatabase database: CfoDatabase,
        sessionLock: SessionLock,
    ): CfoDatabase {
        check(sessionLock.isUnlocked.value) {
            "The encrypted database was opened while the app was locked (SEC-002). Feature code " +
                "must not read financial data before a successful unlock; only the audit log may, " +
                "and it injects @AuditDatabase."
        }
        return database
    }
}

/**
 * Marks the database binding that is **not** gated on the app lock (issue 2.2; SEC-002).
 *
 * Why:    the audit log has to record a *failed* unlock, and a failed unlock happens while the app
 *         is locked — so one consumer genuinely needs the database before the session opens. Making
 *         that a qualifier rather than a comment means the exemption is visible at every injection
 *         site, is exactly one line to grep for, and cannot be taken by accident: a repository that
 *         wants ungated access has to name it and justify it in review.
 * Result: only `provideAuditLogRepository` and the gated provider itself use it.
 * Changelog: 2026-07-26 — Created for issue 2.2.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuditDatabase
