package com.aicfo.app.di

import com.aicfo.core.common.Clock
import com.aicfo.core.datastore.SettingsStore
import com.aicfo.data.repository.QuickSetupRepository
import com.aicfo.domain.engines.quicksetup.QuickSetupEngine
import com.aicfo.feature.settings.DefaultMoneyPlanWriter
import com.aicfo.feature.settings.MoneyPlanWriter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds FR-SET-001's settings screen into the graph (ARC-003).
 *
 * Why:  the settings screen is the only way, after onboarding, to change the monthly income, the
 *       per-feature consents or the app lock. Everything else it needs — `SettingsStore`,
 *       `ConsentStore`, `AppLockStore`, `PinVerifier`, `QuickSetupEngine` and
 *       `QuickSetupRepository` — is already in the graph, so this module binds exactly one thing:
 *       the coordinator that turns three typed amounts into a stored envelope plan.
 *
 *       A module of its own rather than a fifth function on `CoreModule`, which is already close to
 *       detekt's `TooManyFunctions` ceiling — the same pressure that produced `WealthEngineModule`.
 * What: `MoneyPlanWriter` → `DefaultMoneyPlanWriter`.
 * Result: `SettingsViewModel` can be constructed by Hilt.
 * Changelog: 2026-08-29 — Created for FR-SET-001.
 */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {
    /**
     * Builds the money-plan coordinator.
     * Why:    `@Provides` rather than `@Binds`, matching every other binding here, so the seam looks
     *         the same wherever a reader lands.
     * Result: the writer the settings screen saves through.
     * Input:  [engine] — derives the split (P-03); [quickSetup] — writes the envelopes;
     *         [settingsStore] — stores the seeds; [clock] — injected, never the wall clock (TIM-001).
     * Output: a [MoneyPlanWriter].
     * Changelog: 2026-08-29 — Created for FR-SET-001.
     */
    @Provides
    @Singleton
    fun provideMoneyPlanWriter(
        engine: QuickSetupEngine,
        quickSetup: QuickSetupRepository,
        settingsStore: SettingsStore,
        clock: Clock,
    ): MoneyPlanWriter = DefaultMoneyPlanWriter(engine, quickSetup, settingsStore, clock)
}
