package com.aicfo.app.di

import com.aicfo.domain.engines.networth.NetWorthEngine
import com.aicfo.domain.engines.networth.NetWorthEngineFactory
import com.aicfo.domain.engines.quicksetup.QuickSetupEngine
import com.aicfo.domain.engines.quicksetup.QuickSetupEngineFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The `:domain:engines:*` bindings (ARC-002, ARC-003, P-03).
 *
 * Why:  split out of [CoreModule] when adding the net-worth engine took that object to detekt's
 *       `TooManyFunctions` ceiling — the same pressure that produced [RepositoryModule] for issue
 *       2.4, and the same kind of seam rather than an arbitrary cut. Everything here is a
 *       **pure-Kotlin calculator**: no Android imports (ARC-002), no state, no dependencies, and by
 *       P-03 the only code in the app allowed to produce a financial figure. A reviewer asking
 *       "what computes the numbers?" now has one file to read.
 * What: one `@Provides` per engine, each built through its factory because the implementations are
 *       `internal` to their modules (ARC-003).
 * Result: features and repositories inject an interface and never name an implementation.
 * Changelog: 2026-08-01 — Created for issue 2.6, with the app's second engine.
 *
 * **Singletons, safely.** Every engine here is stateless and deterministic — fixed input, fixed
 * output (P-08) — so one instance shared across the app has nothing to reset between screens and
 * no way for one caller's use to affect another's.
 */
@Module
@InstallIn(SingletonComponent::class)
object EngineModule {
    /**
     * The quick-setup engine (issue 2.3; FR-ONB-002).
     * Result: a [QuickSetupEngine]. Input: none. Output: the engine.
     * Changelog: 2026-07-27 — Created for issue 2.3, in CoreModule.
     *            2026-08-01 — Issue 2.6: moved here.
     */
    @Provides
    @Singleton
    fun provideQuickSetupEngine(): QuickSetupEngine = QuickSetupEngineFactory.create()

    /**
     * The net-worth engine (issue 2.6; FR-ACC-005).
     * Why:    the one place `assets − liabilities` is computed. The repository hands it balances and
     *         stores what it returns; the dashboard renders what was stored. Neither adds up a rupee
     *         of its own (P-03).
     * Result: a [NetWorthEngine]. Input: none. Output: the engine.
     * Changelog: 2026-08-01 — Created for issue 2.6.
     */
    @Provides
    @Singleton
    fun provideNetWorthEngine(): NetWorthEngine = NetWorthEngineFactory.create()
}
