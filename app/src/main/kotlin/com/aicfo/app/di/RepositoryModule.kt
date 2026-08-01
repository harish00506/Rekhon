package com.aicfo.app.di

import com.aicfo.core.common.Clock
import com.aicfo.core.common.DispatcherProvider
import com.aicfo.core.common.IdGenerator
import com.aicfo.core.database.CfoDatabase
import com.aicfo.core.datastore.SettingsStore
import com.aicfo.data.repository.AccountRepository
import com.aicfo.data.repository.DemoModeRepository
import com.aicfo.data.repository.NetWorthRepository
import com.aicfo.data.repository.QuickSetupRepository
import com.aicfo.data.repository.RepositoryFactory
import com.aicfo.domain.engines.networth.NetWorthEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The `:data:repository` bindings (ARC-003, ARC-005, SEC-002).
 *
 * Why:  split out of [CoreModule] when that object reached detekt's `TooManyFunctions` ceiling. The
 *       seam is a real one rather than an arbitrary cut: everything left in [CoreModule] is a
 *       platform primitive — a clock, a dispatcher, a database handle — while everything here is a
 *       repository, the only kind of class allowed to touch a DAO (ARC-005). A reviewer asking
 *       "what can read the user's data?" now has one file to read.
 * What: one `@Provides` per repository, each built through `RepositoryFactory` because the
 *       implementations are `internal` to their module (ARC-003).
 * Result: features inject an interface and never name an implementation.
 * Changelog: 2026-07-28 — Created for issue 2.4.
 *
 * **Every binding here takes the gated [CfoDatabase], never `@AuditDatabase`.** These hold the
 * user's financial data, which is exactly what the app lock exists to gate; the audit log's
 * exemption is for security events written *while* locked, and nothing here has that excuse.
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    /**
     * The quick-setup store (issue 2.3; FR-ONB-002).
     * Why:    takes its active profile from [DemoModeRepository], which is what makes every
     *         no-argument read follow the demo without the reading screen knowing demo mode exists
     *         (issue 2.4). The dependency runs demo → quick setup and never back.
     * Result: a [QuickSetupRepository]. Input: [database], [clock], [dispatchers], [demoMode].
     * Output: the repository.
     * Changelog: 2026-07-27 — Created for issue 2.3, in CoreModule.
     *            2026-07-28 — Issue 2.4: moved here, and given the active profile.
     */
    @Provides
    @Singleton
    fun provideQuickSetupRepository(
        database: CfoDatabase,
        clock: Clock,
        dispatchers: DispatcherProvider,
        demoMode: DemoModeRepository,
    ): QuickSetupRepository = RepositoryFactory.quickSetup(database, clock, dispatchers, demoMode.activeProfileId)

    /**
     * Demo mode (issue 2.4; FR-ONB-004).
     * Why:    it both seeds and erases rows in the shared tables, so it goes through the same lock
     *         gate as everything else — a wipe path reachable before the first unlock would be a
     *         delete outside the security perimeter.
     * Result: a [DemoModeRepository]. Input: [database], [settingsStore] — holds the flag; [clock];
     *         [dispatchers]. Output: the repository.
     * Changelog: 2026-07-28 — Created for issue 2.4.
     */
    @Provides
    @Singleton
    fun provideDemoModeRepository(
        database: CfoDatabase,
        settingsStore: SettingsStore,
        clock: Clock,
        dispatchers: DispatcherProvider,
    ): DemoModeRepository = RepositoryFactory.demoMode(database, settingsStore, clock, dispatchers)

    /**
     * The accounts store (issue 2.5; FR-ACC-001, FR-ACC-007).
     * Why:    takes its active profile from [DemoModeRepository] for the same reason quick setup
     *         does — the accounts list must show the demo's four sample accounts while a demo is
     *         loaded, and the user's own the moment it is left, without the screen knowing either.
     * Result: an [AccountRepository]. Input: [database], [clock], [ids] — the injected id source
     *         (P-08), [dispatchers], [demoMode]. Output: the repository.
     * Changelog: 2026-07-28 — Created for issue 2.5.
     */
    @Provides
    @Singleton
    fun provideAccountRepository(
        database: CfoDatabase,
        clock: Clock,
        ids: IdGenerator,
        dispatchers: DispatcherProvider,
        demoMode: DemoModeRepository,
    ): AccountRepository = RepositoryFactory.accounts(database, clock, ids, dispatchers, demoMode.activeProfileId)

    /**
     * The net-worth store (issue 2.6; FR-ACC-005).
     * Why:    takes the engine rather than building one, so the figure and the code that produced it
     *         are assembled in the graph rather than by the repository (ARC-003, P-03). Follows the
     *         demo like everything else here: exploring the sample data shows the sample net worth.
     * Result: a [NetWorthRepository]. Input: [database], [engine], [clock], [dispatchers], [demoMode].
     * Output: the repository.
     * Changelog: 2026-08-01 — Created for issue 2.6.
     */
    @Provides
    @Singleton
    fun provideNetWorthRepository(
        database: CfoDatabase,
        engine: NetWorthEngine,
        clock: Clock,
        dispatchers: DispatcherProvider,
        demoMode: DemoModeRepository,
    ): NetWorthRepository = RepositoryFactory.netWorth(database, engine, clock, dispatchers, demoMode.activeProfileId)
}
