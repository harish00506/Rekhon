package com.aicfo.app.di

import android.content.Context
import com.aicfo.core.common.Clock
import com.aicfo.core.common.DefaultDispatcherProvider
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.SystemClock
import com.aicfo.core.common.errorOrNull
import com.aicfo.core.common.getOrNull
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
     * The encrypted database (issue 1.6).
     * Why:    **fails loudly if it cannot be opened.** `CfoDatabaseFactory.open` returns a
     *         `Result`, and the honest response to `Err` here is to stop: an app that ran without
     *         its database would show a user an empty net worth and no transactions, which looks
     *         like data loss rather than a fault. Better a crash report than a silently empty app.
     *         Hilt constructs this lazily, so nothing opens the Keystore until a repository asks.
     * Result: the open database, or a thrown [IllegalStateException] naming the error code.
     * Input:  [context]. Output: [CfoDatabase].
     */
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): CfoDatabase {
        val result = CfoDatabaseFactory.open(context)
        return result.getOrNull()
            ?: error("Could not open the encrypted database: ${result.errorOrNull()?.code}")
    }
}
